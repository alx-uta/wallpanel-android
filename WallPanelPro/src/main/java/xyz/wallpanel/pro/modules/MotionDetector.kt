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

package xyz.wallpanel.pro.modules

import android.os.SystemClock
import android.util.SparseArray

import com.google.android.gms.vision.Detector
import com.google.android.gms.vision.Frame
import com.jjoe64.motiondetection.motiondetection.AggregateLumaMotionDetection
import xyz.wallpanel.pro.modules.Motion.Companion.MOTION_DETECTED
import xyz.wallpanel.pro.modules.Motion.Companion.MOTION_NOT_DETECTED
import xyz.wallpanel.pro.modules.Motion.Companion.MOTION_TOO_DARK

import timber.log.Timber

/**
 * Created by Michael Ritchie on 7/6/18.
 */
class MotionDetector private constructor(
    private val minLuma: Int, 
    private val motionLeniency: Int,
    private val frameSkip: Int
) : Detector<Motion>() {

    private var aggregateLumaMotionDetection: AggregateLumaMotionDetection? = null
    private var frameCount = 0
    // Elapsed realtime of the first frame this detector was handed. A camera that has just
    // opened is still adjusting exposure and focus, and each step of that looks like motion.
    private var firstFrameAt = 0L

    init {
        aggregateLumaMotionDetection = AggregateLumaMotionDetection()
        aggregateLumaMotionDetection!!.setLeniency(motionLeniency)
    }

    override fun detect(frame: Frame?): SparseArray<Motion> {
        if (frame == null) {
            throw IllegalArgumentException("No frame supplied.")
        } else {
            // Skip frame skip logic entirely if frameSkip is 0 (disabled)
            if (frameSkip > 0) {
                frameCount++
                if (frameCount % frameSkip != 0) {
                    return SparseArray()
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (firstFrameAt == 0L) {
                firstFrameAt = now
            }
            val settling = now - firstFrameAt < SETTLE_MILLIS

            val byteBuffer = frame.grayscaleImageData
            val bytes = byteBuffer.array()
            val w = frame.metadata.width
            val h = frame.metadata.height
            val sparseArray = SparseArray<Motion>()
            val motion = Motion()
            motion.byteArray = bytes
            motion.width = w
            motion.height = h

            val step = MotionFrames.step(w, h)
            val sampledWidth = w / step
            val sampledHeight = h / step
            val img = MotionFrames.luma(bytes, w, h, step)
            var lumaSum = 0L
            for (i in img) {
                lumaSum += i
            }
            if (lumaSum < MotionFrames.scaledMinLuma(minLuma, img.size)) {
                motion.type = if (settling) MOTION_NOT_DETECTED else MOTION_TOO_DARK
                sparseArray.put(0, motion)
                return sparseArray
            }

            try {
                // Still fed while settling, so the frame after the window is compared with
                // a settled one rather than with the first frame the camera produced.
                val motionDetected = aggregateLumaMotionDetection!!.detect(img, sampledWidth, sampledHeight)
                if (motionDetected && !settling) {
                    motion.type = MOTION_DETECTED
                } else {
                    motion.type = MOTION_NOT_DETECTED
                }
            } catch (e: Exception) {
                Timber.e(e.message)
                motion.type = MOTION_NOT_DETECTED
            }
            sparseArray.put(0, motion)
            return sparseArray
        }
    }

    class Builder(
        private val minLuma: Int, 
        private val motionLeniency: Int,
        private val frameSkip: Int = 10
    ) {
        fun build(): MotionDetector {
            return MotionDetector(minLuma, motionLeniency, frameSkip)
        }
    }

    companion object {
        // How long after the camera opens a difference between frames is not reported.
        const val SETTLE_MILLIS = 2000L
    }
}
