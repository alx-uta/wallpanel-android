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
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the system readings against real hardware. These readings go through APIs whose
 * behaviour is version and vendor specific -- free space, the network interface list, the
 * Wi-Fi connection info -- and the fallbacks around them only show up on a device that
 * actually refuses the call.
 *
 * The reading values are printed rather than pinned, so the per-device report shows what
 * each Android version really returned.
 */
@RunWith(AndroidJUnit4::class)
class SensorReaderDeviceTest {

    private lateinit var context: Context
    private lateinit var sensorReader: SensorReader
    private val readings = ConcurrentHashMap<String, JSONObject>()
    private val published = AtomicInteger()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sensorReader = SensorReader(context)
        publishOneCycle()
    }

    @After
    fun tearDown() {
        sensorReader.stopReadings()
    }

    /**
     * Drives a single publish cycle and waits for the readings that are handed off to a
     * background thread. The CPU reading spends a second sampling and the disk and network
     * readings queue up behind it.
     */
    private fun publishOneCycle() {
        sensorReader.startReadings(SENSOR_FREQUENCY_SECONDS, object : SensorCallback {
            override fun publishSensorData(sensorName: String, sensorData: JSONObject) {
                readings[sensorName] = sensorData
                published.incrementAndGet()
            }
        })
        sensorReader.refreshSensors()
        awaitCycle()
        println("device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        for ((name, data) in readings.toSortedMap()) {
            println("  $name = $data")
        }
    }

    /**
     * Waits for the cycle to go quiet rather than for one named reading: which readings a
     * device produces, and in what order, is the thing under test. A device that produces
     * nothing at all falls through to the timeout, which the assertions then report on.
     */
    private fun awaitCycle() {
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
            if (count > 0 && quietPolls >= QUIET_POLLS) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun reading(name: String): JSONObject {
        val data = readings[name]
        assertNotNull("$name was never published on this device", data)
        return data!!
    }

    @Test
    fun freeStorageIsReadableAndPlausible() {
        val storage = reading(SensorReader.STORAGE_FREE)
        val freeGb = storage.getDouble(SensorReader.VALUE)
        val totalGb = storage.getDouble("total")
        assertEquals(SensorReader.UNIT_GB, storage.getString(SensorReader.UNIT))
        assertTrue("total storage came back as $totalGb GB", totalGb > 0.0)
        assertTrue("free storage came back as $freeGb GB", freeGb >= 0.0)
        assertTrue("free ($freeGb GB) exceeds total ($totalGb GB)", freeGb <= totalGb)
        // Home Assistant reads GB as a thousand million bytes when it converts the value,
        // so the number published has to be counted the same way.
        assertEquals(storage.getLong("freeBytes") / 1.0e9, freeGb, 0.01)
        assertEquals(storage.getLong("totalBytes") / 1.0e9, totalGb, 0.01)
    }

    @Test
    fun memoryIsReportedWithRoomToSpare() {
        val memory = reading(SensorReader.MEMORY_USAGE)
        val total = memory.getLong("total")
        val available = memory.getLong("available")
        val percentage = memory.getInt("percentage")
        assertTrue("total memory came back as $total MB", total > 0)
        // Kernels older than 3.14 have no MemAvailable field. Without the fallback the
        // reading claims every byte of memory is in use.
        assertTrue("no memory reported as available out of $total MB", available > 0)
        assertTrue("memory use came back as $percentage%", percentage in 1..99)
        assertEquals(total - available, memory.getLong(SensorReader.VALUE))
    }

    @Test
    fun uptimeCountsFromBoot() {
        val uptime = reading(SensorReader.UPTIME)
        val seconds = uptime.getLong(SensorReader.VALUE)
        assertEquals(SensorReader.UNIT_SECONDS, uptime.getString(SensorReader.UNIT))
        assertTrue("uptime came back as $seconds seconds", seconds > 0)
        // days, hours and minutes are a breakdown, so they have to add back up.
        val days = uptime.getLong("days")
        val hours = uptime.getLong("hours")
        val minutes = uptime.getLong("minutes")
        assertEquals(seconds / 86400, days)
        assertTrue("hours out of range: $hours", hours in 0..23)
        assertTrue("minutes out of range: $minutes", minutes in 0..59)
        assertEquals(seconds / 60, days * 1440 + hours * 60 + minutes)
        assertTrue(uptime.getString("formatted").endsWith("m"))
    }

    /**
     * Reading the interface list is what replaced shelling out to `ip addr`, which is
     * refused outright on some devices. Every test device is on the network over adb, so
     * an address is expected on all of them.
     */
    @Test
    fun theDeviceReportsItsOwnIpAddress() {
        val address = reading(SensorReader.IP_ADDRESS).getString(SensorReader.VALUE)
        assertTrue(
            "'$address' does not look like an IPv4 address",
            address.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        )
        assertTrue("loopback was reported as the device address", !address.startsWith("127."))
    }

    @Test
    fun theApplicationAndAndroidVersionsAreReported() {
        val appVersion = reading(SensorReader.APP_VERSION).getString(SensorReader.VALUE)
        assertTrue("the app version came back empty", appVersion.isNotEmpty())

        val android = reading(SensorReader.ANDROID_VERSION)
        assertEquals(Build.VERSION.RELEASE, android.getString(SensorReader.VALUE))
        assertEquals(Build.VERSION.SDK_INT, android.getInt("sdk"))
        assertEquals(Build.MODEL, android.getString("model"))
    }

    /**
     * The Wi-Fi signal entity exists wherever the device has a radio, whether or not it
     * happens to be associated: a kiosk that boots before the Wi-Fi comes up would otherwise
     * go without it until the application was restarted. What still has to hold is the other
     * direction -- a value that gets published needs an entity to land in.
     *
     * The network name is not reported at all. Android hands it out only to applications
     * holding a location permission, which this one does not ask for.
     */
    @Test
    fun theWifiSensorsFollowTheHardwareRatherThanTheConnection() {
        val discovered = sensorReader.getSensors().mapNotNull { it.sensorType }.toSet()
        val hasWifi = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        assertEquals(
            "wifiSignal discovered=${SensorReader.WIFI_SIGNAL in discovered} on a device with wifi=$hasWifi",
            hasWifi,
            SensorReader.WIFI_SIGNAL in discovered
        )

        if (readings.containsKey(SensorReader.WIFI_SIGNAL)) {
            assertTrue("wifiSignal published a value without being discovered", SensorReader.WIFI_SIGNAL in discovered)
        }
        assertFalse("wifiSsid is retired and must not be reported", "wifiSsid" in discovered)
        assertFalse("wifiSsid is retired and must not be published", readings.containsKey("wifiSsid"))

        readings[SensorReader.WIFI_SIGNAL]?.let { rssi ->
            val dbm = rssi.getInt(SensorReader.VALUE)
            val percentage = rssi.getInt("percentage")
            assertTrue("signal came back as $dbm dBm", dbm < 0 && dbm > -127)
            assertTrue("signal percentage came back as $percentage", percentage in 0..100)
        }
        println("wifi hardware=$hasWifi signal=${readings[SensorReader.WIFI_SIGNAL]}")
    }

    /**
     * The check that matters for Home Assistant: anything the discovery layer advertises
     * has to actually arrive. `cpuUsage` is the reason this exists -- two of the three test
     * devices deny `/proc/stat`, and the entity used to be discovered on them regardless.
     */
    @Test
    fun everyDiscoveredSensorEventuallyPublishesAValue() {
        val discovered = sensorReader.getSensors().mapNotNull { it.sensorType }.toSet()
        // Hardware sensors publish from their own listener rather than the timed cycle, and
        // a device may legitimately have none, so this covers the system readings.
        val systemReadings = setOf(
            SensorReader.BATTERY,
            SensorReader.CPU_USAGE,
            SensorReader.MEMORY_USAGE,
            SensorReader.STORAGE_FREE,
            SensorReader.UPTIME,
            SensorReader.IP_ADDRESS,
            SensorReader.APP_VERSION,
            SensorReader.ANDROID_VERSION,
        )
        val missing = discovered.intersect(systemReadings) - readings.keys
        assertTrue("discovered but never published: $missing", missing.isEmpty())
        println("cpuUsage discovered on this device: ${SensorReader.CPU_USAGE in discovered}")
    }

    companion object {
        private const val SENSOR_FREQUENCY_SECONDS = 60
        private const val POLL_MILLIS = 50L
        // Long enough to cover the second the CPU reading spends sampling.
        private const val QUIET_POLLS = 40
        private const val MAX_POLLS = 600
    }
}
