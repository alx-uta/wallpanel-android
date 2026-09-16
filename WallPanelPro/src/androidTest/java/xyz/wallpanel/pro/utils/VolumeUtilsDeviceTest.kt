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
import android.media.AudioManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Exercises the media volume against real hardware. The number of steps on the media
 * stream is a vendor choice, and whether a stream can be silenced at all changes with the
 * Android version, so neither the rounding nor the refusal path shows up off-device.
 *
 * The volume in place when the test starts is put back afterwards -- these run against
 * real kiosk devices, not throwaway emulators.
 */
@RunWith(AndroidJUnit4::class)
class VolumeUtilsDeviceTest {

    private lateinit var volumeUtils: VolumeUtils
    private lateinit var audioManager: AudioManager
    private var originalStreamVolume = 0

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        volumeUtils = VolumeUtils(context)
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        println(
            "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} " +
                "mediaSteps=${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
                "startingVolume=$originalStreamVolume"
        )
    }

    @After
    fun tearDown() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalStreamVolume, 0)
    }

    @Test
    fun aVolumeSetIsTheVolumeReadBack() {
        val steps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        assertTrue("the media stream reported $steps steps", steps > 0)
        // Half a step is the closest a percentage can land on a stream with this many steps.
        val tolerance = Math.round(100f / steps / 2) + 1

        for (target in listOf(25, 50, 75, 100)) {
            assertTrue("setting the volume to $target% was refused", volumeUtils.setVolumePercent(target))
            val actual = volumeUtils.getVolumePercent()
            assertTrue(
                "asked for $target%, read back $actual% on a $steps step stream",
                abs(actual - target) <= tolerance
            )
        }
    }

    @Test
    fun outOfRangeValuesAreClampedRatherThanThrowing() {
        assertTrue(volumeUtils.setVolumePercent(500))
        assertEquals(100, volumeUtils.getVolumePercent())

        // Below zero clamps to a silent stream, which is the one level a device may refuse.
        val silenced = volumeUtils.setVolumePercent(-20)
        println("silencing the media stream ${if (silenced) "succeeded" else "was refused"}")
        if (silenced) {
            assertEquals(0, volumeUtils.getVolumePercent())
        }
    }

    @Test
    fun theConversionRoundTripsAcrossTheStepCountsDevicesActuallyUse() {
        for (steps in listOf(7, 15, 16, 25, 100)) {
            for (percent in 0..100) {
                val streamVolume = VolumeUtils.percentToStreamVolume(percent, steps)
                assertTrue(
                    "$percent% mapped to step $streamVolume on a $steps step stream",
                    streamVolume in 0..steps
                )
            }
            assertEquals(0, VolumeUtils.percentToStreamVolume(0, steps))
            assertEquals(steps, VolumeUtils.percentToStreamVolume(100, steps))
            assertEquals(100, VolumeUtils.streamVolumeToPercent(steps, steps))
        }
        // A device that reports no media stream at all shouldn't divide by zero.
        assertEquals(0, VolumeUtils.percentToStreamVolume(50, 0))
        assertEquals(0, VolumeUtils.streamVolumeToPercent(5, 0))
    }
}
