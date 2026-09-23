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

/**
 * Brings a camera frame of any size down to roughly 320x240 before motion detection looks
 * at it, so detection costs the same and behaves the same at every camera resolution.
 *
 * The comparison works on the average brightness of a 10x10 grid of boxes, so taking every
 * n-th pixel leaves the result close to what the full frame gives.
 */
object MotionFrames {

    const val REFERENCE_WIDTH = 320
    const val REFERENCE_HEIGHT = 240
    private const val REFERENCE_PIXELS = REFERENCE_WIDTH * REFERENCE_HEIGHT

    /** Every how many pixels, in both directions, a frame of this size is sampled. */
    fun step(width: Int, height: Int): Int {
        return maxOf(1, minOf(width / REFERENCE_WIDTH, height / REFERENCE_HEIGHT))
    }

    /**
     * The luma of every [step]-th pixel of an NV21 frame, row by row, with the video range
     * offset of 16 taken off. The result is `(width / step) * (height / step)` long.
     */
    fun luma(yuv: ByteArray, width: Int, height: Int, step: Int): IntArray {
        val sampledWidth = width / step
        val sampledHeight = height / step
        val out = IntArray(sampledWidth * sampledHeight)
        var o = 0
        for (row in 0 until sampledHeight) {
            var p = row * step * width
            for (col in 0 until sampledWidth) {
                val y = (yuv[p].toInt() and 0xff) - 16
                out[o++] = if (y < 0) 0 else y
                p += step
            }
        }
        return out
    }

    /**
     * The "too dark" threshold for a sampled frame of [pixels] pixels.
     *
     * The minimum luma setting is a sum over every pixel of the frame, sized for the default
     * 320x240 camera. Scaling it by the pixel count keeps a configured value meaning the same
     * brightness at every resolution; read against a whole 640x480 frame it was four times
     * easier to pass.
     */
    fun scaledMinLuma(minLuma: Int, pixels: Int): Long {
        return minLuma.toLong() * pixels / REFERENCE_PIXELS
    }
}
