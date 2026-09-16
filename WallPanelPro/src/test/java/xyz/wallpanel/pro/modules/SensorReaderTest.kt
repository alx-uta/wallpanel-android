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
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks the metadata and payloads of the system readings, which is what the Home
 * Assistant discovery configs are generated from -- a sensor declared as numeric that
 * publishes a string ends up as an unavailable entity rather than an obvious error.
 */
@RunWith(RobolectricTestRunner::class)
class SensorReaderTest {

    private lateinit var context: Context
    private lateinit var sensorReader: SensorReader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sensorReader = SensorReader(context)
    }

    private fun sensorTypes(reader: SensorReader = sensorReader): List<String> {
        return reader.getSensors().mapNotNull { it.sensorType }
    }

    private fun sensor(type: String): SensorInfo {
        val info = sensorReader.getSensors().firstOrNull { it.sensorType == type }
        assertNotNull("$type is not published", info)
        return info!!
    }

    @Test
    fun `the system readings are all published`() {
        assertTrue(
            sensorTypes().containsAll(
                listOf(
                    SensorReader.BATTERY,
                    SensorReader.MEMORY_USAGE,
                    SensorReader.STORAGE_FREE,
                    SensorReader.UPTIME,
                    SensorReader.IP_ADDRESS,
                    SensorReader.APP_VERSION,
                    SensorReader.ANDROID_VERSION,
                )
            )
        )
    }

    @Test
    fun `numeric readings declare a unit and a state class`() {
        val storage = sensor(SensorReader.STORAGE_FREE)
        assertTrue(storage.numeric)
        assertEquals(SensorReader.UNIT_GB, storage.unit)
        assertEquals("data_size", storage.deviceClass)
        assertEquals(SensorReader.STATE_CLASS_MEASUREMENT, storage.stateClass)
        assertTrue(storage.diagnostic)

        val uptime = sensor(SensorReader.UPTIME)
        assertEquals(SensorReader.UNIT_SECONDS, uptime.unit)
        assertEquals("duration", uptime.deviceClass)
    }

    @Test
    fun `text readings are not numeric and carry no unit`() {
        for (type in listOf(SensorReader.IP_ADDRESS, SensorReader.APP_VERSION, SensorReader.ANDROID_VERSION)) {
            val info = sensor(type)
            assertFalse("$type should not be numeric", info.numeric)
            assertEquals("$type should have no unit", null, info.unit)
            assertEquals("$type should have no state class", null, info.stateClass)
            assertTrue("$type belongs in diagnostics", info.diagnostic)
        }
    }

    @Test
    fun `the battery reading keeps its home assistant device class`() {
        val battery = sensor(SensorReader.BATTERY)
        assertEquals("battery", battery.deviceClass)
        assertEquals(SensorReader.UNIT_PERCENTAGE, battery.unit)
    }

    @Test
    fun `every published sensor has a display name`() {
        for (info in sensorReader.getSensors()) {
            assertNotNull("${info.sensorType} has no display name", info.displayName)
            assertTrue("${info.sensorType} has an empty display name", info.displayName!!.isNotEmpty())
        }
    }

    /**
     * The reading a kiosk loses if availability is settled once at startup: nothing is
     * associated here, and the entity still has to exist for the moment the radio comes up.
     */
    @Test
    fun `the wifi signal is listed on a wifi device that is not connected`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI, true)
        assertTrue(SensorReader.WIFI_SIGNAL in sensorTypes(SensorReader(context)))
    }

    @Test
    fun `a device with no wifi radio does not list the signal reading`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI, false)
        assertFalse(SensorReader.WIFI_SIGNAL in sensorTypes(SensorReader(context)))
    }

    /**
     * The network name was dropped: from Android 8.1 the platform hands it out only to
     * applications holding a location permission, and a kiosk browser asking for one to
     * label a sensor is a worse trade than going without. Home Assistant is told to remove
     * the entity through DiscoveryCatalog.RETIRED_SENSOR_IDS, so nothing here may start
     * reporting it again.
     */
    @Test
    fun `the wifi network name is not reported`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI, true)
        assertFalse("wifiSsid" in sensorTypes(SensorReader(context)))
    }

    @Test
    fun `uptime publishes seconds alongside a readable breakdown`() {
        val data = readingFor(SensorReader.UPTIME)
        val seconds = data.getLong(SensorReader.VALUE)
        assertTrue(seconds >= 0)
        assertEquals(SensorReader.UNIT_SECONDS, data.getString(SensorReader.UNIT))
        // A total in the hours field next to a days field reads as a breakdown and gets
        // templated as one, so the parts have to add back up to the whole.
        assertEquals(seconds / 86400, data.getLong("days"))
        assertTrue(data.getLong("hours") in 0..23)
        assertTrue(data.getLong("minutes") in 0..59)
        assertEquals(
            seconds / 60,
            data.getLong("days") * 1440 + data.getLong("hours") * 60 + data.getLong("minutes")
        )
        assertTrue(data.getString("formatted").endsWith("m"))
    }

    @Test
    fun `the android version reading carries the model as an attribute`() {
        val data = readingFor(SensorReader.ANDROID_VERSION)
        assertEquals(android.os.Build.MODEL, data.getString("model"))
        assertEquals(android.os.Build.VERSION.SDK_INT, data.getInt("sdk"))
    }

    /**
     * The values that never change go out when the readings start and again on a refresh,
     * which is what the service asks for when it reconnects to the broker. On every cycle
     * they would be a message a minute per device, for ever, saying the same thing.
     */
    @Test
    fun `the version readings are published once per refresh rather than once per cycle`() {
        val counts = ConcurrentHashMap<String, Int>()
        val published = AtomicInteger()
        sensorReader.startReadings(SENSOR_FREQUENCY_SECONDS, object : SensorCallback {
            override fun publishSensorData(sensorName: String, sensorData: JSONObject) {
                counts[sensorName] = (counts[sensorName] ?: 0) + 1
                published.incrementAndGet()
            }
        })

        awaitNextCycle(published)
        assertEquals(1, counts[SensorReader.APP_VERSION])
        assertEquals(1, counts[SensorReader.ANDROID_VERSION])

        awaitNextCycle(published)
        assertEquals("the app version went out twice", 1, counts[SensorReader.APP_VERSION])
        assertEquals("the android version went out twice", 1, counts[SensorReader.ANDROID_VERSION])
        // The uptime moves, so it goes out on every cycle.
        assertEquals(2, counts[SensorReader.UPTIME])

        sensorReader.refreshSensors()
        drainCycle(published)
        assertEquals("a refresh has to send the versions again", 2, counts[SensorReader.APP_VERSION])

        sensorReader.stopReadings()
    }

    /**
     * Runs one publish cycle and returns the payload for a single sensor. Readings are
     * driven by a handler on the main looper, which Robolectric only runs when the looper
     * is idled.
     */
    private fun readingFor(sensorType: String): JSONObject {
        val payloads = ConcurrentHashMap<String, JSONObject>()
        val published = AtomicInteger()
        sensorReader.startReadings(SENSOR_FREQUENCY_SECONDS, object : SensorCallback {
            override fun publishSensorData(sensorName: String, sensorData: JSONObject) {
                payloads[sensorName] = sensorData
                published.incrementAndGet()
            }
        })
        sensorReader.refreshSensors()
        drainCycle(published)
        sensorReader.stopReadings()
        return requireNotNull(payloads[sensorType]) { "no reading published for $sensorType" }
    }

    /** Moves the shadow clock past the publish frequency so the next cycle is due. */
    private fun awaitNextCycle(published: AtomicInteger) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(SENSOR_FREQUENCY_SECONDS + 1L))
        drainCycle(published)
    }

    /**
     * Idles the looper and then waits for the cycle to finish. The disk, /proc and network
     * readings are handed to a background thread which outlives the test otherwise, and it
     * has finished once the callback has been quiet for a moment -- the CPU reading spends a
     * second sampling inside that window. Polls are counted rather than timed: Robolectric's
     * clock only moves when the test moves it.
     */
    private fun drainCycle(published: AtomicInteger) {
        shadowOf(Looper.getMainLooper()).idle()
        var seen = -1
        var quietPolls = 0
        repeat(MAX_POLLS) {
            val count = published.get()
            if (count != seen) {
                seen = count
                quietPolls = 0
            } else {
                quietPolls++
            }
            if (quietPolls >= QUIET_POLLS) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        fail("the readings did not finish within ${MAX_POLLS * POLL_MILLIS}ms")
    }

    companion object {
        private const val SENSOR_FREQUENCY_SECONDS = 60
        private const val POLL_MILLIS = 50L
        // Long enough to cover the second the CPU reading spends sampling.
        private const val QUIET_POLLS = 30
        private const val MAX_POLLS = 300
    }
}
