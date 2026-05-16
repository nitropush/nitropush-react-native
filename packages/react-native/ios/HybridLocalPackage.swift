//
//  HybridLocalPackage.swift
//  NitroPush
//
//  HybridObject wrapping a single `NPLocalPackage` snapshot. Returned from
//  `RemotePackage.download()` and the inspection methods on the parent
//  singleton (`getCurrentPackage`, `getPendingPackage`).
//

import Foundation
import NitroModules

final class HybridLocalPackage: HybridLocalPackageSpec {
    private let plain: NPLocalPackage

    init(plain: NPLocalPackage) {
        self.plain = plain
        super.init()
    }

    // MARK: - Properties

    var releaseId: String { plain.releaseId }
    var label: String { plain.label }
    var packageHash: String { plain.packageHash }
    var packageSize: Double { plain.packageSize }
    var appVersion: String { plain.appVersion }
    var otaVersion: Double? { plain.otaVersion }
    var displayVersion: String? { plain.displayVersion }
    var platforms: [String]? { plain.platforms }
    var isMandatory: Bool { plain.isMandatory }
    var description: String? { plain.description }
    var isPending: Bool { plain.isPending }
    var isFailedInstall: Bool { plain.isFailedInstall }
    var isFirstRun: Bool { plain.isFirstRun }
    var bundlePath: String { plain.bundlePath }

    // MARK: - Methods

    func install(installMode: InstallMode, minimumBackgroundDuration: Double) throws -> Promise<Void> {
        let snapshot = self.plain
        let mode = installMode.toPlain()
        return Promise.async {
            try await NitroPushSdk.shared.installUpdate(
                pkg: snapshot,
                installMode: mode,
                minimumBackgroundDuration: minimumBackgroundDuration
            )
        }
    }

    func rollback() throws -> Promise<Void> {
        let id = plain.releaseId
        return Promise.async {
            try NitroPushSdk.shared.rollback(releaseId: id)
        }
    }
}

private extension InstallMode {
    func toPlain() -> NPInstallMode {
        switch self {
        case .immediate: return .immediate
        case .onNextRestart: return .onNextRestart
        case .onNextResume: return .onNextResume
        case .onNextSuspend: return .onNextSuspend
        }
    }
}
