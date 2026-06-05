// Wraps RCTTriggerReloadCommandListeners (React Native 0.71+) as a plain C
// entry point so NitroPushSdk.swift can call it via @_silgen_name without
// taking a compile-time dependency on React headers in Swift.
#if __has_include(<React/RCTReloadCommand.h>)
#import <React/RCTReloadCommand.h>

void NitroPushTriggerReload(void) {
    RCTTriggerReloadCommandListeners(@"NitroPush install");
}
#else
void NitroPushTriggerReload(void) {}
#endif
