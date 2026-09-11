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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttDiscoverySensorsTest {

    @Test
    fun `sensor ids are unique`() {
        val ids = MqttDiscoverySensors.ALL.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `covers battery camera and state sensors`() {
        val ids = MqttDiscoverySensors.ALL.map { it.id }.toSet()

        assertTrue(ids.contains("battery"))
        assertTrue(ids.contains("usbPlugged"))
        assertTrue(ids.contains(MqttUtils.STATE_ANDROID_VERSION))
        assertTrue(ids.contains(MqttUtils.STATE_SCREEN_ON))
        assertTrue(ids.contains(MqttDiscoverySensors.ID_FACE))
        assertTrue(ids.contains(MqttDiscoverySensors.ID_MOTION))
        assertTrue(ids.contains(MqttDiscoverySensors.ID_QR))
    }

    @Test
    fun `uses the Home Assistant component assigned to each sensor`() {
        assertEquals(DiscoveryComponent.SENSOR, MqttDiscoverySensors.componentFor("battery"))
        assertEquals(DiscoveryComponent.BINARY_SENSOR, MqttDiscoverySensors.componentFor("usbPlugged"))
        assertEquals(DiscoveryComponent.SENSOR, MqttDiscoverySensors.componentFor(MqttUtils.STATE_ANDROID_VERSION))
        assertEquals(DiscoveryComponent.BINARY_SENSOR, MqttDiscoverySensors.componentFor(MqttUtils.STATE_SCREEN_ON))
        assertEquals(DiscoveryComponent.BINARY_SENSOR, MqttDiscoverySensors.componentFor(MqttDiscoverySensors.ID_FACE))
        assertEquals(DiscoveryComponent.TAG, MqttDiscoverySensors.componentFor(MqttDiscoverySensors.ID_QR))
    }
}
