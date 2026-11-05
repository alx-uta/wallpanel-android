package xyz.wallpanel.pro.ui.views

import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView

interface WebClientCallback {
    fun complete()

    fun pageLoadComplete(url: String)

    var isConnected: Boolean

    fun isFinishing(): Boolean

    fun displayProgress(): Boolean

    fun startReloadDelay()

    fun stopReloadDelay()

    /**
     * Rebuild the GeckoView session after its content process died and reload the page
     */
    fun recreateGeckoSession()

    /**
     * The WebView's renderer is gone and [deadView] with it. It is taken out and destroyed,
     * and a new WebView loads the page in its place.
     */
    fun replaceDeadWebView(deadView: WebView)

    /**
     * Hands a URI the browser cannot show, an `intent:` or an app's own scheme such as
     * `tel:`, to the app that handles it. False when none could.
     */
    fun openExternally(url: String): Boolean

    /**
     * Asks Android for runtime permissions a page needs. [onResult] receives true only when
     * every one of them was granted.
     */
    fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit)

    /**
     * The page entered or left fullscreen, a video player's fullscreen button for instance.
     */
    fun onPageFullScreen(fullScreen: Boolean)

    /**
     * A new document started loading in GeckoView. It opens at the top, and Gecko does not
     * always report the scroll position of a page that never scrolled.
     */
    fun geckoPageStarted()

    /**
     * Lets the user pick files for a page's upload field. [onResult] receives null when
     * nothing was picked.
     */
    fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, onResult: (Array<Uri>?) -> Unit)

    /**
     * The WebView shows a page element fullscreen in [view], which is put over the page
     * until [hideCustomView]. [onHidden] tells the WebView the user left fullscreen.
     */
    fun showCustomView(view: View, onHidden: WebChromeClient.CustomViewCallback)

    fun hideCustomView()

    /**
     * The username and password from the settings for the server [host] asking for them, or
     * null when it is not the dashboard's own server or none are set.
     */
    fun storedLogin(host: String?): Pair<String, String>?

    /** As [storedLogin], for the whole address asking, whose scheme and port count too. */
    fun storedLoginForUrl(url: String?): Pair<String, String>?

}
