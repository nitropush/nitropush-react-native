import UIKit
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider
import NitroPush

/// Native code only selects the active OTA bundle. Configuration, syncing and
/// the post-render health confirmation live in App.tsx so a broken JS bundle
/// can never be marked healthy merely because UIKit became active.

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  var reactNativeDelegate: ReactNativeDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    // Standard React Native bringup.
    let delegate = ReactNativeDelegate()
    let factory = RCTReactNativeFactory(delegate: delegate)
    delegate.dependencyProvider = RCTAppDependencyProvider()

    reactNativeDelegate = delegate
    reactNativeFactory = factory

    window = UIWindow(frame: UIScreen.main.bounds)

    factory.startReactNative(
      withModuleName: "NitropushRNExample",
      in: window,
      launchOptions: launchOptions
    )

    return true
  }
}

class ReactNativeDelegate: RCTDefaultReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? {
    self.bundleURL()
  }

  override func bundleURL() -> URL? {
#if DEBUG
    return RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
#else
    // Hand React the active OTA bundle, falling back to the binary-shipped
    // one. NitroPushSdk.shared activates any pending bundle on first access
    // (or rolls back if the previous install was unhealthy).
    if let nitropushBundleURL = NitroPushSdk.shared.activeBundleURL() {
      return nitropushBundleURL
    }
    return Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }
}
