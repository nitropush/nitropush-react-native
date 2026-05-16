import UIKit
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider
import NitroPush

/**
 * Demonstrates **driving the NitroPush SDK from native code** instead of from
 * JS. Configure runs before the React Native bundle loads, the full update
 * cycle (check → download → install) runs in a detached Task while React
 * boots in parallel, and `notifyAppReady()` fires on every active-foreground
 * transition. JS doesn't call any SDK method — App.tsx is purely a status
 * display.
 *
 * Why native-side:
 *   • Updates can begin downloading before JS even loads. By the time React
 *     Native is ready to render, a fresh bundle may already be staged.
 *   • Works during the launch animation / splash without bridge round-trips.
 *   • Recovers cleanly when the previous bundle was poison and the JS thread
 *     never reaches a useEffect — the rollback safety net runs from native
 *     before this code path executes (in NitroPushSdk's init).
 */

private enum NitroPushConfig {
  /// Replace these with your real values, or read them from Info.plist
  /// (`Bundle.main.object(forInfoDictionaryKey:)`) for per-config injection.
  static let serverUrl      = "http://192.168.0.141:3003"
  static let deploymentKey  = "nl_test_gLaFtFCoG6M6v3WrTCT8yConLA0onKfb1HxU7k8EoxI"
  // Port 9000 is the MinIO S3 API. Port 9001 is the MinIO Console (web UI)
  // and returns the console SPA HTML for every request — easy mistake.
  static let storageBaseUrl = "http://192.168.0.141:9000/nitrolift-bundles"
}

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  var reactNativeDelegate: ReactNativeDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    // 1. Configure the SDK *before* the JS bundle loads. The launch-time
    //    rollback sweep already ran (it's wired in `NitroPushSdk.init`),
    //    but `configure()` wires the analytics emitter and replays any
    //    pending rollback event for the previous failed install.
    do {
      try NitroPushSdk.shared.configure(
        NPConfig(
          serverUrl:      NitroPushConfig.serverUrl,
          deploymentKey:  NitroPushConfig.deploymentKey,
          storageBaseUrl: NitroPushConfig.storageBaseUrl
        )
      )
    } catch {
      NSLog("[NitroPush] configure failed: %@", "\(error)")
    }
    NitroPushSdk.shared.setEnableLogs(true)

    // 2. Kick off a detached check+download+install task. `Task.detached`
    //    so it doesn't capture the surrounding actor and can run in
    //    parallel with React Native bringup. We use ON_NEXT_RESTART —
    //    the user keeps using the current bundle this session, the new
    //    one takes effect on the next cold start.
    Task.detached(priority: .background) {
      do {
        guard let remote = try await NitroPushSdk.shared.checkForUpdate() else {
          NSLog("[NitroPush] up to date")
          return
        }
        NSLog("[NitroPush] downloading %@ (%.0f bytes)", remote.label, remote.packageSize)
        let local = try await NitroPushSdk.shared.downloadUpdate(remote)
        try await NitroPushSdk.shared.installUpdate(
          pkg: local,
          installMode: .onNextRestart,
          minimumBackgroundDuration: 0
        )
        NSLog("[NitroPush] staged %@, takes effect on next launch", local.label)
      } catch {
        NSLog("[NitroPush] background sync failed: %@", "\(error)")
      }
    }

    // 3. Standard React Native bringup — concurrent with the update task.
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

  /// Called every time the app becomes active — initial launch + every
  /// foreground transition. Confirms the running bundle is healthy so the
  /// next launch's pointer sweep won't roll it back. Idempotent (the SDK
  /// dedups internally), so calling it on every become-active is safe.
  func applicationDidBecomeActive(_ application: UIApplication) {
    NitroPushSdk.shared.notifyAppReady()
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
