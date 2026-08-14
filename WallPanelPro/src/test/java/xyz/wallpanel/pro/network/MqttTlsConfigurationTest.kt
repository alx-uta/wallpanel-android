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

package xyz.wallpanel.pro.network

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.wallpanel.pro.persistence.Configuration

/**
 * https://github.com/alx-uta/wallpanel-android/issues/16 -- enabling "TLS Connection" in
 * MQTT settings had no effect: getTlsConnection() was only ever read for a log line, so
 * the HiveMQ client builder never received sslConfig()/sslWithDefaultConfig() and every
 * connection went out as plain TCP, regardless of the setting.
 */
class MqttTlsConfigurationTest {

    private fun mqttOptions(tlsEnabled: Boolean): MQTTOptions {
        val configuration = mockk<Configuration>()
        every { configuration.mqttBroker } returns "broker.example.com"
        every { configuration.mqttClientId } returns "test-client"
        every { configuration.mqttServerPort } returns if (tlsEnabled) 8883 else 1883
        every { configuration.mqttTlsEnabled } returns tlsEnabled
        return MQTTOptions(configuration)
    }

    @Test
    fun `MQTT3 client applies SSL config when TLS is enabled`() {
        val client = MQTT3Service.buildTransportConfiguredBuilder(mqttOptions(true))
            .useMqttVersion3().build()

        assertTrue(client.config.sslConfig.isPresent)
    }

    @Test
    fun `MQTT3 client has no SSL config when TLS is disabled`() {
        val client = MQTT3Service.buildTransportConfiguredBuilder(mqttOptions(false))
            .useMqttVersion3().build()

        assertFalse(client.config.sslConfig.isPresent)
    }

    @Test
    fun `MQTT5 client applies SSL config when TLS is enabled`() {
        val client = MQTT5Service.buildTransportConfiguredBuilder(mqttOptions(true))
            .useMqttVersion5().build()

        assertTrue(client.config.sslConfig.isPresent)
    }

    @Test
    fun `MQTT5 client has no SSL config when TLS is disabled`() {
        val client = MQTT5Service.buildTransportConfiguredBuilder(mqttOptions(false))
            .useMqttVersion5().build()

        assertFalse(client.config.sslConfig.isPresent)
    }
}
