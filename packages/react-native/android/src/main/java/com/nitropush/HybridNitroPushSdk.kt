package com.nitropush

import com.margelo.nitro.nitropush.HybridNitroPushClientSpec
import com.margelo.nitro.nitropush.HybridNitroPushSpec
import com.margelo.nitro.nitropush.NitroPushConfig
import com.nitropush.sdk.NlConfig
import com.nitropush.sdk.NitroPushSdk

/**
 * Kotlin counterpart of `ios/HybridNitroPushSdk.swift`. Singleton factory
 * exposed as the JS `NitroPush` HybridObject. Two factories build a
 * [HybridNitroPushClient]:
 *
 *  • [configure]     — reads `NITROPUSH_*` keys from `<application>` `<meta-data>`
 *                      in `AndroidManifest.xml` and applies them to the
 *                      underlying core.
 *  • [configureWith] — applies an explicit [NitroPushConfig].
 *
 * Both share the process-wide [NitroPushSdk.shared] core; the returned client
 * is a thin wrapper that delegates every call to it.
 *
 * Requires `NitroPushSdk.install(application)` to have already been called in
 * `MainApplication.onCreate()` so the singleton is initialised, the rollback
 * sweep has run, and lifecycle observers are attached before the JS bridge
 * boots.
 */
class HybridNitroPushSdk : HybridNitroPushSpec() {

  init {
    // Touch the singleton to fail loudly here if the host app forgot to call
    // `NitroPushSdk.install(application)` in `MainApplication.onCreate()`.
    NitroPushSdk.shared
  }

  override fun configure(): HybridNitroPushClientSpec {
    NitroPushSdk.shared.configure(NitroPushSdk.configFromManifest())
    return HybridNitroPushClient()
  }

  override fun configureWith(config: NitroPushConfig): HybridNitroPushClientSpec {
    NitroPushSdk.shared.configure(config.toPlain())
    return HybridNitroPushClient()
  }
}

private fun NitroPushConfig.toPlain() = NlConfig(
  serverUrl = serverUrl,
  deploymentKey = deploymentKey,
  storageBaseUrl = storageBaseUrl,
  appVersion = appVersion,
  clientUniqueId = clientUniqueId,
)
