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
import android.net.Uri
import androidx.annotation.UiThread
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import timber.log.Timber
import xyz.wallpanel.pro.GeckoCrashHandlerService

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
    private var scrollDelegate: GeckoSession.ScrollDelegate? = null

    companion object {
        private var runtime: GeckoRuntime? = null

        /**
         * Initialize the GeckoRuntime (should be called once per app lifecycle)
         */
        fun initializeRuntime(context: Context, zoomLevel: Float, darkTheme: Boolean) {
            if (runtime == null) {
                // Configure GeckoRuntime with optimal settings
                val builder = GeckoRuntimeSettings.Builder()

                // The browser zoom setting. Gecko fixes the display density when the runtime
                // starts, so a change applies the next time the application starts.
                if (zoomLevel > 0f && zoomLevel != 1.0f) {
                    builder.displayDensityOverride(context.resources.displayMetrics.density * zoomLevel)
                }

                builder.preferredColorScheme(colorScheme(darkTheme))
                
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
                
                // Switches on Gecko's native crash reporter, so a crashed content process
                // is not reported to Android as an application crash, see the service
                builder.crashHandler(GeckoCrashHandlerService::class.java)

                runtime = GeckoRuntime.create(context.applicationContext, builder.build())
            }
        }

        /**
         * Get the shared GeckoRuntime instance
         */
        private fun getRuntime(): GeckoRuntime {
            return runtime ?: throw IllegalStateException("initializeRuntime() has not been called")
        }

        /**
         * Follows the dark theme setting rather than the system theme, as the WebView does.
         * Without this, a device in system dark mode shows a dark dashboard with the setting off.
         */
        fun setDarkTheme(darkTheme: Boolean) {
            runtime?.settings?.preferredColorScheme = colorScheme(darkTheme)
        }

        /**
         * With third-party cookies on, a page embedded in the dashboard shares its cookies
         * with the same site opened on its own, as it does in the WebView. Off, it keeps
         * none at all.
         */
        fun setThirdPartyCookies(allow: Boolean) {
            runtime?.settings?.contentBlocking?.setCookieBehavior(
                if (allow) ContentBlocking.CookieBehavior.ACCEPT_ALL
                else ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            )
        }

        private fun colorScheme(darkTheme: Boolean): Int {
            return if (darkTheme) {
                GeckoRuntimeSettings.COLOR_SCHEME_DARK
            } else {
                GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            }
        }

        /**
         * Clears the network and image caches, so the next load fetches everything again.
         * Cookies and site storage stay: Home Assistant keeps its login in local storage,
         * and clearing that would put the kiosk on the login page.
         *
         * @return false when GeckoView has not been started in this process.
         */
        fun clearRuntimeCaches(): Boolean {
            val geckoRuntime = runtime ?: return false
            geckoRuntime.storageController.clearData(
                org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES
            )
            return true
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
            Timber.i("GeckoSession recreated")
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
        val runtime = getRuntime()
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
        scrollDelegate?.let { session.scrollDelegate = it }
    }

    /**
     * Close the current session, tolerating one that is already dead
     */
    private fun closeSessionQuietly() {
        val session = geckoSession ?: return
        // Gecko can keep a closed session reachable from native code (its accessibility
        // provider does), and the delegates lead from the session back to the activity.
        // Detached, the activity can be collected even when the session cannot.
        try {
            session.navigationDelegate = null
            session.progressDelegate = null
            session.permissionDelegate = null
            session.promptDelegate = null
            session.contentDelegate = null
            session.scrollDelegate = null
        } catch (e: Exception) {
            Timber.e(e, "Error detaching the GeckoSession delegates")
        }
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
     * Execute JavaScript in the current page
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null) {
        val session = geckoSession
        if (session != null) {
            // GeckoView doesn't have a direct evaluateJS method
            // We'll load it as a javascript: URI which executes it. The address is
            // percent-decoded and stripped of line breaks before it runs, so the script goes
            // in percent-encoded. A page whose script ends on a string is replaced by that
            // string, so a last line ending on nothing follows it.
            try {
                session.loadUri(javascriptUri(script))
                callback?.invoke(null)
            } catch (e: Exception) {
                Timber.e(e, "JavaScript evaluation error")
                callback?.invoke(null)
            }
        }
    }

    private fun javascriptUri(script: String): String =
        "javascript:" + Uri.encode(script) + "%0Avoid%200"

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
     * Set scroll delegate, which reports how far the page is scrolled
     */
    fun setScrollDelegate(delegate: GeckoSession.ScrollDelegate) {
        scrollDelegate = delegate
        geckoSession?.scrollDelegate = delegate
    }

    /**
     * Set user agent
     */
    fun setUserAgent(userAgent: String) {
        geckoSession?.settings?.userAgentOverride = userAgent
    }

    /**
     * Tear down the content process without opening a replacement. Unlike setActive(false),
     * which merely throttles the page, this leaves no process behind to burn background CPU
     * or be killed by Android. Call recreateSession() to bring the session back.
     */
    fun suspend() {
        closeSessionQuietly()
        try {
            geckoView.releaseSession()
        } catch (e: Exception) {
            Timber.e(e, "Error releasing GeckoSession from the view")
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        suspend()
    }
}
