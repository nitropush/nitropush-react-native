package com.nitropushrnexample

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.nitropush.sdk.NitroPushSdk

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "NitropushRNExample"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  /**
   * Confirms the running bundle is healthy on every resume. Idempotent —
   * the SDK dedups internally, so calling this on every onResume is safe.
   * Without this, the next launch would treat fresh installs as failed and
   * roll them back. We do it natively so JS never has to know.
   */
  override fun onResume() {
    super.onResume()
    NitroPushSdk.shared.notifyAppReady()
  }
}
