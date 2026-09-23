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
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.modules.CameraProfile
import xyz.wallpanel.pro.modules.CameraResolution
import xyz.wallpanel.pro.modules.SensorInfo
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.utils.ScreenUtils
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_BRIGHTNESS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CAMERA
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CAMERA_FPS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CAMERA_RESOLUTION
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CLEAR_CACHE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RELAUNCH
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RELOAD
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RESTART_APP
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SCREENSAVER
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_FACE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_MOTION
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_QR_CODE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_SHELL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SETTINGS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SHELL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SPEAK
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_STATE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_TOAST
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_URL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_VOLUME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_WAKE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_BRIGHTNESS_SETPOINT
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_CAMERA
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_CAMERA_FPS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_CAMERA_RESOLUTION
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_CURRENT_URL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_SCREENSAVER_ON
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_SCREEN_AWAKE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_VOLUME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.TOPIC_COMMAND
import xyz.wallpanel.pro.utils.MqttUtils.Companion.TOPIC_CONNECTION
import xyz.wallpanel.pro.utils.MqttUtils.Companion.VALUE
import javax.inject.Inject

/**
 * One Home Assistant discovery entity. A null [config] means the entity should be
 * removed rather than published, which happens when the feature behind it is switched
 * off. Both cases are published to the same topic, so callers never have to keep a
 * separate list of what to clean up -- the entity list always describes the full set.
 */
data class DiscoveryEntity(
    val component: String,
    val objectId: String,
    val config: JSONObject?,
)

/**
 * Builds the Home Assistant MQTT discovery payloads for everything the application
 * publishes and everything it can be told to do.
 *
 * Controls are ordinary discovery entities pointed at the single command topic the
 * application already subscribes to, with a `command_template` (or `payload_press` for
 * buttons) that renders the same JSON a user would send by hand. Nothing here is a new
 * control path: if a command can't be sent over MQTT already, it isn't discoverable.
 */
class MqttDiscovery @Inject
constructor(
    private val context: Context,
    private val configuration: Configuration,
    private val screenUtils: ScreenUtils,
) {

    private val baseTopic: String
        get() = configuration.mqttBaseTopic

    private val commandTopic: String
        get() = "$baseTopic$TOPIC_COMMAND"

    private val stateTopic: String
        get() = "$baseTopic$COMMAND_STATE"

    private val availabilityTopic: String
        get() = "$baseTopic$TOPIC_CONNECTION"

    /**
     * The full set of entities, in publish order. Entities whose feature is turned off
     * carry a null config so the caller removes them from Home Assistant.
     *
     * Switching discovery off entirely is a removal of everything rather than an empty
     * list: the configs already on the broker are retained, so leaving them alone would
     * strand every entity in Home Assistant instead of taking them down.
     */
    fun entities(sensors: List<SensorInfo>): List<DiscoveryEntity> {
        // Read once and carried down: each one is a preference lookup and a set
        // subtraction, and the builders below ask about them for every entity.
        val enabledSensors = configuration.mqttDiscoverySensorIds
        val enabledControls = configuration.mqttDiscoveryControlIds
        val all = sensorEntities(sensors, enabledSensors) + cameraEntities(enabledSensors) +
                controlEntities(enabledControls, enabledSensors)
        return if (configuration.mqttDiscovery) all else all.map { it.copy(config = null) }
    }

    private fun sensorEntities(sensors: List<SensorInfo>, enabledSensors: Set<String>): List<DiscoveryEntity> {
        val entities = mutableListOf<DiscoveryEntity>()

        // Sensors this version no longer has. Their configs are retained on the broker
        // from whichever version last advertised them, so they are published as removals
        // for good rather than simply dropped.
        for (objectId in DiscoveryCatalog.RETIRED_SENSOR_IDS) {
            entities += sensor(objectId = objectId, config = null)
        }

        // The battery level itself comes through the sensor loop below like any other
        // reading; only the extra flags carried in its payload need defining here.
        entities += batteryFlag(USB_PLUGGED, R.string.mqtt_sensor_usb_plugged, "power", sensorOn(USB_PLUGGED, enabledSensors))
        entities += batteryFlag(AC_PLUGGED, R.string.mqtt_sensor_ac_plugged, "power", sensorOn(AC_PLUGGED, enabledSensors))
        entities += batteryFlag(CHARGING, R.string.mqtt_sensor_charging, "battery_charging", sensorOn(CHARGING, enabledSensors))

        // Walked from the catalogue rather than from the readings the device happens to be
        // producing. A sensor whose hardware or permission has gone away drops out of the
        // reading list, and an entity that isn't listed is one whose retained config never
        // gets cleared -- it would sit in Home Assistant on its last value forever.
        val reported = sensors.mapNotNull { info -> info.sensorType?.let { type -> type to info } }.toMap()
        for (objectId in DiscoveryCatalog.READER_SENSOR_IDS) {
            val sensor = reported[objectId]
            entities += sensor(
                objectId = objectId,
                config = if (sensor == null || !sensorOn(objectId, enabledSensors)) null else sensorConfig(
                    displayName = sensor.displayName.orEmpty(),
                    topic = "$COMMAND_SENSOR$objectId",
                    field = VALUE,
                    objectId = objectId,
                    deviceClass = sensor.deviceClass,
                    unit = sensor.unit,
                    stateClass = sensor.stateClass,
                    numeric = sensor.numeric,
                    diagnostic = sensor.diagnostic,
                )
            )
        }

        // Reported from the application state rather than a sensor topic, but it reads as
        // a sensor in Home Assistant like the rest of them.
        entities += sensor(
            objectId = OBJECT_CURRENT_URL,
            config = (OBJECT_CURRENT_URL in enabledSensors).then { sensorConfig(
                displayName = context.getString(R.string.mqtt_sensor_current_url),
                topic = COMMAND_STATE,
                field = STATE_CURRENT_URL,
                objectId = STATE_CURRENT_URL,
                deviceClass = null,
                unit = null,
                stateClass = null,
                numeric = false,
                diagnostic = true,
                // A dashboard URL carrying query parameters runs past the longest state
                // Home Assistant accepts, and an over-length state is rejected outright
                // rather than trimmed, so the entity would simply stop updating.
                valueTemplate = "{{ (value_json.$STATE_CURRENT_URL or '')[:$TEXT_MAX_LENGTH] }}",
            ) }
        )

        return entities
    }

    /**
     * Whether a sensor should be advertised: publishing has to be on, and the user has to
     * have left it ticked in the settings.
     */
    private fun sensorOn(objectId: String, enabledSensors: Set<String>): Boolean {
        return configuration.sensorsEnabled && objectId in enabledSensors
    }

    private fun batteryFlag(
        field: String,
        displayNameRes: Int,
        deviceClass: String,
        enabled: Boolean,
    ): DiscoveryEntity {
        return DiscoveryEntity(
            component = BINARY_SENSOR,
            objectId = field,
            config = enabled.then {
                binarySensorConfig(
                    displayName = context.getString(displayNameRes),
                    topic = "${COMMAND_SENSOR}$BATTERY",
                    field = field,
                    objectId = field,
                    deviceClass = deviceClass,
                )
            }
        )
    }

    private fun cameraEntities(enabledSensors: Set<String>): List<DiscoveryEntity> {
        val cameraOn = configuration.cameraEnabled
        return listOf(
            DiscoveryEntity(
                component = BINARY_SENSOR,
                objectId = OBJECT_FACE,
                config = (cameraOn && configuration.cameraFaceEnabled && OBJECT_FACE in enabledSensors).then {
                    binarySensorConfig(
                        displayName = context.getString(R.string.mqtt_sensor_face_detected),
                        topic = COMMAND_SENSOR_FACE,
                        field = VALUE,
                        objectId = OBJECT_FACE,
                        deviceClass = "occupancy",
                    )
                }
            ),
            DiscoveryEntity(
                component = BINARY_SENSOR,
                objectId = OBJECT_MOTION,
                config = (cameraOn && configuration.cameraMotionEnabled && OBJECT_MOTION in enabledSensors).then {
                    binarySensorConfig(
                        displayName = context.getString(R.string.mqtt_sensor_motion_detected),
                        topic = COMMAND_SENSOR_MOTION,
                        field = VALUE,
                        objectId = OBJECT_MOTION,
                        deviceClass = "motion",
                    )
                }
            ),
            DiscoveryEntity(
                component = "tag",
                objectId = OBJECT_QR,
                config = (cameraOn && configuration.cameraQRCodeEnabled && OBJECT_QR in enabledSensors).then {
                    JSONObject().apply {
                        put("topic", "$baseTopic$COMMAND_SENSOR_QR_CODE")
                        put("value_template", "{{ value_json.$VALUE }}")
                        put("device", deviceDef())
                    }
                }
            ),
        )
    }

    /**
     * Buttons, switches, sliders and text boxes for the commands the application already
     * accepts. Each entity maps onto one command, so the set here matches the command
     * table one for one.
     */
    private fun controlEntities(enabledControls: Set<String>, enabledSensors: Set<String>): List<DiscoveryEntity> {
        val entities = mutableListOf<DiscoveryEntity>()

        entities += button(COMMAND_RELOAD, R.string.mqtt_control_reload, """{"$COMMAND_RELOAD": true}""", controlOn(COMMAND_RELOAD, enabledControls))
        entities += button(COMMAND_CLEAR_CACHE, R.string.mqtt_control_clear_cache, """{"$COMMAND_CLEAR_CACHE": true}""", controlOn(COMMAND_CLEAR_CACHE, enabledControls))
        entities += button(COMMAND_RELAUNCH, R.string.mqtt_control_relaunch, """{"$COMMAND_RELAUNCH": true}""", controlOn(COMMAND_RELAUNCH, enabledControls))
        entities += button(COMMAND_WAKE, R.string.mqtt_control_wake, """{"$COMMAND_WAKE": true}""", controlOn(COMMAND_WAKE, enabledControls))
        entities += button(COMMAND_RESTART_APP, R.string.mqtt_control_restart_app, """{"$COMMAND_RESTART_APP": true}""", controlOn(COMMAND_RESTART_APP, enabledControls), configCategory = true)
        entities += button(COMMAND_SETTINGS, R.string.mqtt_control_settings, """{"$COMMAND_SETTINGS": true}""", controlOn(COMMAND_SETTINGS, enabledControls), configCategory = true)

        entities += switch(
            objectId = OBJECT_SCREEN,
            displayNameRes = R.string.mqtt_control_screen,
            onPayload = """{"$COMMAND_WAKE": true}""",
            offPayload = """{"$COMMAND_WAKE": false}""",
            stateField = STATE_SCREEN_AWAKE,
            enabled = controlOn(OBJECT_SCREEN, enabledControls),
        )
        entities += switch(
            objectId = COMMAND_CAMERA,
            displayNameRes = R.string.mqtt_control_camera,
            onPayload = """{"$COMMAND_CAMERA": true}""",
            offPayload = """{"$COMMAND_CAMERA": false}""",
            stateField = STATE_CAMERA,
            enabled = controlOn(COMMAND_CAMERA, enabledControls),
        )
        // Only while the camera is on, like the motion sensor: with it off there is nothing
        // for them to change.
        entities += select(
            objectId = COMMAND_CAMERA_RESOLUTION,
            displayNameRes = R.string.mqtt_control_camera_resolution,
            command = COMMAND_CAMERA_RESOLUTION,
            stateField = STATE_CAMERA_RESOLUTION,
            options = listOf(CameraProfile.AUTO) + CameraResolution.SUPPORTED.map { it.toString() },
            enabled = controlOn(COMMAND_CAMERA_RESOLUTION, enabledControls) && configuration.cameraEnabled,
        )
        entities += select(
            objectId = COMMAND_CAMERA_FPS,
            displayNameRes = R.string.mqtt_control_camera_fps,
            command = COMMAND_CAMERA_FPS,
            stateField = STATE_CAMERA_FPS,
            options = listOf(CameraProfile.AUTO) + CAMERA_FPS_OPTIONS.map { it.toString() },
            enabled = controlOn(COMMAND_CAMERA_FPS, enabledControls) && configuration.cameraEnabled,
        )
        entities += switch(
            objectId = COMMAND_SCREENSAVER,
            displayNameRes = R.string.mqtt_control_screensaver,
            onPayload = """{"$COMMAND_SCREENSAVER": true}""",
            offPayload = """{"$COMMAND_SCREENSAVER": false}""",
            stateField = STATE_SCREENSAVER_ON,
            enabled = controlOn(COMMAND_SCREENSAVER, enabledControls),
        )

        // Brightness commands are ignored unless the application is set to control the
        // screen brightness and still holds the permission to write it -- that permission
        // is granted from a system settings screen and can be taken away again, so the
        // setting alone isn't proof the slider would do anything.
        entities += number(
            objectId = COMMAND_BRIGHTNESS,
            displayNameRes = R.string.mqtt_control_brightness,
            command = COMMAND_BRIGHTNESS,
            stateField = STATE_BRIGHTNESS_SETPOINT,
            min = 0,
            max = 255,
            enabled = controlOn(COMMAND_BRIGHTNESS, enabledControls) && configuration.useScreenBrightness && screenUtils.canWriteScreenSetting(),
        )
        entities += number(
            objectId = COMMAND_VOLUME,
            displayNameRes = R.string.mqtt_control_volume,
            command = COMMAND_VOLUME,
            stateField = STATE_VOLUME,
            min = 0,
            max = 100,
            enabled = controlOn(COMMAND_VOLUME, enabledControls),
        )

        entities += text(COMMAND_URL, R.string.mqtt_control_url, COMMAND_URL, controlOn(COMMAND_URL, enabledControls))
        entities += text(COMMAND_SPEAK, R.string.mqtt_control_speak, COMMAND_SPEAK, controlOn(COMMAND_SPEAK, enabledControls))
        entities += text(COMMAND_TOAST, R.string.mqtt_control_toast, COMMAND_TOAST, controlOn(COMMAND_TOAST, enabledControls))

        // The shell command carries the same risk over MQTT as it does over HTTP, so its
        // entities follow the same opt-in toggle rather than appearing for everyone.
        val shellOn = controlOn(COMMAND_SHELL, enabledControls) && configuration.httpShellEnabled
        entities += text(COMMAND_SHELL, R.string.mqtt_control_shell, COMMAND_SHELL, shellOn, configCategory = true)
        // The result follows the shell feature and its own tick in the sensor list. It is
        // reported for commands sent over HTTP too, so it does not depend on the input box
        // being published.
        entities += sensor(
            objectId = OBJECT_SHELL_RESULT,
            config = (configuration.httpShellEnabled && OBJECT_SHELL_RESULT in enabledSensors).then {
                sensorConfig(
                    displayName = context.getString(R.string.mqtt_sensor_shell_result),
                    topic = COMMAND_SENSOR_SHELL,
                    field = VALUE,
                    objectId = OBJECT_SHELL_RESULT,
                    deviceClass = null,
                    unit = null,
                    stateClass = null,
                    numeric = false,
                    diagnostic = true,
                    attributesTopic = COMMAND_SENSOR_SHELL,
                )
            }
        )

        return entities
    }

    /**
     * Whether a control should be advertised: controls have to be on as a whole, and the
     * user has to have left this one ticked in the settings.
     */
    private fun controlOn(objectId: String, enabledControls: Set<String>): Boolean {
        return configuration.mqttDiscoveryControls && objectId in enabledControls
    }

    private fun button(
        objectId: String,
        displayNameRes: Int,
        payload: String,
        enabled: Boolean,
        configCategory: Boolean = false,
    ): DiscoveryEntity {
        return DiscoveryEntity(
            component = "button",
            objectId = objectId,
            config = enabled.then {
                baseConfig(context.getString(displayNameRes), objectId).apply {
                    put("command_topic", commandTopic)
                    // Buttons have no command template in Home Assistant, the press
                    // payload is published verbatim.
                    put("payload_press", payload)
                    if (configCategory) {
                        put("entity_category", "config")
                    }
                }
            }
        )
    }

    private fun switch(
        objectId: String,
        displayNameRes: Int,
        onPayload: String,
        offPayload: String,
        stateField: String,
        enabled: Boolean,
    ): DiscoveryEntity {
        return DiscoveryEntity(
            component = "switch",
            objectId = objectId,
            config = enabled.then {
                baseConfig(context.getString(displayNameRes), objectId).apply {
                    put("command_topic", commandTopic)
                    put("payload_on", onPayload)
                    put("payload_off", offPayload)
                    put("state_topic", stateTopic)
                    put("value_template", onOffTemplate(stateField))
                    put("state_on", ON)
                    put("state_off", OFF)
                }
            }
        )
    }

    private fun number(
        objectId: String,
        displayNameRes: Int,
        command: String,
        stateField: String,
        min: Int,
        max: Int,
        enabled: Boolean,
    ): DiscoveryEntity {
        return DiscoveryEntity(
            component = "number",
            objectId = objectId,
            config = enabled.then {
                baseConfig(context.getString(displayNameRes), objectId).apply {
                    put("command_topic", commandTopic)
                    put("command_template", """{"$command": {{ value | int }}}""")
                    put("state_topic", stateTopic)
                    put("value_template", "{{ value_json.$stateField }}")
                    put("min", min)
                    put("max", max)
                    put("step", 1)
                    put("mode", "slider")
                }
            }
        )
    }

    /**
     * A drop-down list. The value is sent as a JSON string, and the state field has to hold
     * one of [options] for Home Assistant to show it.
     */
    private fun select(
        objectId: String,
        displayNameRes: Int,
        command: String,
        stateField: String,
        options: List<String>,
        enabled: Boolean,
    ): DiscoveryEntity {
        return DiscoveryEntity(
            component = "select",
            objectId = objectId,
            config = enabled.then {
                baseConfig(context.getString(displayNameRes), objectId).apply {
                    put("command_topic", commandTopic)
                    put("command_template", """{"$command": {{ value | to_json }}}""")
                    put("state_topic", stateTopic)
                    put("value_template", "{{ value_json.$stateField }}")
                    put("options", JSONArray(options))
                    put("entity_category", "config")
                }
            }
        )
    }

    /**
     * A write-only input box. Home Assistant treats a text entity without a state topic
     * as optimistic and keeps whatever was last typed, which is what these commands want
     * -- there is no "current toast message" to read back.
     */
    private fun text(
        objectId: String,
        displayNameRes: Int,
        command: String,
        enabled: Boolean,
        configCategory: Boolean = false,
    ): DiscoveryEntity {
        return DiscoveryEntity(
            component = "text",
            objectId = objectId,
            config = enabled.then {
                baseConfig(context.getString(displayNameRes), objectId).apply {
                    put("command_topic", commandTopic)
                    put("command_template", """{"$command": {{ value | to_json }}}""")
                    put("max", TEXT_MAX_LENGTH)
                    if (configCategory) {
                        put("entity_category", "config")
                    }
                }
            }
        )
    }

    private fun sensor(objectId: String, config: JSONObject?): DiscoveryEntity {
        return DiscoveryEntity(component = SENSOR, objectId = objectId, config = config)
    }

    private fun sensorConfig(
        displayName: String,
        topic: String,
        field: String,
        objectId: String,
        deviceClass: String?,
        unit: String?,
        stateClass: String?,
        numeric: Boolean = true,
        diagnostic: Boolean = false,
        attributesTopic: String? = null,
        valueTemplate: String? = null,
    ): JSONObject {
        return baseConfig(displayName, objectId).apply {
            put("state_topic", "$baseTopic$topic")
            put("value_template", valueTemplate
                ?: if (numeric) "{{ value_json.$field | float }}" else "{{ value_json.$field }}")
            unit?.let { put("unit_of_measurement", it) }
            deviceClass?.let { put("device_class", it) }
            stateClass?.let { put("state_class", it) }
            attributesTopic?.let { put("json_attributes_topic", "$baseTopic$it") }
            if (diagnostic) {
                put("entity_category", "diagnostic")
            }
        }
    }

    private fun binarySensorConfig(
        displayName: String,
        topic: String,
        field: String,
        objectId: String,
        deviceClass: String,
    ): JSONObject {
        return baseConfig(displayName, objectId).apply {
            put("state_topic", "$baseTopic$topic")
            put("value_template", onOffTemplate(field))
            put("payload_on", ON)
            put("payload_off", OFF)
            put("device_class", deviceClass)
        }
    }

    /**
     * Renders a JSON boolean as ON/OFF rather than letting Home Assistant compare the
     * template's `True`/`False` output against a payload, which only lines up by accident
     * of how Python stringifies booleans.
     */
    private fun onOffTemplate(field: String): String {
        return "{% if value_json.$field %}$ON{% else %}$OFF{% endif %}"
    }

    private fun baseConfig(displayName: String, objectId: String): JSONObject {
        return JSONObject().apply {
            if (configuration.mqttLegacyDiscoveryEntities) {
                put("name", "${configuration.mqttDiscoveryDeviceName} $displayName")
            } else {
                put("name", displayName)
            }
            put("origin", originDef())
            put("unique_id", "wallpanel_${configuration.mqttClientId}_$objectId")
            put("device", deviceDef())
            put("availability_topic", availabilityTopic)
        }
    }

    private fun originDef(): JSONObject {
        return JSONObject().apply {
            put("name", "WallPanel")
            put("sw", applicationVersion)
            put("url", "https://wallpanel.xyz")
        }
    }

    private fun deviceDef(): JSONObject {
        return JSONObject().apply {
            // A plain Kotlin list here serialises as the string "[wallpanel_x]" rather
            // than a JSON array, because org.json only writes types it recognises.
            put("identifiers", JSONArray().put("wallpanel_${configuration.mqttClientId}"))
            put("name", configuration.mqttDiscoveryDeviceName)
            put("manufacturer", Build.MANUFACTURER.lowercase().replaceFirstChar { it.uppercase() })
            put("model", Build.MODEL)
            put("sw_version", applicationVersion)
        }
    }

    // Read once: every entity carries it twice and the installed version can't change
    // while the process is alive.
    private val applicationVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "Could not read the application version")
            ""
        }
    }

    /**
     * The discovery topic an entity is published to. Removing an entity means publishing
     * an empty retained payload here.
     */
    fun topicFor(entity: DiscoveryEntity): String {
        return "${configuration.mqttDiscoveryTopic}/${entity.component}/${configuration.mqttClientId}/${entity.objectId}/config"
    }

    /**
     * Every entity's discovery topic mapped to the retained payload it should hold, with an
     * empty payload for an entity that is being removed.
     */
    fun payloads(sensors: List<SensorInfo>): Map<String, String> {
        return entities(sensors).associate { topicFor(it) to (it.config?.toString() ?: "") }
    }

    /**
     * The retained messages that bring the broker in line with [payloads], given the topics
     * that held a config after the last publish.
     *
     * - Nothing is sent when nothing is advertised now and nothing was before, so a device
     *   that doesn't use discovery doesn't publish removals on every connect.
     * - A topic advertised before but missing from [payloads] was published under a
     *   discovery prefix or client id that has since changed, and gets an empty payload so
     *   the old device doesn't stay behind in Home Assistant.
     * - [sweepUnrecorded] skips that first rule. Versions before this one published their
     *   configs retained but cleared them unretained, which left them on the broker, and
     *   they were never recorded either. Sending the empty payload for every topic once
     *   takes those down; on a device that never had discovery it clears topics that hold
     *   nothing anyway.
     */
    fun messages(
        payloads: Map<String, String>,
        previouslyAdvertised: Set<String>,
        sweepUnrecorded: Boolean = false
    ): Map<String, String> {
        if (!sweepUnrecorded && previouslyAdvertised.isEmpty() && advertisedTopics(payloads).isEmpty()) {
            return emptyMap()
        }
        val messages = LinkedHashMap(payloads)
        for (topic in previouslyAdvertised) {
            if (topic !in messages) {
                messages[topic] = ""
            }
        }
        return messages
    }

    fun advertisedTopics(payloads: Map<String, String>): Set<String> {
        return payloads.filterValues { it.isNotEmpty() }.keys
    }

    private inline fun Boolean.then(block: () -> JSONObject): JSONObject? {
        return if (this) block() else null
    }

    companion object {
        // Object ids that are not simply a sensor name, referenced by DiscoveryCatalog.
        const val OBJECT_CURRENT_URL = STATE_CURRENT_URL
        const val OBJECT_SHELL_RESULT = "shellResult"
        const val OBJECT_MOTION = "motion"
        const val OBJECT_FACE = "face"
        const val OBJECT_QR = "qr"
        const val OBJECT_SCREEN = "screen"

        private const val SENSOR = "sensor"
        private const val BINARY_SENSOR = "binary_sensor"
        private const val BATTERY = "battery"
        private const val CHARGING = "charging"
        private const val AC_PLUGGED = "acPlugged"
        private const val USB_PLUGGED = "usbPlugged"
        private const val ON = "ON"
        private const val OFF = "OFF"

        // The frame rates the Home Assistant select offers. The command takes any whole
        // number from 1 to 30.
        private val CAMERA_FPS_OPTIONS = listOf(5, 10, 15, 20, 25, 30)

        // Home Assistant rejects text entities longer than this.
        private const val TEXT_MAX_LENGTH = 255
    }
}
