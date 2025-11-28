/*
 * Copyright (c) 2022 WallPanel
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

package xyz.wallpanel.app.ui.views

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import timber.log.Timber

/**
 * Wrapper for GeckoView that provides a similar interface to WebView
 * This allows runtime switching between WebView and GeckoView engines
 */
class GeckoViewWrapper(
    private val context: Context,
    private val geckoView: GeckoView
) {

    var geckoSession: GeckoSession? = null
        private set

    companion object {
        private var runtime: GeckoRuntime? = null

        /**
         * Initialize the GeckoRuntime (should be called once per app lifecycle)
         */
        fun initializeRuntime(context: Context) {
            if (runtime == null) {
                // Configure GeckoRuntime with optimal settings
                val builder = GeckoRuntimeSettings.Builder()
                
                // Enable JavaScript (equivalent to WebView's javaScriptEnabled)
                builder.javaScriptEnabled(true)
                
                // Enable web fonts
                builder.webFontsEnabled(true)
                
                // Enable remote debugging in debug builds
                builder.remoteDebuggingEnabled(android.os.Build.TYPE == "userdebug")
                
                // Configure console output for debugging
                builder.consoleOutput(true)
                
                // About:config settings for better performance
                builder.aboutConfigEnabled(true)
                
                runtime = GeckoRuntime.create(context.applicationContext, builder.build())
            }
        }

        /**
         * Get the shared GeckoRuntime instance
         */
        fun getRuntime(context: Context): GeckoRuntime {
            if (runtime == null) {
                initializeRuntime(context)
            }
            return runtime!!
        }

        /**
         * Shutdown the GeckoRuntime (call on app termination)
         */
        fun shutdownRuntime() {
            runtime?.shutdown()
            runtime = null
        }
    }

    init {
        initializeSession()
    }

    /**
     * Initialize the GeckoSession and attach it to this view
     */
    private fun initializeSession() {
        try {
            val rt = getRuntime(context)
            val session = GeckoSession()
            session.open(rt)
            geckoView.setSession(session)
            geckoSession = session
        } catch (e: Exception) {
            Timber.e(e, "Error initializing GeckoSession")
        }
    }

    /**
     * Load a URL in the GeckoView
     */
    fun loadUrl(url: String) {
        geckoSession?.loadUri(url)
    }

    /**
     * Reload the current page
     */
    fun reload() {
        geckoSession?.reload()
    }

    /**
     * Clear cache and data
     */
    fun clearCache(includeDiskFiles: Boolean = true) {
        // Clear session data using runtime storage controller
        runtime?.storageController?.clearData(
            org.mozilla.geckoview.StorageController.ClearFlags.ALL
        )
    }

    /**
     * Execute JavaScript in the current page
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null) {
        val session = geckoSession
        if (session != null) {
            // GeckoView doesn't have a direct evaluateJS method
            // We'll load it as a javascript: URI which executes it
            try {
                session.loadUri("javascript:$script")
                callback?.invoke(null)
            } catch (e: Exception) {
                Timber.e(e, "JavaScript evaluation error")
                callback?.invoke(null)
            }
        }
    }

    /**
     * Set navigation delegate
     */
    fun setNavigationDelegate(delegate: GeckoSession.NavigationDelegate) {
        geckoSession?.navigationDelegate = delegate
    }

    /**
     * Set progress delegate
     */
    fun setProgressDelegate(delegate: GeckoSession.ProgressDelegate) {
        geckoSession?.progressDelegate = delegate
    }

    /**
     * Set permission delegate
     */
    fun setPermissionDelegate(delegate: GeckoSession.PermissionDelegate) {
        geckoSession?.permissionDelegate = delegate
    }

    /**
     * Set prompt delegate
     */
    fun setPromptDelegate(delegate: GeckoSession.PromptDelegate) {
        geckoSession?.promptDelegate = delegate
    }

    /**
     * Set user agent
     */
    fun setUserAgent(userAgent: String) {
        geckoSession?.settings?.userAgentOverride = userAgent
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        geckoSession?.close()
        geckoView.releaseSession()
    }

    /**
     * Go back in navigation history
     */
    fun canGoBack(): Boolean {
        // GeckoView handles this through GeckoSession.goBack()
        return false // Will be properly implemented with navigation delegate
    }

    /**
     * Go back in navigation
     */
    fun goBack() {
        geckoSession?.goBack()
    }

    /**
     * Get current URL
     */
    fun getUrl(): String? {
        // URL tracking will be done through NavigationDelegate
        return null
    }
}
