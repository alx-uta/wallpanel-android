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

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import timber.log.Timber
import javax.inject.Inject

/**
 * The device's media volume as a percentage, which is what the remote control commands
 * and the Home Assistant slider both work in. The media stream is the one audio playback
 * and text to speech go through.
 */
class VolumeUtils @Inject
constructor(context: Context) : ContextWrapper(context) {

    private val audioManager: AudioManager?
        get() = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Returns whether the level was applied. Silencing a stream counts as a Do Not Disturb
     * change on some versions of Android and is refused without a permission a kiosk
     * browser shouldn't hold, so setting 0 can fail where other levels succeed.
     */
    fun setVolumePercent(percent: Int): Boolean {
        val level = percent.coerceIn(0, 100)
        return try {
            val manager = audioManager ?: return false
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, percentToStreamVolume(level, maxVolume), 0)
            true
        } catch (e: Exception) {
            Timber.e(e, "Could not set the media volume to $level%")
            false
        }
    }

    fun getVolumePercent(): Int {
        return try {
            val manager = audioManager ?: return 0
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            streamVolumeToPercent(manager.getStreamVolume(AudioManager.STREAM_MUSIC), maxVolume)
        } catch (e: Exception) {
            Timber.e(e, "Could not read the media volume")
            0
        }
    }

    companion object {
        /**
         * Devices expose anything from 7 to 100 steps on the media stream, so a percentage
         * only ever lands on the nearest step -- a round trip is accurate to half a step,
         * not to the percent.
         */
        fun percentToStreamVolume(percent: Int, maxVolume: Int): Int {
            if (maxVolume <= 0) {
                return 0
            }
            return Math.round(maxVolume * percent.coerceIn(0, 100) / 100f)
        }

        fun streamVolumeToPercent(streamVolume: Int, maxVolume: Int): Int {
            if (maxVolume <= 0) {
                return 0
            }
            return Math.round(streamVolume * 100f / maxVolume)
        }
    }
}
