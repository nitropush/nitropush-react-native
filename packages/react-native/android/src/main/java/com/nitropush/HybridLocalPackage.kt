package com.nitropush

import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitropush.HybridLocalPackageSpec
import com.margelo.nitro.nitropush.InstallMode
import com.nitropush.sdk.NitroPushSdk
import com.nitropush.sdk.NPInstallMode
import com.nitropush.sdk.NPLocalPackage

/**
 * Kotlin counterpart of `ios/HybridLocalPackage.swift`. HybridObject wrapping
 * a single [NPLocalPackage] snapshot. Returned from
 * [HybridRemotePackage.download] and the inspection methods on
 * [HybridNitroPushClient] (`getCurrentPackage`, `getPendingPackage`).
 */
internal class HybridLocalPackage(
  private val plain: NPLocalPackage,
) : HybridLocalPackageSpec() {

  // ── Properties ────────────────────────────────────────────────────────────
  override val releaseId: String get() = plain.releaseId
  override val label: String get() = plain.label
  override val packageHash: String get() = plain.packageHash
  override val packageSize: Double get() = plain.packageSize
  override val appVersion: String get() = plain.appVersion
  override val otaVersion: Double? get() = plain.otaVersion
  override val displayVersion: String? get() = plain.displayVersion
  override val platforms: Array<String>? get() = plain.platforms
  override val isMandatory: Boolean get() = plain.isMandatory
  override val description: String? get() = plain.description
  override val isPending: Boolean get() = plain.isPending
  override val isFailedInstall: Boolean get() = plain.isFailedInstall
  override val isFirstRun: Boolean get() = plain.isFirstRun
  override val bundlePath: String get() = plain.bundlePath

  // ── Methods ───────────────────────────────────────────────────────────────
  override fun install(
    installMode: InstallMode,
    minimumBackgroundDuration: Double,
  ): Promise<Unit> = Promise.parallel {
    NitroPushSdk.shared.installUpdate(
      pkg = plain,
      installMode = installMode.toPlain(),
      minimumBackgroundDurationSeconds = minimumBackgroundDuration,
    )
  }

  override fun rollback(): Promise<Unit> = Promise.parallel {
    NitroPushSdk.shared.rollback(plain.releaseId)
  }
}

private fun InstallMode.toPlain(): NPInstallMode = when (this) {
  InstallMode.IMMEDIATE -> NPInstallMode.IMMEDIATE
  InstallMode.ON_NEXT_RESTART -> NPInstallMode.ON_NEXT_RESTART
  InstallMode.ON_NEXT_RESUME -> NPInstallMode.ON_NEXT_RESUME
  InstallMode.ON_NEXT_SUSPEND -> NPInstallMode.ON_NEXT_SUSPEND
}
