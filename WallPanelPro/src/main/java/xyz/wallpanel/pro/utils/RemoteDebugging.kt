/*
 * Copyright (c) 2026 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.pro.utils

import android.webkit.WebView
import timber.log.Timber
import xyz.wallpanel.pro.BuildConfig
import xyz.wallpanel.pro.ui.views.GeckoViewWrapper

/**
 * Lets a computer connected over ADB inspect the dashboard: Chrome's chrome://inspect for the
 * WebView, Firefox's about:debugging for GeckoView. Debug builds always have it on.
 *
 * Both engines hold it for the whole process, so it covers the web screensaver as well, and
 * a WebView created later picks it up. It is only passed on when it changes. A WebView starts
 * with it off, so with the setting off a release build never loads the WebView for it.
 */
object RemoteDebugging {

    private var webViewEnabled = false

    /** Main thread only. */
    fun apply(setting: Boolean) {
        val enabled = isEnabled(setting)
        if (enabled != webViewEnabled) {
            try {
                WebView.setWebContentsDebuggingEnabled(enabled)
                webViewEnabled = enabled
                Timber.i("WebView remote debugging ${if (enabled) "on" else "off"}")
            } catch (e: Exception) {
                // A missing or updating WebView provider throws here
                Timber.w(e, "Could not change WebView remote debugging")
            }
        }
        GeckoViewWrapper.setRemoteDebugging(enabled)
    }

    fun isEnabled(setting: Boolean): Boolean = BuildConfig.DEBUG || setting
}
