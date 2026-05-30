import Foundation

/// Plain SDK types — used by `NitroPushSdk` and exposed to host AppDelegate
/// integration code. The Nitrogen-generated types live in `NitroPushSDK`'s
/// internal Swift module; the bridge converts between the two.
public struct NPConfig {
    public let serverUrl: String
    public let deploymentKey: String
    /// Public base URL where bundles + assets live (S3 / CDN). Joined
    /// with each `objectKey` at fetch time.
    public let storageBaseUrl: String
    public let appVersion: String?
    public let clientUniqueId: String?
    /// Base64-encoded DER SubjectPublicKeyInfo for the ECDSA P-256 key
    /// used to verify bundle signatures. When set, every downloaded
    /// bundle *must* carry a valid signature in its manifest entry —
    /// bundles with a missing or invalid signature are rejected.
    /// When `nil` (default) signature verification is skipped.
    public let bundlePublicKey: String?

    public init(
        deploymentKey: String,
        serverUrl: String = "https://api.nitropush.org",
        storageBaseUrl: String = "https://cdn.nitropush.org",
        appVersion: String? = nil,
        clientUniqueId: String? = nil,
        bundlePublicKey: String? = nil
    ) {
        self.serverUrl = serverUrl
        self.deploymentKey = deploymentKey
        self.storageBaseUrl = storageBaseUrl
        self.appVersion = appVersion
        self.clientUniqueId = clientUniqueId
        self.bundlePublicKey = bundlePublicKey
    }
}

public struct NPRemotePackage {
    public let releaseId: String
    /// "codepush" (tarball) or "expo" (manifest).
    public let kind: String
    public let label: String
    public let packageHash: String
    public let packageSize: Double
    public let appVersion: String
    /// Per-target-version OTA sequence assigned by the server.
    public let otaVersion: Double?
    /// Concatenated display version (e.g. "1.0.0.2").
    public let displayVersion: String?
    /// Platforms this release covers.
    public let platforms: [String]?
    public let isMandatory: Bool
    public let description: String?
    /// Bucket-relative key. SDK joins with `NlConfig.storageBaseUrl`.
    /// Codepush → tarball key; expo → manifest key.
    public let downloadObjectKey: String

    public init(
        releaseId: String,
        kind: String,
        label: String,
        packageHash: String,
        packageSize: Double,
        appVersion: String,
        otaVersion: Double? = nil,
        displayVersion: String? = nil,
        platforms: [String]? = nil,
        isMandatory: Bool,
        description: String?,
        downloadObjectKey: String
    ) {
        self.releaseId = releaseId
        self.kind = kind
        self.label = label
        self.packageHash = packageHash
        self.packageSize = packageSize
        self.appVersion = appVersion
        self.otaVersion = otaVersion
        self.displayVersion = displayVersion
        self.platforms = platforms
        self.isMandatory = isMandatory
        self.description = description
        self.downloadObjectKey = downloadObjectKey
    }
}

public struct NPLocalPackage {
    public let releaseId: String
    public let label: String
    public let packageHash: String
    public let packageSize: Double
    public let appVersion: String
    public let otaVersion: Double?
    public let displayVersion: String?
    public let platforms: [String]?
    public let isMandatory: Bool
    public let description: String?
    public let isPending: Bool
    public let isFailedInstall: Bool
    public let isFirstRun: Bool
    public let bundlePath: String

    public init(
        releaseId: String,
        label: String,
        packageHash: String,
        packageSize: Double,
        appVersion: String,
        otaVersion: Double? = nil,
        displayVersion: String? = nil,
        platforms: [String]? = nil,
        isMandatory: Bool,
        description: String?,
        isPending: Bool,
        isFailedInstall: Bool,
        isFirstRun: Bool,
        bundlePath: String
    ) {
        self.releaseId = releaseId
        self.label = label
        self.packageHash = packageHash
        self.packageSize = packageSize
        self.appVersion = appVersion
        self.otaVersion = otaVersion
        self.displayVersion = displayVersion
        self.platforms = platforms
        self.isMandatory = isMandatory
        self.description = description
        self.isPending = isPending
        self.isFailedInstall = isFailedInstall
        self.isFirstRun = isFirstRun
        self.bundlePath = bundlePath
    }
}

public struct NPDownloadProgress {
    public let receivedBytes: Double
    public let totalBytes: Double

    public init(receivedBytes: Double, totalBytes: Double) {
        self.receivedBytes = receivedBytes
        self.totalBytes = totalBytes
    }
}

public enum NPInstallMode {
    case immediate
    case onNextRestart
    case onNextResume
    case onNextSuspend
}

public enum NitroPushError: Error, LocalizedError {
    case notConfigured
    case invalidConfig(String)
    case networkFailure(String)
    case integrityFailure(String)

    public var errorDescription: String? {
        switch self {
        case .notConfigured: return "NitroPushSdk.configure(...) was not called."
        case .invalidConfig(let m): return "NitroPush config error: \(m)"
        case .networkFailure(let m): return "NitroPush network error: \(m)"
        case .integrityFailure(let m): return "NitroPush integrity error: \(m)"
        }
    }
}
