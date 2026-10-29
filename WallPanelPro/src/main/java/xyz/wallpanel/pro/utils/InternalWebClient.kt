package xyz.wallpanel.pro.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.ui.views.WebClientCallback
import java.util.*

open class InternalWebClient(val resources: Resources, private val callback: WebClientCallback, val configuration: Configuration) :
    WebViewClient() {

    private var pageLoaded = false
    private var currentUrl = ""
    // The page loading now hit an error, so finishing it is not a sign the server is back
    private var loadFailed = false

    // The certificate warning on screen, and every load waiting on its answer
    private var sslDialog: AlertDialog? = null
    private val pendingSslErrors = mutableListOf<SslErrorHandler>()
    // The login prompt on screen, and the requests waiting on it. They are dropped when
    // another page starts.
    private var loginDialog: AlertDialog? = null
    private val pendingLogins = mutableListOf<PendingLogin>()

    /**
     * Anything the WebView cannot show, an `intent:` or an app's own scheme such as `tel:`,
     * is handed to Android, which is what a tap on such a link means. Only a load the user
     * started is handed over, so a page cannot open other apps on its own.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (GeckoWebClientAdapter.isExternalUri(url)) {
            if (request.hasGesture()) {
                callback.openExternally(url)
            } else {
                Timber.w("Not opening $url, the page asked for it without a tap")
            }
            return true
        }
        if (!request.isForMainFrame) {
            return false
        }
        return loadInPlace(view, url)
    }

    /** Below Android 7 the WebView does not say whether the user tapped. */
    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (GeckoWebClientAdapter.isExternalUri(url)) {
            callback.openExternally(url)
            return true
        }
        return loadInPlace(view, url)
    }

    private fun loadInPlace(view: WebView, url: String): Boolean {
        if (currentUrl.equals(url, ignoreCase = true)) {
            return false;
        }
        else if (!currentUrl.equals(url, ignoreCase = true)){
            currentUrl = url;
            view.loadUrl(currentUrl);
        }
        return true;
    }

    open fun isCurrentUrl(url: String): Boolean {
        return url.lowercase(Locale.getDefault()).contains(currentUrl.lowercase())
    }

    override fun onPageStarted(
        webView: WebView?, url: String,
        bitmap: Bitmap?
    ) {
        if (isCurrentUrl(url)) {
            pageLoaded = false
        }
        if (!isPlaceholderPage(url)) {
            loadFailed = false
        }
        cancelLogins()
    }

    /**
     * A page that could not be reached is tried again later. A certificate that was refused
     * is not: trying again would only ask the same question every 30 seconds.
     */
    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        Timber.e("WebView could not load $failingUrl: $errorCode $description")
        loadFailed = true
        if (!callback.isFinishing()) {
            view.loadUrl("about:blank")
            view.loadUrl(ERROR_PAGE)
            if (errorCode != ERROR_FAILED_SSL_HANDSHAKE) {
                callback.isConnected = false
                callback.startReloadDelay()
            }
        }
    }

    /**
     * A certificate the device does not trust is only accepted when the user says so, or
     * when "Ignore SSL errors" is on. A page raises one error per resource, so the ones that
     * arrive while the warning is up share its answer.
     */
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        if (configuration.ignoreSSLErrors) {
            handler.proceed()
            return
        }
        Timber.w("Certificate error ${error.primaryError} for ${error.url}")
        if (callback.isFinishing()) {
            handler.cancel()
            return
        }
        pendingSslErrors += handler
        if (sslDialog != null) {
            return
        }
        val reason = when (error.primaryError) {
            SslError.SSL_UNTRUSTED -> R.string.dialog_message_ssl_untrusted
            SslError.SSL_EXPIRED -> R.string.dialog_message_ssl_expired
            SslError.SSL_IDMISMATCH -> R.string.dialog_message_ssl_mismatch
            SslError.SSL_NOTYETVALID -> R.string.dialog_message_ssl_not_yet_valid
            else -> R.string.dialog_message_ssl_generic
        }
        val message = resources.getString(reason) + "\n\n" +
                resources.getString(R.string.dialog_message_ssl_continue)
        val dialog = AlertDialog.Builder(view.context)
            .setTitle(R.string.dialog_title_ssl_error)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // The dialog goes away a moment after Continue is tapped, and a warning for another
        // host may be up by then, so each dialog only answers while it is the one on screen
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, resources.getString(R.string.button_continue)) { _, _ ->
            answerSslErrors(dialog, true)
        }
        // Back, a tap outside, or Cancel
        dialog.setOnDismissListener { answerSslErrors(dialog, false) }
        sslDialog = dialog
        dialog.show()
    }

    private fun answerSslErrors(dialog: AlertDialog, proceed: Boolean) {
        if (sslDialog !== dialog) {
            return
        }
        sslDialog = null
        val handlers = pendingSslErrors.toList()
        pendingSslErrors.clear()
        handlers.forEach { if (proceed) it.proceed() else it.cancel() }
    }

    /**
     * A page behind HTTP authentication, basic auth for instance. Without an answer the
     * WebView cancels and shows the server's 401 page. The dashboard's own server gets the
     * username and password from the settings, until it turns them down, and then the user
     * is asked. A page asks once for each resource behind the login, so the requests for the
     * same server and realm wait on the one prompt and share its answer.
     */
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
        val stored = callback.storedLogin(host)
        if (stored != null && handler.useHttpAuthUsernamePassword()) {
            handler.proceed(stored.first, stored.second)
            return
        }
        if (callback.isFinishing()) {
            handler.cancel()
            return
        }
        pendingLogins += PendingLogin(handler, host.orEmpty(), realm.orEmpty())
        if (loginDialog == null) {
            showNextLogin(view, stored?.first)
        }
    }

    private class PendingLogin(val handler: HttpAuthHandler, val host: String, val realm: String)

    private fun showNextLogin(view: WebView, username: String?) {
        val next = pendingLogins.firstOrNull() ?: return
        val fields = LoginFields(view.context, username ?: callback.storedLogin(next.host)?.first, passwordOnly = false)
        var answered = false
        val dialog = AlertDialog.Builder(view.context)
            .setTitle(resources.getString(R.string.dialog_title_login, next.host))
            .setMessage(next.realm)
            .setView(fields.view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                answered = true
                answerLogins(next) { it.proceed(fields.username, fields.password) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Back, a tap outside, Cancel, or another page starting
        dialog.setOnDismissListener {
            if (loginDialog !== dialog) {
                return@setOnDismissListener
            }
            loginDialog = null
            if (!answered) {
                answerLogins(next) { it.cancel() }
            }
            if (!callback.isFinishing()) {
                showNextLogin(view, null)
            }
        }
        loginDialog = dialog
        try {
            dialog.show()
        } catch (e: Exception) {
            Timber.e(e, "Unable to show the login prompt")
            loginDialog = null
            cancelLogins()
        }
    }

    /** Answers every waiting request for the same server and realm as [first]. */
    private fun answerLogins(first: PendingLogin, answer: (HttpAuthHandler) -> Unit) {
        val same = pendingLogins.filter { it.host == first.host && it.realm == first.realm }
        pendingLogins.removeAll(same)
        same.forEach { answer(it.handler) }
    }

    /** The page they belong to has gone, or is going. */
    private fun cancelLogins() {
        val waiting = pendingLogins.toList()
        pendingLogins.clear()
        val dialog = loginDialog
        loginDialog = null
        dialog?.dismiss()
        waiting.forEach { it.handler.cancel() }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        callback.pageLoadComplete(view.url.toString())
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    /**
     * A page of the user's that loads without an error shows the server can be reached
     * again, so it ends any retry still waiting, whatever page failed before it.
     */
    override fun onPageFinished(view: WebView, url: String) {
        if (!loadFailed && !isPlaceholderPage(url)) {
            callback.isConnected = true
        }
        if (callback.isConnected) {
            callback.stopReloadDelay()
        }
        if (isCurrentUrl(url)) {
            pageLoaded = true
        }
    }

    /**
     * The renderer crashed or Android killed it for memory. The WebView is dead and shows
     * nothing until it is replaced, and returning false would take the application with it.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Timber.e(
            "WebView renderer %s, replacing the WebView",
            if (detail.didCrash()) "crashed" else "was killed by the system"
        )
        sslDialog?.dismiss()
        cancelLogins()
        callback.replaceDeadWebView(view)
        return true
    }

    // The pages the client loads itself while a page fails, which say nothing about the server
    private fun isPlaceholderPage(url: String): Boolean {
        return url == ERROR_PAGE || url == "about:blank"
    }

    companion object {
        const val ERROR_PAGE = "file:///android_asset/error_page.html"
    }
}
