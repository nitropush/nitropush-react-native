//
//  HybridNitroPushClient.swift
//  NitroPush
//
//  HybridObject returned from `NitroPush.configure()` /
//  `NitroPush.configureWith(...)`. Carries every runtime operation —
//  `checkForUpdate`, `notifyAppReady`, `restartApp`, the inspection
//  helpers, and `clearUpdates`. Internally delegates to the
//  process-wide `NitroPushSdk.shared` singleton.
//

import Foundation
import NitroModules

final class HybridNitroPushClient: HybridNitroPushClientSpec {
    private var sdk: NitroPushSdk { NitroPushSdk.shared }

    public func checkForUpdate(deploymentKeyOverride: String?) throws -> Promise<Variant_NullType__any_HybridRemotePackageSpec_> {
        return Promise.async {
            let r = try await self.sdk.checkForUpdate(deploymentKeyOverride: deploymentKeyOverride)
            guard let r = r else { return .first(NullType.null) }
            return .second(HybridRemotePackage(plain: r))
        }
    }

    public func notifyAppReady() throws -> Promise<Void> {
        return Promise.async { self.sdk.notifyAppReady() }
    }

    public func restartApp(onlyIfUpdateIsPending: Bool) throws -> Promise<Void> {
        return Promise.async { try self.sdk.restartApp(onlyIfUpdateIsPending: onlyIfUpdateIsPending) }
    }

    public func getCurrentPackage() throws -> Promise<Variant_NullType__any_HybridLocalPackageSpec_> {
        return Promise.async {
            guard let p = self.sdk.getCurrentPackage() else { return .first(NullType.null) }
            return .second(HybridLocalPackage(plain: p))
        }
    }

    public func getUpdateMetadataSync() throws -> Variant_NullType__any_HybridLocalPackageSpec_ {
        // `sdk.getCurrentPackage()` already runs synchronously off
        // `UserDefaults`; no I/O blocking, safe to call on the JS thread.
        guard let p = sdk.getCurrentPackage() else { return .first(NullType.null) }
        return .second(HybridLocalPackage(plain: p))
    }

    public func getPendingPackage() throws -> Promise<Variant_NullType__any_HybridLocalPackageSpec_> {
        return Promise.async {
            guard let p = self.sdk.getPendingPackage() else { return .first(NullType.null) }
            return .second(HybridLocalPackage(plain: p))
        }
    }

    public func clearUpdates() throws -> Promise<Void> {
        return Promise.async { self.sdk.clearUpdates() }
    }
}
