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
import com.facebook.react.ReactApplication
import com.facebook.react.ReactNativeHost
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

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
    /** SHA-256 of the JS bundle packaged in the APK/AAB, used before the first OTA. */
    private val embeddedBundleHash: String? by lazy {
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
            if (digest != null) return@lazy digest
        }
        null
    }

    private val progressListeners = mutableMapOf<Int, (NPDownloadProgress) -> Unit>()
    private var nextListenerId = 1

    /** releaseId → pre-signed manifest proxy URL from the server. */
    private val manifestUrlOverride = mutableMapOf<String, String>()

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
    private val maxCacheBytes = 50L * 1024L * 1024L
    private val targetCacheBytesAfterPrune = 40L * 1024L * 1024L
    private val maxUnprotectedCacheAgeMs = 14L * 24L * 60L * 60L * 1000L

    fun configure(config: NPConfig) {
        log("configure") {
            "serverUrl=${config.serverUrl} deploymentKey=${config.deploymentKey.take(20)}… " +
                "storageBaseUrl=${config.storageBaseUrl} appVersion=${config.appVersion ?: "(auto)"}"
        }
        serverUrl = config.serverUrl.trimEnd('/')
        deploymentKey = config.deploymentKey
        val storage = config.storageBaseUrl.trim()
        require(storage.isNotEmpty()) { "storageBaseUrl is required" }
        storageBaseUrl = storage.trimEnd('/')
        appVersion = config.appVersion ?: binaryAppVersion()
        clientUniqueId = config.clientUniqueId ?: fallbackDeviceId()
        val tokenScopeHash = MessageDigest.getInstance("SHA-256")
            .digest("${serverUrl}\u0000${config.deploymentKey}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val tokenKey = "nitropush.deviceToken.$tokenScopeHash"
        deviceTokenStorageKey = tokenKey
        deviceToken = prefs.getString(tokenKey, null)
        bundlePublicKey = config.bundlePublicKey
        enableDeltaUpdates = config.enableDeltaUpdates

        // Replace any prior emitter — re-configure can change endpoint
        // or deployment key, and the in-flight queue is no longer valid.
        analytics?.stop()
        analytics = NPAnalytics(
            serverUrl = config.serverUrl,
            deploymentKey = config.deploymentKey,
        )

        emit(type = "app_started")
        // The launch-time pointer sweep ran before `configure()` and only
        // mutated state. Now that the emitter is wired, replay any pending
        // rollback event once.
        detectAndReportRollback()
        pruneUnusedBundlesAndCache()
    }

    /** Resolve a bucket-relative `objectKey` to an absolute URL. */
    private fun resolveObjectUrl(objectKey: String): String {
        val base = storageBaseUrl
            ?: error("NitroPushSdk.configure(...) was not called.")
        val key = if (objectKey.startsWith("/")) objectKey.substring(1) else objectKey
        return "$base/$key"
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
    fun activeBundleFile(): String? {
        val active = readActive() ?: return null
        val file = File(active.bundlePath)
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
        val key = deploymentKey ?: error("deploymentKey not set")

        val params = mutableMapOf(
            "deploymentKey" to key,
            "platform" to "android",
        )
        appVersion?.let { params["appVersion"] = it }
        clientUniqueId?.let { params["clientUniqueId"] = it }
        val active = readActive()
        active?.let {
            params["currentReleaseId"] = active.releaseId
        }
        if (enableDeltaUpdates) {
            (active?.bundleHash ?: embeddedBundleHash)?.let { params["currentBundleHash"] = it }
        }

        val query = params.entries.joinToString("&") {
            "${Uri.encode(it.key)}=${Uri.encode(it.value)}"
        }
        val url = URL("$server/api/sdk/releases/latest?$query")
        log("checkForUpdate") { "GET $url" }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 60_000
            readTimeout = 60_000
            deviceToken?.let { setRequestProperty("x-nitropush-device-token", it) }
        }
        try {
            val code = conn.responseCode
            conn.getHeaderField("x-nitropush-device-token")
                ?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 2_048 }
                ?.let { issuedToken ->
                    deviceToken = issuedToken
                    deviceTokenStorageKey?.let { prefs.edit().putString(it, issuedToken).apply() }
                }
            if (code == 204) return null
            if (code !in 200..299) {
                val errBody = runCatching {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: ""
                error(
                    describeFetchFailure(
                        url = url.toString(),
                        body = errBody,
                        reason = "checkForUpdate non-2xx HTTP $code",
                        contentType = conn.contentType,
                    )
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = try {
                JSONObject(body)
            } catch (e: Throwable) {
                throw IllegalStateException(
                    describeFetchFailure(
                        url = url.toString(),
                        body = body,
                        reason = "checkForUpdate JSON parse failed: ${e.message}",
                        contentType = conn.contentType,
                    ),
                    e,
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
                    )
                } else null,
            )
            // Cache the pre-signed manifest proxy URL for use in downloadManifestRelease.
            r.optString("downloadUrl").takeIf { it.isNotEmpty() }?.let {
                manifestUrlOverride[pkg.releaseId] = if (it.startsWith("http://") || it.startsWith("https://")) {
                    it
                } else {
                    "${server.trimEnd('/')}/${it.trimStart('/')}"
                }
            }
            return pkg
        } finally {
            conn.disconnect()
        }
    }

    private fun performDownload(pkg: NPRemotePackage): NPLocalPackage {
        val releaseDir = File(applicationContext.filesDir, "nitropush/${pkg.releaseId}")
        if (releaseDir.exists()) releaseDir.deleteRecursively()
        releaseDir.mkdirs()

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

    private fun downloadManifestRelease(pkg: NPRemotePackage, releaseDir: File): String {
        val manifestUrl = manifestUrlOverride[pkg.releaseId]
            ?: resolveObjectUrl(pkg.downloadObjectKey)
        log("downloadManifestRelease") { "GET $manifestUrl" }
        val manifestText = httpGetString(manifestUrl)
        check(manifestText.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
            "release manifest exceeds the allowed size"
        }
        val manifest = try {
            JSONObject(manifestText)
        } catch (e: Throwable) {
            // Most common cause: the server returned an HTML error page or
            // an S3/MinIO XML envelope with a 200 status. Surface URL + body
            // snippet so the failure is self-diagnosing instead of just
            // "Value <... of type String cannot be converted to JSONObject".
            throw IllegalStateException(
                describeFetchFailure(
                    url = manifestUrl,
                    body = manifestText,
                    reason = "JSON parse failed: ${e.message}",
                ),
                e,
            )
        }

        val schemaVersion = manifest.optInt("schemaVersion", 2)
        check(schemaVersion == 2 || schemaVersion == 3) {
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
            check(schemaVersion == 3) {
                "signed projects require manifest schema 3; refusing legacy downgrade"
            }
            check(bundleSignature != null) {
                "bundle is unsigned but a bundlePublicKey is configured — refusing to install"
            }
            verifyBundleSignature(bundleSha256, bundleSignature, pubKey)
            val integritySignature = manifest.optString("integritySignature")
                .takeIf { it.isNotEmpty() }
                ?: error("signed manifest is missing its integrity signature")
            verifySignature(
                manifestIntegrityPayload(manifest),
                integritySignature,
                pubKey,
                "release manifest",
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
        val activeBundleHash = readActive()?.bundleHash
        val canUseDelta = enableDeltaUpdates &&
            selectedDelta != null &&
            activeBundleHash != null &&
            selectedDelta.algorithm == "bsdiff4" &&
            activeBundleHash == selectedDelta.fromBundleHash

        var usedDelta = false
        if (canUseDelta && selectedDelta != null) {
            try {
                applyDeltaPatch(
                    patchObjectKey = selectedDelta.patchObjectKey,
                    patchSha256 = selectedDelta.patchSha256,
                    patchSize = selectedDelta.patchSize,
                    fromBundleHash = selectedDelta.fromBundleHash,
                    expectedOutputSha256 = bundleSha256,
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

        if (!usedDelta) {
            val bundleDownloadUrl = bundleObj.optString("downloadUrl").takeIf { it.isNotEmpty() }
                ?: resolveObjectUrl(bundleObjectKey)
            fetchByContentHash(bundleDownloadUrl, bundleSha256, bundleDest, bundleSize.toLong())
        }

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
            fetchByContentHash(assetDownloadUrl, sha256, dest, size)
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

        writeReleaseFilesManifest(releaseDir, cachedHashes)
        return bundleDest.absolutePath
    }

    /**
     * Cross-release content-addressable cache: a file with sha256 == hash
     * is kept at `nitropush/cache/<hash>`. If present, hardlink/copy to
     * `dest` and skip the network. Otherwise download, verify, copy.
     */
    private fun fetchByContentHash(url: String, sha256: String, dest: File, announcedSize: Long) {
        val cache = File(applicationContext.filesDir, "nitropush/cache").also { it.mkdirs() }
        val cached = File(cache, sha256)
        if (cached.exists()) {
            if (sha256Hex(cached) != sha256 || (announcedSize > 0 && cached.length() != announcedSize)) {
                cached.delete()
            } else {
                cached.setLastModified(System.currentTimeMillis())
                cached.copyTo(dest, overwrite = true)
                return
            }
        }
        downloadToFile(url, cached, expectedSha256 = sha256, announcedSize = announcedSize)
        cached.copyTo(dest, overwrite = true)
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
     * Download a bsdiff4 patch, verify its hash, apply it against the cached
     * base bundle using the JNI bspatch wrapper, verify the output hash, then
     * write the patched file to [dest].
     *
     * Throws on any failure — the caller falls back to full bundle download.
     */
    private fun applyDeltaPatch(
        patchObjectKey: String,
        patchSha256: String,
        patchSize: Int,
        fromBundleHash: String,
        expectedOutputSha256: String,
        dest: File,
    ) {
        val cache = File(applicationContext.filesDir, "nitropush/cache").also { it.mkdirs() }
        val baseCached = File(cache, fromBundleHash)
        check(baseCached.exists()) { "base bundle not in cache: $fromBundleHash" }

        val patchUrl = resolveObjectUrl(patchObjectKey)
        val tmpPatch = File.createTempFile("nitropush-patch-", ".bsdiff", applicationContext.cacheDir)
        try {
            downloadToFile(patchUrl, tmpPatch, expectedSha256 = patchSha256, announcedSize = patchSize.toLong())

            val rc = BspatchJni.patch(baseCached.absolutePath, tmpPatch.absolutePath, dest.absolutePath)
            check(rc == 0) { "bspatch failed with code $rc" }

            // Verify output integrity.
            val actualDigest = java.security.MessageDigest.getInstance("SHA-256").digest(dest.readBytes())
            val actualSha256 = actualDigest.joinToString("") { "%02x".format(it) }
            check(actualSha256 == expectedOutputSha256) {
                "patched bundle hash mismatch (expected $expectedOutputSha256 got $actualSha256)"
            }

            // Copy verified output into the content-hash cache for future use.
            val cachedOutput = File(cache, expectedOutputSha256)
            if (!cachedOutput.exists()) {
                dest.copyTo(cachedOutput, overwrite = false)
            }
        } finally {
            tmpPatch.delete()
        }
    }

    /** GET → file with optional SHA-256 verification. */
    private fun downloadToFile(
        url: String,
        dest: File,
        expectedSha256: String,
        announcedSize: Long,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 60_000
            readTimeout = 5 * 60_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("downloadUpdate: HTTP $code from $url")

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
                    error("integrity check failed for $url (expected $expectedSha256 got $actual)")
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
        val pubKeyBytes = try {
            Base64.decode(publicKeyBase64, Base64.DEFAULT)
        } catch (e: Throwable) {
            error("bundlePublicKey is not valid base64: ${e.message}")
        }
        val sigBytes = try {
            Base64.decode(signatureBase64, Base64.DEFAULT)
        } catch (e: Throwable) {
            error("$description signature is not valid base64: ${e.message}")
        }
        val publicKey = try {
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(pubKeyBytes))
        } catch (e: Throwable) {
            error("bundlePublicKey parse failed: ${e.message}")
        }
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
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 60_000
            readTimeout = 60_000
            deviceToken?.let { setRequestProperty("x-nitropush-device-token", it) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: ""
                error(
                    describeFetchFailure(
                        url = url,
                        body = body,
                        reason = "non-2xx HTTP $code",
                        contentType = conn.contentType,
                    )
                )
            }
            return conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    check(total <= 2 * 1024 * 1024) { "release manifest exceeds the allowed size" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Build a self-diagnosing error string for an HTTP fetch that didn't
     * produce the expected JSON. Includes the URL, optional content-type,
     * and a 200-char body snippet so the failure tells the operator exactly
     * what the server returned.
     */
    private fun describeFetchFailure(
        url: String,
        body: String,
        reason: String,
        contentType: String? = null,
    ): String {
        val snippet = if (body.length > 200) {
            body.substring(0, 200) + "…(+${body.length - 200} more chars)"
        } else body
        return "$reason — url=$url" +
            (contentType?.let { " contentType=$it" } ?: "") +
            " body=$snippet"
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

    private fun readActive(): NPLocalPackage? =
        prefs.getString(Keys.ACTIVE, null)?.let { NPLocalPackage.fromJson(JSONObject(it)) }

    private fun readPending(): NPLocalPackage? =
        prefs.getString(Keys.PENDING, null)?.let { NPLocalPackage.fromJson(JSONObject(it)) }

    private fun readPrevious(): NPLocalPackage? =
        prefs.getString(Keys.PREVIOUS, null)?.let { NPLocalPackage.fromJson(JSONObject(it)) }

    private fun deleteBundleDir(releaseId: String) {
        File(applicationContext.filesDir, "nitropush/$releaseId").deleteRecursively()
    }

    private fun pruneUnusedBundlesAndCache() {
        val protectedPackages = listOfNotNull(readActive(), readPending(), readPrevious())
        val protectedReleaseIds = protectedPackages.map { it.releaseId }.toSet()
        val protectedHashes = protectedPackages
            .flatMap { readReleaseFileHashes(it.releaseId) }
            .toSet()
        val root = File(applicationContext.filesDir, "nitropush")

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
        val manifest = File(
            File(applicationContext.filesDir, "nitropush/$releaseId"),
            releaseFilesManifestName,
        )
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
        Handler(Looper.getMainLooper()).post {
            try {
                val app = applicationContext as? Application ?: return@post
                val host: ReactNativeHost = (app as? ReactApplication)?.reactNativeHost ?: return@post
                if (host.hasInstance()) {
                    host.reactInstanceManager.recreateReactContextInBackground()
                }
            } catch (_: Throwable) {
                // Host app isn't a ReactApplication during early bootstrap.
            }
        }
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
    val platformsArr = obj.optJSONArray("platforms")
    val platforms = if (platformsArr != null) {
        Array(platformsArr.length()) { platformsArr.getString(it) }
    } else null
    return NPLocalPackage(
        releaseId = obj.getString("releaseId"),
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
