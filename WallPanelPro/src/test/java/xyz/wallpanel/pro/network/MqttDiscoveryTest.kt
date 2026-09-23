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

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.modules.SensorInfo
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.utils.ScreenUtils

/**
 * Covers the discovery payloads the application publishes to Home Assistant. These are a
 * wire format -- a wrong topic or a template that renders the wrong JSON produces an
 * entity that looks fine in the Home Assistant UI and silently does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class MqttDiscoveryTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var configuration: Configuration
    private lateinit var screenUtils: ScreenUtils
    private lateinit var discovery: MqttDiscovery

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        configuration = Configuration(context, preferences)
        configuration.mqttBaseTopic = "wallpanel/kitchen/"
        configuration.mqttClientId = "kitchen"
        setBool(R.string.key_setting_mqtt_discovery, true)
        screenUtils = ScreenUtils(context, configuration)
        discovery = MqttDiscovery(context, configuration, screenUtils)
    }

    private fun entities(sensors: List<SensorInfo> = emptyList()) =
        discovery.entities(sensors).associateBy { "${it.component}/${it.objectId}" }

    private fun config(key: String, sensors: List<SensorInfo> = emptyList()): JSONObject {
        val entity = entities(sensors)[key]
        assertNotNull("no entity published for $key", entity)
        return requireNotNull(entity!!.config) { "$key was published for removal" }
    }

    private fun setBool(keyRes: Int, value: Boolean) {
        preferences.edit().putBoolean(context.getString(keyRes), value).commit()
    }

    @Test
    fun `discovery topic follows the configured discovery and client id`() {
        val entity = DiscoveryEntity("button", "reload", JSONObject())
        assertEquals(
            "homeassistant/button/kitchen/reload/config",
            discovery.topicFor(entity)
        )
    }

    @Test
    fun `buttons press the same json a user would send by hand`() {
        val reload = config("button/reload")
        assertEquals("wallpanel/kitchen/command", reload.getString("command_topic"))
        assertEquals("""{"reload": true}""", reload.getString("payload_press"))
    }

    @Test
    fun `switches send json and read their state back off the state topic`() {
        val screen = config("switch/screen")
        assertEquals("wallpanel/kitchen/command", screen.getString("command_topic"))
        assertEquals("""{"wake": true}""", screen.getString("payload_on"))
        assertEquals("""{"wake": false}""", screen.getString("payload_off"))
        assertEquals("wallpanel/kitchen/state", screen.getString("state_topic"))
        assertEquals("ON", screen.getString("state_on"))
        assertEquals("OFF", screen.getString("state_off"))
        assertEquals(
            // The wake lock, not the display: releasing the lock doesn't blank the screen,
            // so reading screenOn would show the switch on again straight after turning it off.
            "{% if value_json.screenAwake %}ON{% else %}OFF{% endif %}",
            screen.getString("value_template")
        )
    }

    @Test
    fun `camera selects send the chosen option as json and read the request back`() {
        configuration.cameraEnabled = true
        val resolution = config("select/cameraResolution")
        assertEquals("wallpanel/kitchen/command", resolution.getString("command_topic"))
        assertEquals("""{"cameraResolution": {{ value | to_json }}}""", resolution.getString("command_template"))
        assertEquals("wallpanel/kitchen/state", resolution.getString("state_topic"))
        assertEquals("{{ value_json.cameraResolution }}", resolution.getString("value_template"))
        assertEquals(
            listOf("auto", "320x240", "640x480", "1280x720"),
            resolution.getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) } }
        )

        val fps = config("select/cameraFps")
        assertEquals("""{"cameraFps": {{ value | to_json }}}""", fps.getString("command_template"))
        assertEquals("{{ value_json.cameraFps }}", fps.getString("value_template"))
        assertEquals(
            listOf("auto", "5", "10", "15", "20", "25", "30"),
            fps.getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) } }
        )
    }

    @Test
    fun `camera selects are removed while the camera is off`() {
        configuration.cameraEnabled = false
        val all = entities()
        assertNull(all.getValue("select/cameraResolution").config)
        assertNull(all.getValue("select/cameraFps").config)
    }

    @Test
    fun `brightness slider covers the full android range`() {
        configuration.useScreenBrightness = true
        val brightness = config("number/brightness")
        assertEquals(0, brightness.getInt("min"))
        assertEquals(255, brightness.getInt("max"))
        assertEquals("""{"brightness": {{ value | int }}}""", brightness.getString("command_template"))
        // The configured level, not the live screen value -- the screensaver dims the
        // latter, which would drag the slider away from whatever was just set.
        assertEquals("{{ value_json.brightnessSetpoint }}", brightness.getString("value_template"))
    }

    @Test
    fun `the brightness slider is removed when the app does not control brightness`() {
        configuration.useScreenBrightness = false
        assertNull(entities()["number/brightness"]!!.config)
    }

    /**
     * Robolectric grants WRITE_SETTINGS, so this covers the setting being on while the
     * permission is missing -- which is what happens after a user revokes it from the
     * system settings screen, leaving a slider that would quietly do nothing.
     */
    @Test
    fun `the brightness slider is removed when the write settings permission is missing`() {
        configuration.useScreenBrightness = true
        assertNotNull(entities()["number/brightness"]!!.config)

        val withoutPermission = MqttDiscovery(context, configuration, object : ScreenUtils(context, configuration) {
            override fun canWriteScreenSetting(): Boolean = false
        })
        val brightness = withoutPermission.entities(emptyList())
            .first { it.component == "number" && it.objectId == "brightness" }
        assertNull(brightness.config)
    }

    @Test
    fun `volume slider is a percentage`() {
        val volume = config("number/volume")
        assertEquals(0, volume.getInt("min"))
        assertEquals(100, volume.getInt("max"))
        assertEquals("""{"volume": {{ value | int }}}""", volume.getString("command_template"))
    }

    @Test
    fun `text controls quote their value so a message with quotes stays valid json`() {
        val speak = config("text/speak")
        assertEquals("""{"speak": {{ value | to_json }}}""", speak.getString("command_template"))
        assertEquals(255, speak.getInt("max"))
        // Write-only: Home Assistant keeps the last value typed rather than reading one back.
        assertFalse(speak.has("state_topic"))
    }

    @Test
    fun `every entity carries availability and a unique id scoped to the client`() {
        for ((key, entity) in entities()) {
            val config = entity.config ?: continue
            if (entity.component == "tag") {
                // Tags have no availability or unique id in Home Assistant's schema.
                continue
            }
            assertEquals(
                "$key has the wrong availability topic",
                "wallpanel/kitchen/connection",
                config.getString("availability_topic")
            )
            assertTrue(
                "$key has a unique id that isn't scoped to the client",
                config.getString("unique_id").startsWith("wallpanel_kitchen_")
            )
            assertEquals(
                "$key is attached to the wrong device",
                "wallpanel_kitchen",
                config.getJSONObject("device").getJSONArray("identifiers").getString(0)
            )
        }
    }

    @Test
    fun `the device identifier serialises as a json array`() {
        val device = config("button/reload").getJSONObject("device")
        assertTrue(
            "identifiers was serialised as ${device}",
            device.toString().contains("\"identifiers\":[\"wallpanel_kitchen\"]")
        )
    }

    /**
     * The configs are retained, so switching discovery off has to publish a removal for
     * every entity. Returning an empty list instead would leave them all stranded in Home
     * Assistant with nothing left to take them down.
     */
    @Test
    fun `switching discovery off turns every entity into a removal`() {
        val advertised = discovery.entities(sampleSensors())
        assertTrue(advertised.any { it.config != null })

        setBool(R.string.key_setting_mqtt_discovery, false)
        val removals = discovery.entities(sampleSensors())
        assertEquals(
            "the removal list has to cover everything that was advertised",
            advertised.map { it.component to it.objectId },
            removals.map { it.component to it.objectId }
        )
        assertTrue("every entity must be a removal", removals.all { it.config == null })
    }

    @Test
    fun `everything is published until the user unticks something`() {
        setBool(R.string.key_setting_sensors_enabled, true)
        val published = entities(sampleSensors()).filterValues { it.config != null }
        assertTrue("controls", published.keys.any { it.startsWith("button/") })
        assertTrue("sensors", published.containsKey("sensor/battery"))
    }

    @Test
    fun `a control left out of the selection is removed`() {
        configuration.mqttDiscoveryControlIds = DiscoveryCatalog.ALL_CONTROL_IDS - "screen" - "volume"
        val published = entities()
        assertNull("screen was unticked", published["switch/screen"]!!.config)
        assertNull("volume was unticked", published["number/volume"]!!.config)
        // Everything still ticked carries on as before.
        assertNotNull(published["switch/screensaver"]!!.config)
        assertNotNull(published["button/reload"]!!.config)
    }

    @Test
    fun `a sensor left out of the selection is removed`() {
        setBool(R.string.key_setting_sensors_enabled, true)
        configuration.mqttDiscoverySensorIds =
            DiscoveryCatalog.ALL_SENSOR_IDS - "uptime" - "charging"
        val published = entities(sampleSensors() + SensorInfo("uptime", "s", "duration", "Uptime"))
        assertNull("uptime was unticked", published["sensor/uptime"]!!.config)
        assertNull("charging was unticked", published["binary_sensor/charging"]!!.config)
        assertNotNull(published["sensor/battery"]!!.config)
    }

    @Test
    fun `unticking everything removes everything without dropping it from the list`() {
        configuration.mqttDiscoveryControlIds = emptySet()
        configuration.mqttDiscoverySensorIds = emptySet()
        val published = entities(sampleSensors())
        assertTrue("entities must still be listed so their configs get cleared",
            published.isNotEmpty())
        assertTrue("all must be removals", published.values.all { it.config == null })
    }

    /**
     * The picker offers the catalogue and the discovery code checks against it, so an id
     * in one and not the other is either an entity that can't be switched off or a
     * setting that does nothing.
     */
    @Test
    fun `the catalogue matches the entities that are actually built`() {
        setBool(R.string.key_setting_sensors_enabled, true)
        configuration.cameraEnabled = true
        setBool(R.string.key_setting_camera_motionenabled, true)
        setBool(R.string.key_setting_camera_faceenabled, true)
        setBool(R.string.key_setting_camera_qrcodeenabled, true)
        setBool(R.string.key_setting_http_shellenabled, true)
        configuration.useScreenBrightness = true

        val built = discovery.entities(allCatalogSensors()).map { it.objectId }.toSet()
        val catalogued = DiscoveryCatalog.ALL_CONTROL_IDS + DiscoveryCatalog.ALL_SENSOR_IDS +
                DiscoveryCatalog.RETIRED_SENSOR_IDS
        assertEquals("catalogued but never built", emptySet<String>(), catalogued - built)
        assertEquals("built but not in the catalogue", emptySet<String>(), built - catalogued)
    }

    /**
     * A retired sensor is not in the catalogue any more, so nothing would list it and its
     * retained config would sit on the broker forever. It has to keep being published as
     * a removal instead.
     */
    @Test
    fun `a retired sensor is always published as a removal`() {
        setBool(R.string.key_setting_sensors_enabled, true)
        // Passes over an empty list, which is what holds until a sensor is retired.
        for (objectId in DiscoveryCatalog.RETIRED_SENSOR_IDS) {
            val entity = entities(sampleSensors())["sensor/$objectId"]
            assertNotNull("$objectId must still be listed", entity)
            assertNull("$objectId must be a removal", entity!!.config)
            assertFalse(
                "$objectId must not be offered in the picker",
                objectId in DiscoveryCatalog.ALL_SENSOR_IDS
            )
        }
    }

    @Test
    fun `unique ids do not collide across entities`() {
        val ids = discovery.entities(sampleSensors())
            .mapNotNull { it.config?.optString("unique_id") }
            .filter { it.isNotEmpty() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `controls are removed when the controls setting is off`() {
        setBool(R.string.key_setting_mqtt_discovery_controls, false)
        val published = entities()
        // Still listed, so the caller clears the retained config rather than orphaning it.
        assertNotNull(published["button/reload"])
        assertNull(published["button/reload"]!!.config)
        assertNull(published["switch/screen"]!!.config)
        assertNull(published["number/volume"]!!.config)
        assertNull(published["text/speak"]!!.config)
    }

    @Test
    fun `the shell control only appears once shell commands are enabled`() {
        assertNull(entities()["text/shell"]!!.config)
        assertNull(entities()["sensor/shellResult"]!!.config)

        setBool(R.string.key_setting_http_shellenabled, true)
        val shell = config("text/shell")
        assertEquals("""{"shell": {{ value | to_json }}}""", shell.getString("command_template"))
        val result = config("sensor/shellResult")
        assertEquals("wallpanel/kitchen/sensor/shell", result.getString("state_topic"))
        assertEquals("wallpanel/kitchen/sensor/shell", result.getString("json_attributes_topic"))
    }

    @Test
    fun `sensors are removed when sensor publishing is off`() {
        setBool(R.string.key_setting_sensors_enabled, true)
        assertNotNull(config("sensor/battery", sampleSensors()))

        setBool(R.string.key_setting_sensors_enabled, false)
        assertNull(entities(sampleSensors())["sensor/battery"]!!.config)
        assertNull(entities(sampleSensors())["binary_sensor/charging"]!!.config)
    }

    @Test
    fun `string sensors are not cast to a float`() {
        setBool(R.string.key_setting_sensors_enabled, true)
        val ip = config("sensor/ipAddress", sampleSensors())
        assertEquals("{{ value_json.value }}", ip.getString("value_template"))
        assertEquals("diagnostic", ip.getString("entity_category"))
        assertFalse(ip.has("unit_of_measurement"))

        val battery = config("sensor/battery", sampleSensors())
        assertEquals("{{ value_json.value | float }}", battery.getString("value_template"))
        assertEquals("measurement", battery.getString("state_class"))
        assertEquals("battery", battery.getString("device_class"))
    }

    @Test
    fun `the current url is reported as a sensor separately from the navigate control`() {
        val currentUrl = config("sensor/currentUrl")
        assertEquals("wallpanel/kitchen/state", currentUrl.getString("state_topic"))
        // Sliced to the longest state Home Assistant accepts. A dashboard URL carrying
        // query parameters runs past it, and an over-length state is rejected rather than
        // trimmed, so the entity would stop updating instead of showing a shortened value.
        assertEquals(
            "{{ (value_json.currentUrl or '')[:255] }}",
            currentUrl.getString("value_template")
        )
        assertEquals("Current URL", currentUrl.getString("name"))
        assertEquals("Navigate URL", config("text/url").getString("name"))
    }

    @Test
    fun `camera entities are removed while the camera is off`() {
        val published = entities()
        assertNull(published["binary_sensor/motion"]!!.config)
        assertNull(published["binary_sensor/face"]!!.config)
        assertNull(published["tag/qr"]!!.config)
    }

    @Test
    fun `motion is discovered once the camera and motion detection are both on`() {
        configuration.cameraEnabled = true
        setBool(R.string.key_setting_camera_motionenabled, true)
        val motion = config("binary_sensor/motion")
        assertEquals("wallpanel/kitchen/sensor/motion", motion.getString("state_topic"))
        assertEquals("motion", motion.getString("device_class"))
        assertEquals("ON", motion.getString("payload_on"))
        assertEquals(
            "{% if value_json.value %}ON{% else %}OFF{% endif %}",
            motion.getString("value_template")
        )
    }

    @Test
    fun `legacy naming prefixes the device name`() {
        assertEquals("Reload Page", config("button/reload").getString("name"))
        setBool(R.string.key_setting_mqtt_discovery_legacy_entities, true)
        val name = config("button/reload").getString("name")
        assertEquals("${configuration.mqttDiscoveryDeviceName} Reload Page", name)
    }

    /**
     * The reading list only carries what a device is managing to report right now. Driving
     * removals off it would mean a sensor that stops being readable simply disappears,
     * leaving its retained config on the broker with nothing left to clear it.
     */
    @Test
    fun `a sensor the device is not reporting is still listed so its config gets cleared`() {
        setBool(R.string.key_setting_sensors_enabled, true)
        val published = entities(sampleSensors())
        val wifi = published["sensor/wifiSignal"]
        assertNotNull("an unreported sensor must still be listed", wifi)
        assertNull("and must be a removal", wifi!!.config)
        assertNotNull("while a reported one carries a config", published["sensor/battery"]!!.config)
    }

    @Test
    fun `the shell result follows the shell feature rather than the controls toggle`() {
        setBool(R.string.key_setting_http_shellenabled, true)
        setBool(R.string.key_setting_mqtt_discovery_controls, false)
        // The input box is a control, so it goes when controls do.
        assertNull(entities()["text/shell"]!!.config)
        // The result is reported for commands sent over HTTP too, so it stays.
        assertNotNull(entities()["sensor/shellResult"]!!.config)
    }

    @Test
    fun `a device that never advertised anything publishes nothing while discovery is off`() {
        setBool(R.string.key_setting_mqtt_discovery, false)
        val payloads = discovery.payloads(emptyList())
        assertTrue(discovery.advertisedTopics(payloads).isEmpty())
        assertTrue(discovery.messages(payloads, previouslyAdvertised = emptySet()).isEmpty())
    }

    @Test
    fun `an upgrade with discovery off clears configs no version recorded`() {
        setBool(R.string.key_setting_mqtt_discovery, false)
        val payloads = discovery.payloads(emptyList())
        val messages = discovery.messages(payloads, previouslyAdvertised = emptySet(), sweepUnrecorded = true)
        // Versions before the advertised-topic list published retained and cleared
        // unretained, so their configs are still on the broker under these same topics.
        assertEquals("", messages["homeassistant/button/kitchen/reload/config"])
        assertEquals("", messages["homeassistant/sensor/kitchen/battery/config"])
        assertTrue(messages.values.all { it.isEmpty() })
    }

    @Test
    fun `switching discovery off clears what was advertised`() {
        val advertised = discovery.advertisedTopics(discovery.payloads(emptyList()))
        assertTrue(advertised.contains("homeassistant/button/kitchen/reload/config"))

        setBool(R.string.key_setting_mqtt_discovery, false)
        val messages = discovery.messages(discovery.payloads(emptyList()), advertised)
        assertEquals("", messages["homeassistant/button/kitchen/reload/config"])
    }

    @Test
    fun `configs published under an old client id are cleared`() {
        val advertised = discovery.advertisedTopics(discovery.payloads(emptyList()))

        configuration.mqttClientId = "hallway"
        val payloads = discovery.payloads(emptyList())
        val messages = discovery.messages(payloads, advertised)
        assertEquals("", messages["homeassistant/button/kitchen/reload/config"])
        assertTrue(messages["homeassistant/button/hallway/reload/config"]!!.isNotEmpty())
    }

    private fun sampleSensors(): List<SensorInfo> = listOf(
        SensorInfo("battery", "%", "battery", "Battery Level", "measurement"),
        SensorInfo("ipAddress", null, null, "IP Address", null, numeric = false, diagnostic = true),
    )

    /**
     * Every sensor the catalogue offers, as if a device reported all of them at once. No
     * real device does; this is what makes the catalogue comparison exhaustive.
     */
    private fun allCatalogSensors(): List<SensorInfo> {
        val notFromTheSensorReader = setOf(
            MqttDiscovery.OBJECT_CURRENT_URL, MqttDiscovery.OBJECT_SHELL_RESULT,
            MqttDiscovery.OBJECT_MOTION, MqttDiscovery.OBJECT_FACE, MqttDiscovery.OBJECT_QR,
            "battery", "charging", "acPlugged", "usbPlugged",
        )
        return DiscoveryCatalog.SENSORS
            .filterNot { it.objectId in notFromTheSensorReader }
            .map { SensorInfo(it.objectId, null, null, it.objectId) } +
            SensorInfo("battery", "%", "battery", "Battery Level", "measurement")
    }
}
