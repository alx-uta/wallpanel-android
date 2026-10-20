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

package xyz.wallpanel.pro.network

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.wallpanel.pro.persistence.Configuration

/**
 * https://github.com/TheTimeWalker/wallpanel-android/issues/115 -- a TLS broker taking
 * anonymous or certificate clients never connected, because a TLS connection counted as
 * configured only with both a username and a password.
 */
@RunWith(RobolectricTestRunner::class)
class MqttOptionsTest {

    private fun mqttOptions(tls: Boolean, broker: String = "broker.example.com", username: String = "", password: String = ""): MQTTOptions {
        val configuration = mockk<Configuration>()
        every { configuration.mqttBroker } returns broker
        every { configuration.mqttClientId } returns "test-client"
        every { configuration.mqttBaseTopic } returns "wallpanel/test/"
        every { configuration.mqttTlsEnabled } returns tls
        every { configuration.mqttUsername } returns username
        every { configuration.mqttPassword } returns password
        return MQTTOptions(configuration)
    }

    @Test
    fun `a TLS broker without credentials is configured`() {
        assertTrue(mqttOptions(tls = true).isConfigured)
    }

    @Test
    fun `a TLS broker with credentials is configured`() {
        assertTrue(mqttOptions(tls = true, username = "user", password = "pass").isConfigured)
    }

    @Test
    fun `no broker is not configured, with or without TLS`() {
        assertFalse(mqttOptions(tls = true, broker = "").isConfigured)
        assertFalse(mqttOptions(tls = false, broker = "").isConfigured)
    }
}
