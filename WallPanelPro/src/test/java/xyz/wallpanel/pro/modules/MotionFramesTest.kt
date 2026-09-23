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

package xyz.wallpanel.pro.modules

import com.jjoe64.motiondetection.motiondetection.AggregateLumaMotionDetection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionFramesTest {

    @Test
    fun `every supported resolution samples down to about 320x240`() {
        assertEquals(1, MotionFrames.step(320, 240))
        assertEquals(2, MotionFrames.step(640, 480))
        assertEquals(3, MotionFrames.step(1280, 720))
        // Smaller than the reference is read in full rather than rejected.
        assertEquals(1, MotionFrames.step(176, 144))
    }

    @Test
    fun `sampling takes every step-th pixel of every step-th row`() {
        val width = 4
        val height = 4
        // Luma plane values 16..31, then a chroma plane the sampler must not read.
        val yuv = ByteArray(width * height * 3 / 2) { i -> if (i < width * height) (16 + i).toByte() else 0x7f }

        val luma = MotionFrames.luma(yuv, width, height, 2)

        // Pixels (0,0), (2,0), (0,2), (2,2), less the video range offset of 16.
        assertArrayEquals(intArrayOf(0, 2, 8, 10), luma)
    }

    @Test
    fun `luma below the video range floor is clamped to zero and bytes read unsigned`() {
        val yuv = byteArrayOf(5, 0xff.toByte())

        assertArrayEquals(intArrayOf(0, 239), MotionFrames.luma(yuv, 2, 1, 1))
    }

    @Test
    fun `the darkness threshold means the same brightness at every resolution`() {
        val atReference = MotionFrames.scaledMinLuma(1000, 320 * 240)
        assertEquals(1000L, atReference)
        // A 1280x720 frame samples to 426x240.
        assertEquals(1000L * 426 * 240 / (320 * 240), MotionFrames.scaledMinLuma(1000, 426 * 240))
    }

    @Test
    fun `a new detector does not compare its first frames with another detector's`() {
        val small = IntArray(320 * 240) { 50 }
        val large = IntArray(426 * 240) { 200 }
        val first = AggregateLumaMotionDetection()
        first.detect(small, 320, 240)
        first.detect(small, 320, 240)

        val second = AggregateLumaMotionDetection()

        assertFalse(second.detect(large, 426, 240))
        assertFalse(second.detect(large, 426, 240))
    }

    @Test
    fun `a detector still reports a real change`() {
        val dark = IntArray(320 * 240) { 20 }
        val bright = IntArray(320 * 240) { 200 }
        val detector = AggregateLumaMotionDetection()
        detector.detect(dark, 320, 240)
        detector.detect(dark, 320, 240)

        assertTrue(detector.detect(bright, 320, 240))
    }
}
