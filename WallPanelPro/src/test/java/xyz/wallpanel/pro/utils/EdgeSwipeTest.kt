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

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.wallpanel.pro.utils.EdgeSwipe.Outcome

class EdgeSwipeTest {

    private val swipe = EdgeSwipe(edge = 50f, distance = 150f, slop = 10f)
    private val width = 1000

    @Test
    fun `a swipe in from the left edge goes back`() {
        assertEquals(Outcome.PASS, swipe.onDown(10f, 500f, width))
        assertEquals(Outcome.PASS, swipe.onMove(15f, 501f))
        assertEquals(Outcome.CLAIMED, swipe.onMove(40f, 505f))
        assertEquals(Outcome.CONSUMED, swipe.onMove(120f, 510f))
        assertEquals(Outcome.BACK, swipe.onUp(200f))
    }

    @Test
    fun `a swipe in from the right edge goes forward`() {
        swipe.onDown(990f, 300f, width)
        assertEquals(Outcome.CLAIMED, swipe.onMove(950f, 300f))
        assertEquals(Outcome.FORWARD, swipe.onUp(800f))
    }

    @Test
    fun `a short swipe does nothing but is still kept from the page`() {
        swipe.onDown(10f, 500f, width)
        assertEquals(Outcome.CLAIMED, swipe.onMove(60f, 500f))
        assertEquals(Outcome.CONSUMED, swipe.onUp(100f))
    }

    @Test
    fun `a touch away from the edges is the page's`() {
        swipe.onDown(400f, 500f, width)
        assertEquals(Outcome.PASS, swipe.onMove(700f, 500f))
        assertEquals(Outcome.PASS, swipe.onUp(800f))
    }

    @Test
    fun `scrolling near the edge is the page's`() {
        swipe.onDown(10f, 500f, width)
        assertEquals(Outcome.PASS, swipe.onMove(14f, 540f))
        assertEquals(Outcome.PASS, swipe.onMove(200f, 560f))
        assertEquals(Outcome.PASS, swipe.onUp(300f))
    }

    @Test
    fun `a second finger makes it a pinch`() {
        swipe.onDown(10f, 500f, width)
        assertEquals(Outcome.PASS, swipe.onPointerDown())
        assertEquals(Outcome.PASS, swipe.onMove(200f, 500f))
        assertEquals(Outcome.PASS, swipe.onUp(300f))
    }

    @Test
    fun `a cancelled swipe stays kept to its end`() {
        swipe.onDown(10f, 500f, width)
        swipe.onMove(80f, 500f)
        assertEquals(Outcome.CONSUMED, swipe.onCancel())
        // And the next touch starts afresh
        assertEquals(Outcome.PASS, swipe.onDown(500f, 500f, width))
        assertEquals(Outcome.PASS, swipe.onMove(800f, 500f))
    }
}
