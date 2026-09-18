package com.nitropushrnexample

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.nitropush.sdk.NitroPushSdk

/**
 * Native code installs the singleton and selects the active OTA bundle.
 * App.tsx owns configure/sync and confirms health only after React renders.
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
    NitroPushSdk.install(this)
    loadReactNative(this)
  }
}
