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

import android.view.KeyEvent

/**
 * Turns the volume keys into presses and long presses for Home Assistant, in place of
 * changing the volume.
 *
 * A key held down repeats, and the first repeat past [longPressMillis], or flagged as a long
 * press, is the long press; letting go before then is a press. Exactly one of the two is
 * reported per push of a key.
 */
class VolumeKeys(
    private val longPressMillis: Long,
    private val report: (key: String, action: String) -> Unit,
) {
    // Keys whose long press has gone out, so the repeats after it and the release are quiet
    private val longPressed = mutableSetOf<Int>()

    /** Whether [event] was a volume key, which is then consumed. */
    fun handle(event: KeyEvent): Boolean {
        val key = KEYS[event.keyCode] ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    longPressed.remove(event.keyCode)
                } else if (isLongPress(event) && longPressed.add(event.keyCode)) {
                    report(key, LONG_PRESS)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (!longPressed.remove(event.keyCode) && !event.isCanceled) {
                    report(key, PRESS)
                }
            }
        }
        return true
    }

    // The flag is only set on a key someone tracked, which a dispatched key is not, so the
    // time held is what normally decides. `input keyevent --longpress` sets the flag with
    // no time held on Android 8.
    private fun isLongPress(event: KeyEvent): Boolean {
        return event.isLongPress || event.eventTime - event.downTime >= longPressMillis
    }

    companion object {
        const val PRESS = "press"
        const val LONG_PRESS = "long_press"
        const val VOLUME_UP = "volume_up"
        const val VOLUME_DOWN = "volume_down"

        private val KEYS = mapOf(
            KeyEvent.KEYCODE_VOLUME_UP to VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN to VOLUME_DOWN,
        )

        /** The Home Assistant event types, one per key and action. */
        val EVENT_TYPES: List<String> = listOf(VOLUME_UP, VOLUME_DOWN).flatMap { key ->
            listOf(PRESS, LONG_PRESS).map { "${key}_$it" }
        }
    }
}
