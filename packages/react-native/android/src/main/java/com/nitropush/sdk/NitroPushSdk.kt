package com.nitropush.sdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

private val RELEASE_ID_REGEX = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

private fun validateReleaseId(releaseId: String) {
    validateCanonicalUuid(releaseId, "releaseId")
}

private fun validateCanonicalUuid(value: String, fieldName: String) {
    check(RELEASE_ID_REGEX.matches(value)) { "$fieldName is not a lowercase canonical UUID" }
}

private fun releaseRoot(context: Context): File =
    File(context.filesDir, "nitropush").also { root ->
        check(root.exists() || root.mkdirs()) { "could not create the NitroPush update directory" }
    }.canonicalFile

private fun releaseDirectory(context: Context, releaseId: String): File {
    validateReleaseId(releaseId)
    val root = releaseRoot(context)
    val directory = File(root, releaseId).canonicalFile
    check(directory.parentFile?.canonicalFile == root && directory.name == releaseId) {
        "release directory escapes the update root"
    }
    return directory
}

/**
 * Plain Android implementation of the NitroPush OTA core.
 *
 * **Has zero dependency on Nitro Modules.** The Nitrogen-generated bridge
 * `com.margelo.nitro.nitropush.nativesdk.HybridNitroPush` is a thin wrapper
 * that calls into [NitroPushSdk.shared] for every JS-facing operation.
 *
 * Owns the full update lifecycle on Android:
 * - Downloads release bundles from the configured NitroPush server.
 * - Persists them under `${context.filesDir}/nitropush/{releaseId}/main.jsbundle`.
 * - Tracks active / pending / previous bundle pointers in `SharedPreferences`.
 * - Coordinates the bundle-pointer swap on cold launch.
 * - Observes process lifecycle for `ON_NEXT_RESUME` / `ON_NEXT_SUSPEND`.
 * - Implements rollback-on-failed-install.
 *
 * Wire-up in `MainApplication.kt`:
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         NitroPushSdk.install(this)
 *     }
 *
 *     // …passed to the React host:
 *     jsBundleFilePath = if (BuildConfig.DEBUG) null
 *                        else NitroPushSdk.shared.activeBundleFile()
 */
class NitroPushSdk private constructor(
    private val applicationContext: Context,
) {

    private object Keys {
        const val ACTIVE = "nitropush.active"
        const val PENDING = "nitropush.pending"
        const val PREVIOUS = "nitropush.previous"
        const val UNCONFIRMED = "nitropush.unconfirmed"
        /**
         * Snapshot of the release we just rolled BACK from. Written by
         * [consumePendingPointerOnLaunch] *before* the active pointer
         * gets clobbered with `previous`. Read + cleared by
         * [detectAndReportRollback] after [configure] wires the emitter.
         */
        const val PENDING_ROLLBACK_EVENT = "nitropush.pendingRollbackEvent"
    }

    companion object {
        private const val TAG = "NitroPushSdk"
        private lateinit var _shared: NitroPushSdk

        @JvmStatic
        val shared: NitroPushSdk
            get() {
                check(::_shared.isInitialized) {
                    "NitroPushSdk.install(application) must be called from MainApplication.onCreate()"
                }
                return _shared
            }

        @JvmStatic
        fun install(context: Context) {
            if (::_shared.isInitialized) return
            _shared = NitroPushSdk(context.applicationContext)
            _shared.consumePendingPointerOnLaunch()
            _shared.installLifecycleObservers()
        }

        /**
         * Build an [NPConfig] by reading `NITROPUSH_*` keys from the app's
         * `<application>` `<meta-data>` tags in `AndroidManifest.xml`. Mirror
         * of iOS's `configFromInfoPlist()`. Only `NITROPUSH_DEPLOYMENT_KEY` is
         * required — server and storage URLs fall back to the NitroPush-hosted
         * endpoints when absent.
         */
        @JvmStatic
        fun configFromManifest(): NPConfig {
            val ctx = shared.applicationContext
            val ai = ctx.packageManager.getApplicationInfo(
                ctx.packageName,
                PackageManager.GET_META_DATA,
            )
            val meta = ai.metaData
                ?: error("AndroidManifest.xml is missing NITROPUSH_DEPLOYMENT_KEY <meta-data>")
            val deploymentKey = meta.getString("NITROPUSH_DEPLOYMENT_KEY")
                ?: error("missing NITROPUSH_DEPLOYMENT_KEY in AndroidManifest meta-data")
            return NPConfig(
                serverUrl = meta.getString("NITROPUSH_SERVER_URL") ?: "https://api.nitropush.org",
                deploymentKey = deploymentKey,
                storageBaseUrl = meta.getString("NITROPUSH_STORAGE_BASE_URL") ?: "https://cdn.nitropush.org",
                appVersion = meta.getString("NITROPUSH_APP_VERSION"),
                clientUniqueId = meta.getString("NITROPUSH_CLIENT_UNIQUE_ID"),
                bundlePublicKey = meta.getString("NITROPUSH_BUNDLE_PUBLIC_KEY"),
                enableDeltaUpdates = meta.getBoolean("NITROPUSH_ENABLE_DELTA_UPDATES", false),
            )
        }
    }

    private val prefs: SharedPreferences =
        applicationContext.getSharedPreferences("nitropush", Context.MODE_PRIVATE)

    /**
     * Toggleable debug logging — off by default so production builds don't
     * spam logcat. Flip with [setEnableLogs] from `MainApplication.onCreate`
     * (or anywhere on the native side) when you want a trace of every SDK
     * action. The flag is process-local; no persistence.
     */
    @Volatile
    private var logsEnabled: Boolean = false

    /** Enable/disable debug logging at runtime. Idempotent. */
    fun setEnableLogs(enabled: Boolean) {
        if (logsEnabled == enabled) return
        logsEnabled = enabled
        // Always log the toggle itself so flipping it ON appears in the log
        // stream, and flipping it OFF leaves a visible "last log" trail.
        Log.i(TAG, "setEnableLogs: $enabled")
    }

    private inline fun log(action: String, details: () -> String = { "" }) {
        if (!logsEnabled) return
        val d = details()
        if (d.isEmpty()) Log.i(TAG, action) else Log.i(TAG, "$action $d")
    }

    private fun log(action: String, throwable: Throwable) {
        if (!logsEnabled) return
        Log.w(TAG, action, throwable)
    }

    private var serverUrl: String? = null
    private var deploymentKey: String? = null
    private var deploymentKeyHash: String? = null
    /** Public base URL for bundle/asset storage. No trailing slash. */
    private var storageBaseUrl: String? = null
    private var appVersion: String? = null
    private var clientUniqueId: String? = null
    /** Server-issued proof, scoped to the configured server + deployment. */
    private var deviceToken: String? = null
    private var deviceTokenStorageKey: String? = null
    /** Base64-encoded DER SubjectPublicKeyInfo for ECDSA P-256 bundle
     *  signature verification. `null` means verification is skipped. */
    private var bundlePublicKey: String? = null
    /** Experimental: when true, send currentBundleHash in update checks and
     *  attempt delta download/patch before falling back to full bundle. */
    private var enableDeltaUpdates: Boolean = false
    private data class EmbeddedBundleSource(val assetName: String, val sha256: String)

    /** SHA-256 of the JS bundle packaged in the APK/AAB, used before the first OTA. */
    private val embeddedBundleSource: EmbeddedBundleSource? by lazy {
        val names = listOf("index.android.bundle", "main.jsbundle", "main.hbc")
        for (name in names) {
            val digest = runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                applicationContext.assets.open(name).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        md.update(buffer, 0, count)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
            if (digest != null) return@lazy EmbeddedBundleSource(name, digest)
        }
        null
    }
    private val embeddedBundleHash: String?
        get() = embeddedBundleSource?.sha256

    /** Never advertise the embedded bundle while an OTA package is active,
     *  even if that package is missing its stored bundle hash. */
    private fun currentBundleHashForDelta(): String? {
        val active = readActive()
        return if (active != null) active.bundleHash else embeddedBundleHash
    }

    private val progressListeners = mutableMapOf<Int, (NPDownloadProgress) -> Unit>()
    private var nextListenerId = 1

    /** releaseId → pre-signed manifest proxy URL from the server. */
    private val manifestUrlOverride = mutableMapOf<String, String>()
    private val manifestDeploymentKeyHash = mutableMapOf<String, String>()

    private var pendingResume: Pair<NPLocalPackage, Long>? = null
    private var pendingSuspend: NPLocalPackage? = null
    private var lastBackgroundedAt: Long = 0

    /**
     * Native analytics emitter. Replaces the deleted JS-side
     * `createAnalyticsEmitter` so events fire even when the JS thread
     * hasn't loaded (cold-start, post-rollback boots).
     */
    private var analytics: NPAnalytics? = null

    /**
     * Rollback releases we've already reported this process — keeps
     * `install_failed_rollback` from firing on every launch.
     */
    private val reportedRollbacks = mutableSetOf<String>()

    /**
     * First-run releases we've already reported `install_completed` for.
     * Lets the host call `notifyAppReady()` defensively without spam.
     */
    private val reportedFirstRuns = mutableSetOf<String>()

    private val releaseFilesManifestName = ".nitropush-files.json"
    private val maxLatestResponseBytes = 512 * 1024
    private val maxManifestBytes = 2 * 1024 * 1024
    private val maxBundleBytes = 64L * 1024L * 1024L
    private val maxAssetBytes = 16L * 1024L * 1024L
    private val maxReleaseBytes = 128L * 1024L * 1024L
    private val maxCacheBytes = 50L * 1024L * 1024L
    private val targetCacheBytesAfterPrune = 40L * 1024L * 1024L
    private val maxUnprotectedCacheAgeMs = 14L * 24L * 60L * 60L * 1000L

    fun configure(config: NPConfig) {
        log("configure") {
            "serverUrl=${redactedUrl(config.serverUrl)} " +
                "storageBaseUrl=${redactedUrl(config.storageBaseUrl)} " +
                "appVersion=${config.appVersion ?: "(auto)"}"
        }
        val apiUrl = validatedNetworkUrl(config.serverUrl, "serverUrl")
        val storageUrl = validatedNetworkUrl(config.storageBaseUrl, "storageBaseUrl")
        require(config.deploymentKey.isNotBlank()) { "deploymentKey is required" }
        val validatedPublicKey = config.bundlePublicKey?.trim()?.takeIf { it.isNotEmpty() }?.also {
            parseBundlePublicKey(it)
        }
        serverUrl = apiUrl.toString().trimEnd('/')
        deploymentKey = config.deploymentKey.trim()
        deploymentKeyHash = sha256Hex(config.deploymentKey.trim().toByteArray(Charsets.UTF_8))
        storageBaseUrl = storageUrl.toString().trimEnd('/')
        appVersion = config.appVersion ?: binaryAppVersion()
        clientUniqueId = config.clientUniqueId ?: fallbackDeviceId()
        val tokenScopeHash = MessageDigest.getInstance("SHA-256")
            .digest("${serverUrl}\u0000${deploymentKey}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val tokenKey = "nitropush.deviceToken.$tokenScopeHash"
        deviceTokenStorageKey = tokenKey
        deviceToken = prefs.getString(tokenKey, null)
        bundlePublicKey = validatedPublicKey
        enableDeltaUpdates = config.enableDeltaUpdates
        // Pre-signed URLs are scoped to the response that created them and
        // must never survive a server/project reconfiguration.
        manifestUrlOverride.clear()
        manifestDeploymentKeyHash.clear()

        // Replace any prior emitter — re-configure can change endpoint
        // or deployment key, and the in-flight queue is no longer valid.
        analytics?.stop()
        analytics = NPAnalytics(
            serverUrl = config.serverUrl,
            deploymentKey = config.deploymentKey,
            deviceToken = deviceToken,
        )

        emit(type = "app_started")
        // The launch-time pointer sweep ran before `configure()` and only
        // mutated state. Now that the emitter is wired, replay any pending
        // rollback event once.
        detectAndReportRollback()
        pruneUnusedBundlesAndCache()
    }

    private fun isLoopback(host: String?): Boolean = when (host?.lowercase(Locale.ROOT)) {
        "localhost", "127.0.0.1", "::1", "[::1]" -> true
        else -> false
    }

    private fun validatedNetworkUrl(raw: String, name: String, allowQuery: Boolean = false): URL {
        val url = runCatching { URL(raw.trim()) }.getOrElse {
            error("$name must be an absolute HTTPS URL")
        }
        check(url.userInfo == null && (allowQuery || url.query == null) &&
            url.ref == null && url.host.isNotEmpty()) {
            "$name must not contain credentials, a query, or a fragment"
        }
        check(url.protocol.equals("https", ignoreCase = true) ||
            (url.protocol.equals("http", ignoreCase = true) && isLoopback(url.host))) {
            "$name must use HTTPS (HTTP is allowed only for loopback development)"
        }
        return url
    }

    private fun effectivePort(url: URL): Int = when {
        url.port >= 0 -> url.port
        url.protocol.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun sameOrigin(left: URL, right: URL): Boolean =
        left.protocol.equals(right.protocol, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun redactedUrl(raw: String): String = runCatching {
        val url = URL(raw)
        URL(url.protocol, url.host, url.port, url.path).toString()
    }.getOrDefault("<invalid-url>")

    private fun parseBundlePublicKey(publicKeyBase64: String): ECPublicKey {
        check(Regex("^[A-Za-z0-9+/]+={0,2}$").matches(publicKeyBase64) &&
            publicKeyBase64.length % 4 == 0) {
            "bundlePublicKey is not canonical base64 DER"
        }
        val decoded = try {
            Base64.decode(publicKeyBase64, Base64.DEFAULT)
        } catch (_: Throwable) {
            error("bundlePublicKey is not valid base64 DER")
        }
        check(Base64.encodeToString(decoded, Base64.NO_WRAP) == publicKeyBase64) {
            "bundlePublicKey is not canonical base64 DER"
        }
        val key = try {
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(decoded)) as? ECPublicKey
        } catch (_: Throwable) {
            null
        } ?: error("bundlePublicKey is not a valid P-256 SPKI public key")
        check(key.params.curve.field.fieldSize == 256) {
            "bundlePublicKey must use the P-256 curve"
        }
        return key
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun openConnection(
        url: URL,
        includeDeviceToken: Boolean = false,
        readTimeoutMs: Int = 60_000,
    ): HttpURLConnection {
        val validated = validatedNetworkUrl(url.toString(), "request URL", allowQuery = true)
        val connection = validated.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 60_000
        connection.readTimeout = readTimeoutMs
        if (includeDeviceToken) {
            val api = serverUrl?.let(::URL) ?: error("NitroPushSdk.configure(...) was not called.")
            check(sameOrigin(validated, api)) { "refusing to send device proof to a different origin" }
            deviceToken?.let { connection.setRequestProperty("x-nitropush-device-token", it) }
        }
        return connection
    }

    /** Resolve an API-issued delta URL and constrain it to the configured
     * HTTPS API origin before the device proof header is attached. */
    private fun validatedDeltaDownloadUrl(raw: String): URL {
        val api = serverUrl?.let(::URL)
            ?: error("NitroPushSdk.configure(...) was not called.")
        check(raw.isNotBlank()) { "delta download URL is invalid" }
        val resolved = runCatching { URL(api, raw.trim()) }
            .getOrElse { error("delta download URL is invalid") }
        val validated = validatedNetworkUrl(
            resolved.toString(),
            "delta download URL",
            allowQuery = true,
        )
        check(validated.protocol.equals("https", ignoreCase = true) &&
            sameOrigin(validated, api)) {
            "delta download URL must use the configured HTTPS API origin"
        }
        return validated
    }

    private fun readBounded(input: java.io.InputStream, maximumBytes: Int): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                total += count
                check(total <= maximumBytes) { "response exceeds the allowed size" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    /** Resolve a bucket-relative `objectKey` to an absolute URL. */
    private fun resolveObjectUrl(objectKey: String): String {
        val base = storageBaseUrl
            ?: error("NitroPushSdk.configure(...) was not called.")
        val segments = objectKey.split("/")
        check(objectKey.isNotEmpty() &&
            !objectKey.startsWith("//") &&
            !objectKey.contains('?') &&
            !objectKey.contains('#') &&
            objectKey.none { it.code < 0x20 || it.code == 0x7f } &&
            segments.none { it == "." || it == ".." }) {
            "unsafe storage object key"
        }
        val key = if (objectKey.startsWith("/")) objectKey.substring(1) else objectKey
        return validatedNetworkUrl("$base/$key", "download URL").toString()
    }

    /** Blocking. Run on a worker thread (the Nitro bridge uses Promise.async). */
    fun checkForUpdate(deploymentKeyOverride: String? = null): NPRemotePackage? {
        log("checkForUpdate") { "deploymentKeyOverride=${deploymentKeyOverride?.take(20) ?: "(none)"}" }
        val r = requestLatestRelease(deploymentKeyOverride ?: deploymentKey)
        log("checkForUpdate → result") {
            if (r == null) "no update available"
            else "releaseId=${r.releaseId} label=${r.label} kind=${r.kind} size=${r.packageSize.toLong()}B"
        }
        emit(
            type = "update_check",
            releaseId = r?.releaseId,
            appVersion = r?.appVersion,
            otaVersion = r?.otaVersion,
        )
        return r
    }

    /** Blocking. Emits progress synchronously on the calling thread. */
    fun downloadUpdate(pkg: NPRemotePackage): NPLocalPackage {
        log("downloadUpdate") { "releaseId=${pkg.releaseId} label=${pkg.label} kind=${pkg.kind}" }
        emit(
            type = "download_started",
            releaseId = pkg.releaseId,
            appVersion = pkg.appVersion,
            otaVersion = pkg.otaVersion,
        )
        val local = try {
            performDownload(pkg)
        } catch (e: Throwable) {
            log("downloadUpdate FAILED", e)
            throw e
        }
        log("downloadUpdate → completed") { "releaseId=${local.releaseId} bundlePath=${local.bundlePath}" }
        emit(
            type = "download_completed",
            releaseId = local.releaseId,
            appVersion = local.appVersion,
            otaVersion = local.otaVersion,
        )
        return local
    }

    fun installUpdate(
        pkg: NPLocalPackage,
        installMode: NPInstallMode,
        minimumBackgroundDurationSeconds: Double,
    ) {
        log("installUpdate") {
            "releaseId=${pkg.releaseId} mode=$installMode minBgDur=${minimumBackgroundDurationSeconds}s"
        }
        persistPending(pkg)
        when (installMode) {
            NPInstallMode.IMMEDIATE -> {
                log("installUpdate.IMMEDIATE → activate + reload")
                activatePending()
                reloadBridge()
            }
            NPInstallMode.ON_NEXT_RESTART -> {
                log("installUpdate.ON_NEXT_RESTART") { "staged; will activate on next cold start" }
            }
            NPInstallMode.ON_NEXT_RESUME -> {
                pendingResume = pkg to (minimumBackgroundDurationSeconds.toLong() * 1000L)
                log("installUpdate.ON_NEXT_RESUME") { "scheduled after ≥${minimumBackgroundDurationSeconds}s background" }
            }
            NPInstallMode.ON_NEXT_SUSPEND -> {
                pendingSuspend = pkg
                log("installUpdate.ON_NEXT_SUSPEND") { "will activate when app goes background" }
            }
        }
    }

    fun notifyAppReady() {
        // Emit `install_completed` once per first-run release. We read
        // `isFirstRun` *before* clearing the unconfirmed flag so we never
        // miss a healthy install. Dedup on `releaseId` so callers can
        // call `notifyAppReady()` repeatedly without spamming events.
        val active = readActive()
        log("notifyAppReady") {
            "active=${active?.releaseId ?: "(none)"} isFirstRun=${active?.isFirstRun}"
        }
        if (active != null && active.isFirstRun &&
            !reportedFirstRuns.contains(active.releaseId)
        ) {
            reportedFirstRuns.add(active.releaseId)
            log("notifyAppReady → install_completed") { "releaseId=${active.releaseId}" }
            emit(
                type = "install_completed",
                releaseId = active.releaseId,
                appVersion = active.appVersion,
                otaVersion = active.otaVersion,
            )
        }
        prefs.edit()
            .putBoolean(Keys.UNCONFIRMED, false)
            .remove(Keys.PREVIOUS)
            .apply()
        pruneUnusedBundlesAndCache()
    }

    fun restartApp(onlyIfUpdateIsPending: Boolean) {
        val pending = readPending()
        log("restartApp") { "onlyIfUpdateIsPending=$onlyIfUpdateIsPending pending=${pending?.releaseId ?: "(none)"}" }
        if (onlyIfUpdateIsPending && pending == null) {
            log("restartApp → no-op (no pending update)")
            return
        }
        if (pending != null) activatePending()
        reloadBridge()
    }

    fun getCurrentPackage(): NPLocalPackage? = readActive()
    fun getPendingPackage(): NPLocalPackage? = readPending()

    fun clearPendingUpdate() {
        val pending = readPending()
        log("clearPendingUpdate") { "pending=${pending?.releaseId ?: "(none)"}" }
        pending?.let { deleteBundleDir(it.releaseId) }
        prefs.edit().remove(Keys.PENDING).apply()
        pendingResume = null
        pendingSuspend = null
        pruneUnusedBundlesAndCache()
    }

    /**
     * Roll back a release by id. Three cases:
     *  1. `releaseId` matches the **pending** release → drop pending + its
     *     bundle. Same effect as [clearPendingUpdate] but scoped.
     *  2. `releaseId` matches the **active** release → swap `previous` into
     *     `active` (or clear active if no previous), delete the bundle for
     *     the failed release, reload the JS bridge.
     *  3. Otherwise → no-op (release isn't reachable from the current
     *     pointers, so there's nothing to roll back).
     */
    @Synchronized
    fun rollback(releaseId: String) {
        log("rollback") { "releaseId=$releaseId" }
        readPending()?.let { pending ->
            if (pending.releaseId == releaseId) {
                log("rollback → drop pending") { "releaseId=$releaseId" }
                deleteBundleDir(pending.releaseId)
                prefs.edit().remove(Keys.PENDING).apply()
                pendingResume = null
                pendingSuspend = null
                pruneUnusedBundlesAndCache()
                return
            }
        }

        val active = readActive() ?: run {
            log("rollback → no-op (no active bundle)")
            return
        }
        if (active.releaseId != releaseId) {
            log("rollback → no-op") { "releaseId=$releaseId is neither pending nor active (active=${active.releaseId})" }
            return
        }

        val previousJson = prefs.getString(Keys.PREVIOUS, null)
        log("rollback → swap active") {
            if (previousJson != null) "restoring previous bundle"
            else "no previous bundle, clearing active"
        }
        val editor = prefs.edit()
        if (previousJson != null) {
            editor.putString(Keys.ACTIVE, previousJson)
            editor.remove(Keys.PREVIOUS)
        } else {
            editor.remove(Keys.ACTIVE)
        }
        editor.putBoolean(Keys.UNCONFIRMED, false)
        editor.apply()
        deleteBundleDir(releaseId)
        pruneUnusedBundlesAndCache()
        reloadBridge()
    }

    fun clearUpdates() {
        log("clearUpdates") { "wiping prefs + ${File(applicationContext.filesDir, "nitropush").absolutePath}" }
        prefs.edit().clear().apply()
        File(applicationContext.filesDir, "nitropush").deleteRecursively()
    }

    fun addDownloadProgressListener(callback: (NPDownloadProgress) -> Unit): Int {
        val id = nextListenerId++
        progressListeners[id] = callback
        return id
    }

    fun removeDownloadProgressListener(listenerId: Int) {
        progressListeners.remove(listenerId)
    }

    private fun emitProgress(progress: NPDownloadProgress) {
        val snapshot = progressListeners.values.toList()
        Handler(Looper.getMainLooper()).post {
            snapshot.forEach { it(progress) }
        }
    }

    /**
     * Build + enqueue an analytics event. No-ops before [configure] —
     * the launch-time pointer sweep can't tag events because it has no
     * emitter yet; [configure] replays anything urgent (rollbacks).
     */
    private fun emit(
        type: String,
        releaseId: String? = null,
        appVersion: String? = null,
        otaVersion: Double? = null,
        metadata: JSONObject? = null,
    ) {
        val a = analytics ?: return
        a.enqueue(
            NPAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = type,
                clientUniqueId = clientUniqueId ?: fallbackDeviceId(),
                appVersion = appVersion ?: this.appVersion ?: "*",
                otaVersion = otaVersion,
                releaseId = releaseId,
                platform = "android",
                osVersion = NPAnalyticsContext.osVersion(),
                deviceModel = NPAnalyticsContext.deviceModel(),
                occurredAt = NPAnalyticsContext.now(),
                metadata = metadata,
            )
        )
    }

    private fun classifyDeltaError(e: Throwable): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("hash") || msg.contains("sha") || msg.contains("integrity") -> "hash_mismatch"
            msg.contains("bspatch") || msg.contains("patch") -> "bspatch_error"
            else -> "patch_download_failed"
        }
    }

    /**
     * Drain the rollback snapshot that [consumePendingPointerOnLaunch]
     * stashed before the active pointer got clobbered. We can't read the
     * failed release off `active` because the pointer-swap moved
     * `previous` into `active` already.
     */
    private fun detectAndReportRollback() {
        val raw = prefs.getString(Keys.PENDING_ROLLBACK_EVENT, null) ?: return
        prefs.edit().remove(Keys.PENDING_ROLLBACK_EVENT).apply()
        val rolled = try {
            NPLocalPackage.fromJson(JSONObject(raw))
        } catch (_: Throwable) {
            return
        }
        if (reportedRollbacks.contains(rolled.releaseId)) return
        reportedRollbacks.add(rolled.releaseId)
        emit(
            type = "install_failed_rollback",
            releaseId = rolled.releaseId,
            appVersion = rolled.appVersion,
            otaVersion = rolled.otaVersion,
        )
    }

    /**
     * Absolute path of the currently-active bundle, or `null` when running
     * the binary-shipped bundle. Wire into the host React configuration —
     * NOT exposed through the Nitro bridge (it's a native-only call).
     */
    /** NativeScript boot gate; verifies the full signed local tree before V8 starts. */
    fun verifiedApplicationRoot(embeddedRoot: File): File? = runCatching {
        val active = readActive() ?: return null
        val key = bundlePublicKey ?: error("NativeScript requires signing")
        check(active.appVersion == appVersion && active.appVersion != "*")
        val root = releaseDirectory(applicationContext, active.releaseId)
        val manifestFile = File(root, "verified-manifest.json")
        check(manifestFile.length() in 1..(4 * 1024 * 1024))
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getInt("schemaVersion") == 4 && manifest.getString("kind") == "nativescript")
        check(manifest.getString("releaseId") == active.releaseId)
        check(manifest.getString("targetAppVersion") == appVersion)
        check(manifest.getString("deploymentKeyHash") == deploymentKeyHash)
        val platforms = manifest.getJSONArray("platforms")
        check(platforms.length() == 1 && platforms.getString(0) == "android")
        verifySignature(releaseIntegrityPayload(manifest), manifest.getString("releaseSignature"), key, "release envelope")
        val entries = mutableListOf(manifest.getJSONObject("bundle"))
        val assets = manifest.getJSONArray("assets")
        check(assets.length() <= 10_000)
        for (i in 0 until assets.length()) entries += assets.getJSONObject(i)
        val occupied = mutableSetOf<String>()
        val expected = mutableSetOf<String>()
        for (entry in entries) {
            val path = entry.getString("originalPath")
            check(path.startsWith("app/"))
            val file = safeReleaseDestination(path, root, occupied)
            check(file.absolutePath == File(root, path).absolutePath && file.isFile)
            check(file.length() == entry.getLong("size"))
            val hash = file.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            check(hash == entry.getString("sha256"))
            expected += path
        }
        val actual = File(root, "app").walkTopDown().onEnter { directory ->
            check(directory.canonicalPath == directory.absolutePath) { "symlink in application tree" }; true
        }.filter { file -> check(file.canonicalPath == file.absolutePath) { "symlink in application tree" }; file.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        check(actual == expected)
        for (path in listOf("app/package.json", "app/tns-java-classes.js")) {
            val embedded = File(embeddedRoot, path)
            val installed = File(root, path)
            check(embedded.exists() == installed.exists())
            if (embedded.exists()) check(embedded.readBytes().contentEquals(installed.readBytes()))
        }
        val main = JSONObject(File(root, "app/package.json").readText()).getString("main")
        val candidates = if (main.endsWith(".js") || main.endsWith(".mjs")) listOf("app/$main") else listOf("app/$main.js", "app/$main.mjs")
        check(manifest.getJSONObject("bundle").getString("originalPath") in candidates && candidates.count { it in expected } == 1)
        root
    }.getOrNull()

    fun activeBundleFile(): String? {
        val active = readActive() ?: return null
        val releaseDir = runCatching { releaseDirectory(applicationContext, active.releaseId) }
            .getOrNull() ?: return null
        val file = File(active.bundlePath).canonicalFile
        if (!file.path.startsWith(releaseDir.path + File.separator)) {
            log("activeBundleFile → skipped") { "stored bundle path escapes its release directory" }
            return null
        }
        if (!file.exists()) return null
        if (!isHermesBundle(file)) {
            log("activeBundleFile → skipped") {
                "bundle at ${file.absolutePath} is not Hermes bytecode (.hbc); " +
                "falling back to binary bundle. Re-release with --bundle to upload a Hermes-compiled bundle."
            }
            return null
        }
        return file.absolutePath
    }

    /**
     * Returns true when [file] begins with the 4-byte Hermes HBC magic
     * (0xc6 0x1f 0xbc 0x03). All other formats are rejected so the SDK
     * never loads a plain-JS bundle that would silently fail on the new arch.
     */
    private fun isHermesBundle(file: File): Boolean {
        val magic = byteArrayOf(0xc6.toByte(), 0x1f, 0xbc.toByte(), 0x03)
        return try {
            file.inputStream().use { it.readNBytes(4).contentEquals(magic) }
        } catch (_: Exception) {
            false
        }
    }

    private fun consumePendingPointerOnLaunch() {
        // Note: logging is intentionally muted here — `logsEnabled` is still
        // its default `false` at this point (install() runs before any
        // setEnableLogs() call from the host).
        val editor = prefs.edit()

        val pendingJson = prefs.getString(Keys.PENDING, null)
        if (pendingJson != null) {
            prefs.getString(Keys.ACTIVE, null)?.let { editor.putString(Keys.PREVIOUS, it) }
            editor.putString(Keys.ACTIVE, pendingJson)
            editor.remove(Keys.PENDING)
            editor.putBoolean(Keys.UNCONFIRMED, true)
            editor.apply()
            persistFlag(JSONObject(pendingJson).getString("releaseId"), isFirstRun = true)
            return
        }

        if (prefs.getBoolean(Keys.UNCONFIRMED, false)) {
            val previousJson = prefs.getString(Keys.PREVIOUS, null)
            val active = readActive()
            if (previousJson != null) {
                if (active != null) {
                    deleteBundleDir(active.releaseId)
                    persistFlag(active.releaseId, isFailedInstall = true)
                    // Stash the rollback context so `configure()` can fire
                    // an `install_failed_rollback` event once the analytics
                    // emitter is up. Active is about to get clobbered by
                    // `previous` below — this snapshot survives.
                    editor.putString(
                        Keys.PENDING_ROLLBACK_EVENT,
                        active.toJson().toString(),
                    )
                }
                editor.putString(Keys.ACTIVE, previousJson)
            } else {
                editor.remove(Keys.ACTIVE)
            }
            editor.remove(Keys.PREVIOUS)
            editor.putBoolean(Keys.UNCONFIRMED, false)
            editor.apply()
        }
    }

    private fun installLifecycleObservers() {
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    lastBackgroundedAt = System.currentTimeMillis()
                    pendingSuspend?.let {
                        log("lifecycle.onStop → activating ON_NEXT_SUSPEND") { "releaseId=${it.releaseId}" }
                        activatePending()
                        pendingSuspend = null
                    }
                }

                override fun onStart(owner: LifecycleOwner) {
                    val (pkg, minMs) = pendingResume ?: return
                    val elapsed = System.currentTimeMillis() - lastBackgroundedAt
                    log("lifecycle.onStart") {
                        "pendingResume=${pkg.releaseId} elapsed=${elapsed}ms threshold=${minMs}ms"
                    }
                    if (elapsed >= minMs) {
                        log("lifecycle.onStart → activating ON_NEXT_RESUME + reload") { "releaseId=${pkg.releaseId}" }
                        persistPending(pkg)
                        activatePending()
                        reloadBridge()
                        pendingResume = null
                        pruneUnusedBundlesAndCache()
                    }
                }
            })
        }
    }

    private fun requestLatestRelease(deploymentKey: String?): NPRemotePackage? {
        val server = serverUrl ?: error("NitroPushSdk.configure(...) was not called.")
        val key = deploymentKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("deploymentKey not set")

        val params = mutableMapOf(
            "platform" to "android",
        )
        appVersion?.let { params["appVersion"] = it }
        clientUniqueId?.let { params["clientUniqueId"] = it }
        val active = readActive()
        active?.let {
            params["currentReleaseId"] = active.releaseId
        }
        if (enableDeltaUpdates) {
            currentBundleHashForDelta()?.let { params["currentBundleHash"] = it }
        }

        val query = params.entries.joinToString("&") {
            "${Uri.encode(it.key)}=${Uri.encode(it.value)}"
        }
        val url = validatedNetworkUrl(
            "$server/api/sdk/releases/latest?$query",
            "latest-release URL",
            allowQuery = true,
        )
        log("checkForUpdate") { "GET ${redactedUrl(url.toString())}" }

        val conn = openConnection(url, includeDeviceToken = true).apply {
            requestMethod = "GET"
            setRequestProperty("x-nitropush-deployment-key", key)
        }
        try {
            val code = conn.responseCode
            conn.getHeaderField("x-nitropush-device-token")
                ?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 2_048 }
                ?.let { issuedToken ->
                    deviceToken = issuedToken
                    deviceTokenStorageKey?.let { prefs.edit().putString(it, issuedToken).apply() }
                    analytics?.setDeviceToken(issuedToken)
                }
            if (code == 204) return null
            if (code !in 200..299) {
                error(
                    describeFetchFailure(
                        url = url.toString(),
                        reason = "checkForUpdate non-2xx HTTP $code",
                        contentType = conn.contentType,
                    )
                )
            }
            if (conn.contentLengthLong > maxLatestResponseBytes) {
                error("latest-release response exceeds the allowed size")
            }
            val bodyBytes = readBounded(conn.inputStream, maxLatestResponseBytes)
            val body = bodyBytes.toString(Charsets.UTF_8)
            val root = try {
                JSONObject(body)
            } catch (_: Throwable) {
                throw IllegalStateException(
                    describeFetchFailure(
                        url = url.toString(),
                        reason = "checkForUpdate JSON parse failed",
                        contentType = conn.contentType,
                        responseBytes = bodyBytes.size,
                    ),
                )
            }
            if (root.isNull("release")) return null
            val r = root.getJSONObject("release")
            val platformsArr = r.optJSONArray("platforms")
            val platforms = if (platformsArr != null) {
                Array(platformsArr.length()) { platformsArr.getString(it) }
            } else null
            val pkg = NPRemotePackage(
                releaseId = r.getString("releaseId"),
                kind = r.optString("kind", "codepush"),
                label = r.getString("label"),
                packageHash = r.getString("packageHash"),
                packageSize = r.getDouble("packageSize"),
                appVersion = r.getString("appVersion"),
                otaVersion = if (r.has("otaVersion") && !r.isNull("otaVersion"))
                    r.getDouble("otaVersion") else null,
                displayVersion = r.optString("displayVersion").takeIf { it.isNotEmpty() },
                platforms = platforms,
                isMandatory = r.optBoolean("isMandatory", false),
                description = r.optString("description").takeIf { it.isNotEmpty() },
                downloadObjectKey = r.getString("downloadObjectKey"),
                delta = if (
                    r.has("deltaFromBundleHash") &&
                    r.has("deltaObjectKey") &&
                    r.has("deltaSizeBytes") &&
                    r.has("deltaPatchSha256")
                ) {
                    NPDeltaPatch(
                        fromBundleHash = r.getString("deltaFromBundleHash"),
                        patchObjectKey = r.getString("deltaObjectKey"),
                        patchSize = r.getInt("deltaSizeBytes"),
                        patchSha256 = r.getString("deltaPatchSha256"),
                        algorithm = r.optString("deltaAlgorithm", "bsdiff4"),
                        deltaDownloadUrl = r.optString("deltaDownloadUrl")
                            .trim()
                            .takeIf { it.isNotEmpty() },
                    )
                } else null,
            )
            validateReleaseId(pkg.releaseId)
            manifestDeploymentKeyHash[pkg.releaseId] =
                sha256Hex(key.toByteArray(Charsets.UTF_8))
            check(NPHostRuntime.accepts(pkg.kind)) { "unsupported release kind" }
            check(pkg.packageSize.isFinite() && pkg.packageSize > 0 && pkg.packageSize <= maxBundleBytes) {
                "release package size is invalid"
            }
            check(Regex("^[a-f0-9]{64}$").matches(pkg.packageHash)) {
                "release package hash is invalid"
            }
            if (pkg.appVersion != "*" && appVersion != null) {
                check(pkg.appVersion == appVersion) { "release runtime does not match this binary" }
            }
            val activeVersion = active?.otaVersion
            if (active != null && active.appVersion == pkg.appVersion &&
                activeVersion != null && pkg.otaVersion != null &&
                pkg.otaVersion <= activeVersion && active.releaseId != pkg.releaseId
            ) {
                error("refusing a non-monotonic OTA release")
            }
            // Cache the pre-signed manifest proxy URL for use in downloadManifestRelease.
            r.optString("downloadUrl").takeIf { it.isNotEmpty() }?.let {
                val resolved = if (it.startsWith("http://") || it.startsWith("https://")) {
                    it
                } else {
                    "${server.trimEnd('/')}/${it.trimStart('/')}"
                }
                manifestUrlOverride[pkg.releaseId] =
                    validatedNetworkUrl(resolved, "manifest download URL", allowQuery = true).toString()
            }
            return pkg
        } finally {
            conn.disconnect()
        }
    }

    private fun performDownload(pkg: NPRemotePackage): NPLocalPackage {
        val releaseDir = releaseDirectory(applicationContext, pkg.releaseId)
        check(listOfNotNull(readActive(), readPending(), readPrevious()).none { it.releaseId == pkg.releaseId }) { "cannot overwrite a referenced release" }
        if (releaseDir.exists()) releaseDir.deleteRecursively()
        check(releaseDir.mkdirs()) { "could not create release staging directory" }

        // Both kinds (`expo` and `codepush`) ship a manifest at
        // `pkg.downloadObjectKey`. The manifest layout is identical across
        // kinds — the distinction lives only at the API/DB layer.
        val bundlePath = downloadManifestRelease(pkg, releaseDir)

        // Compute SHA-256 of the raw bundle for future delta eligibility checks.
        val bundleHash = runCatching {
            val bytes = File(bundlePath).readBytes()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        }.getOrNull()

        return NPLocalPackage(
            releaseId = pkg.releaseId,
            label = pkg.label,
            packageHash = pkg.packageHash,
            packageSize = pkg.packageSize,
            appVersion = pkg.appVersion,
            otaVersion = pkg.otaVersion,
            displayVersion = pkg.displayVersion,
            platforms = pkg.platforms,
            isMandatory = pkg.isMandatory,
            description = pkg.description,
            isPending = true,
            isFailedInstall = false,
            isFirstRun = false,
            bundlePath = bundlePath,
            bundleHash = bundleHash,
        )
    }

    /**
     * GET the manifest JSON at `${storageBaseUrl}/${pkg.downloadObjectKey}`.
     * The manifest is host-free — it stores only `objectKey` for the bundle
     * and each asset; this layer joins each with `storageBaseUrl` to fetch.
     * Files are written at their `originalPath` inside `releaseDir` so
     * RN's relative-to-bundle asset resolution still works. Used for both
     * `expo` and `codepush` kinds — the manifest format is identical.
     */
    private fun safeReleaseDestination(
        originalPath: String,
        releaseDir: File,
        occupied: MutableSet<String>,
    ): File {
        val segments = originalPath.split("/")
        check(
            originalPath.isNotEmpty() &&
                originalPath.toByteArray(Charsets.UTF_8).size <= 512 &&
                originalPath == Normalizer.normalize(originalPath, Normalizer.Form.NFC) &&
                !originalPath.startsWith("/") &&
                !originalPath.endsWith("/") &&
                !originalPath.contains('\\') &&
                originalPath.none { it.code < 0x20 || it.code == 0x7f } &&
                segments.none {
                    it.isEmpty() || it == "." || it == ".." ||
                        it.lowercase(Locale.ROOT) == releaseFilesManifestName
                }
        ) { "unsafe release path: $originalPath" }
        check(occupied.add(originalPath.lowercase(Locale.ROOT))) {
            "duplicate or colliding release path: $originalPath"
        }
        val root = releaseDir.canonicalFile
        val destination = File(root, originalPath).canonicalFile
        check(destination.path.startsWith(root.path + File.separator)) {
            "release path escapes staging directory"
        }
        return destination
    }

    private fun manifestIntegrityPayload(manifest: JSONObject): String {
        val bundle = manifest.getJSONObject("bundle")
        check(bundle.has("size")) { "signed manifest is missing bundle size" }
        fun line(kind: String, entry: JSONObject): String {
            check(entry.has("size")) { "signed manifest entry is missing size" }
            val path = entry.getString("originalPath")
            return "$kind:${path.toByteArray(Charsets.UTF_8).size}:$path:${entry.getString("sha256")}:${entry.getLong("size")}\n"
        }
        val assets = manifest.optJSONArray("assets") ?: org.json.JSONArray()
        return buildString {
            append("nitropush-manifest-v1\n")
            append(line("bundle", bundle))
            append("assets:${assets.length()}\n")
            for (i in 0 until assets.length()) append(line("asset", assets.getJSONObject(i)))
        }
    }

    /** Canonical schema-4 envelope. See the matching CLI signer. */
    private fun releaseIntegrityPayload(manifest: JSONObject): String {
        val releaseId = manifest.getString("releaseId")
        val projectId = manifest.getString("projectId")
        val environment = manifest.getString("environment")
        val signedDeploymentKeyHash = manifest.getString("deploymentKeyHash")
        val kind = manifest.getString("kind")
        val targetAppVersion = manifest.getString("targetAppVersion")
        val label = manifest.getString("label")
        val otaVersion = manifest.getDouble("otaVersion")
        check(otaVersion.isFinite() && otaVersion > 0 && otaVersion % 1.0 == 0.0 &&
            otaVersion <= Long.MAX_VALUE.toDouble()) {
            "signed release otaVersion is invalid"
        }
        check(manifest.has("isMandatory")) { "signed release mandatory flag is missing" }
        val isMandatory = manifest.getBoolean("isMandatory")
        validateReleaseId(releaseId)
        validateCanonicalUuid(projectId, "projectId")
        check(Regex("^[a-f0-9]{64}$").matches(signedDeploymentKeyHash)) {
            "signed deployment key hash is invalid"
        }

        val platformArray = manifest.getJSONArray("platforms")
        val platforms = List(platformArray.length()) { platformArray.getString(it) }.sorted()
        check(platforms.isNotEmpty() && platforms.toSet().size == platforms.size &&
            platforms.all { it == "ios" || it == "android" }) {
            "signed release platforms are invalid"
        }
        fun field(name: String, value: String): String =
            "$name:${value.toByteArray(Charsets.UTF_8).size}:$value\n"
        fun artifact(type: String, entry: JSONObject): String {
            check(entry.has("size")) { "signed release artifact is missing size" }
            val path = entry.getString("originalPath")
            return "$type:${path.toByteArray(Charsets.UTF_8).size}:$path:" +
                "${entry.getString("sha256")}:${entry.getLong("size")}\n"
        }
        val bundle = manifest.getJSONObject("bundle")
        val assets = manifest.optJSONArray("assets") ?: org.json.JSONArray()
        return buildString {
            append("nitropush-release-v2\n")
            append(field("releaseId", releaseId.lowercase(Locale.ROOT)))
            append(field("projectId", projectId.lowercase(Locale.ROOT)))
            append(field("environment", environment))
            append(field("deploymentKeyHash", signedDeploymentKeyHash.lowercase(Locale.ROOT)))
            append(field("kind", kind))
            append("platforms:${platforms.size}\n")
            platforms.forEach { append(field("platform", it)) }
            append(field("runtimeVersion", targetAppVersion))
            append(field("label", label))
            append("otaVersion:${otaVersion.toLong()}\n")
            append("mandatory:${if (isMandatory) 1 else 0}\n")
            append(artifact("bundle", bundle))
            append("assets:${assets.length()}\n")
            for (i in 0 until assets.length()) append(artifact("asset", assets.getJSONObject(i)))
        }
    }

    private fun validateSignedContext(manifest: JSONObject, pkg: NPRemotePackage) {
        val expectedDeploymentHash = manifestDeploymentKeyHash[pkg.releaseId] ?: deploymentKeyHash
        val manifestPlatforms = manifest.getJSONArray("platforms").let { array ->
            List(array.length()) { array.getString(it) }.toSet()
        }
        check(manifest.getString("releaseId").equals(pkg.releaseId, ignoreCase = true) &&
            manifest.getString("kind") == pkg.kind &&
            manifest.getString("label") == pkg.label &&
            manifest.getString("targetAppVersion") == pkg.appVersion &&
            manifest.getDouble("otaVersion") == pkg.otaVersion &&
            manifest.getBoolean("isMandatory") == pkg.isMandatory &&
            manifestPlatforms == (pkg.platforms?.toSet() ?: emptySet<String>()) &&
            manifest.getString("deploymentKeyHash").lowercase(Locale.ROOT) == expectedDeploymentHash &&
            manifest.getJSONObject("bundle").getString("sha256") == pkg.packageHash.lowercase(Locale.ROOT)) {
            "latest-release metadata does not match the signed release envelope"
        }
        check("android" in manifestPlatforms) { "signed release does not target Android" }
    }

    private fun downloadManifestRelease(pkg: NPRemotePackage, releaseDir: File): String {
        val manifestUrl = manifestUrlOverride[pkg.releaseId]
            ?: resolveObjectUrl(pkg.downloadObjectKey)
        log("downloadManifestRelease") { "GET ${redactedUrl(manifestUrl)}" }
        val manifestText = httpGetString(manifestUrl)
        check(manifestText.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
            "release manifest exceeds the allowed size"
        }
        val manifest = try {
            JSONObject(manifestText)
        } catch (_: Throwable) {
            throw IllegalStateException(
                describeFetchFailure(
                    url = manifestUrl,
                    reason = "JSON parse failed",
                    responseBytes = manifestText.toByteArray(Charsets.UTF_8).size,
                ),
            )
        }

        val schemaVersion = manifest.optInt("schemaVersion", 2)
        check(schemaVersion == 2 || schemaVersion == 3 || schemaVersion == 4) {
            "unsupported release manifest schema $schemaVersion"
        }
        val bundleObj = manifest.getJSONObject("bundle")
        val bundleSha256 = bundleObj.getString("sha256")
        val bundleObjectKey = bundleObj.getString("objectKey")
        val bundleOriginalPath = bundleObj.getString("originalPath")
        val bundleSize = bundleObj.optInt("size", -1)
        val bundleSignature = bundleObj.optString("signature").takeIf { it.isNotEmpty() }
        check(pkg.packageHash.lowercase(Locale.ROOT) == bundleSha256) {
            "release metadata does not match its manifest bundle"
        }
        check(Regex("^[a-f0-9]{64}$").matches(bundleSha256)) { "bundle SHA-256 is invalid" }
        if (bundleSize >= 0) check(bundleSize in 1..(64 * 1024 * 1024)) {
            "bundle size is outside the allowed range"
        }

        val assets = manifest.optJSONArray("assets") ?: org.json.JSONArray()
        check(assets.length() <= 10_000) { "release manifest contains too many assets" }
        val occupied = mutableSetOf<String>()
        val bundleDest = safeReleaseDestination(bundleOriginalPath, releaseDir, occupied)
            .also { it.parentFile?.mkdirs() }
        var totalBytes = maxOf(bundleSize, 0).toLong()
        val assetDestinations = ArrayList<File>(assets.length())
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val assetHash = asset.getString("sha256")
            check(Regex("^[a-f0-9]{64}$").matches(assetHash)) { "asset SHA-256 is invalid" }
            val assetSize = asset.optInt("size", -1)
            if (assetSize >= 0) {
                check(assetSize in 1..(16 * 1024 * 1024)) { "asset size is outside the allowed range" }
                totalBytes += assetSize
            }
            assetDestinations += safeReleaseDestination(
                asset.getString("originalPath"), releaseDir, occupied,
            )
        }
        check(totalBytes <= 128L * 1024L * 1024L) { "release content exceeds the allowed size" }

        bundlePublicKey?.let { pubKey ->
            check(schemaVersion == 4) {
                "signed projects require manifest schema 4; refusing legacy/context downgrade"
            }
            validateSignedContext(manifest, pkg)
            check(bundleSignature != null) {
                "bundle is unsigned but a bundlePublicKey is configured — refusing to install"
            }
            verifyBundleSignature(bundleSha256, bundleSignature, pubKey)
            val releaseSignature = manifest.optString("releaseSignature")
                .takeIf { it.isNotEmpty() }
                ?: error("signed manifest is missing its release envelope signature")
            verifySignature(
                releaseIntegrityPayload(manifest),
                releaseSignature,
                pubKey,
                "release envelope",
            )
        }

        // Experimental delta path: attempt patch application, fall back to full download.
        val deltaObj = bundleObj.optJSONObject("delta")
        val selectedDelta = pkg.delta ?: deltaObj?.let {
            NPDeltaPatch(
                fromBundleHash = it.getString("fromBundleHash"),
                patchObjectKey = it.getString("patchObjectKey"),
                patchSize = it.getInt("patchSize"),
                patchSha256 = it.getString("patchSha256"),
                algorithm = it.optString("algorithm", "bsdiff4"),
            )
        }
        val currentBundleHash = currentBundleHashForDelta()
        val canUseDelta = enableDeltaUpdates && pkg.kind != "nativescript" &&
            selectedDelta != null &&
            currentBundleHash != null &&
            selectedDelta.algorithm == "bsdiff4" &&
            currentBundleHash == selectedDelta.fromBundleHash

        var usedDelta = false
        if (canUseDelta) {
            try {
                applyDeltaPatch(
                    patchObjectKey = selectedDelta.patchObjectKey,
                    patchSha256 = selectedDelta.patchSha256,
                    patchSize = selectedDelta.patchSize,
                    fromBundleHash = selectedDelta.fromBundleHash,
                    expectedOutputSha256 = bundleSha256,
                    deltaDownloadUrl = selectedDelta.deltaDownloadUrl,
                    dest = bundleDest,
                )
                usedDelta = true
                log("downloadManifestRelease → applied delta patch") { selectedDelta.patchSha256 }
                val patchSize = selectedDelta.patchSize
                val fullSize = pkg.packageSize.toInt()
                emit(
                    type = "download_delta_applied",
                    releaseId = pkg.releaseId,
                    appVersion = pkg.appVersion,
                    otaVersion = pkg.otaVersion,
                    metadata = JSONObject().apply {
                        put("patchSizeBytes", patchSize)
                        put("fullSizeBytes", fullSize)
                        put("savedBytes", maxOf(0, fullSize - patchSize))
                    },
                )
            } catch (e: Throwable) {
                log("downloadManifestRelease → delta failed, falling back") { e.message ?: "unknown error" }
                emit(
                    type = "download_delta_failed",
                    releaseId = pkg.releaseId,
                    appVersion = pkg.appVersion,
                    otaVersion = pkg.otaVersion,
                    metadata = JSONObject().apply { put("reason", classifyDeltaError(e)) },
                )
                bundleDest.delete()
            }
        }

        var filePatchBytes = 0
        var filePatchFullBytes = 0
        var filePatchFailed = false
        fun tryFileDelta(entry: JSONObject, dest: File, maximum: Long): Boolean {
            if (pkg.kind != "nativescript" || bundlePublicKey == null || !entry.has("fileDelta")) return false
            return try {
                val patch = entry.getJSONObject("fileDelta")
                if (!applyNativeScriptFileDelta(entry, patch, dest, maximum)) false
                else {
                    filePatchBytes += patch.getInt("patchSize")
                    filePatchFullBytes += entry.getInt("size")
                    true
                }
            } catch (_: Throwable) {
                filePatchFailed = true
                log("NativeScript file delta failed; downloading full file") { entry.getString("originalPath") }
                dest.delete()
                false
            }
        }
        if (!usedDelta) usedDelta = tryFileDelta(bundleObj, bundleDest, maxBundleBytes)
        var installedBytes = 0L
        if (!usedDelta) {
            val bundleDownloadUrl = bundleObj.optString("downloadUrl").takeIf { it.isNotEmpty() }
                ?: resolveObjectUrl(bundleObjectKey)
            installedBytes += fetchByContentHash(
                bundleDownloadUrl,
                bundleSha256,
                bundleDest,
                bundleSize.toLong(),
                maxBundleBytes,
            )
        } else {
            installedBytes += bundleDest.length()
        }
        check(installedBytes <= maxReleaseBytes) { "release content exceeds the allowed size" }

        val cachedHashes = mutableSetOf(bundleSha256)
        val total = assets.length()
        for (i in 0 until total) {
            val a = assets.getJSONObject(i)
            val sha256 = a.getString("sha256")
            val size = a.optLong("size", -1)
            val objectKey = a.getString("objectKey")
            val assetDownloadUrl = a.optString("downloadUrl").takeIf { it.isNotEmpty() }
                ?: resolveObjectUrl(objectKey)
            val dest = assetDestinations[i].also { it.parentFile?.mkdirs() }
            installedBytes += if (tryFileDelta(a, dest, maxAssetBytes)) size else fetchByContentHash(
                assetDownloadUrl, sha256, dest, size, maxAssetBytes,
            )
            check(installedBytes <= maxReleaseBytes) { "release content exceeds the allowed size" }
            cachedHashes.add(sha256)

            // Coarse progress in the absence of byte totals: 1 unit per asset.
            emitProgress(
                NPDownloadProgress(
                    receivedBytes = (i + 1).toDouble(),
                    totalBytes = total.toDouble(),
                )
            )
        }

        check(sha256Hex(bundleDest) == bundleSha256) {
            releaseDir.deleteRecursively()
            "bundle changed while assets were installed"
        }

        if (pkg.kind == "nativescript") {
            File(releaseDir, "verified-manifest.json").writeText(manifestText)
            if (filePatchFullBytes > 0) {
                log("NativeScript file deltas applied") { "patchBytes=$filePatchBytes fullBytes=$filePatchFullBytes savedBytes=${filePatchFullBytes - filePatchBytes}" }
                emit(type = "download_delta_applied", releaseId = pkg.releaseId, appVersion = pkg.appVersion, otaVersion = pkg.otaVersion,
                    metadata = JSONObject().put("patchSizeBytes", filePatchBytes).put("fullSizeBytes", filePatchFullBytes).put("savedBytes", filePatchFullBytes - filePatchBytes))
            }
            if (filePatchFailed) emit(type = "download_delta_failed", releaseId = pkg.releaseId, appVersion = pkg.appVersion, otaVersion = pkg.otaVersion,
                metadata = JSONObject().put("reason", "file_patch_fallback"))
        }
        writeReleaseFilesManifest(releaseDir, cachedHashes)
        return bundleDest.absolutePath
    }

    private fun applyNativeScriptFileDelta(entry: JSONObject, patch: JSONObject, dest: File, maximum: Long): Boolean {
        val active = readActive() ?: return false
        if (active.releaseId != patch.getString("baseReleaseId") || active.appVersion != appVersion) return false
        val size = entry.getInt("size")
        val patchSize = patch.getInt("patchSize")
        val fromHash = patch.getString("fromSha256")
        val patchHash = patch.getString("patchSha256")
        check(patch.getString("algorithm") == "npdiff1" && Regex("^[a-f0-9]{64}$").matches(fromHash) &&
            Regex("^[a-f0-9]{64}$").matches(patchHash) && patchSize >= 12 && patchSize < size && size.toLong() <= maximum) { "Invalid file delta metadata" }
        val root = releaseDirectory(applicationContext, active.releaseId)
        val path = entry.getString("originalPath")
        val source = File(root, path)
        check(path.startsWith("app/") && source.canonicalPath == source.absolutePath &&
            source.canonicalPath.startsWith(root.canonicalPath + File.separator) && source.isFile && source.length() in 1..maximum) { "Invalid file delta base path or size" }
        val base = source.readBytes()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(base.size.toLong() <= maximum && hash(base) == fromHash) { "File delta base hash mismatch" }
        val url = patch.optString("downloadUrl").takeIf { it.isNotEmpty() } ?: resolveObjectUrl(patch.getString("objectKey"))
        val temporary = File.createTempFile("nitropush-file-", ".npdiff", applicationContext.cacheDir)
        try {
            downloadToFile(url, temporary, patchHash, patchSize.toLong(), patchSize.toLong())
            val result = NPFileDelta.apply(base, temporary.readBytes(), maximum.toInt())
            check(result.size == size && hash(result) == entry.getString("sha256")) { "Reconstructed file hash mismatch" }
            dest.writeBytes(result)
            val cache = File(applicationContext.filesDir, "nitropush/cache").also { it.mkdirs() }
            File(cache, entry.getString("sha256")).writeBytes(result)
            return true
        } finally { temporary.delete() }
    }

    /**
     * Cross-release content-addressable cache: a file with sha256 == hash
     * is kept at `nitropush/cache/<hash>`. If present, hardlink/copy to
     * `dest` and skip the network. Otherwise download, verify, copy.
     */
    private fun fetchByContentHash(
        url: String,
        sha256: String,
        dest: File,
        announcedSize: Long,
        maximumBytes: Long,
    ): Long {
        val cache = File(applicationContext.filesDir, "nitropush/cache").also { it.mkdirs() }
        val cached = File(cache, sha256)
        if (cached.exists()) {
            if (sha256Hex(cached) != sha256 || cached.length() <= 0 || cached.length() > maximumBytes ||
                (announcedSize > 0 && cached.length() != announcedSize)) {
                cached.delete()
            } else {
                cached.setLastModified(System.currentTimeMillis())
                cached.copyTo(dest, overwrite = true)
                return dest.length()
            }
        }
        downloadToFile(
            url,
            cached,
            expectedSha256 = sha256,
            announcedSize = announcedSize,
            maximumBytes = maximumBytes,
        )
        cached.copyTo(dest, overwrite = true)
        check(dest.length() in 1..maximumBytes) { "download exceeds the allowed size" }
        return dest.length()
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Snapshot the bundle that is actually running and verify that its bytes
     * exactly match [expectedHash]. Before the first OTA this materializes the
     * APK/AAB asset; afterwards only the active OTA bundle is eligible. A
     * historical content-cache entry alone is deliberately not trusted.
     */
    private fun materializeDeltaBase(expectedHash: String): File {
        check(Regex("^[a-f0-9]{64}$").matches(expectedHash)) { "delta base hash is invalid" }
        val snapshot = File.createTempFile("nitropush-base-", ".bundle", applicationContext.cacheDir)
        try {
            val active = readActive()
            if (active != null) {
                check(active.bundleHash == expectedHash) {
                    "active bundle does not match the delta base"
                }
                val releaseDir = releaseDirectory(applicationContext, active.releaseId)
                val activeFile = File(active.bundlePath).canonicalFile
                check(
                    activeFile.path.startsWith(releaseDir.path + File.separator) &&
                        activeFile.isFile &&
                        activeFile.length() in 1..maxBundleBytes
                ) { "active delta base is outside its release directory" }
                activeFile.copyTo(snapshot, overwrite = true)
            } else {
                val embedded = embeddedBundleSource
                check(embedded != null && embedded.sha256 == expectedHash) {
                    "embedded bundle does not match the delta base"
                }
                applicationContext.assets.open(embedded.assetName).use { input ->
                    snapshot.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            check(written <= maxBundleBytes) {
                                "delta base size is outside the allowed range"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }

            check(snapshot.isFile && snapshot.length() in 1..maxBundleBytes) {
                "delta base size is outside the allowed range"
            }
            check(sha256Hex(snapshot) == expectedHash) { "delta base hash mismatch" }
            check(isHermesBundle(snapshot)) { "delta base is not a valid Hermes bundle" }
            return snapshot
        } catch (error: Throwable) {
            snapshot.delete()
            throw error
        }
    }

    /**
     * Download a bsdiff4 patch, verify its hash, apply it against an exact
     * snapshot of the currently running bundle using the JNI bspatch wrapper,
     * verify the output hash, then write the patched file to [dest].
     *
     * Throws on any failure — the caller falls back to full bundle download.
     */
    private fun applyDeltaPatch(
        patchObjectKey: String,
        patchSha256: String,
        patchSize: Int,
        fromBundleHash: String,
        expectedOutputSha256: String,
        deltaDownloadUrl: String?,
        dest: File,
    ) {
        check(Regex("^[a-f0-9]{64}$").matches(patchSha256) &&
            patchSize.toLong() in 1..maxBundleBytes) {
            "delta patch metadata is invalid"
        }
        val cache = File(applicationContext.filesDir, "nitropush/cache").also { it.mkdirs() }
        val baseSnapshot = materializeDeltaBase(fromBundleHash)

        try {
            val protectedUrl = deltaDownloadUrl?.let(::validatedDeltaDownloadUrl)
            val patchUrl = protectedUrl?.toString() ?: resolveObjectUrl(patchObjectKey)
            val tmpPatch = File.createTempFile("nitropush-patch-", ".bsdiff", applicationContext.cacheDir)
            try {
                downloadToFile(
                    patchUrl,
                    tmpPatch,
                    expectedSha256 = patchSha256,
                    announcedSize = patchSize.toLong(),
                    maximumBytes = maxBundleBytes,
                    includeDeviceToken = protectedUrl != null,
                )

                val rc = BspatchJni.patch(
                    baseSnapshot.absolutePath,
                    tmpPatch.absolutePath,
                    dest.absolutePath,
                )
                check(rc == 0) { "bspatch failed with code $rc" }

                // Verify output integrity.
                val actualSha256 = sha256Hex(dest)
                check(actualSha256 == expectedOutputSha256) {
                    "patched bundle hash mismatch (expected $expectedOutputSha256 got $actualSha256)"
                }
                check(isHermesBundle(dest)) { "patched file is not a valid Hermes bundle" }

                // Copy verified output into the content-hash cache for future use.
                val cachedOutput = File(cache, expectedOutputSha256)
                if (!cachedOutput.exists()) {
                    dest.copyTo(cachedOutput, overwrite = false)
                }
            } finally {
                tmpPatch.delete()
            }
        } finally {
            baseSnapshot.delete()
        }
    }

    /** GET → file with optional SHA-256 verification. */
    private fun downloadToFile(
        url: String,
        dest: File,
        expectedSha256: String,
        announcedSize: Long,
        maximumBytes: Long,
        includeDeviceToken: Boolean = false,
    ) {
        val safeUrl = validatedNetworkUrl(url, "asset download URL", allowQuery = true)
        val conn = openConnection(
            safeUrl,
            includeDeviceToken = includeDeviceToken,
            readTimeoutMs = 5 * 60_000,
        )
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("download returned HTTP $code from ${redactedUrl(url)}")
            }
            if (conn.contentLengthLong > maximumBytes ||
                (announcedSize > 0 && conn.contentLengthLong > announcedSize)) {
                error("download exceeds the allowed size")
            }

            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        written += n
                        if (written > maximumBytes) {
                            dest.delete()
                            error("download exceeds the allowed size")
                        }
                        if (announcedSize > 0 && written > announcedSize) {
                            dest.delete()
                            error("download exceeded announced size of $announcedSize bytes")
                        }
                        if (announcedSize > 0) {
                            emitProgress(
                                NPDownloadProgress(
                                    receivedBytes = written.toDouble(),
                                    totalBytes = announcedSize.toDouble(),
                                )
                            )
                        }
                    }
                }
            }
            if (announcedSize > 0 && dest.length() != announcedSize) {
                dest.delete()
                error("download size mismatch: expected $announcedSize, received ${dest.length()}")
            }
            if (expectedSha256.isNotEmpty()) {
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    dest.delete()
                    error("download integrity check failed")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Verify an ECDSA P-256 (SHA-256) bundle signature.
     *
     * @param sha256           Hex SHA-256 of the bundle bytes.
     * @param signatureBase64  Base64 DER-encoded ECDSA signature from the manifest.
     * @param publicKeyBase64  Base64 DER SubjectPublicKeyInfo from [NPConfig.bundlePublicKey].
     * @throws IllegalStateException if the signature is invalid or the key/sig can't be parsed.
     */
    private fun verifyBundleSignature(
        sha256: String,
        signatureBase64: String,
        publicKeyBase64: String,
    ) {
        verifySignature(
            "bundle:$sha256",
            signatureBase64,
            publicKeyBase64,
            "bundle",
        )
    }

    private fun verifySignature(
        message: String,
        signatureBase64: String,
        publicKeyBase64: String,
        description: String,
    ) {
        val sigBytes = try {
            Base64.decode(signatureBase64, Base64.DEFAULT)
        } catch (e: Throwable) {
            error("$description signature is not valid base64: ${e.message}")
        }
        val publicKey = parseBundlePublicKey(publicKeyBase64)
        val valid = try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(message.toByteArray(Charsets.UTF_8))
            sig.verify(sigBytes)
        } catch (e: Throwable) {
            error("bundle signature verification error: ${e.message}")
        }
        check(valid) { "$description signature mismatch" }
    }

    /** GET → string. Used for the small Expo manifest fetch. */
    private fun httpGetString(url: String): String {
        val parsed = validatedNetworkUrl(url, "manifest download URL", allowQuery = true)
        val api = serverUrl?.let { URL(it) }
        val conn = openConnection(
            parsed,
            includeDeviceToken = api != null && sameOrigin(parsed, api),
        )
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error(
                    describeFetchFailure(
                        url = url,
                        reason = "non-2xx HTTP $code",
                        contentType = conn.contentType,
                    )
                )
            }
            check(conn.contentLengthLong <= maxManifestBytes) {
                "release manifest exceeds the allowed size"
            }
            return readBounded(conn.inputStream, maxManifestBytes).toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Diagnostic that deliberately omits presigned query strings and body
     * contents so errors can safely reach logcat/crash reporting.
     */
    private fun describeFetchFailure(
        url: String,
        reason: String,
        contentType: String? = null,
        responseBytes: Int? = null,
    ): String {
        return "$reason — url=${redactedUrl(url)}" +
            (contentType?.let { " contentType=$it" } ?: "") +
            (responseBytes?.let { " responseBytes=$it" } ?: "")
    }

    private fun persistPending(pkg: NPLocalPackage) {
        prefs.edit().putString(Keys.PENDING, pkg.toJson().toString()).apply()
    }

    @Synchronized
    private fun activatePending() {
        val pendingJson = prefs.getString(Keys.PENDING, null) ?: return
        val editor = prefs.edit()
        prefs.getString(Keys.ACTIVE, null)?.let { editor.putString(Keys.PREVIOUS, it) }
        editor.putString(Keys.ACTIVE, pendingJson)
        editor.remove(Keys.PENDING)
        editor.putBoolean(Keys.UNCONFIRMED, true)
        editor.apply()
    }

    private fun readActive(): NPLocalPackage? = runCatching {
        prefs.getString(Keys.ACTIVE, null)?.let { NPLocalPackage.fromJson(JSONObject(it)) }
    }.getOrNull()

    private fun readPending(): NPLocalPackage? = runCatching {
        prefs.getString(Keys.PENDING, null)?.let { NPLocalPackage.fromJson(JSONObject(it)) }
    }.getOrNull()

    private fun readPrevious(): NPLocalPackage? = runCatching {
        prefs.getString(Keys.PREVIOUS, null)?.let { NPLocalPackage.fromJson(JSONObject(it)) }
    }.getOrNull()

    private fun deleteBundleDir(releaseId: String) {
        runCatching { releaseDirectory(applicationContext, releaseId) }
            .getOrNull()
            ?.deleteRecursively()
    }

    private fun pruneUnusedBundlesAndCache() {
        val protectedPackages = listOfNotNull(readActive(), readPending(), readPrevious())
        val protectedReleaseIds = protectedPackages.map { it.releaseId }.toSet()
        val protectedHashes = protectedPackages
            .flatMap { readReleaseFileHashes(it.releaseId) }
            .toSet()
        val root = releaseRoot(applicationContext)

        root.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) return@forEach
            if (entry.name == "cache") return@forEach
            if (entry.name in protectedReleaseIds) return@forEach
            entry.deleteRecursively()
        }

        pruneContentCache(protectedHashes)
    }

    private fun writeReleaseFilesManifest(releaseDir: File, hashes: Set<String>) {
        val arr = org.json.JSONArray()
        hashes.sorted().forEach { arr.put(it) }
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("cachedHashes", arr)
        runCatching {
            File(releaseDir, releaseFilesManifestName).writeText(payload.toString())
        }
    }

    private fun readReleaseFileHashes(releaseId: String): List<String> {
        val releaseDir = runCatching { releaseDirectory(applicationContext, releaseId) }
            .getOrNull() ?: return emptyList()
        val manifest = File(releaseDir, releaseFilesManifestName)
        if (!manifest.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(manifest.readText()).optJSONArray("cachedHashes")
                ?: return@runCatching emptyList()
            List(arr.length()) { idx -> arr.getString(idx) }
        }.getOrDefault(emptyList())
    }

    private fun pruneContentCache(protectedHashes: Set<String>) {
        val cache = File(applicationContext.filesDir, "nitropush/cache")
        val files = cache.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        var totalBytes = files.sumOf { it.length() }
        val candidates = mutableListOf<File>()

        for (file in files) {
            if (file.name in protectedHashes) continue
            candidates.add(file)
            if (now - file.lastModified() > maxUnprotectedCacheAgeMs) {
                val size = file.length()
                if (file.delete()) totalBytes -= size
            }
        }

        if (totalBytes <= maxCacheBytes) return
        for (file in candidates.sortedBy { it.lastModified() }) {
            if (totalBytes <= targetCacheBytesAfterPrune) break
            if (!file.exists()) continue
            val size = file.length()
            if (file.delete()) totalBytes -= size
        }
    }

    private fun persistFlag(
        releaseId: String,
        isFirstRun: Boolean = false,
        isFailedInstall: Boolean = false,
    ) {
        val activeJson = prefs.getString(Keys.ACTIVE, null) ?: return
        val obj = JSONObject(activeJson)
        if (obj.optString("releaseId") != releaseId) return
        if (isFirstRun) obj.put("isFirstRun", true)
        if (isFailedInstall) obj.put("isFailedInstall", true)
        prefs.edit().putString(Keys.ACTIVE, obj.toString()).apply()
    }

    private fun reloadBridge() {
        NPHostRuntime.reload(applicationContext)
    }

    private fun binaryAppVersion(): String? = try {
        val pkg = applicationContext.packageName
        applicationContext.packageManager.getPackageInfo(pkg, 0).versionName
    } catch (_: Throwable) {
        null
    }

    private fun fallbackDeviceId(): String {
        val key = "nitropush.fallbackDeviceId"
        prefs.getString(key, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(key, id).apply()
        return id
    }
}

private fun NPLocalPackage.toJson(): JSONObject = JSONObject().apply {
    put("releaseId", releaseId)
    put("label", label)
    put("packageHash", packageHash)
    put("packageSize", packageSize)
    put("appVersion", appVersion)
    otaVersion?.let { put("otaVersion", it) }
    displayVersion?.let { put("displayVersion", it) }
    platforms?.let {
        val arr = org.json.JSONArray()
        for (p in it) arr.put(p)
        put("platforms", arr)
    }
    put("isMandatory", isMandatory)
    put("isPending", isPending)
    put("isFailedInstall", isFailedInstall)
    put("isFirstRun", isFirstRun)
    put("bundlePath", bundlePath)
    description?.let { put("description", it) }
    bundleHash?.let { put("bundleHash", it) }
}

private fun NPLocalPackage.Companion.fromJson(obj: JSONObject): NPLocalPackage {
    val releaseId = obj.getString("releaseId")
    validateReleaseId(releaseId)
    val platformsArr = obj.optJSONArray("platforms")
    val platforms = if (platformsArr != null) {
        Array(platformsArr.length()) { platformsArr.getString(it) }
    } else null
    return NPLocalPackage(
        releaseId = releaseId,
        label = obj.getString("label"),
        packageHash = obj.getString("packageHash"),
        packageSize = obj.getDouble("packageSize"),
        appVersion = obj.getString("appVersion"),
        otaVersion = if (obj.has("otaVersion") && !obj.isNull("otaVersion"))
            obj.getDouble("otaVersion") else null,
        displayVersion = obj.optString("displayVersion").takeIf { it.isNotEmpty() },
        platforms = platforms,
        isMandatory = obj.optBoolean("isMandatory", false),
        description = obj.optString("description").takeIf { it.isNotEmpty() },
        isPending = obj.optBoolean("isPending", false),
        isFailedInstall = obj.optBoolean("isFailedInstall", false),
        isFirstRun = obj.optBoolean("isFirstRun", false),
        bundlePath = obj.getString("bundlePath"),
        bundleHash = obj.optString("bundleHash").takeIf { it.isNotEmpty() },
    )
}
