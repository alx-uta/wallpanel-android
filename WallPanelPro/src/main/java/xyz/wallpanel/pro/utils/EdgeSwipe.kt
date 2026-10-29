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

import kotlin.math.abs

/**
 * Recognises a swipe in from the left or right edge of the screen, for going back and
 * forward through the page's history. A touch that starts away from the edges, or that
 * moves up or down first, is left alone. Once a touch is taken as a swipe the page is
 * told the touch was cancelled, so it does not also scroll a slider or a carousel.
 *
 * [edge] is how far from the edge a swipe may start, [distance] how far it has to go,
 * and [slop] how far a finger moves before it counts as moving, all in pixels.
 */
class EdgeSwipe(private val edge: Float, private val distance: Float, private val slop: Float) {

    enum class Outcome {
        /** Not a swipe, or not yet: the touch goes to the page. */
        PASS,
        /** The touch just became a swipe: cancel it for the page and keep it. */
        CLAIMED,
        /** Part of a swipe already claimed. */
        CONSUMED,
        BACK,
        FORWARD,
    }

    private enum class Side { NONE, LEFT, RIGHT }

    private var side = Side.NONE
    private var claimed = false
    private var startX = 0f
    private var startY = 0f

    fun onDown(x: Float, y: Float, width: Int): Outcome {
        claimed = false
        startX = x
        startY = y
        side = when {
            width <= 0 -> Side.NONE
            x <= edge -> Side.LEFT
            x >= width - edge -> Side.RIGHT
            else -> Side.NONE
        }
        return Outcome.PASS
    }

    fun onMove(x: Float, y: Float): Outcome {
        if (claimed) {
            return Outcome.CONSUMED
        }
        if (side == Side.NONE) {
            return Outcome.PASS
        }
        val dx = inward(x)
        val dy = abs(y - startY)
        when {
            // Scrolling the page, or dragging back out over the edge
            dy > slop && dy > abs(dx) -> side = Side.NONE
            dx < -slop -> side = Side.NONE
            dx > slop && dx > dy * 2 -> {
                claimed = true
                return Outcome.CLAIMED
            }
        }
        return Outcome.PASS
    }

    /** A second finger: a pinch, not a swipe. A swipe already claimed stays claimed. */
    fun onPointerDown(): Outcome {
        if (claimed) {
            return Outcome.CONSUMED
        }
        side = Side.NONE
        return Outcome.PASS
    }

    fun onUp(x: Float): Outcome {
        val wasClaimed = claimed
        val wentFarEnough = wasClaimed && inward(x) >= distance
        val from = side
        reset()
        return when {
            !wasClaimed -> Outcome.PASS
            !wentFarEnough -> Outcome.CONSUMED
            from == Side.LEFT -> Outcome.BACK
            else -> Outcome.FORWARD
        }
    }

    fun onCancel(): Outcome {
        val wasClaimed = claimed
        reset()
        return if (wasClaimed) Outcome.CONSUMED else Outcome.PASS
    }

    private fun inward(x: Float): Float = if (side == Side.LEFT) x - startX else startX - x

    private fun reset() {
        side = Side.NONE
        claimed = false
    }
}
