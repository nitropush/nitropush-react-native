// Maps the C symbol `NitroPushTriggerReload` (from NitroPushReloadBridge.m)
// directly into Swift without requiring an Objective-C bridging header.
// Called only from reloadBridge() inside NitroPushSdk.
@_silgen_name("NitroPushTriggerReload")
func _nitroPushTriggerReload() -> Void
