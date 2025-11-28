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

package xyz.wallpanel.app.utils

import android.content.res.Resources
import androidx.annotation.UiThread
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.AllowOrDeny
import timber.log.Timber
import xyz.wallpanel.app.R
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.ui.views.WebClientCallback

/**
 * Adapter class that maps GeckoView's NavigationDelegate to WebViewClient-like behavior
 * This allows GeckoView to work with the existing WebClientCallback interface
 */
class GeckoWebClientAdapter(
    private val resources: Resources,
    private val callback: WebClientCallback,
    private val configuration: Configuration
) : NavigationDelegate {

    private var currentUrl = ""
    private var pageLoaded = false
    private var geckoSession: GeckoSession? = null
    
    /**
     * Set the GeckoSession for JavaScript execution
     */
    fun setGeckoSession(session: GeckoSession) {
        this.geckoSession = session
    }

    @UiThread
    // Note: onLocationChange signature may have changed in GeckoView 134.+
    // Keeping as regular method for now - will be called if signature matches
    fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
    ) {
        url?.let {
            currentUrl = it
            // Notify page load complete
            callback.pageLoadComplete(it)
        }
    }

    @UiThread
    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        // Handle back navigation state
    }

    @UiThread
    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        // Handle forward navigation state
    }

    @UiThread
    override fun onLoadRequest(
        session: GeckoSession,
        request: LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        // Track URL changes
        callback.pageLoadComplete(request.uri)
        
        // Returning null allows the load
        return GeckoResult.allow()
    }

    @UiThread
    override fun onSubframeLoadRequest(
        session: GeckoSession,
        request: LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        // Returning null allows the load
        return GeckoResult.allow()
    }

    @UiThread
    override fun onNewSession(
        session: GeckoSession,
        uri: String
    ): GeckoResult<GeckoSession>? {
        // Return allow to open in same session
        return GeckoResult.fromValue(null)
    }

    fun isCurrentUrl(url: String): Boolean {
        return url.lowercase().contains(currentUrl.lowercase())
    }

    /**
     * Page loading started callback
     */
    fun onPageStarted(url: String) {
        if (isCurrentUrl(url)) {
            pageLoaded = false
        }
    }

    /**
     * Page loading finished callback
     */
    fun onPageFinished(url: String) {
        if (callback.isConnected) {
            callback.stopReloadDelay()
        }
        if (isCurrentUrl(url)) {
            pageLoaded = true
        }
        
        // Apply dark mode via JavaScript meta tag injection
        if (configuration.useDarkTheme) {
            injectDarkModeMetaTag()
        }
        
        callback.complete()
    }
    
    /**
     * Inject dark mode meta tag via JavaScript
     * This works on any GeckoView version and leverages Firefox's native dark mode support
     */
    private fun injectDarkModeMetaTag() {
        val darkModeScript = """
            (function() {
                // Set color-scheme meta tag
                var meta = document.querySelector('meta[name="color-scheme"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'color-scheme';
                    document.head.appendChild(meta);
                }
                meta.content = 'dark';
                
                // Also set it on root element for CSS
                document.documentElement.style.colorScheme = 'dark';
            })()
        """.trimIndent()
        
        geckoSession?.loadUri("javascript:$darkModeScript")
    }
    
    private fun disableWebNFCAPI() {
        val disableNFCScript = """
            (function() {
                if ('NDEFReader' in window) {
                    delete window.NDEFReader;
                    console.log('[WallPanel] Disabled Web NFC API on page load');
                }
            })()
        """.trimIndent()
        
        geckoSession?.loadUri("javascript:$disableNFCScript")
    }

    /**
     * Handle navigation errors
     */
    fun onReceivedError(errorCode: Int, description: String?, failingUrl: String?) {
        Timber.e("GeckoView navigation error: $description at $failingUrl")
        if (!callback.isFinishing()) {
            callback.isConnected = false
            callback.startReloadDelay()
        }
    }
}
