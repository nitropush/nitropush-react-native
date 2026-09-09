import CryptoKit
import Foundation
import UIKit


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
    private var deploymentKeyHash: String?
    /// Public base URL for bundle/asset storage. Joined with `objectKey`
    /// at fetch time. Trailing slash is stripped on store.
    private var storageBaseUrl: String?
    private var appVersion: String?
    private var clientUniqueId: String?
    /// Server-issued proof used for abuse-resistant device metering. Stored
    /// under a server+deployment scoped key so it is never sent cross-origin.
    private var deviceToken: String?
    private var deviceTokenStorageKey: String?
    /// Base64-encoded DER SubjectPublicKeyInfo for ECDSA P-256 bundle
    /// signature verification. `nil` means verification is skipped.
    private var bundlePublicKey: String?
    /// Experimental: when true, send currentBundleHash in update checks and
    /// attempt delta download/patch before falling back to full bundle.
    private var enableDeltaUpdates: Bool = false
    /// SHA-256 of the JS bundle shipped in the native binary. Used as the
    /// first delta base before any OTA package is active.
    private lazy var embeddedBundleHash: String? = {
        guard let bundleUrl = embeddedBundleURL() else { return nil }
        return try? Self.sha256Hex(of: bundleUrl)
    }()

    private func embeddedBundleURL() -> URL? {
        let named = Bundle.main.url(forResource: "main", withExtension: "jsbundle")
        let fallback = Bundle.main.urls(forResourcesWithExtension: "hbc", subdirectory: nil)?.first
            ?? Bundle.main.urls(forResourcesWithExtension: "jsbundle", subdirectory: nil)?.first
        return named ?? fallback
    }

    /// The embedded bundle is a valid delta base only while no OTA release is
    /// active. In particular, never fall back to the embedded hash merely
    /// because an active package is missing its stored bundle hash.
    private func currentBundleHashForDelta() -> String? {
        if let active = readActive() {
            return active.bundleHash
        }
        return embeddedBundleHash
    }

    private var progressListeners: [Int: (NPDownloadProgress) -> Void] = [:]
    private var nextListenerId: Int = 1

    /// Keyed by releaseId — pre-signed manifest proxy URL from the server.
    /// Populated in checkForUpdate; consumed by downloadManifestRelease.
    private var manifestUrlOverride: [String: String] = [:]
    private var manifestDeploymentKeyHash: [String: String] = [:]

    private var pendingResumeAfterBackground: (NPLocalPackage, TimeInterval)?
    private var pendingSuspend: NPLocalPackage?
    private var lastBackgroundedAt: Date?

    private let redirectDelegate: NoRedirectSessionDelegate
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

    private static let releaseFilesManifestName = ".nitropush-files.json"
    private static let maxLatestResponseBytes = 512 * 1024
    private static let maxManifestBytes = 2 * 1024 * 1024
    private static let maxBundleBytes = 64 * 1024 * 1024
    private static let maxAssetBytes = 16 * 1024 * 1024
    private static let maxReleaseBytes = 128 * 1024 * 1024
    private static let maxCacheBytes = 50 * 1024 * 1024
    private static let targetCacheBytesAfterPrune = 40 * 1024 * 1024
    private static let maxUnprotectedCacheAgeSeconds: TimeInterval = 14 * 24 * 60 * 60

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 60
        cfg.timeoutIntervalForResource = 5 * 60
        // Redirects are handled as errors. In particular, this prevents an
        // API request carrying the long-lived device proof from being
        // redirected to a different origin by a compromised proxy.
        let redirectDelegate = NoRedirectSessionDelegate()
        self.redirectDelegate = redirectDelegate
        self.session = URLSession(
            configuration: cfg,
            delegate: redirectDelegate,
            delegateQueue: nil
        )
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
            "serverUrl=\(Self.redactedURLString(config.serverUrl)) "
            + "storageBaseUrl=\(Self.redactedURLString(config.storageBaseUrl)) "
            + "appVersion=\(config.appVersion ?? "(auto)")")
        let url = try Self.validatedNetworkURL(config.serverUrl, name: "serverUrl")
        guard !config.deploymentKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NitroPushError.invalidConfig("deploymentKey is required")
        }
        let trimmedStorage = config.storageBaseUrl
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let storageURL = try Self.validatedNetworkURL(trimmedStorage, name: "storageBaseUrl")
        var validatedPublicKey: String?
        if let rawPublicKey = config.bundlePublicKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !rawPublicKey.isEmpty {
            guard rawPublicKey.range(
                of: "^[A-Za-z0-9+/]+={0,2}$",
                options: .regularExpression
            ) != nil,
            rawPublicKey.count % 4 == 0,
            let keyData = Data(base64Encoded: rawPublicKey),
            keyData.base64EncodedString() == rawPublicKey else {
                throw NitroPushError.invalidConfig("bundlePublicKey is not valid base64 DER")
            }
            do {
                _ = try P256.Signing.PublicKey(derRepresentation: keyData)
            } catch {
                throw NitroPushError.invalidConfig("bundlePublicKey is not a valid P-256 SPKI public key")
            }
            validatedPublicKey = rawPublicKey
        }
        self.serverUrl = url
        self.deploymentKey = config.deploymentKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.deploymentKeyHash = Self.sha256Hex(
            Data(self.deploymentKey!.utf8)
        )
        // Keep one canonical form: no trailing slash. Joining helpers
        // always insert exactly one separator.
        self.storageBaseUrl = storageURL.absoluteString.hasSuffix("/")
            ? String(storageURL.absoluteString.dropLast())
            : storageURL.absoluteString
        self.appVersion = config.appVersion ?? Self.binaryAppVersion()
        self.clientUniqueId = config.clientUniqueId ?? Self.fallbackDeviceId()
        let tokenScope = Data("\(url.absoluteString)\u{0}\(self.deploymentKey!)".utf8)
        let tokenScopeHash = SHA256.hash(data: tokenScope)
            .map { String(format: "%02x", $0) }.joined()
        self.deviceTokenStorageKey = "nitropush.deviceToken.\(tokenScopeHash)"
        self.deviceToken = UserDefaults.standard.string(forKey: self.deviceTokenStorageKey!)
        self.bundlePublicKey = validatedPublicKey
        self.enableDeltaUpdates = config.enableDeltaUpdates
        // These URLs are scoped to the previous API response. Never reuse
        // them after reconfiguration, even when a release id happens to
        // collide across projects or environments.
        self.manifestUrlOverride.removeAll()
        self.manifestDeploymentKeyHash.removeAll()

        // Replace any prior emitter — re-configure can change endpoint
        // or deployment key, and the in-flight queue is no longer valid.
        self.analytics?.stop()
        self.analytics = NlAnalytics(
            serverUrl: config.serverUrl,
            deploymentKey: config.deploymentKey,
            deviceToken: self.deviceToken
        )

        emit(type: "app_started")
        // The launch-time pointer sweep ran before `configure()` and only
        // mutated state (`isFailedInstall = true` on the rolled-back row).
        // Now that the emitter is wired, replay the rollback event once.
        detectAndReportRollback()
        pruneUnusedBundlesAndCache()
    }

    private static func isLoopbackHost(_ host: String?) -> Bool {
        guard let host = host?.lowercased() else { return false }
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }

    private static func validatedNetworkURL(
        _ raw: String,
        name: String,
        allowQuery: Bool = false
    ) throws -> URL {
        guard let url = URL(string: raw),
              url.baseURL == nil,
              let scheme = url.scheme?.lowercased(),
              let host = url.host,
              !host.isEmpty,
              url.user == nil,
              url.password == nil,
              (allowQuery || url.query == nil),
              url.fragment == nil,
              scheme == "https" || (scheme == "http" && isLoopbackHost(host)) else {
            throw NitroPushError.invalidConfig(
                "\(name) must be an absolute HTTPS URL (HTTP is allowed only for loopback development)"
            )
        }
        return url
    }

    private static func effectivePort(_ url: URL) -> Int? {
        if let port = url.port { return port }
        switch url.scheme?.lowercased() {
        case "https": return 443
        case "http": return 80
        default: return nil
        }
    }

    private static func sameOrigin(_ lhs: URL, _ rhs: URL) -> Bool {
        lhs.scheme?.lowercased() == rhs.scheme?.lowercased()
            && lhs.host?.lowercased() == rhs.host?.lowercased()
            && effectivePort(lhs) == effectivePort(rhs)
    }

    private static func redactedURL(_ url: URL) -> String {
        var components = URLComponents()
        components.scheme = url.scheme
        components.host = url.host
        components.port = url.port
        components.path = url.path
        return components.string ?? "<redacted-url>"
    }

    private static func redactedURLString(_ raw: String) -> String {
        guard let url = URL(string: raw) else { return "<invalid-url>" }
        return redactedURL(url)
    }

    private static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func requestForAPIURL(
        _ url: URL,
        deploymentKey: String? = nil
    ) throws -> URLRequest {
        guard let api = serverUrl,
              Self.sameOrigin(url, api),
              url.scheme?.lowercased() == api.scheme?.lowercased() else {
            throw NitroPushError.networkFailure("refusing API request to a different origin")
        }
        var request = URLRequest(url: url)
        if let deviceToken {
            request.setValue(deviceToken, forHTTPHeaderField: "x-nitropush-device-token")
        }
        if let deploymentKey {
            request.setValue(deploymentKey, forHTTPHeaderField: "x-nitropush-deployment-key")
        }
        return request
    }

    /// Resolve a server-issued delta URL and constrain it to the configured
    /// HTTPS API origin before attaching the device proof header. Relative API
    /// paths are accepted; protocol-relative/cross-origin URLs are rejected.
    private func requestForDeltaDownload(_ raw: String) throws -> URLRequest {
        guard let api = serverUrl,
              let resolved = URL(string: raw, relativeTo: api)?.absoluteURL else {
            throw NitroPushError.networkFailure("delta download URL is invalid")
        }
        let validated = try Self.validatedNetworkURL(
            resolved.absoluteString,
            name: "delta download URL",
            allowQuery: true
        )
        guard validated.scheme?.lowercased() == "https",
              Self.sameOrigin(validated, api) else {
            throw NitroPushError.networkFailure(
                "delta download URL must use the configured HTTPS API origin"
            )
        }
        return try requestForAPIURL(validated)
    }

    private func data(
        for request: URLRequest,
        maximumBytes: Int
    ) async throws -> (Data, URLResponse) {
        let (bytes, response) = try await session.bytes(for: request)
        if response.expectedContentLength > Int64(maximumBytes) {
            throw NitroPushError.integrityFailure("response exceeds the allowed size")
        }
        var result = Data()
        result.reserveCapacity(min(maximumBytes, max(0, Int(response.expectedContentLength))))
        for try await byte in bytes {
            guard result.count < maximumBytes else {
                throw NitroPushError.integrityFailure("response exceeds the allowed size")
            }
            result.append(byte)
        }
        return (result, response)
    }

    private static func validateCanonicalUUID(_ value: String, fieldName: String) throws {
        guard value.range(
            of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            options: .regularExpression
        ) != nil else {
            throw NitroPushError.integrityFailure(
                "\(fieldName) is not a lowercase canonical UUID"
            )
        }
    }

    fileprivate static func validateReleaseId(_ releaseId: String) throws {
        try validateCanonicalUUID(releaseId, fieldName: "releaseId")
    }

    fileprivate static func releaseDirectory(for releaseId: String) throws -> URL {
        try validateReleaseId(releaseId)
        let root = rootDir().standardizedFileURL
        let directory = root.appendingPathComponent(releaseId, isDirectory: true).standardizedFileURL
        guard directory.deletingLastPathComponent().path == root.path,
              directory.lastPathComponent == releaseId else {
            throw NitroPushError.integrityFailure("release directory escapes the update root")
        }
        return directory
    }

    /// Resolve a bucket-relative `objectKey` to an absolute URL using the
    /// configured `storageBaseUrl`.
    private func resolveObjectURL(_ objectKey: String) throws -> URL {
        guard let base = self.storageBaseUrl else {
            throw NitroPushError.notConfigured
        }
        let segments = objectKey.split(separator: "/", omittingEmptySubsequences: false)
        guard !objectKey.isEmpty,
              !objectKey.hasPrefix("//"),
              !objectKey.contains("?"),
              !objectKey.contains("#"),
              !objectKey.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }),
              !segments.contains(where: { $0 == "." || $0 == ".." }) else {
            throw NitroPushError.integrityFailure("unsafe storage object key")
        }
        let key = objectKey.hasPrefix("/") ? String(objectKey.dropFirst()) : objectKey
        guard let url = URL(string: "\(base)/\(key)") else {
            throw NitroPushError.invalidConfig("Cannot build URL from \(base) and \(objectKey)")
        }
        return try Self.validatedNetworkURL(url.absoluteString, name: "download URL")
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
        pruneUnusedBundlesAndCache()
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
        pruneUnusedBundlesAndCache()
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
        pruneUnusedBundlesAndCache()
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
        otaVersion: Double? = nil,
        metadata: NlEventMetadata? = nil
    ) {
        guard let a = analytics else { return }
        let event = NlAnalyticsEvent(
            eventId: UUID().uuidString.lowercased(),
            eventType: type,
            clientUniqueId: self.clientUniqueId ?? Self.fallbackDeviceId(),
            appVersion: appVersion ?? self.appVersion ?? "*",
            otaVersion: otaVersion,
            releaseId: releaseId,
            platform: "ios",
            osVersion: NlAnalyticsContext.osVersion(),
            deviceModel: NlAnalyticsContext.deviceModel(),
            occurredAt: NlAnalyticsContext.now(),
            metadata: metadata?.isEmpty == false ? metadata : nil
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
    /// NativeScript boot gate. No downloaded code runs until the complete local inventory verifies.
    public func verifiedApplicationRoot(embeddedRoot: URL) -> URL? {
        do {
            guard let active = readActive(), let key = bundlePublicKey,
                  active.appVersion == appVersion, active.appVersion != "*" else { return nil }
            let root = try Self.releaseDirectory(for: active.releaseId).resolvingSymlinksInPath()
            let manifestURL = root.appendingPathComponent("verified-manifest.json")
            let size = try manifestURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0, size <= Self.maxManifestBytes else { return nil }
            let manifest = try JSONDecoder().decode(SdkManifest.self, from: Data(contentsOf: manifestURL))
            guard manifest.schemaVersion == 4, manifest.kind == "nativescript",
                  manifest.releaseId == active.releaseId,
                  manifest.targetAppVersion == appVersion,
                  manifest.deploymentKeyHash == deploymentKeyHash,
                  manifest.platforms == ["ios"], let signature = manifest.releaseSignature else { return nil }
            try Self.verifySignature(message: try Self.releaseIntegrityPayload(manifest),
                signatureBase64: signature, publicKeyBase64: key, description: "release envelope")
            _ = try validateManifestLayout(manifest, releaseDir: root)
            let entries = [(manifest.bundle.originalPath, manifest.bundle.sha256, manifest.bundle.size)] +
                manifest.assets.map { ($0.originalPath, $0.sha256, $0.size) }
            var expected = Set<String>()
            for (path, hash, byteCount) in entries {
                guard path.hasPrefix("app/") else { return nil }
                let file = root.appendingPathComponent(path).standardizedFileURL
                guard file.resolvingSymlinksInPath().path == file.path,
                      try file.resourceValues(forKeys: [.fileSizeKey]).fileSize == byteCount,
                      try Self.sha256Hex(of: file) == hash else { return nil }
                expected.insert(path)
            }
            let app = root.appendingPathComponent("app")
            guard let files = FileManager.default.enumerator(at: app, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]) else { return nil }
            var actual = Set<String>()
            for case let file as URL in files {
                let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                guard values.isSymbolicLink != true else { return nil }
                if values.isRegularFile == true { actual.insert(String(file.path.dropFirst(root.path.count + 1))) }
            }
            guard actual == expected else { return nil }
            for path in ["app/package.json", "app/tns-java-classes.js"] {
                let embedded = try? Data(contentsOf: embeddedRoot.appendingPathComponent(path))
                let installed = try? Data(contentsOf: root.appendingPathComponent(path))
                guard embedded == installed else { return nil }
            }
            let package = try JSONSerialization.jsonObject(with: Data(contentsOf: app.appendingPathComponent("package.json"))) as? [String: Any]
            guard let main = package?["main"] as? String else { return nil }
            let candidates = main.hasSuffix(".js") || main.hasSuffix(".mjs") ? ["app/" + main] : ["app/" + main + ".js", "app/" + main + ".mjs"]
            guard candidates.contains(manifest.bundle.originalPath), candidates.filter({ expected.contains($0) }).count == 1 else { return nil }
            return root
        } catch { return nil }
    }

    public func activeBundleURL() -> URL? {
        guard let active = readActive() else { return nil }
        guard let releaseDir = try? Self.releaseDirectory(for: active.releaseId) else { return nil }
        let url = URL(fileURLWithPath: active.bundlePath).standardizedFileURL
        guard url.path.hasPrefix(releaseDir.path + "/") else {
            log("activeBundleURL → skipped", "stored bundle path escapes its release directory")
            return nil
        }
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        guard isHermesBundle(at: url.path) else {
            log("activeBundleURL → skipped",
                "bundle at \(url.path) is not Hermes bytecode (.hbc); " +
                "falling back to binary bundle. Re-release with --bundle to upload a Hermes-compiled bundle.")
            return nil
        }
        return url
    }

    /// Returns `true` when the file at `path` begins with the 4-byte Hermes
    /// HBC magic (0xc6 0x1f 0xbc 0x03). All other formats are rejected so the
    /// SDK never loads a plain-JS bundle that would silently fail on the new arch.
    private func isHermesBundle(at path: String) -> Bool {
        guard let fh = FileHandle(forReadingAtPath: path) else { return false }
        defer { fh.closeFile() }
        let magic = fh.readData(ofLength: 4)
        return magic == Data([0xc6, 0x1f, 0xbc, 0x03])
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
            _ = try? activatePendingSync()
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
            _ = try? activatePendingSync()
            reloadBridge()
            pendingResumeAfterBackground = nil
            pruneUnusedBundlesAndCache()
        }
    }

    private func requestLatestRelease(deploymentKey: String?) async throws -> NPRemotePackage? {
        guard let server = serverUrl else { throw NitroPushError.notConfigured }
        guard let key = deploymentKey?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !key.isEmpty else {
            throw NitroPushError.invalidConfig("deploymentKey not set")
        }

        var components = URLComponents(
            url: server.appendingPathComponent("/api/sdk/releases/latest"),
            resolvingAgainstBaseURL: false
        )
        var qs: [URLQueryItem] = [
            URLQueryItem(name: "platform", value: "ios"),
        ]
        if let v = appVersion { qs.append(URLQueryItem(name: "appVersion", value: v)) }
        if let id = clientUniqueId { qs.append(URLQueryItem(name: "clientUniqueId", value: id)) }
        let active = readActive()
        if let active {
            qs.append(URLQueryItem(name: "currentReleaseId", value: active.releaseId))
        }
        if enableDeltaUpdates, let bundleHash = currentBundleHashForDelta() {
            qs.append(URLQueryItem(name: "currentBundleHash", value: bundleHash))
        }
        components?.queryItems = qs
        guard let url = components?.url else {
            throw NitroPushError.invalidConfig("bad server URL")
        }

        let request = try requestForAPIURL(url, deploymentKey: key)
        let (data, response) = try await data(
            for: request,
            maximumBytes: Self.maxLatestResponseBytes
        )
        guard let http = response as? HTTPURLResponse else {
            throw NitroPushError.networkFailure("non-HTTP response")
        }
        if let issuedToken = http.value(forHTTPHeaderField: "x-nitropush-device-token"),
           !issuedToken.isEmpty,
           issuedToken.utf8.count <= 2_048,
           let tokenKey = deviceTokenStorageKey {
            deviceToken = issuedToken
            UserDefaults.standard.set(issuedToken, forKey: tokenKey)
            analytics?.setDeviceToken(issuedToken)
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
                                      reason: "checkForUpdate JSON decode failed")
            )
        }
        // Cache the pre-signed manifest proxy URL so downloadManifestRelease
        // uses it instead of constructing an unsigned storageBaseUrl + objectKey.
        if let wrapper = parsed.release, let proxyUrl = wrapper.downloadUrl {
            if let resolved = URL(string: proxyUrl, relativeTo: server)?.absoluteURL {
                let validated = try Self.validatedNetworkURL(
                    resolved.absoluteString,
                    name: "manifest download URL",
                    allowQuery: true
                )
                manifestUrlOverride[wrapper.pkg.releaseId] = validated.absoluteString
            }
        }
        guard let pkg = parsed.release?.pkg else { return nil }
        try Self.validateReleaseId(pkg.releaseId)
        manifestDeploymentKeyHash[pkg.releaseId] = Self.sha256Hex(Data(key.utf8))
        guard NPHostRuntime.accepts(kind: pkg.kind) else {
            throw NitroPushError.integrityFailure("unsupported release kind")
        }
        guard pkg.packageSize.isFinite,
              pkg.packageSize > 0,
              pkg.packageSize <= Double(Self.maxBundleBytes),
              pkg.packageHash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
            throw NitroPushError.integrityFailure("release metadata is malformed")
        }
        if pkg.appVersion != "*", let binaryVersion = appVersion, pkg.appVersion != binaryVersion {
            throw NitroPushError.integrityFailure("release runtime does not match this binary")
        }
        if let active = readActive(),
           active.appVersion == pkg.appVersion,
           let activeVersion = active.otaVersion,
           let offeredVersion = pkg.otaVersion,
           offeredVersion <= activeVersion,
           active.releaseId != pkg.releaseId {
            throw NitroPushError.integrityFailure("refusing a non-monotonic OTA release")
        }
        return pkg
    }

    /// Build a diagnostic without copying presigned query parameters or
    /// potentially sensitive response bodies into device logs/crash reports.
    private func describeManifestFetch(
        url: URL,
        response: HTTPURLResponse?,
        body: Data,
        reason: String
    ) -> String {
        let status = response?.statusCode.description ?? "no-http-response"
        let contentType = response?.value(forHTTPHeaderField: "Content-Type") ?? "unknown"
        return "\(reason) — url=\(Self.redactedURL(url)) status=\(status) " +
               "contentType=\(contentType) responseBytes=\(body.count)"
    }

    private func performDownload(_ pkg: NPRemotePackage) async throws -> NPLocalPackage {
        let dir = try Self.releaseDirectory(for: pkg.releaseId)
        let defaults = UserDefaults.standard
        for key in [DefaultsKey.active, DefaultsKey.previous, DefaultsKey.pending, DefaultsKey.unconfirmed] {
            if let row = defaults.dictionary(forKey: key), row["releaseId"] as? String == pkg.releaseId {
                throw NitroPushError.integrityFailure("cannot overwrite a referenced release")
            }
        }
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // Both kinds (`expo` and `codepush`) ship a manifest at
        // `pkg.downloadObjectKey`. The manifest layout is identical across
        // kinds — the distinction lives only at the API/DB layer.
        let bundlePath = try await downloadManifestRelease(pkg: pkg, releaseDir: dir)

        // Compute SHA-256 of the raw bundle file for future delta eligibility checks.
        var bundleHash: String? = nil
        if let bundleData = try? Data(contentsOf: URL(fileURLWithPath: bundlePath)) {
            bundleHash = CryptoKit.SHA256.hash(data: bundleData)
                .compactMap { String(format: "%02x", $0) }.joined()
        }

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
            bundlePath: bundlePath,
            bundleHash: bundleHash
        )
    }

    /// GET the SDK manifest, then for each asset (and the bundle itself)
    /// reuse the cached copy if present, otherwise fetch + verify. Files
    /// are written at their `originalPath` inside `releaseDir` so RN's
    /// relative-to-bundle asset resolution still works. Used for both
    /// `expo` and `codepush` kinds — the manifest format is identical.
    private func safeReleaseDestination(
        originalPath: String,
        releaseDir: URL,
        occupied: inout Set<String>
    ) throws -> URL {
        let utf8Length = originalPath.lengthOfBytes(using: .utf8)
        let segments = originalPath.split(separator: "/", omittingEmptySubsequences: false)
        let containsControl = originalPath.unicodeScalars.contains {
            $0.value < 0x20 || $0.value == 0x7f
        }
        guard !originalPath.isEmpty,
              utf8Length <= 512,
              originalPath == originalPath.precomposedStringWithCanonicalMapping,
              !originalPath.hasPrefix("/"),
              !originalPath.hasSuffix("/"),
              !originalPath.contains("\\"),
              !containsControl,
              !segments.contains(where: {
                  $0.isEmpty || $0 == "." || $0 == ".." ||
                  $0.lowercased() == Self.releaseFilesManifestName
              }) else {
            throw NitroPushError.integrityFailure("unsafe release path: \(originalPath)")
        }

        let collisionKey = originalPath.lowercased(with: Locale(identifier: "en_US_POSIX"))
        guard occupied.insert(collisionKey).inserted else {
            throw NitroPushError.integrityFailure("duplicate or colliding release path: \(originalPath)")
        }

        let root = releaseDir.standardizedFileURL.path
        let destination = releaseDir.appendingPathComponent(originalPath).standardizedFileURL
        guard destination.path.hasPrefix(root + "/") else {
            throw NitroPushError.integrityFailure("release path escapes staging directory")
        }
        return destination
    }

    private func validateManifestLayout(
        _ manifest: SdkManifest,
        releaseDir: URL
    ) throws -> (bundle: URL, assets: [URL]) {
        guard manifest.assets.count <= 10_000 else {
            throw NitroPushError.integrityFailure("release manifest contains too many assets")
        }
        guard manifest.bundle.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
            throw NitroPushError.integrityFailure("bundle SHA-256 is invalid")
        }
        var occupied = Set<String>()
        let bundle = try safeReleaseDestination(
            originalPath: manifest.bundle.originalPath,
            releaseDir: releaseDir,
            occupied: &occupied
        )
        var totalBytes = manifest.bundle.size ?? 0
        if let size = manifest.bundle.size, size <= 0 || size > 64 * 1024 * 1024 {
            throw NitroPushError.integrityFailure("bundle size is outside the allowed range")
        }
        var destinations: [URL] = []
        for asset in manifest.assets {
            guard asset.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                throw NitroPushError.integrityFailure("asset SHA-256 is invalid")
            }
            if let size = asset.size {
                guard size > 0 && size <= 16 * 1024 * 1024 else {
                    throw NitroPushError.integrityFailure("asset size is outside the allowed range")
                }
                totalBytes += size
            }
            destinations.append(try safeReleaseDestination(
                originalPath: asset.originalPath,
                releaseDir: releaseDir,
                occupied: &occupied
            ))
        }
        if totalBytes > 128 * 1024 * 1024 {
            throw NitroPushError.integrityFailure("release content exceeds the allowed size")
        }
        return (bundle, destinations)
    }

    private static func manifestIntegrityPayload(_ manifest: SdkManifest) throws -> String {
        guard let bundleSize = manifest.bundle.size else {
            throw NitroPushError.integrityFailure("signed manifest is missing bundle size")
        }
        func line(_ kind: String, _ path: String, _ hash: String, _ size: Int) -> String {
            return "\(kind):\(path.lengthOfBytes(using: .utf8)):\(path):\(hash):\(size)\n"
        }
        var payload = "nitropush-manifest-v1\n"
        payload += line("bundle", manifest.bundle.originalPath, manifest.bundle.sha256, bundleSize)
        payload += "assets:\(manifest.assets.count)\n"
        for asset in manifest.assets {
            guard let size = asset.size else {
                throw NitroPushError.integrityFailure("signed manifest asset is missing size")
            }
            payload += line("asset", asset.originalPath, asset.sha256, size)
        }
        return payload
    }

    /// Canonical signature envelope for schema 4. The upload client signs
    /// this only after the API has reserved `releaseId` and `otaVersion`.
    /// Length-prefixing makes separators inside labels/paths unambiguous.
    private static func releaseIntegrityPayload(_ manifest: SdkManifest) throws -> String {
        guard let releaseId = manifest.releaseId,
              let projectId = manifest.projectId,
              let environment = manifest.environment,
              let deploymentKeyHash = manifest.deploymentKeyHash,
              let kind = manifest.kind,
              let platforms = manifest.platforms,
              let targetAppVersion = manifest.targetAppVersion,
              let otaVersion = manifest.otaVersion,
              otaVersion.isFinite,
              otaVersion > 0,
              otaVersion.rounded(.towardZero) == otaVersion,
              otaVersion <= Double(Int64.max),
              let label = manifest.label,
              let isMandatory = manifest.isMandatory,
              let bundleSize = manifest.bundle.size else {
            throw NitroPushError.integrityFailure("signed release envelope is incomplete")
        }
        try validateReleaseId(releaseId)
        try validateCanonicalUUID(projectId, fieldName: "projectId")
        guard deploymentKeyHash.range(
            of: "^[a-f0-9]{64}$",
            options: .regularExpression
        ) != nil else {
            throw NitroPushError.integrityFailure("signed deployment key hash is invalid")
        }
        func field(_ name: String, _ value: String) -> String {
            "\(name):\(value.lengthOfBytes(using: .utf8)):\(value)\n"
        }
        func artifact(_ kind: String, _ path: String, _ hash: String, _ size: Int) -> String {
            "\(kind):\(path.lengthOfBytes(using: .utf8)):\(path):\(hash):\(size)\n"
        }
        let sortedPlatforms = platforms.sorted()
        guard !sortedPlatforms.isEmpty,
              sortedPlatforms.count == Set(sortedPlatforms).count,
              sortedPlatforms.allSatisfy({ $0 == "ios" || $0 == "android" }) else {
            throw NitroPushError.integrityFailure("signed release platforms are invalid")
        }
        var payload = "nitropush-release-v2\n"
        payload += field("releaseId", releaseId.lowercased())
        payload += field("projectId", projectId.lowercased())
        payload += field("environment", environment)
        payload += field("deploymentKeyHash", deploymentKeyHash.lowercased())
        payload += field("kind", kind)
        payload += "platforms:\(sortedPlatforms.count)\n"
        for platform in sortedPlatforms { payload += field("platform", platform) }
        payload += field("runtimeVersion", targetAppVersion)
        payload += field("label", label)
        payload += "otaVersion:\(Int64(otaVersion))\n"
        payload += "mandatory:\(isMandatory ? 1 : 0)\n"
        payload += artifact(
            "bundle",
            manifest.bundle.originalPath,
            manifest.bundle.sha256,
            bundleSize
        )
        payload += "assets:\(manifest.assets.count)\n"
        for asset in manifest.assets {
            guard let size = asset.size else {
                throw NitroPushError.integrityFailure("signed release asset is missing size")
            }
            payload += artifact("asset", asset.originalPath, asset.sha256, size)
        }
        return payload
    }

    private func validateSignedContext(_ manifest: SdkManifest, package: NPRemotePackage) throws {
        let expectedDeploymentHash = manifestDeploymentKeyHash[package.releaseId]
            ?? deploymentKeyHash
        guard manifest.releaseId?.caseInsensitiveCompare(package.releaseId) == .orderedSame,
              manifest.kind == package.kind,
              manifest.label == package.label,
              manifest.targetAppVersion == package.appVersion,
              manifest.otaVersion == package.otaVersion,
              manifest.isMandatory == package.isMandatory,
              Set(manifest.platforms ?? []) == Set(package.platforms ?? []),
              manifest.bundle.sha256 == package.packageHash.lowercased(),
              manifest.deploymentKeyHash?.lowercased() == expectedDeploymentHash else {
            throw NitroPushError.integrityFailure(
                "latest-release metadata does not match the signed release envelope"
            )
        }
        guard (manifest.platforms ?? []).contains("ios") else {
            throw NitroPushError.integrityFailure("signed release does not target iOS")
        }
    }

    private func downloadManifestRelease(pkg: NPRemotePackage, releaseDir: URL) async throws -> String {
        // Prefer the server-issued downloadUrl (manifest proxy with signed asset URLs)
        // over constructing an unsigned CDN URL from storageBaseUrl + objectKey.
        let url: URL
        if let directUrl = manifestUrlOverride[pkg.releaseId],
           let parsed = URL(string: directUrl) {
            url = parsed
        } else {
            url = try resolveObjectURL(pkg.downloadObjectKey)
        }
        let validatedURL = try Self.validatedNetworkURL(
            url.absoluteString,
            name: "manifest download URL",
            allowQuery: true
        )
        log("downloadManifestRelease", "GET \(Self.redactedURL(validatedURL))")
        let manifestRequest: URLRequest
        if let api = serverUrl, Self.sameOrigin(validatedURL, api) {
            manifestRequest = try requestForAPIURL(validatedURL)
        } else {
            manifestRequest = URLRequest(url: validatedURL)
        }
        let (manifestData, response) = try await data(
            for: manifestRequest,
            maximumBytes: Self.maxManifestBytes
        )
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
                                      reason: "JSON decode failed")
            )
        }

        let schemaVersion = manifest.schemaVersion ?? 2
        guard schemaVersion == 2 || schemaVersion == 3 || schemaVersion == 4 else {
            throw NitroPushError.integrityFailure("unsupported release manifest schema \(schemaVersion)")
        }
        guard pkg.packageHash.lowercased() == manifest.bundle.sha256 else {
            throw NitroPushError.integrityFailure("release metadata does not match its manifest bundle")
        }
        let layout = try validateManifestLayout(manifest, releaseDir: releaseDir)

        if let pubKey = self.bundlePublicKey {
            guard schemaVersion == 4 else {
                throw NitroPushError.integrityFailure(
                    "signed projects require manifest schema 4; refusing legacy/context downgrade"
                )
            }
            try validateSignedContext(manifest, package: pkg)
            guard let sig = manifest.bundle.signature else {
                throw NitroPushError.integrityFailure(
                    "bundle is unsigned but a bundlePublicKey is configured — refusing to install"
                )
            }
            try Self.verifyBundleSignature(
                sha256: manifest.bundle.sha256,
                signatureBase64: sig,
                publicKeyBase64: pubKey
            )
            guard let releaseSignature = manifest.releaseSignature else {
                throw NitroPushError.integrityFailure("signed manifest is missing its release envelope signature")
            }
            try Self.verifySignature(
                message: try Self.releaseIntegrityPayload(manifest),
                signatureBase64: releaseSignature,
                publicKeyBase64: pubKey,
                description: "release envelope"
            )
        }

        let bundleDest = layout.bundle
        try FileManager.default.createDirectory(
            at: bundleDest.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        // Experimental delta path: attempt patch application, fall back to full download.
        var usedDelta = false
        if enableDeltaUpdates, pkg.kind != "nativescript",
           let delta = pkg.delta ?? manifest.bundle.delta?.toPlain(),
           delta.algorithm == "bsdiff4",
           let currentBundleHash = currentBundleHashForDelta(),
           currentBundleHash == delta.fromBundleHash {
            do {
                try await applyDeltaPatch(
                    delta: delta,
                    expectedOutputSha256: manifest.bundle.sha256,
                    dest: bundleDest
                )
                usedDelta = true
                log("downloadManifestRelease → applied delta patch", delta.patchSha256)
                let fullSize = Int(pkg.packageSize)
                emit(
                    type: "download_delta_applied",
                    releaseId: pkg.releaseId,
                    appVersion: pkg.appVersion,
                    otaVersion: pkg.otaVersion,
                    metadata: NlEventMetadata(
                        patchSizeBytes: delta.patchSize,
                        fullSizeBytes: fullSize,
                        savedBytes: max(0, fullSize - delta.patchSize)
                    )
                )
            } catch {
                log("downloadManifestRelease → delta failed, falling back to full bundle", error: error)
                emit(
                    type: "download_delta_failed",
                    releaseId: pkg.releaseId,
                    appVersion: pkg.appVersion,
                    otaVersion: pkg.otaVersion,
                    metadata: NlEventMetadata(reason: classifyDeltaError(error))
                )
                try? FileManager.default.removeItem(at: bundleDest)
            }
        }

        var filePatchBytes = 0, filePatchFullBytes = 0
        var filePatchFailed = false
        func tryFileDelta(_ path: String, _ hash: String, _ size: Int?, _ patch: SdkFileDelta?, _ dest: URL, _ maximum: Int) async -> Bool {
            guard pkg.kind == "nativescript", bundlePublicKey != nil, let patch, let size else { return false }
            do {
                guard try await applyNativeScriptFileDelta(path: path, hash: hash, size: size, patch: patch, dest: dest, maximumBytes: maximum) else { return false }
                filePatchBytes += patch.patchSize; filePatchFullBytes += size
                return true
            } catch {
                filePatchFailed = true
                log("NativeScript file delta failed; downloading full file", path)
                try? FileManager.default.removeItem(at: dest)
                return false
            }
        }
        if !usedDelta {
            usedDelta = await tryFileDelta(manifest.bundle.originalPath, manifest.bundle.sha256, manifest.bundle.size, manifest.bundle.fileDelta, bundleDest, Self.maxBundleBytes)
        }
        var installedBytes = 0
        if !usedDelta {
            let bundleDownloadUrl: String
            if let url = manifest.bundle.downloadUrl {
                bundleDownloadUrl = url
            } else {
                bundleDownloadUrl = try resolveObjectURL(manifest.bundle.objectKey).absoluteString
            }
            installedBytes += try await fetchByContentHash(
                urlString: bundleDownloadUrl,
                sha256: manifest.bundle.sha256,
                dest: bundleDest,
                announcedSize: manifest.bundle.size ?? -1,
                maximumBytes: Self.maxBundleBytes
            )
        } else {
            let values = try bundleDest.resourceValues(forKeys: [.fileSizeKey])
            installedBytes += values.fileSize ?? 0
        }
        guard installedBytes <= Self.maxReleaseBytes else {
            throw NitroPushError.integrityFailure("release content exceeds the allowed size")
        }

        var cachedHashes = Set<String>()
        cachedHashes.insert(manifest.bundle.sha256)

        for (idx, asset) in manifest.assets.enumerated() {
            let dest = layout.assets[idx]
            try FileManager.default.createDirectory(
                at: dest.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let assetDownloadUrl: String
            if let url = asset.downloadUrl {
                assetDownloadUrl = url
            } else {
                assetDownloadUrl = try resolveObjectURL(asset.objectKey).absoluteString
            }
            if await tryFileDelta(asset.originalPath, asset.sha256, asset.size, asset.fileDelta, dest, Self.maxAssetBytes) {
                installedBytes += asset.size ?? 0
            } else {
            installedBytes += try await fetchByContentHash(
                urlString: assetDownloadUrl,
                sha256: asset.sha256,
                dest: dest,
                announcedSize: asset.size ?? -1,
                maximumBytes: Self.maxAssetBytes
            )
            }
            guard installedBytes <= Self.maxReleaseBytes else {
                throw NitroPushError.integrityFailure("release content exceeds the allowed size")
            }
            cachedHashes.insert(asset.sha256)
            emitProgress(NPDownloadProgress(
                receivedBytes: Double(idx + 1),
                totalBytes: Double(manifest.assets.count)
            ))
        }

        let finalBundleHash = try Self.sha256Hex(of: bundleDest)
        guard finalBundleHash == manifest.bundle.sha256 else {
            try? FileManager.default.removeItem(at: releaseDir)
            throw NitroPushError.integrityFailure("bundle changed while assets were installed")
        }

        if pkg.kind == "nativescript" {
            try manifestData.write(to: releaseDir.appendingPathComponent("verified-manifest.json"), options: .atomic)
            if filePatchFullBytes > 0 {
                log("NativeScript file deltas applied", "patchBytes=\(filePatchBytes) fullBytes=\(filePatchFullBytes) savedBytes=\(filePatchFullBytes - filePatchBytes)")
                emit(type: "download_delta_applied", releaseId: pkg.releaseId, appVersion: pkg.appVersion, otaVersion: pkg.otaVersion,
                    metadata: NlEventMetadata(patchSizeBytes: filePatchBytes, fullSizeBytes: filePatchFullBytes, savedBytes: filePatchFullBytes - filePatchBytes))
            }
            if filePatchFailed { emit(type: "download_delta_failed", releaseId: pkg.releaseId, appVersion: pkg.appVersion, otaVersion: pkg.otaVersion, metadata: NlEventMetadata(reason: "file_patch_fallback")) }
        }
        writeReleaseFilesManifest(releaseDir: releaseDir, hashes: Array(cachedHashes))
        return bundleDest.path
    }

    private func applyNativeScriptFileDelta(path: String, hash: String, size: Int, patch: SdkFileDelta, dest: URL, maximumBytes: Int) async throws -> Bool {
        guard let active = readActive(), active.releaseId == patch.baseReleaseId,
              active.appVersion == appVersion else { return false }
        guard patch.algorithm == "npdiff1", patch.fromSha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              patch.patchSha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              patch.patchSize >= 12, patch.patchSize < size, size <= maximumBytes else {
            throw NitroPushError.integrityFailure("invalid file delta metadata")
        }
        let root = try Self.releaseDirectory(for: active.releaseId).resolvingSymlinksInPath()
        let source = root.appendingPathComponent(path).standardizedFileURL
        guard path.hasPrefix("app/"), source.path.hasPrefix(root.path + "/"), source.resolvingSymlinksInPath().path == source.path else {
            throw NitroPushError.integrityFailure("file delta base path is invalid")
        }
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, let sourceSize = values.fileSize, sourceSize > 0, sourceSize <= maximumBytes else {
            throw NitroPushError.integrityFailure("file delta base size is invalid")
        }
        let base = try Data(contentsOf: source)
        guard base.count == sourceSize, SHA256.hash(data: base).map({ String(format: "%02x", $0) }).joined() == patch.fromSha256 else {
            throw NitroPushError.integrityFailure("file delta base hash mismatch")
        }
        let patchURL = try patch.downloadUrl.map { try Self.validatedNetworkURL($0, name: "file delta URL", allowQuery: true) } ?? resolveObjectURL(patch.objectKey)
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("nitropush-file-\(UUID().uuidString).npdiff")
        defer { try? FileManager.default.removeItem(at: temporary) }
        try await downloadToFile(url: patchURL, dest: temporary, expectedSha256: patch.patchSha256, announcedSize: patch.patchSize, maximumBytes: patch.patchSize)
        let result = try NPFileDelta.apply(base: base, patch: Data(contentsOf: temporary), maximumBytes: maximumBytes)
        guard result.count == size, SHA256.hash(data: result).map({ String(format: "%02x", $0) }).joined() == hash else {
            throw NitroPushError.integrityFailure("reconstructed file hash mismatch")
        }
        try result.write(to: dest, options: .atomic)
        let cache = Self.rootDir().appendingPathComponent("cache", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        try result.write(to: cache.appendingPathComponent(hash), options: .atomic)
        return true
    }

    /// sha256-keyed disk cache, shared across releases. If `<cache>/<sha>` exists
    /// we copy it to `dest` and skip the network. Otherwise download to the cache
    /// path (verifying), then copy.
    private func fetchByContentHash(
        urlString: String,
        sha256: String,
        dest: URL,
        announcedSize: Int,
        maximumBytes: Int
    ) async throws -> Int {
        let cacheDir = Self.rootDir().appendingPathComponent("cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        let cached = cacheDir.appendingPathComponent(sha256)

        if FileManager.default.fileExists(atPath: cached.path) {
            let cachedHash = try Self.sha256Hex(of: cached)
            let values = try cached.resourceValues(forKeys: [.fileSizeKey])
            if cachedHash != sha256
                || values.fileSize == nil
                || values.fileSize! <= 0
                || values.fileSize! > maximumBytes
                || (announcedSize > 0 && values.fileSize != announcedSize) {
                try? FileManager.default.removeItem(at: cached)
            }
        }
        if !FileManager.default.fileExists(atPath: cached.path) {
            let url = try Self.validatedNetworkURL(
                urlString,
                name: "asset download URL",
                allowQuery: true
            )
            try await downloadToFile(
                url: url,
                dest: cached,
                expectedSha256: sha256,
                announcedSize: announcedSize,
                maximumBytes: maximumBytes
            )
        } else {
            touchFile(cached)
        }
        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.copyItem(at: cached, to: dest)
        let values = try dest.resourceValues(forKeys: [.fileSizeKey])
        guard let size = values.fileSize, size > 0, size <= maximumBytes else {
            try? FileManager.default.removeItem(at: dest)
            throw NitroPushError.integrityFailure("download exceeds the allowed size")
        }
        return size
    }

    private func downloadToFile(
        url: URL,
        dest: URL,
        expectedSha256: String,
        announcedSize: Int,
        maximumBytes: Int,
        authenticatedRequest: URLRequest? = nil
    ) async throws {
        let enforcedMaximum = announcedSize > 0
            ? min(maximumBytes, announcedSize)
            : maximumBytes
        let progressDelegate = ProgressTrackingDelegate(maximumBytes: Int64(enforcedMaximum)) { [weak self] received in
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
        let tmpURL: URL
        let response: URLResponse
        do {
            if let authenticatedRequest {
                (tmpURL, response) = try await dlSession.download(for: authenticatedRequest)
            } else {
                (tmpURL, response) = try await dlSession.download(from: url)
            }
        } catch {
            if progressDelegate.exceededLimit {
                throw NitroPushError.integrityFailure("download exceeds the allowed size")
            }
            throw error
        }
        defer { dlSession.invalidateAndCancel() }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw NitroPushError.networkFailure(
                "download returned a non-success response from \(Self.redactedURL(url))"
            )
        }
        let values = try tmpURL.resourceValues(forKeys: [.fileSizeKey])
        guard let downloadedSize = values.fileSize,
              downloadedSize > 0,
              downloadedSize <= maximumBytes else {
            throw NitroPushError.integrityFailure("download exceeds the allowed size")
        }
        if announcedSize > 0 {
            guard values.fileSize == announcedSize else {
                throw NitroPushError.integrityFailure(
                    "download size mismatch (expected \(announcedSize), received \(values.fileSize ?? -1))"
                )
            }
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
                    "download integrity check failed"
                )
            }
        }
    }

    /// Select the bundle that is actually running, snapshot it into a private
    /// temporary file, and verify that its bytes exactly match the delta base.
    /// Before the first OTA this is the app-embedded bundle; afterwards it must
    /// be the active OTA bundle. A historical cache entry is intentionally not
    /// sufficient because it may not represent the currently running code.
    private func materializeDeltaBase(expectedHash: String) throws -> URL {
        guard expectedHash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
            throw NitroPushError.integrityFailure("delta base hash is invalid")
        }

        let source: URL
        if let active = readActive() {
            guard active.bundleHash == expectedHash else {
                throw NitroPushError.integrityFailure("active bundle does not match the delta base")
            }
            let releaseDir = try Self.releaseDirectory(for: active.releaseId)
                .resolvingSymlinksInPath()
            let activeURL = URL(fileURLWithPath: active.bundlePath)
                .resolvingSymlinksInPath()
            guard activeURL.path.hasPrefix(releaseDir.path + "/"),
                  FileManager.default.fileExists(atPath: activeURL.path) else {
                throw NitroPushError.integrityFailure("active delta base is outside its release directory")
            }
            source = activeURL
        } else {
            guard embeddedBundleHash == expectedHash,
                  let embeddedURL = embeddedBundleURL() else {
                throw NitroPushError.integrityFailure("embedded bundle does not match the delta base")
            }
            source = embeddedURL.resolvingSymlinksInPath()
        }

        let sourceValues = try source.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard sourceValues.isRegularFile == true,
              let sourceSize = sourceValues.fileSize,
              sourceSize > 0,
              sourceSize <= Self.maxBundleBytes else {
            throw NitroPushError.integrityFailure("delta base size is outside the allowed range")
        }

        let snapshot = FileManager.default.temporaryDirectory
            .appendingPathComponent("nitropush-base-\(UUID().uuidString.lowercased()).bundle")
        do {
            try FileManager.default.copyItem(at: source, to: snapshot)
            let snapshotValues = try snapshot.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard snapshotValues.isRegularFile == true,
                  snapshotValues.fileSize == sourceSize,
                  try Self.sha256Hex(of: snapshot) == expectedHash else {
                throw NitroPushError.integrityFailure("delta base hash mismatch")
            }
            guard isHermesBundle(at: snapshot.path) else {
                throw NitroPushError.integrityFailure("delta base is not a valid Hermes bundle")
            }
            return snapshot
        } catch {
            try? FileManager.default.removeItem(at: snapshot)
            throw error
        }
    }

    /// Download a bsdiff4 patch, verify its hash, apply it against an exact
    /// snapshot of the currently running bundle, verify the output hash, then
    /// write the patched file to `dest`.
    private func classifyDeltaError(_ error: Error) -> String {
        let msg = error.localizedDescription.lowercased()
        if msg.contains("hash") || msg.contains("sha") || msg.contains("integrity") {
            return "hash_mismatch"
        }
        if msg.contains("bspatch") || msg.contains("patch") {
            return "bspatch_error"
        }
        return "patch_download_failed"
    }

    private func applyDeltaPatch(
        delta: NPDeltaPatch,
        expectedOutputSha256: String,
        dest: URL
    ) async throws {
        guard delta.patchSha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              delta.patchSize > 0,
              delta.patchSize <= Self.maxBundleBytes else {
            throw NitroPushError.integrityFailure("delta patch metadata is invalid")
        }

        let base = try materializeDeltaBase(expectedHash: delta.fromBundleHash)
        defer { try? FileManager.default.removeItem(at: base) }

        let cacheDir = Self.rootDir().appendingPathComponent("cache", isDirectory: true)

        // Prefer the API-issued device-bound URL. Older/self-hosted servers
        // can omit it and retain the legacy object-storage path.
        let patchRequest: URLRequest?
        let patchUrl: URL
        if let protectedUrl = delta.deltaDownloadUrl {
            let request = try requestForDeltaDownload(protectedUrl)
            guard let url = request.url else {
                throw NitroPushError.networkFailure("delta download URL is invalid")
            }
            patchRequest = request
            patchUrl = url
        } else {
            patchRequest = nil
            patchUrl = try resolveObjectURL(delta.patchObjectKey)
        }
        let tmpPatch = FileManager.default.temporaryDirectory
            .appendingPathComponent("nitropush-patch-\(UUID().uuidString.lowercased()).bsdiff")
        defer { try? FileManager.default.removeItem(at: tmpPatch) }

        try await downloadToFile(
            url: patchUrl,
            dest: tmpPatch,
            expectedSha256: delta.patchSha256,
            announcedSize: delta.patchSize,
            maximumBytes: Self.maxBundleBytes,
            authenticatedRequest: patchRequest
        )

        // Apply bsdiff4 patch. _bspatch_apply is declared in BspatchBridge.swift
        // via @_silgen_name, which links directly to the C symbol in bspatch.c.
        let rc = base.path.withCString { basePath in
            tmpPatch.path.withCString { patch in
                dest.path.withCString { out in
                    _bspatch_apply(basePath, patch, out)
                }
            }
        }
        guard rc == 0 else {
            throw NitroPushError.integrityFailure("bspatch failed with code \(rc)")
        }

        // Verify the patched output matches the new bundle hash.
        let actualSha256 = try Self.sha256Hex(of: dest)
        guard actualSha256.lowercased() == expectedOutputSha256.lowercased() else {
            try? FileManager.default.removeItem(at: dest)
            throw NitroPushError.integrityFailure(
                "patched bundle hash mismatch (expected \(expectedOutputSha256) got \(actualSha256))"
            )
        }

        // Hermes magic-byte sanity check.
        guard isHermesBundle(at: dest.path) else {
            try? FileManager.default.removeItem(at: dest)
            throw NitroPushError.integrityFailure("patched file is not a valid Hermes bundle")
        }

        // Copy the verified patched bundle into the content-hash cache for future use.
        let cachedOutput = cacheDir.appendingPathComponent(expectedOutputSha256)
        if !FileManager.default.fileExists(atPath: cachedOutput.path) {
            try? FileManager.default.copyItem(at: dest, to: cachedOutput)
        }
    }

}

// MARK: - Wire types for the SDK release manifest

/// Matches the JSON written by `createCodepushRelease` /
/// `createExpoRelease` in `@nitropush/shared-services`. Context fields are
/// optional only for unsigned legacy manifests; a configured public key
/// requires every schema-4 field below and verifies them fail closed.
private struct SdkManifest: Decodable {
    let schemaVersion: Int?
    let releaseId: String?
    let projectId: String?
    let environment: String?
    let deploymentKeyHash: String?
    let kind: String?
    let platforms: [String]?
    let targetAppVersion: String?
    let otaVersion: Double?
    let label: String?
    let isMandatory: Bool?
    let bundle: SdkManifestBundle
    let assets: [SdkManifestAsset]
    /// Schema 3 legacy content-only signature.
    let integritySignature: String?
    /// Schema 4 signature over release identity, targeting and all artifacts.
    let releaseSignature: String?
}

private struct SdkManifestBundleDelta: Decodable {
    let fromBundleHash: String
    let patchObjectKey: String
    let patchSize: Int
    let patchSha256: String
    let algorithm: String

    func toPlain() -> NPDeltaPatch {
        NPDeltaPatch(
            fromBundleHash: fromBundleHash,
            patchObjectKey: patchObjectKey,
            patchSize: patchSize,
            patchSha256: patchSha256,
            algorithm: algorithm,
            deltaDownloadUrl: nil
        )
    }
}

private struct SdkManifestBundle: Decodable {
    let originalPath: String
    let sha256: String
    let objectKey: String
    let size: Int?
    /// Pre-signed download URL returned by the manifest proxy endpoint.
    /// When present, used directly instead of storageBaseUrl + objectKey.
    let downloadUrl: String?
    /// Base64 DER ECDSA P-256 signature over `"bundle:<sha256>"`.
    /// Present only when the release was created with a signing key.
    let signature: String?
    /// Experimental: bsdiff4 patch against a previous bundle.
    let delta: SdkManifestBundleDelta?
    let fileDelta: SdkFileDelta?
}

private struct SdkManifestAsset: Decodable {
    let originalPath: String
    let ext: String
    let sha256: String
    let objectKey: String
    let size: Int?
    /// Pre-signed download URL returned by the manifest proxy endpoint.
    let downloadUrl: String?
    let fileDelta: SdkFileDelta?
}

private struct SdkFileDelta: Decodable {
    let algorithm: String
    let baseReleaseId: String
    let fromSha256: String
    let patchSha256: String
    let patchSize: Int
    let objectKey: String
    let downloadUrl: String?
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
        persistFlag(releaseId: pending.releaseId, isFirstRun: true)
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

    private func readPrevious() -> NPLocalPackage? {
        guard let dict = UserDefaults.standard.dictionary(forKey: DefaultsKey.previous) else { return nil }
        return NPLocalPackage.fromDict(dict)
    }

    private func deleteBundleDir(releaseId: String) {
        guard let dir = try? Self.releaseDirectory(for: releaseId) else {
            log("deleteBundleDir → skipped", "invalid release id")
            return
        }
        try? FileManager.default.removeItem(at: dir)
    }

    private func pruneUnusedBundlesAndCache() {
        let protectedPackages = [readActive(), readPending(), readPrevious()].compactMap { $0 }
        let protectedReleaseIds = Set(protectedPackages.map(\.releaseId))
        let protectedHashes = Set(protectedPackages.flatMap { readReleaseFileHashes(releaseId: $0.releaseId) })
        let root = Self.rootDir()
        let fm = FileManager.default

        if let entries = try? fm.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) {
            for entry in entries where entry.hasDirectoryPath {
                if entry.lastPathComponent == "cache" { continue }
                if protectedReleaseIds.contains(entry.lastPathComponent) { continue }
                try? fm.removeItem(at: entry)
            }
        }

        pruneContentCache(protectedHashes: protectedHashes)
    }

    private func writeReleaseFilesManifest(releaseDir: URL, hashes: [String]) {
        let uniqueHashes = Array(Set(hashes)).sorted()
        let payload: [String: Any] = [
            "schemaVersion": 1,
            "cachedHashes": uniqueHashes,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]) else {
            return
        }
        let dest = releaseDir.appendingPathComponent(Self.releaseFilesManifestName)
        try? data.write(to: dest, options: [.atomic])
    }

    private func readReleaseFileHashes(releaseId: String) -> [String] {
        guard let releaseDir = try? Self.releaseDirectory(for: releaseId) else { return [] }
        let manifest = releaseDir
            .appendingPathComponent(Self.releaseFilesManifestName)
        guard let data = try? Data(contentsOf: manifest),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hashes = json["cachedHashes"] as? [String] else {
            return []
        }
        return hashes
    }

    private func pruneContentCache(protectedHashes: Set<String>) {
        let fm = FileManager.default
        let cacheDir = Self.rootDir().appendingPathComponent("cache", isDirectory: true)
        guard let entries = try? fm.contentsOfDirectory(
            at: cacheDir,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        let now = Date()
        var candidates: [(url: URL, modified: Date, size: Int)] = []
        var totalBytes = 0
        for entry in entries where !entry.hasDirectoryPath {
            let values = try? entry.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
            let modified = values?.contentModificationDate ?? .distantPast
            let size = values?.fileSize ?? 0
            totalBytes += size
            if protectedHashes.contains(entry.lastPathComponent) { continue }
            candidates.append((entry, modified, size))
            if now.timeIntervalSince(modified) > Self.maxUnprotectedCacheAgeSeconds {
                try? fm.removeItem(at: entry)
                totalBytes -= size
            }
        }

        if totalBytes <= Self.maxCacheBytes { return }
        for candidate in candidates.sorted(by: { $0.modified < $1.modified }) {
            if totalBytes <= Self.targetCacheBytesAfterPrune { break }
            guard fm.fileExists(atPath: candidate.url.path) else { continue }
            try? fm.removeItem(at: candidate.url)
            totalBytes -= candidate.size
        }
    }

    private func touchFile(_ url: URL) {
        try? FileManager.default.setAttributes(
            [.modificationDate: Date()],
            ofItemAtPath: url.path
        )
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
            // NitroPushTriggerReload is defined in NitroPushReloadBridge.m and wraps
            // RCTTriggerReloadCommandListeners (React Native 0.71+). Prior to 0.71,
            // RCTReloadCommand was an ObjC class — that API no longer exists in
            // RN 0.81+ so the old NSClassFromString lookup silently returned nil.
            NPHostRuntime.reload()
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
            deploymentKey: deploymentKey,
            serverUrl: read("NITROPUSH_SERVER_URL") ?? "https://api.nitropush.org",
            storageBaseUrl: read("NITROPUSH_STORAGE_BASE_URL") ?? "https://cdn.nitropush.org",
            appVersion: read("NITROPUSH_APP_VERSION"),
            clientUniqueId: read("NITROPUSH_CLIENT_UNIQUE_ID"),
            bundlePublicKey: read("NITROPUSH_BUNDLE_PUBLIC_KEY"),
            enableDeltaUpdates: (Bundle.main.object(forInfoDictionaryKey: "NITROPUSH_ENABLE_DELTA_UPDATES") as? Bool) ?? false
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
        try verifySignature(
            message: "bundle:\(sha256)",
            signatureBase64: signatureBase64,
            publicKeyBase64: publicKeyBase64,
            description: "bundle"
        )
    }

    private static func verifySignature(
        message: String,
        signatureBase64: String,
        publicKeyBase64: String,
        description: String
    ) throws {
        guard let pubKeyData = Data(base64Encoded: publicKeyBase64) else {
            throw NitroPushError.integrityFailure("bundlePublicKey is not valid base64")
        }
        guard let sigData = Data(base64Encoded: signatureBase64) else {
            throw NitroPushError.integrityFailure("\(description) signature is not valid base64")
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
        guard pubKey.isValidSignature(sig, for: Data(message.utf8)) else {
            throw NitroPushError.integrityFailure(
                "\(description) signature mismatch"
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

/// Wraps `NPRemotePackage` so we can also capture the optional `downloadUrl`
/// field alongside it without modifying the Nitro-generated type.
private struct LatestReleaseWrapper: Decodable {
    let pkg: NPRemotePackage
    let downloadUrl: String?

    private enum ExtraKeys: String, CodingKey { case downloadUrl }

    init(from decoder: Decoder) throws {
        pkg = try NPRemotePackage(from: decoder)
        let c = try decoder.container(keyedBy: ExtraKeys.self)
        downloadUrl = try c.decodeIfPresent(String.self, forKey: .downloadUrl)
    }
}

private struct LatestReleaseResponse: Decodable {
    let release: LatestReleaseWrapper?
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
            downloadObjectKey: try c.decode(String.self, forKey: .downloadObjectKey),
            delta: try {
                guard
                    let from = try c.decodeIfPresent(String.self, forKey: .deltaFromBundleHash),
                    let key = try c.decodeIfPresent(String.self, forKey: .deltaObjectKey),
                    let size = try c.decodeIfPresent(Int.self, forKey: .deltaSizeBytes),
                    let sha = try c.decodeIfPresent(String.self, forKey: .deltaPatchSha256)
                else { return nil }
                return NPDeltaPatch(
                    fromBundleHash: from,
                    patchObjectKey: key,
                    patchSize: size,
                    patchSha256: sha,
                    algorithm: try c.decodeIfPresent(String.self, forKey: .deltaAlgorithm) ?? "bsdiff4",
                    deltaDownloadUrl: try c.decodeIfPresent(String.self, forKey: .deltaDownloadUrl)
                        .flatMap {
                            let trimmed = $0.trimmingCharacters(in: .whitespacesAndNewlines)
                            return trimmed.isEmpty ? nil : trimmed
                        }
                )
            }()
        )
    }
    private enum CodingKeys: String, CodingKey {
        case releaseId, kind, label, packageHash, packageSize, appVersion
        case otaVersion, displayVersion, platforms
        case isMandatory, description, downloadObjectKey
        case deltaFromBundleHash, deltaObjectKey, deltaSizeBytes, deltaPatchSha256, deltaAlgorithm
        case deltaDownloadUrl
    }
}

private final class NoRedirectSessionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

private final class ProgressTrackingDelegate: NSObject, URLSessionDownloadDelegate {
    private let onProgress: (Int64) -> Void
    private let maximumBytes: Int64
    private(set) var exceededLimit = false

    init(maximumBytes: Int64, onProgress: @escaping (Int64) -> Void) {
        self.maximumBytes = maximumBytes
        self.onProgress = onProgress
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > maximumBytes {
            exceededLimit = true
            downloadTask.cancel()
            return
        }
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
        if let bundleHash = bundleHash { dict["bundleHash"] = bundleHash }
        return dict
    }

    static func fromDict(_ dict: [String: Any]) -> NPLocalPackage? {
        guard let releaseId = dict["releaseId"] as? String,
              let label = dict["label"] as? String,
              let packageHash = dict["packageHash"] as? String,
              let packageSize = dict["packageSize"] as? Double,
              let appVersion = dict["appVersion"] as? String,
              let isMandatory = dict["isMandatory"] as? Bool,
              let bundlePath = dict["bundlePath"] as? String,
              let releaseDir = try? NitroPushSdk.releaseDirectory(for: releaseId),
              URL(fileURLWithPath: bundlePath).standardizedFileURL.path.hasPrefix(releaseDir.path + "/")
        else { return nil }
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
            bundlePath: bundlePath,
            bundleHash: dict["bundleHash"] as? String
        )
    }
}
