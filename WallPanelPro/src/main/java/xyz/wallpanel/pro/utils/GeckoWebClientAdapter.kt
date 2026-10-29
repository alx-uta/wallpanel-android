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

package xyz.wallpanel.pro.utils

import androidx.annotation.UiThread
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.WebRequestError
import timber.log.Timber
import xyz.wallpanel.pro.ui.views.WebClientCallback

/**
 * Maps GeckoView's NavigationDelegate and ContentDelegate onto the WebClientCallback the
 * WebView client also reports to.
 */
class GeckoWebClientAdapter(
    private val callback: WebClientCallback
) : NavigationDelegate, GeckoSession.ContentDelegate {

    /** Whether the page has history to go back to, for the back key. */
    var canGoBack = false
        private set

    var canGoForward = false
        private set

    /**
     * The page's address as it changes, including the in-page navigation Home Assistant does
     * between views. Load requests are not used for this: they include redirects, loads that
     * go on to fail, and the `javascript:` URIs the eval command runs through.
     */
    @UiThread
    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean
    ) {
        if (url.isNullOrEmpty() || url == BLANK_PAGE) {
            return
        }
        callback.pageLoadComplete(url)
    }

    @UiThread
    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        this.canGoBack = canGoBack
    }

    /** A new session starts without history, and may not say so before the back key is pressed. */
    @UiThread
    fun onSessionReplaced() {
        canGoBack = false
        canGoForward = false
    }

    @UiThread
    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        this.canGoForward = canGoForward
    }

    /**
     * Web pages load here. Anything else, an `intent:` or an app's own scheme such as `tel:`,
     * is handed to Android, which is what a tap on such a link means. Gecko would otherwise
     * try to load it itself and show nothing. Only a load the user started is handed over,
     * so a page cannot open other apps on its own.
     */
    @UiThread
    override fun onLoadRequest(
        session: GeckoSession,
        request: LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        if (!isExternalUri(request.uri)) {
            return GeckoResult.allow()
        }
        if (request.hasUserGesture) {
            callback.openExternally(request.uri)
        } else {
            Timber.w("Not opening ${request.uri}, the page asked for it without a tap")
        }
        return GeckoResult.deny()
    }

    @UiThread
    override fun onSubframeLoadRequest(
        session: GeckoSession,
        request: LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        return GeckoResult.allow()
    }

    /**
     * A kiosk has a single page, so a link meant for a new window opens in this one.
     */
    @UiThread
    override fun onNewSession(
        session: GeckoSession,
        uri: String
    ): GeckoResult<GeckoSession>? {
        if (isExternalUri(uri)) {
            callback.openExternally(uri)
        } else {
            session.loadUri(uri)
        }
        return null
    }

    /**
     * A page that could not be reached is tried again, the same way the WebView does, so a
     * dashboard whose server comes up after the tablet (after a power cut, say) loads on its
     * own. Errors a retry cannot fix, such as a bad certificate, are left on screen.
     */
    @UiThread
    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError
    ): GeckoResult<String>? {
        Timber.e("GeckoView could not load $uri: category ${error.category}, code ${error.code}")
        if (callback.isFinishing() || !isRetryable(error)) {
            return null
        }
        callback.isConnected = false
        callback.startReloadDelay()
        return GeckoResult.fromValue(ERROR_PAGE)
    }

    @UiThread
    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        callback.onPageFullScreen(fullScreen)
    }

    /**
     * The content process crashed inside Gecko.
     * The session is dead and shows nothing until it is replaced.
     */
    @UiThread
    override fun onCrash(session: GeckoSession) {
        Timber.e("GeckoView content process crashed, recreating the session")
        handleContentProcessDeath()
    }

    /**
     * The content process was killed by Android, typically for excessive background CPU use
     * while the screen is off.
     * Same recovery as a crash.
     */
    @UiThread
    override fun onKill(session: GeckoSession) {
        Timber.e("GeckoView content process was killed by the system, recreating the session")
        handleContentProcessDeath()
    }

    /**
     * Recover from a dead content process. Unlike a network error this is not worth backing
     * off for: nothing will come back on its own, so rebuild the session immediately.
     */
    private fun handleContentProcessDeath() {
        if (callback.isFinishing()) {
            return
        }
        callback.isConnected = false
        callback.recreateGeckoSession()
    }

    private fun isRetryable(error: WebRequestError): Boolean {
        return error.category == WebRequestError.ERROR_CATEGORY_NETWORK ||
                error.category == WebRequestError.ERROR_CATEGORY_PROXY ||
                error.code == WebRequestError.ERROR_UNKNOWN_HOST
    }

    companion object {
        private const val BLANK_PAGE = "about:blank"

        // The WebView's error page, read from the APK's assets.
        const val ERROR_PAGE = "resource://android/assets/error_page.html"

        private val BROWSER_SCHEMES = setOf(
            "http", "https", "about", "data", "blob", "javascript", "file", "resource",
            "moz-extension", "view-source"
        )

        /** True for a URI the browser cannot show and another app has to open. */
        fun isExternalUri(uri: String): Boolean {
            val scheme = uri.substringBefore(':', "").lowercase()
            return scheme.isNotEmpty() && scheme !in BROWSER_SCHEMES
        }
    }
}
