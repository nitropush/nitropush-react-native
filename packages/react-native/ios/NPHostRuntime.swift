import Foundation

/// React Native host adapter. The NativeScript package supplies its own adapter.
enum NPHostRuntime {
    static func accepts(kind: String) -> Bool { kind == "expo" || kind == "codepush" }
    static func reload() { _nitroPushTriggerReload() }
}
