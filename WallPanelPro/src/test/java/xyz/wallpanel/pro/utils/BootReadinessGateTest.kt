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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.wallpanel.pro.utils.BootReadinessGate.Companion.BOOT_WINDOW_MS
import xyz.wallpanel.pro.utils.BootReadinessGate.Companion.EARLIEST_VALID_TIME_MS
import xyz.wallpanel.pro.utils.BootReadinessGate.Companion.NETWORK_STABLE_MS

class BootReadinessGateTest {

    private val dashboard = "http://192.168.1.10:8123/lovelace/0"
    private val setClock = 1790000000000L // September 2026
    private val unsetClock = 30000L // 1970, as seen after a reboot

    private var wallClock = unsetClock
    private var uptime = 20_000L
    private var connected = false

    private val gate = BootReadinessGate({ wallClock }, { uptime }, { connected })

    private fun advance(millis: Long) {
        uptime += millis
        wallClock += millis
    }

    @Test
    fun appliesOnlyToLaunchesCloseToBoot() {
        assertTrue(gate.appliesTo(dashboard))
        uptime = BOOT_WINDOW_MS
        assertFalse(gate.appliesTo(dashboard))
    }

    @Test
    fun appliesOnlyWhenTheLaunchUrlGoesOverTheNetwork() {
        assertFalse(gate.appliesTo("file:///sdcard/dashboard.html"))
        assertTrue(gate.appliesTo("HTTPS://ha.local/"))
        // A playlist with any network URL in it
        assertTrue(gate.appliesTo("file:///sdcard/a.html\n$dashboard"))
    }

    @Test
    fun waitsForTheClockHoweverLongTheNetworkIsUp() {
        connected = true
        repeat(600) {
            assertFalse(gate.isReady())
            advance(1000)
        }
        wallClock = setClock
        assertTrue(gate.isReady())
    }

    @Test
    fun waitsForTheNetworkToStayUp() {
        wallClock = setClock
        assertFalse(gate.isReady())

        connected = true
        assertFalse(gate.isReady())
        advance(NETWORK_STABLE_MS - 1)
        assertFalse(gate.isReady())

        // A drop starts the count again
        connected = false
        advance(1)
        assertFalse(gate.isReady())
        connected = true
        assertFalse(gate.isReady())
        advance(NETWORK_STABLE_MS - 1)
        assertFalse(gate.isReady())
        advance(1)
        assertTrue(gate.isReady())
    }

    @Test
    fun treatsTheClockAsSetFromTheCutoff() {
        wallClock = EARLIEST_VALID_TIME_MS - 1
        assertFalse(gate.isClockValid())
        wallClock = EARLIEST_VALID_TIME_MS
        assertTrue(gate.isClockValid())
    }
}
