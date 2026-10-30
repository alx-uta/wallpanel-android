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

import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import timber.log.Timber
import xyz.wallpanel.pro.persistence.Configuration

/**
 * A click sound and a short vibration for a tap on the dashboard, which the browser
 * engines do not give, see [Configuration.browserTapSound] and
 * [Configuration.browserTapVibrate]. Only a tap counts: a scroll, a pinch or a long press
 * gives nothing. Each tap of a double tap counts on its own.
 *
 * The page's touch listener passes every event to [onTouchEvent]. The vibration is the
 * view's haptic feedback, which needs no permission, so pages do not gain the vibration
 * API with it. From Android 13 Android's own touch vibration setting has to be on for it.
 */
class TapFeedback(context: Context, private val configuration: Configuration) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?

    private var touchedView: View? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap()
            return false
        }
    }).apply {
        setOnDoubleTapListener(null)
    }

    fun onTouchEvent(view: View, event: MotionEvent) {
        // A tap is reported from inside this call, so the view is only held for its length
        touchedView = view
        try {
            detector.onTouchEvent(event)
        } finally {
            touchedView = null
        }
    }

    private fun onTap() {
        try {
            if (configuration.browserTapSound) {
                // The volume overload plays whatever Android's own touch sounds setting says
                audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, -1f)
            }
            if (configuration.browserTapVibrate) {
                vibrate()
            }
        } catch (e: RuntimeException) {
            Timber.e(e, "Tap feedback failed")
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        touchedView?.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    companion object {
        /** Whether the device has anything to vibrate with. */
        @Suppress("DEPRECATION")
        fun canVibrate(context: Context): Boolean =
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?)?.hasVibrator() == true
    }
}
