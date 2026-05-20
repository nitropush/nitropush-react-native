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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
         * Build an [NlConfig] by reading `NITROPUSH_*` keys from the app's
         * `<application>` `<meta-data>` tags in `AndroidManifest.xml`. Mirror
         * of iOS's `configFromInfoPlist()`. Only `NITROPUSH_DEPLOYMENT_KEY` is
         * required — server and storage URLs fall back to the NitroPush-hosted
         * endpoints when absent.
         */
        @JvmStatic
        fun configFromManifest(): NlConfig {
            val ctx = shared.applicationContext
            val ai = ctx.packageManager.getApplicationInfo(
                ctx.packageName,
                PackageManager.GET_META_DATA,
            )
            val meta = ai.metaData
                ?: error("AndroidManifest.xml is missing NITROPUSH_DEPLOYMENT_KEY <meta-data>")
            val deploymentKey = meta.getString("NITROPUSH_DEPLOYMENT_KEY")
                ?: error("missing NITROPUSH_DEPLOYMENT_KEY in AndroidManifest meta-data")
            return NlConfig(
                serverUrl = meta.getString("NITROPUSH_SERVER_URL") ?: "https://api.nitropush.org",
                deploymentKey = deploymentKey,
                storageBaseUrl = meta.getString("NITROPUSH_STORAGE_BASE_URL") ?: "https://cdn.nitropush.org",
                appVersion = meta.getString("NITROPUSH_APP_VERSION"),
                clientUniqueId = meta.getString("NITROPUSH_CLIENT_UNIQUE_ID"),
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

    private val progressListeners = mutableMapOf<Int, (NlDownloadProgress) -> Unit>()
    private var nextListenerId = 1

    private var pendingResume: Pair<NlLocalPackage, Long>? = null
    private var pendingSuspend: NlLocalPackage? = null
    private var lastBackgroundedAt: Long = 0

    /**
     * Native analytics emitter. Replaces the deleted JS-side
     * `createAnalyticsEmitter` so events fire even when the JS thread
     * hasn't loaded (cold-start, post-rollback boots).
     */
    private var analytics: NlAnalytics? = null

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

    fun configure(config: NlConfig) {
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

        // Replace any prior emitter — re-configure can change endpoint
        // or deployment key, and the in-flight queue is no longer valid.
        analytics?.stop()
        analytics = NlAnalytics(
            serverUrl = config.serverUrl,
            deploymentKey = config.deploymentKey,
        )

        emit(type = "app_started")
        // The launch-time pointer sweep ran before `configure()` and only
        // mutated state. Now that the emitter is wired, replay any pending
        // rollback event once.
        detectAndReportRollback()
    }

    /** Resolve a bucket-relative `objectKey` to an absolute URL. */
    private fun resolveObjectUrl(objectKey: String): String {
        val base = storageBaseUrl
            ?: error("NitroPushSdk.configure(...) was not called.")
        val key = if (objectKey.startsWith("/")) objectKey.substring(1) else objectKey
        return "$base/$key"
    }

    /** Blocking. Run on a worker thread (the Nitro bridge uses Promise.async). */
    fun checkForUpdate(deploymentKeyOverride: String? = null): NlRemotePackage? {
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
    fun downloadUpdate(pkg: NlRemotePackage): NlLocalPackage {
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
        pkg: NlLocalPackage,
        installMode: NlInstallMode,
        minimumBackgroundDurationSeconds: Double,
    ) {
        log("installUpdate") {
            "releaseId=${pkg.releaseId} mode=$installMode minBgDur=${minimumBackgroundDurationSeconds}s"
        }
        persistPending(pkg)
        when (installMode) {
            NlInstallMode.IMMEDIATE -> {
                log("installUpdate.IMMEDIATE → activate + reload")
                activatePending()
                reloadBridge()
            }
            NlInstallMode.ON_NEXT_RESTART -> {
                log("installUpdate.ON_NEXT_RESTART") { "staged; will activate on next cold start" }
            }
            NlInstallMode.ON_NEXT_RESUME -> {
                pendingResume = pkg to (minimumBackgroundDurationSeconds.toLong() * 1000L)
                log("installUpdate.ON_NEXT_RESUME") { "scheduled after ≥${minimumBackgroundDurationSeconds}s background" }
            }
            NlInstallMode.ON_NEXT_SUSPEND -> {
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

    fun getCurrentPackage(): NlLocalPackage? = readActive()
    fun getPendingPackage(): NlLocalPackage? = readPending()

    fun clearPendingUpdate() {
        val pending = readPending()
        log("clearPendingUpdate") { "pending=${pending?.releaseId ?: "(none)"}" }
        pending?.let { deleteBundleDir(it.releaseId) }
        prefs.edit().remove(Keys.PENDING).apply()
        pendingResume = null
        pendingSuspend = null
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
        reloadBridge()
    }

    fun clearUpdates() {
        log("clearUpdates") { "wiping prefs + ${File(applicationContext.filesDir, "nitropush").absolutePath}" }
        prefs.edit().clear().apply()
        File(applicationContext.filesDir, "nitropush").deleteRecursively()
    }

    fun addDownloadProgressListener(callback: (NlDownloadProgress) -> Unit): Int {
        val id = nextListenerId++
        progressListeners[id] = callback
        return id
    }

    fun removeDownloadProgressListener(listenerId: Int) {
        progressListeners.remove(listenerId)
    }

    private fun emitProgress(progress: NlDownloadProgress) {
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
    ) {
        val a = analytics ?: return
        a.enqueue(
            NlAnalyticsEvent(
                eventType = type,
                clientUniqueId = clientUniqueId ?: fallbackDeviceId(),
                appVersion = appVersion ?: this.appVersion ?: "*",
                otaVersion = otaVersion,
                releaseId = releaseId,
                platform = "android",
                osVersion = NlAnalyticsContext.osVersion(),
                deviceModel = NlAnalyticsContext.deviceModel(),
                occurredAt = NlAnalyticsContext.now(),
            )
        )
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
            NlLocalPackage.fromJson(JSONObject(raw))
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
        return if (file.exists()) file.absolutePath else null
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
                    }
                }
            })
        }
    }

    private fun requestLatestRelease(deploymentKey: String?): NlRemotePackage? {
        val server = serverUrl ?: error("NitroPushSdk.configure(...) was not called.")
        val key = deploymentKey ?: error("deploymentKey not set")

        val params = mutableMapOf(
            "deploymentKey" to key,
            "platform" to "android",
        )
        appVersion?.let { params["appVersion"] = it }
        clientUniqueId?.let { params["clientUniqueId"] = it }
        readActive()?.let { params["currentReleaseId"] = it.releaseId }

        val query = params.entries.joinToString("&") {
            "${Uri.encode(it.key)}=${Uri.encode(it.value)}"
        }
        val url = URL("$server/api/sdk/releases/latest?$query")
        log("checkForUpdate") { "GET $url" }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 60_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
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
            return NlRemotePackage(
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
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun performDownload(pkg: NlRemotePackage): NlLocalPackage {
        val releaseDir = File(applicationContext.filesDir, "nitropush/${pkg.releaseId}")
        if (releaseDir.exists()) releaseDir.deleteRecursively()
        releaseDir.mkdirs()

        // Both kinds (`expo` and `codepush`) ship a manifest at
        // `pkg.downloadObjectKey`. The manifest layout is identical across
        // kinds — the distinction lives only at the API/DB layer.
        val bundlePath = downloadManifestRelease(pkg, releaseDir)

        return NlLocalPackage(
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
    private fun downloadManifestRelease(pkg: NlRemotePackage, releaseDir: File): String {
        val manifestUrl = resolveObjectUrl(pkg.downloadObjectKey)
        log("downloadManifestRelease") { "GET $manifestUrl" }
        val manifestText = httpGetString(manifestUrl)
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

        val bundleObj = manifest.getJSONObject("bundle")
        val bundleSha256 = bundleObj.getString("sha256")
        val bundleObjectKey = bundleObj.getString("objectKey")
        val bundleOriginalPath = bundleObj.getString("originalPath")
        val bundleDest = File(releaseDir, bundleOriginalPath).also { it.parentFile?.mkdirs() }
        fetchByContentHash(resolveObjectUrl(bundleObjectKey), bundleSha256, bundleDest)

        val assets = manifest.optJSONArray("assets") ?: org.json.JSONArray()
        val total = assets.length()
        for (i in 0 until total) {
            val a = assets.getJSONObject(i)
            val originalPath = a.getString("originalPath")
            val sha256 = a.getString("sha256")
            val objectKey = a.getString("objectKey")
            val dest = File(releaseDir, originalPath).also { it.parentFile?.mkdirs() }
            fetchByContentHash(resolveObjectUrl(objectKey), sha256, dest)

            // Coarse progress in the absence of byte totals: 1 unit per asset.
            emitProgress(
                NlDownloadProgress(
                    receivedBytes = (i + 1).toDouble(),
                    totalBytes = total.toDouble(),
                )
            )
        }

        return bundleDest.absolutePath
    }

    /**
     * Cross-release content-addressable cache: a file with sha256 == hash
     * is kept at `nitropush/cache/<hash>`. If present, hardlink/copy to
     * `dest` and skip the network. Otherwise download, verify, copy.
     */
    private fun fetchByContentHash(url: String, sha256: String, dest: File) {
        val cache = File(applicationContext.filesDir, "nitropush/cache").also { it.mkdirs() }
        val cached = File(cache, sha256)
        if (cached.exists()) {
            cached.copyTo(dest, overwrite = true)
            return
        }
        downloadToFile(url, cached, expectedSha256 = sha256, announcedSize = -1)
        cached.copyTo(dest, overwrite = true)
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
                        if (announcedSize > 0) {
                            emitProgress(
                                NlDownloadProgress(
                                    receivedBytes = written.toDouble(),
                                    totalBytes = announcedSize.toDouble(),
                                )
                            )
                        }
                    }
                }
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

    /** GET → string. Used for the small Expo manifest fetch. */
    private fun httpGetString(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 60_000
            readTimeout = 60_000
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
            return conn.inputStream.bufferedReader().use { it.readText() }
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

    private fun persistPending(pkg: NlLocalPackage) {
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

    private fun readActive(): NlLocalPackage? =
        prefs.getString(Keys.ACTIVE, null)?.let { NlLocalPackage.fromJson(JSONObject(it)) }

    private fun readPending(): NlLocalPackage? =
        prefs.getString(Keys.PENDING, null)?.let { NlLocalPackage.fromJson(JSONObject(it)) }

    private fun deleteBundleDir(releaseId: String) {
        File(applicationContext.filesDir, "nitropush/$releaseId").deleteRecursively()
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

private fun NlLocalPackage.toJson(): JSONObject = JSONObject().apply {
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
}

private fun NlLocalPackage.Companion.fromJson(obj: JSONObject): NlLocalPackage {
    val platformsArr = obj.optJSONArray("platforms")
    val platforms = if (platformsArr != null) {
        Array(platformsArr.length()) { platformsArr.getString(it) }
    } else null
    return NlLocalPackage(
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
    )
}

