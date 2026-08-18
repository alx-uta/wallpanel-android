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

import android.content.res.Resources
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import com.google.android.material.snackbar.Snackbar
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity
import xyz.wallpanel.pro.ui.views.WebClientCallback

/**
 * Adapter class that maps GeckoView's ProgressDelegate and PermissionDelegate
 * to WebChromeClient-like behavior
 */
class GeckoWebChromeClientAdapter(
    private val resources: Resources,
    private val callback: WebClientCallback
) : GeckoSession.ProgressDelegate, GeckoSession.PermissionDelegate, GeckoSession.PromptDelegate {

    var snackbar: Snackbar? = null
    private var geckoView: android.view.View? = null

    fun setGeckoView(view: android.view.View) {
        geckoView = view
    }

    // ProgressDelegate implementation
    @UiThread
    override fun onPageStart(session: GeckoSession, url: String) {
    }

    @UiThread
    override fun onPageStop(session: GeckoSession, success: Boolean) {
        snackbar?.dismiss()
        callback.complete()
    }

    @UiThread
    override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (progress == 100) {
            snackbar?.dismiss()
            callback.complete()
            return
        }

        if (callback.displayProgress() && geckoView != null) {
            val text = resources.getString(R.string.text_loading_percent, progress.toString(), "")
            if (snackbar == null) {
                snackbar = Snackbar.make(geckoView!!, text, Snackbar.LENGTH_INDEFINITE)
            } else {
                snackbar?.setText(text)
            }
            snackbar?.show()
        }
    }

    @UiThread
    override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
    ) {
    }

    @UiThread
    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
        // Session state changed
    }

    // PermissionDelegate implementation
    @UiThread
    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int>? {
        
        when (perm.permission) {
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            else -> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
    }

    @UiThread
    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback
    ) {
        // Handle camera/microphone permissions
        if (video != null && video.isNotEmpty()) {
            callback.grant(video.first(), audio?.firstOrNull())
        } else if (audio != null && audio.isNotEmpty()) {
            callback.grant(null, audio.first())
        } else {
            callback.reject()
        }
    }

    @UiThread
    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        // Grant permissions (they should be requested at app level)
        callback.grant()
    }

    // PromptDelegate implementation for alerts
    @UiThread
    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        // Show alert dialog if needed
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ButtonPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onTextPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.TextPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AuthPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ChoicePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onColorPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ColorPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.DateTimePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.PopupPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onSharePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.SharePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        prompt.dismiss()
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        // Allow repost - dismiss the prompt with default action
        prompt.dismiss()
        return null
    }

    @UiThread
    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt  
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        // Allow navigation - dismiss the prompt with default action
        prompt.dismiss()
        return null
    }
}
