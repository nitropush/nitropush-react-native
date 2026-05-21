package com.nitropush.sdk

/**
 * Plain native data types used by [NitroPushSdk]. These mirror the JS-facing
 * Nitro types one-for-one, but live in the SDK's own namespace so the OTA
 * core has zero Nitro / fbjni dependency. The Nitro bridge translates
 * between these and the auto-generated Nitrogen types.
 */
data class NlConfig(
    val serverUrl: String,
    val deploymentKey: String,
    /** Public base URL where bundles + assets live. Joined with each
     *  `objectKey` at fetch time. */
    val storageBaseUrl: String,
    val appVersion: String? = null,
    val clientUniqueId: String? = null,
    /**
     * Base64-encoded DER SubjectPublicKeyInfo for ECDSA P-256 bundle
     * signature verification. When set, every downloaded bundle *must*
     * carry a valid signature in its manifest entry — unsigned or
     * tampered bundles are rejected. `null` (default) skips verification.
     */
    val bundlePublicKey: String? = null,
)

data class NlRemotePackage(
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
    /** Bucket-relative key. SDK joins with `NlConfig.storageBaseUrl`.
     *  Codepush → tarball key; expo → manifest key. */
    val downloadObjectKey: String,
)

data class NlLocalPackage(
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
) {
    companion object
}

data class NlDownloadProgress(val receivedBytes: Double, val totalBytes: Double)

enum class NlInstallMode { IMMEDIATE, ON_NEXT_RESTART, ON_NEXT_RESUME, ON_NEXT_SUSPEND }
