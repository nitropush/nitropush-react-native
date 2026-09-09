package com.nitropush.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.facebook.react.ReactApplication

internal object NPHostRuntime {
    fun accepts(kind: String) = kind == "expo" || kind == "codepush"
    fun reload(context: Context) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val host = (context as? ReactApplication)?.reactNativeHost ?: return@post
                if (host.hasInstance()) host.reactInstanceManager.recreateReactContextInBackground()
            }
        }
    }
}
