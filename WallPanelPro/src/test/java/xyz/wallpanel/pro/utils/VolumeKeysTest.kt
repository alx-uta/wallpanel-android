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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VolumeKeysTest {

    private val reported = mutableListOf<String>()
    private val keys = VolumeKeys(500) { key, action -> reported += "${key}_$action" }

    private fun event(action: Int, code: Int, down: Long, time: Long, repeat: Int = 0) =
        KeyEvent(down, time, action, code, repeat)

    @Test
    fun `a short push is a press`() {
        assertTrue(keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0, 0)))
        assertTrue(keys.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0, 120)))
        assertEquals(listOf("volume_up_press"), reported)
    }

    @Test
    fun `holding is one long press and no press`() {
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0))
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 400, repeat = 1))
        assertTrue(reported.isEmpty())
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 500, repeat = 2))
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 550, repeat = 3))
        keys.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 900))
        assertEquals(listOf("volume_down_long_press"), reported)

        // The next push starts afresh
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 1000, 1000))
        keys.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 1000, 1100))
        assertEquals(listOf("volume_down_long_press", "volume_down_press"), reported)
    }

    @Test
    fun `other keys are left alone`() {
        assertFalse(keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0)))
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a repeat flagged as a long press counts at once`() {
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0, 0))
        keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 1, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS))
        keys.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0, 10))
        assertEquals(listOf("volume_up_long_press"), reported)
    }
}
