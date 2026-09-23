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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraProfileTest {

    private val idle = CameraProfile(CameraResolution.LOW, 10f)
    private val boost = CameraProfile(CameraResolution.HD, 20f)

    @Test
    fun `idle profile until motion boosts it`() {
        assertEquals(idle, CameraProfile.resolve(idle, boost, boosted = false, resolutionOverride = null, fpsOverride = null))
        assertEquals(boost, CameraProfile.resolve(idle, boost, boosted = true, resolutionOverride = null, fpsOverride = null))
    }

    @Test
    fun `a boost with the feature off stays on the idle profile`() {
        assertEquals(idle, CameraProfile.resolve(idle, null, boosted = true, resolutionOverride = null, fpsOverride = null))
    }

    @Test
    fun `a pinned resolution leaves the frame rate to the boost`() {
        val resolved = CameraProfile.resolve(idle, boost, boosted = true, resolutionOverride = CameraResolution.STANDARD, fpsOverride = null)

        assertEquals(CameraProfile(CameraResolution.STANDARD, 20f), resolved)
    }

    @Test
    fun `a pinned frame rate leaves the resolution to the boost`() {
        val resolved = CameraProfile.resolve(idle, boost, boosted = true, resolutionOverride = null, fpsOverride = 5)

        assertEquals(CameraProfile(CameraResolution.HD, 5f), resolved)
    }

    @Test
    fun `resolution command takes the offered sizes and auto`() {
        assertEquals(CameraResolution.HD, CameraProfile.parseResolutionCommand("1280x720"))
        assertEquals(CameraResolution.LOW, CameraProfile.parseResolutionCommand(" 320X240 "))
        assertNull(CameraProfile.parseResolutionCommand("auto"))
        assertNull(CameraProfile.parseResolutionCommand("AUTO"))
    }

    @Test
    fun `resolution command refuses anything else`() {
        assertThrows(IllegalArgumentException::class.java) { CameraProfile.parseResolutionCommand("1920x1080") }
        assertThrows(IllegalArgumentException::class.java) { CameraProfile.parseResolutionCommand(640) }
        assertThrows(IllegalArgumentException::class.java) { CameraProfile.parseResolutionCommand("") }
    }

    @Test
    fun `fps command takes a number or the text a select sends`() {
        assertEquals(15, CameraProfile.parseFpsCommand(15))
        assertEquals(15, CameraProfile.parseFpsCommand("15"))
        assertEquals(30, CameraProfile.parseFpsCommand(30.0))
        assertEquals(1, CameraProfile.parseFpsCommand(1))
        assertNull(CameraProfile.parseFpsCommand("auto"))
    }

    @Test
    fun `fps command refuses values the camera cannot take`() {
        for (bad in listOf<Any?>(0, 31, -5, 12.5, "fast", "", null, true)) {
            assertThrows("accepted $bad", IllegalArgumentException::class.java) { CameraProfile.parseFpsCommand(bad) }
        }
    }
}
