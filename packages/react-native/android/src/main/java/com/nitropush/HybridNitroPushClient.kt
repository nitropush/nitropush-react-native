package com.nitropush

import com.margelo.nitro.core.NullType
import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitropush.HybridNitroPushClientSpec
import com.margelo.nitro.nitropush.Variant_NullType_HybridLocalPackageSpec
import com.margelo.nitro.nitropush.Variant_NullType_HybridRemotePackageSpec
import com.nitropush.sdk.NitroPushSdk

/**
 * Kotlin counterpart of `ios/HybridNitroPushClient.swift`. HybridObject
 * returned from `NitroPush.configure()` / `NitroPush.configureWith(...)`.
 * Carries every runtime operation — `checkForUpdate`, `notifyAppReady`,
 * `restartApp`, the inspection helpers, and `clearUpdates`. Internally
 * delegates to the process-wide [NitroPushSdk.shared] singleton.
 */
internal class HybridNitroPushClient : HybridNitroPushClientSpec() {

  private val sdk: NitroPushSdk get() = NitroPushSdk.shared

  override fun checkForUpdate(
    deploymentKeyOverride: String?,
  ): Promise<Variant_NullType_HybridRemotePackageSpec> = Promise.parallel {
    val remote = sdk.checkForUpdate(deploymentKeyOverride)
    if (remote == null) {
      Variant_NullType_HybridRemotePackageSpec.First(NullType.NULL)
    } else {
      Variant_NullType_HybridRemotePackageSpec.Second(HybridRemotePackage(remote))
    }
  }

  override fun notifyAppReady(): Promise<Unit> =
    Promise.parallel { sdk.notifyAppReady() }

  override fun restartApp(onlyIfUpdateIsPending: Boolean): Promise<Unit> =
    Promise.parallel { sdk.restartApp(onlyIfUpdateIsPending) }

  override fun getCurrentPackage(): Promise<Variant_NullType_HybridLocalPackageSpec> =
    Promise.parallel { wrapLocal(sdk.getCurrentPackage()) }

  override fun getUpdateMetadataSync(): Variant_NullType_HybridLocalPackageSpec {
    // `getCurrentPackage()` reads `SharedPreferences` synchronously with no
    // blocking I/O — safe to call on the JS thread, mirrors the iOS sync
    // variant backed by `UserDefaults`.
    return wrapLocal(sdk.getCurrentPackage())
  }

  override fun getPendingPackage(): Promise<Variant_NullType_HybridLocalPackageSpec> =
    Promise.parallel { wrapLocal(sdk.getPendingPackage()) }

  override fun clearUpdates(): Promise<Unit> =
    Promise.parallel { sdk.clearUpdates() }
}

private fun wrapLocal(
  pkg: com.nitropush.sdk.NlLocalPackage?,
): Variant_NullType_HybridLocalPackageSpec =
  if (pkg == null) {
    Variant_NullType_HybridLocalPackageSpec.First(NullType.NULL)
  } else {
    Variant_NullType_HybridLocalPackageSpec.Second(HybridLocalPackage(pkg))
  }
