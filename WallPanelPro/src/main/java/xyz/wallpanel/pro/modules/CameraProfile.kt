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
 * A camera preview size WallPanel offers. The camera is asked for this size and opens at
 * the closest one it supports, so a device without 1280x720 still starts.
 */
data class CameraResolution(val width: Int, val height: Int) {

    override fun toString(): String = "${width}x$height"

    companion object {
        val LOW = CameraResolution(320, 240)
        val STANDARD = CameraResolution(640, 480)
        val HD = CameraResolution(1280, 720)

        val SUPPORTED = listOf(LOW, STANDARD, HD)

        /** One of [SUPPORTED] written as `WIDTHxHEIGHT`, or null for anything else. */
        fun parse(value: String?): CameraResolution? {
            val text = value?.trim()?.lowercase() ?: return null
            return SUPPORTED.firstOrNull { it.toString() == text }
        }
    }
}

/** The size and frame rate the camera is opened with. */
data class CameraProfile(val resolution: CameraResolution, val fps: Float) {

    companion object {
        const val MIN_FPS = 1
        const val MAX_FPS = 30

        /** The value a command or the state uses for "no override". */
        const val AUTO = "auto"

        /**
         * What the camera should run at.
         *
         * Each command override replaces its own half only, so pinning the resolution from
         * Home Assistant still lets a motion boost raise the frame rate, and the other way
         * round. Without an override, a boost uses the boost profile when there is one.
         */
        fun resolve(
            idle: CameraProfile,
            boost: CameraProfile?,
            boosted: Boolean,
            resolutionOverride: CameraResolution?,
            fpsOverride: Int?,
        ): CameraProfile {
            val base = if (boosted && boost != null) boost else idle
            return CameraProfile(
                resolution = resolutionOverride ?: base.resolution,
                fps = fpsOverride?.toFloat() ?: base.fps,
            )
        }

        /**
         * A frame rate command value: a whole number from [MIN_FPS] to [MAX_FPS], given as a
         * JSON number or as text, which is what a Home Assistant select sends. Returns null
         * for [AUTO] and throws for anything else, so the caller can tell the two apart.
         */
        fun parseFpsCommand(value: Any?): Int? {
            if (value is String && value.trim().equals(AUTO, ignoreCase = true)) {
                return null
            }
            val fps = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }
            require(fps != null && fps == Math.floor(fps) && fps >= MIN_FPS && fps <= MAX_FPS) {
                "Camera frame rate must be $AUTO or a whole number from $MIN_FPS to $MAX_FPS, not $value"
            }
            return fps.toInt()
        }

        /**
         * A resolution command value: one of [CameraResolution.SUPPORTED] or [AUTO]. Returns
         * null for [AUTO] and throws for anything else.
         */
        fun parseResolutionCommand(value: Any?): CameraResolution? {
            if (value is String && value.trim().equals(AUTO, ignoreCase = true)) {
                return null
            }
            return requireNotNull(CameraResolution.parse(value as? String)) {
                "Camera resolution must be $AUTO or one of ${CameraResolution.SUPPORTED.joinToString()}, not $value"
            }
        }
    }
}
