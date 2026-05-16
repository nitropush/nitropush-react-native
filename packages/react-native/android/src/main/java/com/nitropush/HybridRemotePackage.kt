package com.nitropush

import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitropush.DownloadProgress
import com.margelo.nitro.nitropush.HybridLocalPackageSpec
import com.margelo.nitro.nitropush.HybridRemotePackageSpec
import com.nitropush.sdk.NitroPushSdk
import com.nitropush.sdk.NlDownloadProgress
import com.nitropush.sdk.NlRemotePackage

/**
 * Kotlin counterpart of `ios/HybridRemotePackage.swift`. HybridObject wrapping
 * a single [NlRemotePackage] snapshot returned from
 * [HybridNitroPushClient.checkForUpdate]. Owns the [download] method so
 * callers can drive the lifecycle on the package itself, including a
 * per-download progress callback.
 */
internal class HybridRemotePackage(
  private val plain: NlRemotePackage,
) : HybridRemotePackageSpec() {

  // ── Properties ────────────────────────────────────────────────────────────
  override val releaseId: String get() = plain.releaseId
  override val kind: String get() = plain.kind
  override val label: String get() = plain.label
  override val packageHash: String get() = plain.packageHash
  override val packageSize: Double get() = plain.packageSize
  override val appVersion: String get() = plain.appVersion
  override val otaVersion: Double? get() = plain.otaVersion
  override val displayVersion: String? get() = plain.displayVersion
  override val platforms: Array<String>? get() = plain.platforms
  override val isMandatory: Boolean get() = plain.isMandatory
  override val description: String? get() = plain.description
  override val downloadObjectKey: String get() = plain.downloadObjectKey

  // ── Methods ───────────────────────────────────────────────────────────────
  override fun download(
    onProgress: (progress: DownloadProgress) -> Unit,
  ): Promise<HybridLocalPackageSpec> {
    val sdk = NitroPushSdk.shared
    // Per-download listener — registered before the async download starts and
    // torn down on success or failure. Avoids leaking listeners across
    // multiple downloads of different packages.
    val listenerId = sdk.addDownloadProgressListener { p ->
      onProgress(p.toNitro())
    }
    return Promise.parallel {
      try {
        HybridLocalPackage(sdk.downloadUpdate(plain))
      } finally {
        sdk.removeDownloadProgressListener(listenerId)
      }
    }
  }
}

private fun NlDownloadProgress.toNitro() = DownloadProgress(
  receivedBytes = receivedBytes,
  totalBytes = totalBytes,
)
