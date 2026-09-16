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

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.wallpanel.pro.network.MQTTOptions

/**
 * Covers the client lifecycle without a broker. The options are invalid, so each client is
 * built but never tries to connect.
 */
@RunWith(RobolectricTestRunner::class)
class MQTTModuleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = mockk<LifecycleOwner>(relaxed = true)

    private fun module(version: String): MQTTModule {
        val options = mockk<MQTTOptions>(relaxed = true)
        every { options.getVersion() } returns version
        every { options.isValid } returns false
        every { options.getStateTopics() } returns arrayOf()
        return MQTTModule(context, options, mockk(relaxed = true))
    }

    @Test
    fun `stopping releases the MQTT 3 client`() {
        val module = module("3.1.1")
        module.onStart(owner)
        assertNotNull(module.mqtt3Service)

        module.onStop(owner)
        assertNull(module.mqtt3Service)
        assertFalse(module.isConnected)
    }

    @Test
    fun `stopping releases the MQTT 5 client`() {
        val module = module("5.0")
        module.onStart(owner)
        assertNotNull(module.mqtt5Service)

        module.onStop(owner)
        assertNull(module.mqtt5Service)
    }

    @Test
    fun `a restart builds a fresh MQTT 3 client`() {
        val module = module("3.1.1")
        module.onStart(owner)
        val first = module.mqtt3Service

        module.restart()
        assertNotNull(module.mqtt3Service)
        assertNotSame(first, module.mqtt3Service)
    }
}
