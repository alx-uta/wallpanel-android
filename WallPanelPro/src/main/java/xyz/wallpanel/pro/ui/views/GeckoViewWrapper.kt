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

package xyz.wallpanel.pro.ui.views

import android.content.Context
import androidx.annotation.UiThread
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

    // Delegates are retained so they can be re-applied to a replacement session, which
    // happens when the content process dies (crash, or an OS kill for resource usage).
    private var navigationDelegate: GeckoSession.NavigationDelegate? = null
    private var progressDelegate: GeckoSession.ProgressDelegate? = null
    private var permissionDelegate: GeckoSession.PermissionDelegate? = null
    private var promptDelegate: GeckoSession.PromptDelegate? = null
    private var contentDelegate: GeckoSession.ContentDelegate? = null

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
            geckoSession = openSession()
        } catch (e: Exception) {
            Timber.e(e, "Error initializing GeckoSession")
        }
    }

    /**
     * Replace the current session with a fresh one and attach it to the view.
     *
     * The content process (:tabXX) can be terminated independently of the app, either by a
     * Gecko crash or by Android killing it for excessive background CPU while the screen is off.
     * The session left behind is dead and renders nothing, so the only recovery is to
     * build a new one, re-apply the delegates and re-attach it.
     *
     * @return true when a new session was successfully attached.
     */
    @UiThread
    fun recreateSession(): Boolean {
        return try {
            closeSessionQuietly()
            try {
                geckoView.releaseSession()
            } catch (e: Exception) {
                Timber.e(e, "Error releasing dead GeckoSession from the view")
            }
            geckoSession = openSession()
            Timber.i("GeckoSession recreated after content process death")
            true
        } catch (e: Exception) {
            Timber.e(e, "Error recreating GeckoSession")
            false
        }
    }

    /**
     * Create a session, apply the retained delegates, open it against the shared runtime
     * and attach it to the view.
     */
    private fun openSession(): GeckoSession {
        val runtime = getRuntime(context)
        val session = GeckoSession()
        applyDelegates(session)
        session.open(runtime)
        geckoView.setSession(session)
        return session
    }

    /**
     * Apply every delegate configured so far to the given session
     */
    private fun applyDelegates(session: GeckoSession) {
        navigationDelegate?.let { session.navigationDelegate = it }
        progressDelegate?.let { session.progressDelegate = it }
        permissionDelegate?.let { session.permissionDelegate = it }
        promptDelegate?.let { session.promptDelegate = it }
        contentDelegate?.let { session.contentDelegate = it }
    }

    /**
     * Close the current session, tolerating one that is already dead
     */
    private fun closeSessionQuietly() {
        val session = geckoSession ?: return
        try {
            if (session.isOpen) {
                session.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error closing GeckoSession")
        }
        geckoSession = null
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
     * Mark the session as visible or hidden.
     *
     * An inactive session stops compositing and Gecko throttles the page's timers and
     * animations, which is what keeps a live dashboard from burning CPU while the screen is off.
     * Without this Gecko treats the session as permanently visible and keeps running
     * the page at full foreground rate, which is what gets the content process killed for
     * excessive background CPU.
     */
    fun setActive(active: Boolean) {
        val session = geckoSession ?: return
        try {
            if (session.isOpen) {
                session.setActive(active)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error setting the GeckoSession active state to $active")
        }
    }

    /**
     * Set navigation delegate
     */
    fun setNavigationDelegate(delegate: GeckoSession.NavigationDelegate) {
        navigationDelegate = delegate
        geckoSession?.navigationDelegate = delegate
    }

    /**
     * Set progress delegate
     */
    fun setProgressDelegate(delegate: GeckoSession.ProgressDelegate) {
        progressDelegate = delegate
        geckoSession?.progressDelegate = delegate
    }

    /**
     * Set permission delegate
     */
    fun setPermissionDelegate(delegate: GeckoSession.PermissionDelegate) {
        permissionDelegate = delegate
        geckoSession?.permissionDelegate = delegate
    }

    /**
     * Set prompt delegate
     */
    fun setPromptDelegate(delegate: GeckoSession.PromptDelegate) {
        promptDelegate = delegate
        geckoSession?.promptDelegate = delegate
    }

    /**
     * Set content delegate, which reports content process crashes and kills
     */
    fun setContentDelegate(delegate: GeckoSession.ContentDelegate) {
        contentDelegate = delegate
        geckoSession?.contentDelegate = delegate
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
        closeSessionQuietly()
        try {
            geckoView.releaseSession()
        } catch (e: Exception) {
            Timber.e(e, "Error releasing GeckoSession from the view")
        }
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
