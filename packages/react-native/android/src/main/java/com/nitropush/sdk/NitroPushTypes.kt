package com.nitropush.sdk

/**
 * Plain native data types used by [NitroPushSdk]. These mirror the JS-facing
 * Nitro types one-for-one, but live in the SDK's own namespace so the OTA
 * core has zero Nitro / fbjni dependency. The Nitro bridge translates
 * between these and the auto-generated Nitrogen types.
 */
data class NPConfig(
    val deploymentKey: String,
    val serverUrl: String = "https://api.nitropush.org",
    /** Public base URL where bundles + assets live. Joined with each
     *  `objectKey` at fetch time. */
    val storageBaseUrl: String = "https://cdn.nitropush.org",
    val appVersion: String? = null,
    val clientUniqueId: String? = null,
    /**
     * Base64-encoded DER SubjectPublicKeyInfo for ECDSA P-256 bundle
     * signature verification. When set, every downloaded bundle *must*
     * carry a valid signature in its manifest entry — unsigned or
     * tampered bundles are rejected. `null` (default) skips verification.
     */
    val bundlePublicKey: String? = null,
    /** Enable experimental delta (bsdiff4) bundle updates. When true, the SDK
     *  sends `currentBundleHash` with every update check and downloads a patch
     *  instead of the full bundle when the server offers one. Falls back to full
     *  bundle on any failure. Default: false. */
    val enableDeltaUpdates: Boolean = false,
)

data class NPDeltaPatch(
    val fromBundleHash: String,
    val patchObjectKey: String,
    val patchSize: Int,
    val patchSha256: String,
    val algorithm: String,
    /** Optional API-issued, device-bound patch URL. */
    val deltaDownloadUrl: String? = null,
)

data class NPRemotePackage(
    val releaseId: String,
    /** "codepush" (tarball) or "expo" (manifest). */
    val kind: String,
    val label: String,
    val packageHash: String,
    val packageSize: Double,
    val appVersion: String,
    /** Per-target-version OTA sequence assigned by the server (e.g. 2). */
    val otaVersion: Double?,
    /** Concatenated display version (e.g. "1.0.0.2"). */
    val displayVersion: String?,
    /** Platforms this release covers (`["ios"]`, `["android"]`, or both). */
    val platforms: Array<String>?,
    val isMandatory: Boolean,
    val description: String?,
    /** Bucket-relative key. SDK joins with `NPConfig.storageBaseUrl`.
     *  Codepush → tarball key; expo → manifest key. */
    val downloadObjectKey: String,
    /** Patch selected by the server for this device's current bundle hash. */
    val delta: NPDeltaPatch? = null,
)

data class NPLocalPackage(
    val releaseId: String,
    val label: String,
    val packageHash: String,
    val packageSize: Double,
    val appVersion: String,
    val otaVersion: Double?,
    val displayVersion: String?,
    val platforms: Array<String>?,
    val isMandatory: Boolean,
    val description: String?,
    val isPending: Boolean,
    val isFailedInstall: Boolean,
    val isFirstRun: Boolean,
    val bundlePath: String,
    /** SHA-256 of the raw JS bundle file. Sent as `currentBundleHash` in
     *  update-check requests for delta eligibility checks. */
    val bundleHash: String? = null,
) {
    companion object
}

data class NPDownloadProgress(val receivedBytes: Double, val totalBytes: Double)

enum class NPInstallMode { IMMEDIATE, ON_NEXT_RESTART, ON_NEXT_RESUME, ON_NEXT_SUSPEND }
