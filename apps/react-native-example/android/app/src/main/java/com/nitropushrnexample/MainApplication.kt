package com.nitropushrnexample

import android.app.Application
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.nitropush.sdk.NitroPushSdk
import com.nitropush.sdk.NlConfig
import com.nitropush.sdk.NlInstallMode

/**
 * Demonstrates **driving the NitroPush SDK from native code** instead of
 * from JS. `configure()` runs before the React Native bundle loads, the
 * full update cycle (check → download → install) runs on a worker thread
 * in parallel with React bringup, and `notifyAppReady()` fires on every
 * Activity resume (see [MainActivity]). JS doesn't call any SDK method —
 * App.tsx is purely a status display.
 *
 * Why native-side:
 *   • Updates can begin downloading before JS even loads. By the time
 *     React Native is ready to render, a fresh bundle may already be
 *     staged for the next launch.
 *   • Works during the launch screen without round-tripping through the
 *     Nitro bridge.
 *   • Recovers cleanly when the previous bundle was poison and JS never
 *     reaches a useEffect — the rollback safety net already ran inside
 *     [NitroPushSdk.install] before this code executes.
 */
class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          // add(MyReactNativePackage())
        },
      // Hand React the active OTA bundle (set by NitroPush) when one's
      // available; null in debug keeps Metro in charge; null in release
      // falls back to the binary-shipped bundle.
      jsBundleFilePath = if (BuildConfig.DEBUG) null else NitroPushSdk.shared.activeBundleFile(),
    )
  }

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "MainApplication.onCreate — entered")

    // 1. Create the SDK singleton + run the launch-time pointer sweep
    //    (rollback if the previous install was unhealthy).
    try {
      NitroPushSdk.install(this)
      Log.i(TAG, "NitroPushSdk.install — done")
    } catch (e: Throwable) {
      Log.e(TAG, "NitroPushSdk.install FAILED", e)
    }

    // 2. Configure *before* React Native loads. The launch-time sweep
    //    already mutated state; configure() wires the analytics emitter
    //    so it can replay any pending rollback event.
    try {
      NitroPushSdk.shared.configure(
        NlConfig(
          serverUrl      = NITROPUSH_SERVER_URL,
          deploymentKey  = NITROPUSH_DEPLOYMENT_KEY,
          storageBaseUrl = NITROPUSH_STORAGE_BASE_URL,
        )
      )
      NitroPushSdk.shared.setEnableLogs(true)
      Log.i(TAG, "NitroPushSdk.configure — done")
    } catch (e: Throwable) {
      Log.e(TAG, "NitroPushSdk.configure FAILED", e)
    }

    // 3. Kick off a background update fetch. `Thread { … }.start()` is
    //    the smallest dependency footprint — it works without pulling in
    //    kotlinx-coroutines. The SDK methods are blocking on Android, so
    //    they MUST run off the main thread.
    Log.i(TAG, "creating bootstrap thread…")
    Thread {
      Log.i(TAG, "bootstrap thread starting; serverUrl=$NITROPUSH_SERVER_URL deploymentKey=${NITROPUSH_DEPLOYMENT_KEY.take(20)}…")
      try {
        Log.i(TAG, "calling checkForUpdate()…")
        val remote = NitroPushSdk.shared.checkForUpdate()
        if (remote == null) {
          Log.i(TAG, "up to date")
          return@Thread
        }
        Log.i(TAG, "downloading ${remote.label} (${remote.packageSize.toLong()} bytes)")
        val local = NitroPushSdk.shared.downloadUpdate(remote)
        NitroPushSdk.shared.installUpdate(
          pkg = local,
          installMode = NlInstallMode.ON_NEXT_RESTART,
          minimumBackgroundDurationSeconds = 0.0,
        )
        Log.i(TAG, "staged ${local.label}, takes effect on next launch")
      } catch (e: Throwable) {
        Log.e(TAG, "background sync failed", e)
      }
    }.apply {
      name = "nitropush-bootstrap-sync"
      isDaemon = true
      start()
    }
    Log.i(TAG, "bootstrap thread .start() called; loading React Native…")

    // 4. Standard React Native bringup — concurrent with the update thread.
    loadReactNative(this)
  }

  companion object {
    private const val TAG = "NitroPush"

    // Replace with your real values, or wire via Gradle product flavors:
    //   buildConfigField("String", "NITROPUSH_DEPLOYMENT_KEY", "\"PROD-…\"")
    private const val NITROPUSH_SERVER_URL      = "http://192.168.0.141:3003"
    private const val NITROPUSH_DEPLOYMENT_KEY  = "nl_test_cun0SQxbqGvsjhgKaAXLjMHWmBIO31CUEaJFWG75h7I"
    private const val NITROPUSH_STORAGE_BASE_URL = "http://192.168.0.141:9000/nitrolift-bundles"
  }
}
