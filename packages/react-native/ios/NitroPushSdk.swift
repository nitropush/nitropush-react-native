import CryptoKit
import Foundation
import UIKit

#if canImport(React)
import React
#endif

/**
 * Plain iOS implementation of the NitroPush OTA core.
 *
 * **Has zero dependency on Nitro Modules.** The Nitrogen-generated bridge
 * `HybridNitroPush` is a thin wrapper that delegates every JS-facing
 * operation to `NitroPushSdk.shared`.
 *
 * Wire-up in `AppDelegate.swift`:
 *
 *     override func bundleURL() -> URL? {
 *     #if DEBUG
 *         return RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
 *     #else
 *         return NitroPushSdk.shared.activeBundleURL()
 *             ?? Bundle.main.url(forResource: "main", withExtension: "jsbundle")
 *     #endif
 *     }
 */
public final class NitroPushSdk {

    public static let shared = NitroPushSdk()

    /// Toggleable debug logging — off by default so production builds don't
    /// spam the device log. Flip with `setEnableLogs(true)` from
    /// `AppDelegate.application(_:didFinishLaunchingWithOptions:)` (or
    /// anywhere on the native side) when you want a trace of every SDK
    /// action. Process-local, no persistence.
    private var logsEnabled: Bool = false

    /// Enable / disable debug logging at runtime. Idempotent.
    public func setEnableLogs(_ enabled: Bool) {
        guard self.logsEnabled != enabled else { return }
        self.logsEnabled = enabled
        // Always log the toggle so flipping it ON appears in the stream and
        // flipping it OFF leaves a visible "last log" trail.
        print("[NitroPush] setEnableLogs: \(enabled)")
    }

    private func log(_ action: String, _ details: @autoclosure () -> String = "") {
        guard logsEnabled else { return }
        let d = details()
        if d.isEmpty {
            print("[NitroPushSdk] \(action)")
        } else {
            print("[NitroPushSdk] \(action) \(d)")
        }
    }

    private func log(_ action: String, error: Error) {
        guard logsEnabled else { return }
        print("[NitroPush] \(action) ERROR: \(error)")
    }

    private enum DefaultsKey {
        static let active = "nitropush.active"
        static let pending = "nitropush.pending"
        static let previous = "nitropush.previous"
        static let unconfirmed = "nitropush.unconfirmed"
        /// Snapshot of the release we just rolled BACK from. Written by
        /// `consumePendingPointerOnLaunch` *before* the active pointer
        /// gets clobbered with `previous`. Read + cleared by
        /// `detectAndReportRollback` after `configure()` wires the emitter.
        static let pendingRollbackEvent = "nitropush.pendingRollbackEvent"
    }

    private var serverUrl: URL?
    private var deploymentKey: String?
    /// Public base URL for bundle/asset storage. Joined with `objectKey`
    /// at fetch time. Trailing slash is stripped on store.
    private var storageBaseUrl: String?
    private var appVersion: String?
    private var clientUniqueId: String?
    /// Base64-encoded DER SubjectPublicKeyInfo for ECDSA P-256 bundle
    /// signature verification. `nil` means verification is skipped.
    private var bundlePublicKey: String?

    private var progressListeners: [Int: (NPDownloadProgress) -> Void] = [:]
    private var nextListenerId: Int = 1

    private var pendingResumeAfterBackground: (NPLocalPackage, TimeInterval)?
    private var pendingSuspend: NPLocalPackage?
    private var lastBackgroundedAt: Date?

    private let session: URLSession

    /// Native analytics emitter. Replaces the deleted JS-side
    /// `createAnalyticsEmitter` so events fire even when the JS thread
    /// hasn't loaded (cold-start, post-rollback boots).
    private var analytics: NlAnalytics?
    /// Rollback releases we've already reported this process — keeps
    /// `install_failed_rollback` from firing on every launch.
    private var reportedRollbacks = Set<String>()
    /// First-run releases we've already reported `install_completed` for.
    /// Lets the host call `notifyAppReady()` defensively without spam.
    private var reportedFirstRuns = Set<String>()

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 60
        cfg.timeoutIntervalForResource = 5 * 60
        self.session = URLSession(configuration: cfg)
        // Note: we run the rollback sweep inside `consumePendingPointerOnLaunch`,
        // but can't emit events yet — `analytics` is `nil` until `configure()`.
        // The sweep stashes any rolled-back release id in `reportedRollbacks`
        // so a second invocation post-configure would no-op; we replay the
        // event in `configure()` below.
        consumePendingPointerOnLaunch()
        installLifecycleObservers()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    public func configure(_ config: NPConfig) throws {
        log("configure",
            "serverUrl=\(config.serverUrl) deploymentKey=\(config.deploymentKey.prefix(20))… "
            + "storageBaseUrl=\(config.storageBaseUrl) appVersion=\(config.appVersion ?? "(auto)")")
        guard let url = URL(string: config.serverUrl) else {
            throw NitroPushError.invalidConfig("serverUrl must be an absolute URL")
        }
        let trimmedStorage = config.storageBaseUrl
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedStorage.isEmpty,
              URL(string: trimmedStorage) != nil else {
            throw NitroPushError.invalidConfig("storageBaseUrl must be an absolute URL")
        }
        self.serverUrl = url
        self.deploymentKey = config.deploymentKey
        // Keep one canonical form: no trailing slash. Joining helpers
        // always insert exactly one separator.
        self.storageBaseUrl = trimmedStorage.hasSuffix("/")
            ? String(trimmedStorage.dropLast())
            : trimmedStorage
        self.appVersion = config.appVersion ?? Self.binaryAppVersion()
        self.clientUniqueId = config.clientUniqueId ?? Self.fallbackDeviceId()
        self.bundlePublicKey = config.bundlePublicKey

        // Replace any prior emitter — re-configure can change endpoint
        // or deployment key, and the in-flight queue is no longer valid.
        self.analytics?.stop()
        self.analytics = NlAnalytics(
            serverUrl: config.serverUrl,
            deploymentKey: config.deploymentKey
        )

        emit(type: "app_started")
        // The launch-time pointer sweep ran before `configure()` and only
        // mutated state (`isFailedInstall = true` on the rolled-back row).
        // Now that the emitter is wired, replay the rollback event once.
        detectAndReportRollback()
    }

    /// Resolve a bucket-relative `objectKey` to an absolute URL using the
    /// configured `storageBaseUrl`.
    private func resolveObjectURL(_ objectKey: String) throws -> URL {
        guard let base = self.storageBaseUrl else {
            throw NitroPushError.notConfigured
        }
        let key = objectKey.hasPrefix("/") ? String(objectKey.dropFirst()) : objectKey
        guard let url = URL(string: "\(base)/\(key)") else {
            throw NitroPushError.invalidConfig("Cannot build URL from \(base) and \(objectKey)")
        }
        return url
    }

    public func checkForUpdate(deploymentKeyOverride: String? = nil) async throws -> NPRemotePackage? {
        log("checkForUpdate",
            "deploymentKeyOverride=\(deploymentKeyOverride?.prefix(20) ?? "(none)")")
        let result = try await requestLatestRelease(
            deploymentKey: deploymentKeyOverride ?? self.deploymentKey
        )
        if let r = result {
            log("checkForUpdate → result",
                "releaseId=\(r.releaseId) label=\(r.label) kind=\(r.kind) size=\(Int(r.packageSize))B")
        } else {
            log("checkForUpdate → no update available")
        }
        emit(
            type: "update_check",
            releaseId: result?.releaseId,
            appVersion: result?.appVersion,
            otaVersion: result?.otaVersion
        )
        return result
    }

    public func downloadUpdate(_ pkg: NPRemotePackage) async throws -> NPLocalPackage {
        log("downloadUpdate", "releaseId=\(pkg.releaseId) label=\(pkg.label) kind=\(pkg.kind)")
        emit(
            type: "download_started",
            releaseId: pkg.releaseId,
            appVersion: pkg.appVersion,
            otaVersion: pkg.otaVersion
        )
        do {
            let local = try await performDownload(pkg)
            log("downloadUpdate → completed",
                "releaseId=\(local.releaseId) bundlePath=\(local.bundlePath)")
            emit(
                type: "download_completed",
                releaseId: local.releaseId,
                appVersion: local.appVersion,
                otaVersion: local.otaVersion
            )
            return local
        } catch {
            // We intentionally don't emit a `download_failed` event today —
            // the server schema doesn't yet model it. Leaving the path
            // explicit so a future event type plugs in here cleanly.
            log("downloadUpdate FAILED", error: error)
            throw error
        }
    }

    public func installUpdate(
        pkg: NPLocalPackage,
        installMode: NPInstallMode,
        minimumBackgroundDuration: Double
    ) async throws {
        log("installUpdate",
            "releaseId=\(pkg.releaseId) mode=\(installMode) minBgDur=\(minimumBackgroundDuration)s")
        try persistPending(pkg)
        switch installMode {
        case .immediate:
            log("installUpdate.IMMEDIATE → activate + reload")
            try activatePendingSync()
            reloadBridge()
        case .onNextRestart:
            log("installUpdate.ON_NEXT_RESTART", "staged; will activate on next cold start")
        case .onNextResume:
            self.pendingResumeAfterBackground = (pkg, max(0, minimumBackgroundDuration))
            log("installUpdate.ON_NEXT_RESUME",
                "scheduled after ≥\(minimumBackgroundDuration)s background")
        case .onNextSuspend:
            self.pendingSuspend = pkg
            log("installUpdate.ON_NEXT_SUSPEND", "will activate when app goes background")
        }
    }

    public func notifyAppReady() {
        // Emit `install_completed` once per first-run release. We read
        // `isFirstRun` *before* clearing the unconfirmed flag so we never
        // miss a healthy install. Dedup on `releaseId` so callers can
        // call `notifyAppReady()` repeatedly without spamming events.
        let active = readActive()
        log("notifyAppReady",
            "active=\(active?.releaseId ?? "(none)") isFirstRun=\(active?.isFirstRun ?? false)")
        if let active = active, active.isFirstRun, !reportedFirstRuns.contains(active.releaseId) {
            reportedFirstRuns.insert(active.releaseId)
            log("notifyAppReady → install_completed", "releaseId=\(active.releaseId)")
            emit(
                type: "install_completed",
                releaseId: active.releaseId,
                appVersion: active.appVersion,
                otaVersion: active.otaVersion
            )
        }
        UserDefaults.standard.set(false, forKey: DefaultsKey.unconfirmed)
        UserDefaults.standard.removeObject(forKey: DefaultsKey.previous)
    }

    public func restartApp(onlyIfUpdateIsPending: Bool) throws {
        let pending = readPending()
        log("restartApp",
            "onlyIfUpdateIsPending=\(onlyIfUpdateIsPending) pending=\(pending?.releaseId ?? "(none)")")
        if onlyIfUpdateIsPending && pending == nil {
            log("restartApp → no-op (no pending update)")
            return
        }
        if pending != nil {
            try activatePendingSync()
        }
        reloadBridge()
    }

    public func getCurrentPackage() -> NPLocalPackage? { readActive() }
    public func getPendingPackage() -> NPLocalPackage? { readPending() }

    public func clearPendingUpdate() {
        let pending = readPending()
        log("clearPendingUpdate", "pending=\(pending?.releaseId ?? "(none)")")
        if let pending = pending {
            deleteBundleDir(releaseId: pending.releaseId)
        }
        UserDefaults.standard.removeObject(forKey: DefaultsKey.pending)
        self.pendingResumeAfterBackground = nil
        self.pendingSuspend = nil
    }

    /// Discard the bundle identified by `releaseId`.
    ///
    /// - When it's the pending bundle: drops the staged install (equivalent
    ///   to `clearPendingUpdate`). The active bundle keeps running.
    /// - When it's the active bundle and a previous bundle exists: swaps the
    ///   previous bundle back into active, deletes the rolled-back bundle's
    ///   directory, and reloads the React bridge.
    /// - Otherwise: throws.
    public func rollback(releaseId: String) throws {
        log("rollback", "releaseId=\(releaseId)")
        if let pending = readPending(), pending.releaseId == releaseId {
            log("rollback → drop pending", "releaseId=\(releaseId)")
            clearPendingUpdate()
            return
        }
        guard let active = readActive(), active.releaseId == releaseId else {
            log("rollback FAILED",
                "releaseId=\(releaseId) is neither pending nor active")
            throw NitroPushError.invalidConfig(
                "rollback: \(releaseId) is neither the pending nor active bundle"
            )
        }
        let defaults = UserDefaults.standard
        guard let prevDict = defaults.dictionary(forKey: DefaultsKey.previous) else {
            log("rollback FAILED", "no previous bundle to restore for \(releaseId)")
            throw NitroPushError.invalidConfig(
                "rollback: no previous bundle to restore for \(releaseId)"
            )
        }
        log("rollback → swap active", "restoring previous bundle, deleting \(active.releaseId)")
        deleteBundleDir(releaseId: active.releaseId)
        defaults.set(prevDict, forKey: DefaultsKey.active)
        defaults.removeObject(forKey: DefaultsKey.previous)
        defaults.removeObject(forKey: DefaultsKey.pending)
        defaults.set(false, forKey: DefaultsKey.unconfirmed)
        self.pendingResumeAfterBackground = nil
        self.pendingSuspend = nil
        reloadBridge()
    }

    public func clearUpdates() {
        log("clearUpdates", "wiping defaults + \(Self.rootDir().path)")
        UserDefaults.standard.removeObject(forKey: DefaultsKey.active)
        UserDefaults.standard.removeObject(forKey: DefaultsKey.pending)
        UserDefaults.standard.removeObject(forKey: DefaultsKey.previous)
        UserDefaults.standard.removeObject(forKey: DefaultsKey.unconfirmed)
        UserDefaults.standard.removeObject(forKey: DefaultsKey.pendingRollbackEvent)
        try? FileManager.default.removeItem(at: Self.rootDir())
    }

    @discardableResult
    public func addDownloadProgressListener(_ callback: @escaping (NPDownloadProgress) -> Void) -> Int {
        let id = nextListenerId
        nextListenerId += 1
        progressListeners[id] = callback
        return id
    }

    public func removeDownloadProgressListener(listenerId: Int) {
        progressListeners.removeValue(forKey: listenerId)
    }

    private func emitProgress(_ progress: NPDownloadProgress) {
        let snapshot = progressListeners.values
        DispatchQueue.main.async { snapshot.forEach { $0(progress) } }
    }

    /// Build + enqueue an analytics event. No-ops before `configure()` —
    /// the launch-time pointer sweep can't tag events because it has no
    /// emitter yet; `configure()` replays anything urgent (rollbacks).
    private func emit(
        type: String,
        releaseId: String? = nil,
        appVersion: String? = nil,
        otaVersion: Double? = nil
    ) {
        guard let a = analytics else { return }
        let event = NlAnalyticsEvent(
            eventType: type,
            clientUniqueId: self.clientUniqueId ?? Self.fallbackDeviceId(),
            appVersion: appVersion ?? self.appVersion ?? "*",
            otaVersion: otaVersion,
            releaseId: releaseId,
            platform: "ios",
            osVersion: NlAnalyticsContext.osVersion(),
            deviceModel: NlAnalyticsContext.deviceModel(),
            occurredAt: NlAnalyticsContext.now()
        )
        a.enqueue(event)
    }

    /// Drain the rollback snapshot that `consumePendingPointerOnLaunch`
    /// stashed before the active pointer got clobbered. We can't read the
    /// failed release off `active` because the pointer-swap moved
    /// `previous` into `active` already.
    private func detectAndReportRollback() {
        let defaults = UserDefaults.standard
        guard let dict = defaults.dictionary(forKey: DefaultsKey.pendingRollbackEvent),
              let rolled = NPLocalPackage.fromDict(dict)
        else { return }
        defaults.removeObject(forKey: DefaultsKey.pendingRollbackEvent)
        if reportedRollbacks.contains(rolled.releaseId) { return }
        reportedRollbacks.insert(rolled.releaseId)
        emit(
            type: "install_failed_rollback",
            releaseId: rolled.releaseId,
            appVersion: rolled.appVersion,
            otaVersion: rolled.otaVersion
        )
    }

    /// URL of the currently-active bundle, or `nil` when running the
    /// binary-shipped bundle. Wire into `AppDelegate.bundleURL()`.
    /// **NOT exposed via the Nitro bridge** — it's a native-only call.
    public func activeBundleURL() -> URL? {
        guard let active = readActive() else { return nil }
        let url = URL(fileURLWithPath: active.bundlePath)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private func consumePendingPointerOnLaunch() {
        let defaults = UserDefaults.standard
        if let pendingDict = defaults.dictionary(forKey: DefaultsKey.pending),
           let pending = NPLocalPackage.fromDict(pendingDict) {
            if let activeDict = defaults.dictionary(forKey: DefaultsKey.active) {
                defaults.set(activeDict, forKey: DefaultsKey.previous)
            }
            defaults.set(pendingDict, forKey: DefaultsKey.active)
            defaults.removeObject(forKey: DefaultsKey.pending)
            defaults.set(true, forKey: DefaultsKey.unconfirmed)
            persistFlag(releaseId: pending.releaseId, isFirstRun: true)
            return
        }

        if defaults.bool(forKey: DefaultsKey.unconfirmed) {
            if let prevDict = defaults.dictionary(forKey: DefaultsKey.previous) {
                if let active = readActive() {
                    deleteBundleDir(releaseId: active.releaseId)
                    persistFlag(releaseId: active.releaseId, isFailedInstall: true)
                    // Stash the rollback context so `configure()` can fire
                    // an `install_failed_rollback` event once the analytics
                    // emitter is up. Active is about to get clobbered by
                    // `previous` below — this snapshot survives.
                    defaults.set(active.toDict(), forKey: DefaultsKey.pendingRollbackEvent)
                }
                defaults.set(prevDict, forKey: DefaultsKey.active)
            } else {
                defaults.removeObject(forKey: DefaultsKey.active)
            }
            defaults.removeObject(forKey: DefaultsKey.previous)
            defaults.set(false, forKey: DefaultsKey.unconfirmed)
        }
    }

    private func installLifecycleObservers() {
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(handleDidEnterBackground),
                       name: UIApplication.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(handleWillEnterForeground),
                       name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    @objc private func handleDidEnterBackground() {
        lastBackgroundedAt = Date()
        if let pending = pendingSuspend {
            log("lifecycle.didEnterBackground → activating ON_NEXT_SUSPEND",
                "releaseId=\(pending.releaseId)")
            try? activatePendingSync()
            pendingSuspend = nil
        }
    }

    @objc private func handleWillEnterForeground() {
        guard let (pkg, minimum) = pendingResumeAfterBackground else { return }
        let elapsed = lastBackgroundedAt.map { Date().timeIntervalSince($0) } ?? 0
        log("lifecycle.willEnterForeground",
            "pendingResume=\(pkg.releaseId) elapsed=\(elapsed)s threshold=\(minimum)s")
        if elapsed >= minimum {
            log("lifecycle.willEnterForeground → activating ON_NEXT_RESUME + reload",
                "releaseId=\(pkg.releaseId)")
            try? persistPending(pkg)
            try? activatePendingSync()
            reloadBridge()
            pendingResumeAfterBackground = nil
        }
    }

    private func requestLatestRelease(deploymentKey: String?) async throws -> NPRemotePackage? {
        guard let server = serverUrl else { throw NitroPushError.notConfigured }
        guard let key = deploymentKey else {
            throw NitroPushError.invalidConfig("deploymentKey not set")
        }

        var components = URLComponents(
            url: server.appendingPathComponent("/api/sdk/releases/latest"),
            resolvingAgainstBaseURL: false
        )
        var qs: [URLQueryItem] = [
            URLQueryItem(name: "deploymentKey", value: key),
            URLQueryItem(name: "platform", value: "ios"),
        ]
        if let v = appVersion { qs.append(URLQueryItem(name: "appVersion", value: v)) }
        if let id = clientUniqueId { qs.append(URLQueryItem(name: "clientUniqueId", value: id)) }
        if let active = readActive() {
            qs.append(URLQueryItem(name: "currentReleaseId", value: active.releaseId))
        }
        components?.queryItems = qs
        guard let url = components?.url else {
            throw NitroPushError.invalidConfig("bad server URL")
        }

        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse else {
            throw NitroPushError.networkFailure("non-HTTP response")
        }
        if http.statusCode == 204 { return nil }
        guard (200..<300).contains(http.statusCode) else {
            throw NitroPushError.networkFailure(
                describeManifestFetch(url: url, response: http, body: data,
                                      reason: "checkForUpdate non-2xx status")
            )
        }
        let parsed: LatestReleaseResponse
        do {
            parsed = try JSONDecoder().decode(LatestReleaseResponse.self, from: data)
        } catch {
            throw NitroPushError.networkFailure(
                describeManifestFetch(url: url, response: http, body: data,
                                      reason: "checkForUpdate JSON decode failed: \(error)")
            )
        }
        return parsed.release
    }

    /// Build a self-diagnosing error string for an HTTP fetch that didn't
    /// produce the expected JSON. Includes the URL, status, content-type,
    /// and a 200-char body snippet so the failure tells the operator exactly
    /// what the server returned.
    private func describeManifestFetch(
        url: URL,
        response: HTTPURLResponse?,
        body: Data,
        reason: String
    ) -> String {
        let status = response?.statusCode.description ?? "no-http-response"
        let contentType = response?.value(forHTTPHeaderField: "Content-Type") ?? "unknown"
        let bodyStr = String(data: body, encoding: .utf8) ?? "<non-utf8 \(body.count) bytes>"
        let snippet = bodyStr.count > 200
            ? String(bodyStr.prefix(200)) + "…(+\(bodyStr.count - 200) more chars)"
            : bodyStr
        return "\(reason) — url=\(url.absoluteString) status=\(status) " +
               "contentType=\(contentType) body=\(snippet)"
    }

    private func performDownload(_ pkg: NPRemotePackage) async throws -> NPLocalPackage {
        let dir = Self.rootDir().appendingPathComponent(pkg.releaseId, isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // Both kinds (`expo` and `codepush`) ship a manifest at
        // `pkg.downloadObjectKey`. The manifest layout is identical across
        // kinds — the distinction lives only at the API/DB layer.
        let bundlePath = try await downloadManifestRelease(pkg: pkg, releaseDir: dir)

        return NPLocalPackage(
            releaseId: pkg.releaseId,
            label: pkg.label,
            packageHash: pkg.packageHash,
            packageSize: pkg.packageSize,
            appVersion: pkg.appVersion,
            otaVersion: pkg.otaVersion,
            displayVersion: pkg.displayVersion,
            platforms: pkg.platforms,
            isMandatory: pkg.isMandatory,
            description: pkg.description,
            isPending: true,
            isFailedInstall: false,
            isFirstRun: false,
            bundlePath: bundlePath
        )
    }

    /// GET the SDK manifest, then for each asset (and the bundle itself)
    /// reuse the cached copy if present, otherwise fetch + verify. Files
    /// are written at their `originalPath` inside `releaseDir` so RN's
    /// relative-to-bundle asset resolution still works. Used for both
    /// `expo` and `codepush` kinds — the manifest format is identical.
    private func downloadManifestRelease(pkg: NPRemotePackage, releaseDir: URL) async throws -> String {
        let url = try resolveObjectURL(pkg.downloadObjectKey)
        log("downloadManifestRelease", "GET \(url.absoluteString)")
        let (manifestData, response) = try await session.data(from: url)
        let http = response as? HTTPURLResponse
        guard let http = http, (200..<300).contains(http.statusCode) else {
            throw NitroPushError.networkFailure(
                describeManifestFetch(url: url, response: http, body: manifestData,
                                      reason: "non-2xx status")
            )
        }
        let manifest: SdkManifest
        do {
            manifest = try JSONDecoder().decode(SdkManifest.self, from: manifestData)
        } catch {
            // Most common cause: the server returned an HTML error page or an
            // S3/MinIO XML envelope with a 200 status. Surface the URL +
            // content-type + body snippet so the failure is self-diagnosing
            // instead of "Unexpected character '<' around line 1, column 1".
            throw NitroPushError.networkFailure(
                describeManifestFetch(url: url, response: http, body: manifestData,
                                      reason: "JSON decode failed: \(error)")
            )
        }

        let bundleDest = releaseDir.appendingPathComponent(manifest.bundle.originalPath)
        try FileManager.default.createDirectory(
            at: bundleDest.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try await fetchByContentHash(
            urlString: try resolveObjectURL(manifest.bundle.objectKey).absoluteString,
            sha256: manifest.bundle.sha256,
            dest: bundleDest
        )

        if let pubKey = self.bundlePublicKey {
            guard let sig = manifest.bundle.signature else {
                try? FileManager.default.removeItem(at: releaseDir)
                throw NitroPushError.integrityFailure(
                    "bundle is unsigned but a bundlePublicKey is configured — refusing to install"
                )
            }
            do {
                try Self.verifyBundleSignature(
                    sha256: manifest.bundle.sha256,
                    signatureBase64: sig,
                    publicKeyBase64: pubKey
                )
                log("downloadManifestRelease → signature OK", manifest.bundle.sha256)
            } catch {
                try? FileManager.default.removeItem(at: releaseDir)
                throw error
            }
        }

        for (idx, asset) in manifest.assets.enumerated() {
            let dest = releaseDir.appendingPathComponent(asset.originalPath)
            try FileManager.default.createDirectory(
                at: dest.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try await fetchByContentHash(
                urlString: try resolveObjectURL(asset.objectKey).absoluteString,
                sha256: asset.sha256,
                dest: dest
            )
            emitProgress(NPDownloadProgress(
                receivedBytes: Double(idx + 1),
                totalBytes: Double(manifest.assets.count)
            ))
        }

        return bundleDest.path
    }

    /// sha256-keyed disk cache, shared across releases. If `<cache>/<sha>` exists
    /// we copy it to `dest` and skip the network. Otherwise download to the cache
    /// path (verifying), then copy.
    private func fetchByContentHash(urlString: String, sha256: String, dest: URL) async throws {
        let cacheDir = Self.rootDir().appendingPathComponent("cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        let cached = cacheDir.appendingPathComponent(sha256)

        if !FileManager.default.fileExists(atPath: cached.path) {
            guard let url = URL(string: urlString) else {
                throw NitroPushError.invalidConfig("bad asset URL: \(urlString)")
            }
            try await downloadToFile(
                url: url,
                dest: cached,
                expectedSha256: sha256,
                announcedSize: -1
            )
        }
        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.copyItem(at: cached, to: dest)
    }

    private func downloadToFile(
        url: URL,
        dest: URL,
        expectedSha256: String,
        announcedSize: Int
    ) async throws {
        let progressDelegate = ProgressTrackingDelegate { [weak self] received in
            guard announcedSize > 0 else { return }
            self?.emitProgress(NPDownloadProgress(
                receivedBytes: Double(received),
                totalBytes: Double(announcedSize)
            ))
        }
        let dlSession = URLSession(
            configuration: session.configuration,
            delegate: progressDelegate,
            delegateQueue: .main
        )
        let (tmpURL, response) = try await dlSession.download(from: url)
        defer { dlSession.invalidateAndCancel() }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw NitroPushError.networkFailure("HTTP \(String(describing: response)) for \(url)")
        }

        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.moveItem(at: tmpURL, to: dest)

        if !expectedSha256.isEmpty {
            let actual = try Self.sha256Hex(of: dest)
            if actual.lowercased() != expectedSha256.lowercased() {
                try? FileManager.default.removeItem(at: dest)
                throw NitroPushError.integrityFailure(
                    "integrity check failed for \(url) (expected \(expectedSha256) got \(actual))"
                )
            }
        }
    }

}

// MARK: - Wire types for the SDK release manifest

/// Matches the JSON written by `createCodepushRelease` /
/// `createExpoRelease` in `@nitropush/shared-services`. Decoder is
/// permissive: only the bundle + assets entries are needed at install
/// time, so anything else (kind, platforms, label, otaVersion, …) is
/// ignored.
private struct SdkManifest: Decodable {
    let bundle: SdkManifestBundle
    let assets: [SdkManifestAsset]
}

private struct SdkManifestBundle: Decodable {
    let originalPath: String
    let sha256: String
    let objectKey: String
    /// Base64 DER ECDSA P-256 signature over `"bundle:<sha256>"`.
    /// Present only when the release was created with a signing key.
    let signature: String?
}

private struct SdkManifestAsset: Decodable {
    let originalPath: String
    let ext: String
    let sha256: String
    let objectKey: String
}

extension NitroPushSdk {

    private func persistPending(_ pkg: NPLocalPackage) throws {
        UserDefaults.standard.set(pkg.toDict(), forKey: DefaultsKey.pending)
    }

    @discardableResult
    private func activatePendingSync() throws -> NPLocalPackage? {
        let defaults = UserDefaults.standard
        guard let pendingDict = defaults.dictionary(forKey: DefaultsKey.pending),
              let pending = NPLocalPackage.fromDict(pendingDict) else { return nil }
        if let activeDict = defaults.dictionary(forKey: DefaultsKey.active) {
            defaults.set(activeDict, forKey: DefaultsKey.previous)
        }
        defaults.set(pendingDict, forKey: DefaultsKey.active)
        defaults.removeObject(forKey: DefaultsKey.pending)
        defaults.set(true, forKey: DefaultsKey.unconfirmed)
        return pending
    }

    private func readActive() -> NPLocalPackage? {
        guard let dict = UserDefaults.standard.dictionary(forKey: DefaultsKey.active) else { return nil }
        return NPLocalPackage.fromDict(dict)
    }

    private func readPending() -> NPLocalPackage? {
        guard let dict = UserDefaults.standard.dictionary(forKey: DefaultsKey.pending) else { return nil }
        return NPLocalPackage.fromDict(dict)
    }

    private func deleteBundleDir(releaseId: String) {
        let dir = Self.rootDir().appendingPathComponent(releaseId, isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
    }

    private func persistFlag(releaseId: String, isFirstRun: Bool = false, isFailedInstall: Bool = false) {
        let defaults = UserDefaults.standard
        guard var dict = defaults.dictionary(forKey: DefaultsKey.active) else { return }
        if dict["releaseId"] as? String == releaseId {
            if isFirstRun { dict["isFirstRun"] = true }
            if isFailedInstall { dict["isFailedInstall"] = true }
            defaults.set(dict, forKey: DefaultsKey.active)
        }
    }

    private func reloadBridge() {
        DispatchQueue.main.async {
#if canImport(React)
            if let cls = NSClassFromString("RCTReloadCommand") as AnyObject?,
               cls.responds(to: NSSelectorFromString("triggerReloadCommandListeners:")) {
                _ = cls.perform(NSSelectorFromString("triggerReloadCommandListeners:"),
                                with: "NitroPush install")
            } else {
                NotificationCenter.default.post(
                    name: NSNotification.Name("RCTBridgeWillReloadNotification"),
                    object: nil
                )
            }
#endif
        }
    }

    private static func rootDir() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory,
                                                 in: .userDomainMask,
                                                 appropriateFor: nil,
                                                 create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = base.appendingPathComponent("NitroPush", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func binaryAppVersion() -> String? {
        return Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    }

    /// Read an `NPConfig` from `Info.plist` keys. Used by the no-arg
    /// `NitroPush.configure()` JS factory. Only `NITROPUSH_DEPLOYMENT_KEY` is
    /// required — `serverUrl` and `storageBaseUrl` default to the NitroPush-
    /// hosted endpoints when absent from Info.plist.
    ///   Required:
    ///     • `NITROPUSH_DEPLOYMENT_KEY`
    ///   Optional (override hosted defaults):
    ///     • `NITROPUSH_SERVER_URL`        (default: https://api.nitropush.org)
    ///     • `NITROPUSH_STORAGE_BASE_URL`  (default: https://cdn.nitropush.org)
    ///     • `NITROPUSH_APP_VERSION`
    ///     • `NITROPUSH_CLIENT_UNIQUE_ID`
    public static func configFromInfoPlist() throws -> NPConfig {
        func read(_ key: String) -> String? {
            guard let raw = Bundle.main.object(forInfoDictionaryKey: key) as? String else {
                return nil
            }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        guard let deploymentKey = read("NITROPUSH_DEPLOYMENT_KEY") else {
            throw NitroPushError.invalidConfig(
                "Info.plist is missing NITROPUSH_DEPLOYMENT_KEY"
            )
        }
        return NPConfig(
            serverUrl: read("NITROPUSH_SERVER_URL") ?? "https://api.nitropush.org",
            deploymentKey: deploymentKey,
            storageBaseUrl: read("NITROPUSH_STORAGE_BASE_URL") ?? "https://cdn.nitropush.org",
            appVersion: read("NITROPUSH_APP_VERSION"),
            clientUniqueId: read("NITROPUSH_CLIENT_UNIQUE_ID"),
            bundlePublicKey: read("NITROPUSH_BUNDLE_PUBLIC_KEY")
        )
    }

    private static func fallbackDeviceId() -> String {
        // Prefer the per-vendor id Apple gives us — stable across launches
        // and reinstalls of the same vendor's apps. Fall back to a one-time
        // UUID we persist in UserDefaults so successive calls within a
        // launch (and across launches when identifierForVendor is nil)
        // return the same value, keeping unique-device counts honest.
        if let v = UIDevice.current.identifierForVendor?.uuidString { return v }
        let key = "nitropush.fallbackDeviceId"
        if let v = UserDefaults.standard.string(forKey: key), !v.isEmpty { return v }
        let v = UUID().uuidString
        UserDefaults.standard.set(v, forKey: key)
        return v
    }

    /// Verify an ECDSA P-256 bundle signature.
    /// - Parameters:
    ///   - sha256: Hex SHA-256 of the bundle bytes (already verified by
    ///     `downloadToFile`).
    ///   - signatureBase64: Base64 DER-encoded ECDSA signature produced by
    ///     the server's `signBundleSha256` helper.
    ///   - publicKeyBase64: Base64 DER SubjectPublicKeyInfo from `NPConfig`.
    private static func verifyBundleSignature(
        sha256: String,
        signatureBase64: String,
        publicKeyBase64: String
    ) throws {
        guard let pubKeyData = Data(base64Encoded: publicKeyBase64) else {
            throw NitroPushError.integrityFailure("bundlePublicKey is not valid base64")
        }
        guard let sigData = Data(base64Encoded: signatureBase64) else {
            throw NitroPushError.integrityFailure("bundle signature is not valid base64")
        }
        let pubKey: P256.Signing.PublicKey
        do {
            pubKey = try P256.Signing.PublicKey(derRepresentation: pubKeyData)
        } catch {
            throw NitroPushError.integrityFailure("bundlePublicKey parse failed: \(error)")
        }
        let sig: P256.Signing.ECDSASignature
        do {
            sig = try P256.Signing.ECDSASignature(derRepresentation: sigData)
        } catch {
            throw NitroPushError.integrityFailure("bundle signature parse failed: \(error)")
        }
        let message = Data("bundle:\(sha256)".utf8)
        guard pubKey.isValidSignature(sig, for: message) else {
            throw NitroPushError.integrityFailure(
                "bundle signature mismatch for sha256 \(sha256)"
            )
        }
    }

    private static func sha256Hex(of file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hasher = SHA256()
        while case let chunk = handle.readData(ofLength: 256 * 1024), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

private struct LatestReleaseResponse: Decodable {
    let release: NPRemotePackage?
}

extension NPRemotePackage: Decodable {
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            releaseId: try c.decode(String.self, forKey: .releaseId),
            kind: try c.decodeIfPresent(String.self, forKey: .kind) ?? "codepush",
            label: try c.decode(String.self, forKey: .label),
            packageHash: try c.decode(String.self, forKey: .packageHash),
            packageSize: try c.decode(Double.self, forKey: .packageSize),
            appVersion: try c.decode(String.self, forKey: .appVersion),
            otaVersion: try c.decodeIfPresent(Double.self, forKey: .otaVersion),
            displayVersion: try c.decodeIfPresent(String.self, forKey: .displayVersion),
            platforms: try c.decodeIfPresent([String].self, forKey: .platforms),
            isMandatory: try c.decode(Bool.self, forKey: .isMandatory),
            description: try c.decodeIfPresent(String.self, forKey: .description),
            downloadObjectKey: try c.decode(String.self, forKey: .downloadObjectKey)
        )
    }
    private enum CodingKeys: String, CodingKey {
        case releaseId, kind, label, packageHash, packageSize, appVersion
        case otaVersion, displayVersion, platforms
        case isMandatory, description, downloadObjectKey
    }
}

private final class ProgressTrackingDelegate: NSObject, URLSessionDownloadDelegate {
    private let onProgress: (Int64) -> Void
    init(onProgress: @escaping (Int64) -> Void) { self.onProgress = onProgress }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        onProgress(totalBytesWritten)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {}
}

extension NPLocalPackage {
    func toDict() -> [String: Any] {
        var dict: [String: Any] = [
            "releaseId": releaseId,
            "label": label,
            "packageHash": packageHash,
            "packageSize": packageSize,
            "appVersion": appVersion,
            "isMandatory": isMandatory,
            "isPending": isPending,
            "isFailedInstall": isFailedInstall,
            "isFirstRun": isFirstRun,
            "bundlePath": bundlePath,
        ]
        if let description = description { dict["description"] = description }
        if let otaVersion = otaVersion { dict["otaVersion"] = otaVersion }
        if let displayVersion = displayVersion { dict["displayVersion"] = displayVersion }
        if let platforms = platforms { dict["platforms"] = platforms }
        return dict
    }

    static func fromDict(_ dict: [String: Any]) -> NPLocalPackage? {
        guard let releaseId = dict["releaseId"] as? String,
              let label = dict["label"] as? String,
              let packageHash = dict["packageHash"] as? String,
              let packageSize = dict["packageSize"] as? Double,
              let appVersion = dict["appVersion"] as? String,
              let isMandatory = dict["isMandatory"] as? Bool,
              let bundlePath = dict["bundlePath"] as? String else { return nil }
        return NPLocalPackage(
            releaseId: releaseId,
            label: label,
            packageHash: packageHash,
            packageSize: packageSize,
            appVersion: appVersion,
            otaVersion: dict["otaVersion"] as? Double,
            displayVersion: dict["displayVersion"] as? String,
            platforms: dict["platforms"] as? [String],
            isMandatory: isMandatory,
            description: dict["description"] as? String,
            isPending: (dict["isPending"] as? Bool) ?? false,
            isFailedInstall: (dict["isFailedInstall"] as? Bool) ?? false,
            isFirstRun: (dict["isFirstRun"] as? Bool) ?? false,
            bundlePath: bundlePath
        )
    }
}
