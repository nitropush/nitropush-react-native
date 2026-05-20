//
//  HybridNitroPushSdk.swift
//  NitroPush
//
//  Singleton factory exposed as the JS `NitroPush` HybridObject. Two
//  factories build a `HybridNitroPushClient`:
//
//    • `configure()`     — reads NITROPUSH_DEPLOYMENT_KEY from Info.plist
//                          (AndroidManifest meta-data on Android); server
//                          and CDN URLs default to the NitroPush-hosted
//                          endpoints (api.nitropush.org / cdn.nitropush.org).
//    • `configureWith()` — applies a fully explicit `NitroPushConfig`
//                          (custom server / CDN).
//
//  Both share the process-wide `NitroPushSdk.shared` core; the returned
//  client is a thin wrapper that delegates every call to it.
//

import Foundation
import NitroModules

class HybridNitroPushSdk: HybridNitroPushSpec {
    public override init() {
        super.init()
        // Eagerly create the singleton so its launch-time pointer
        // consumption + lifecycle observers run before the JS bridge boots.
        _ = NitroPushSdk.shared
    }

    public func configure() throws -> (any HybridNitroPushClientSpec) {
        let config = try NitroPushSdk.configFromInfoPlist()
        try NitroPushSdk.shared.configure(config)
        return HybridNitroPushClient()
    }

    public func configureWith(config: NitroPushConfig) throws -> (any HybridNitroPushClientSpec) {
        try NitroPushSdk.shared.configure(config.toPlain())
        return HybridNitroPushClient()
    }
}

private extension NitroPushConfig {
    func toPlain() -> NPConfig {
        NPConfig(
            serverUrl: serverUrl,
            deploymentKey: deploymentKey,
            storageBaseUrl: storageBaseUrl,
            appVersion: appVersion,
            clientUniqueId: clientUniqueId
        )
    }
}
