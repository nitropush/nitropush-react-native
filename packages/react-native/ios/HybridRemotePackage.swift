//
//  HybridRemotePackage.swift
//  NitroPush
//
//  HybridObject wrapping a single `NPRemotePackage` snapshot. Returned from
//  `HybridNitroPushClient.checkForUpdate`. Owns the `download()` method so
//  callers can drive the lifecycle on the package itself, including a
//  per-download progress callback.
//

import Foundation
import NitroModules

final class HybridRemotePackage: HybridRemotePackageSpec {
    private let plain: NPRemotePackage

    init(plain: NPRemotePackage) {
        self.plain = plain
        super.init()
    }

    // MARK: - Properties

    var releaseId: String { plain.releaseId }
    var kind: String { plain.kind }
    var label: String { plain.label }
    var packageHash: String { plain.packageHash }
    var packageSize: Double { plain.packageSize }
    var appVersion: String { plain.appVersion }
    var otaVersion: Double? { plain.otaVersion }
    var displayVersion: String? { plain.displayVersion }
    var platforms: [String]? { plain.platforms }
    var isMandatory: Bool { plain.isMandatory }
    var description: String? { plain.description }
    var downloadObjectKey: String { plain.downloadObjectKey }

    // MARK: - Methods

    func download(
        onProgress: @escaping (DownloadProgress) -> Void
    ) throws -> Promise<(any HybridLocalPackageSpec)> {
        let snapshot = self.plain
        // Per-download listener — registered before the async download
        // starts and torn down on success or failure. Avoids leaking
        // listeners across multiple downloads of different packages.
        let listenerId = NitroPushSdk.shared.addDownloadProgressListener { plain in
            onProgress(DownloadProgress(
                receivedBytes: plain.receivedBytes,
                totalBytes: plain.totalBytes
            ))
        }
        return Promise.async {
            do {
                let local = try await NitroPushSdk.shared.downloadUpdate(snapshot)
                NitroPushSdk.shared.removeDownloadProgressListener(listenerId: listenerId)
                return HybridLocalPackage(plain: local)
            } catch {
                NitroPushSdk.shared.removeDownloadProgressListener(listenerId: listenerId)
                throw error
            }
        }
    }
}
