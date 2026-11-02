package xyz.wallpanel.pro.utils

import android.Manifest
import android.content.res.Resources
import android.view.View
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.ui.views.WebClientCallback

class InternalWebChromeClient(val resources: Resources, val callback: WebClientCallback) :
    WebChromeClient() {

    var snackbar: Snackbar? = null

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        if (newProgress == 100) {
            snackbar?.dismiss()
            if (view.url != null) {

            } else {
                Toast.makeText(
                    view.context,
                    resources.getString(R.string.toast_empty_url),
                    Toast.LENGTH_SHORT
                ).show()
                callback.complete()
            }
            return
        }
        if (callback.displayProgress()) {
            val text =
                resources.getString(R.string.text_loading_percent, newProgress.toString(), view.url)
            if (snackbar == null) {
                snackbar = Snackbar.make(view, text, Snackbar.LENGTH_INDEFINITE)
            } else {
                snackbar?.setText(text)
            }
            snackbar?.show()
        }
    }

    /**
     * A page asking for the microphone or the camera, a voice assistant for instance. The
     * Android permission behind each is asked for first: granting the page without it hands
     * over a device that delivers nothing. Anything else the page asks for is refused.
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val wanted = request.resources.filter { it in ANDROID_PERMISSIONS }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        val permissions = wanted.map { ANDROID_PERMISSIONS.getValue(it) }.toTypedArray()
        callback.requestAndroidPermissions(permissions) { granted ->
            // The page may have withdrawn the request while Android's dialog was up
            try {
                if (granted) {
                    request.grant(wanted.toTypedArray())
                } else {
                    request.deny()
                }
            } catch (e: IllegalStateException) {
                Timber.w(e, "The page's permission request was already answered")
            }
        }
    }

    /**
     * The page waits until the alert is answered, and while it waits no script runs in it,
     * the eval and reload commands included. It is answered exactly once, however the
     * dialog goes away.
     */
    override fun onJsAlert(
        view: WebView,
        url: String,
        message: String,
        result: JsResult
    ): Boolean {
        if (callback.isFinishing()) {
            result.cancel()
            return true
        }
        AlertDialog.Builder(view.context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { result.confirm() }
            .show()
        return true
    }

    /**
     * A page element asking to go fullscreen, a video player's button for instance. Without
     * this the WebView reports fullscreen as unsupported and players grey the button out.
     */
    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (this.callback.isFinishing()) {
            callback.onCustomViewHidden()
            return
        }
        this.callback.showCustomView(view, callback)
    }

    override fun onHideCustomView() {
        callback.hideCustomView()
    }

    companion object {
        private val ANDROID_PERMISSIONS = mapOf(
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to Manifest.permission.RECORD_AUDIO,
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to Manifest.permission.CAMERA
        )
    }
}
