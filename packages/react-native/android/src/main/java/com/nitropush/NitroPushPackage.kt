package com.nitropush

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.margelo.nitro.nitropush.NitroPushOnLoad

/**
 * React Native package class that boots the Nitro JNI bridge. The
 * host app's `MainApplication.kt` adds this to its packages list.
 *
 * NitroPushOnLoad lives in the Nitrogen-generated sources at
 * `nitrogen/generated/android/.../NitroPushOnLoad.java` — run
 * `yarn workspace @nitropush/react-native codegen` if the import
 * is unresolved.
 */
class NitroPushPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? = null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
    ReactModuleInfoProvider { emptyMap() }

  companion object {
    init {
      NitroPushOnLoad.initializeNative()
    }
  }
}
