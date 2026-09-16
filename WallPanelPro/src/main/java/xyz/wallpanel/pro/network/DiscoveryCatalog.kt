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
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.modules.SensorReader

/**
 * One entity a user can choose to publish, or not.
 */
data class DiscoveryChoice(val objectId: String, val displayNameRes: Int)

/**
 * Every entity WallPanel is able to advertise to Home Assistant.
 *
 * This is the list the settings screen offers and the list [MqttDiscovery] checks against,
 * so a new entity has to be added here as well as built there -- otherwise it either can't
 * be switched off, or can't be switched on.
 *
 * Being listed is not a promise the entity appears: a control still needs its feature
 * enabled, and a sensor still needs hardware that reports it. This only ever takes
 * entities away.
 */
object DiscoveryCatalog {

    val CONTROLS = listOf(
        DiscoveryChoice("reload", R.string.mqtt_control_reload),
        DiscoveryChoice("clearCache", R.string.mqtt_control_clear_cache),
        DiscoveryChoice("relaunch", R.string.mqtt_control_relaunch),
        DiscoveryChoice("wake", R.string.mqtt_control_wake),
        DiscoveryChoice("restartApp", R.string.mqtt_control_restart_app),
        DiscoveryChoice("settings", R.string.mqtt_control_settings),
        DiscoveryChoice("screen", R.string.mqtt_control_screen),
        DiscoveryChoice("camera", R.string.mqtt_control_camera),
        DiscoveryChoice("screensaver", R.string.mqtt_control_screensaver),
        DiscoveryChoice("brightness", R.string.mqtt_control_brightness),
        DiscoveryChoice("volume", R.string.mqtt_control_volume),
        DiscoveryChoice("url", R.string.mqtt_control_url),
        DiscoveryChoice("speak", R.string.mqtt_control_speak),
        DiscoveryChoice("toast", R.string.mqtt_control_toast),
        DiscoveryChoice("shell", R.string.mqtt_control_shell),
    )

    val SENSORS = listOf(
        DiscoveryChoice(SensorReader.BATTERY, R.string.mqtt_sensor_battery_level),
        DiscoveryChoice(SensorReader.USB_PLUGGED, R.string.mqtt_sensor_usb_plugged),
        DiscoveryChoice(SensorReader.AC_PLUGGED, R.string.mqtt_sensor_ac_plugged),
        DiscoveryChoice(SensorReader.CHARGING, R.string.mqtt_sensor_charging),
        DiscoveryChoice(SensorReader.CPU_USAGE, R.string.mqtt_sensor_cpu_usage),
        DiscoveryChoice(SensorReader.MEMORY_USAGE, R.string.mqtt_sensor_memory_usage),
        DiscoveryChoice(SensorReader.STORAGE_FREE, R.string.mqtt_sensor_storage_free),
        DiscoveryChoice(SensorReader.UPTIME, R.string.mqtt_sensor_uptime),
        DiscoveryChoice(SensorReader.IP_ADDRESS, R.string.mqtt_sensor_ip_address),
        DiscoveryChoice(SensorReader.WIFI_SIGNAL, R.string.mqtt_sensor_wifi_signal),
        DiscoveryChoice(SensorReader.APP_VERSION, R.string.mqtt_sensor_app_version),
        DiscoveryChoice(SensorReader.ANDROID_VERSION, R.string.mqtt_sensor_android_version),
        DiscoveryChoice(SensorReader.TEMPERATURE, R.string.mqtt_sensor_temperature),
        DiscoveryChoice(SensorReader.LIGHT, R.string.mqtt_sensor_light),
        DiscoveryChoice(SensorReader.HUMIDITY, R.string.mqtt_sensor_humidity),
        DiscoveryChoice(SensorReader.PRESSURE, R.string.mqtt_sensor_pressure),
        DiscoveryChoice(SensorReader.MAGNETIC_FIELD, R.string.mqtt_sensor_magnetic_field),
        DiscoveryChoice(MqttDiscovery.OBJECT_CURRENT_URL, R.string.mqtt_sensor_current_url),
        DiscoveryChoice(MqttDiscovery.OBJECT_SHELL_RESULT, R.string.mqtt_sensor_shell_result),
        DiscoveryChoice(MqttDiscovery.OBJECT_MOTION, R.string.mqtt_sensor_motion_detected),
        DiscoveryChoice(MqttDiscovery.OBJECT_FACE, R.string.mqtt_sensor_face_detected),
        DiscoveryChoice(MqttDiscovery.OBJECT_QR, R.string.mqtt_sensor_qr_code),
    )

    /**
     * Sensors WallPanel used to advertise and no longer does.
     *
     * Discovery configs are retained, so an entity dropped from [SENSORS] would otherwise
     * be left behind on every broker that ever saw it, with nothing in the entity list to
     * clear it. Ids stay here permanently once retired; they cost one empty publish per
     * connect and are the only thing that takes the old entity down.
     *
     * `wifiSsid` was retired because Android hands the network name only to applications
     * holding a location permission from 8.1 onwards, which this one does not request, so
     * the sensor could not report on any supported device.
     */
    val RETIRED_SENSOR_IDS: List<String> = listOf("wifiSsid")

    val ALL_CONTROL_IDS: Set<String> = CONTROLS.map { it.objectId }.toSet()
    val ALL_SENSOR_IDS: Set<String> = SENSORS.map { it.objectId }.toSet()

    /**
     * Ids that come from [SensorReader]'s reading list. The rest of [SENSORS] is built
     * elsewhere: the battery flags ride along in the battery payload, the camera entities
     * come from the detector callbacks, and the current URL and shell result are reported
     * from application state.
     *
     * [MqttDiscovery] walks this rather than the readings a device happens to be
     * producing, so a sensor that stops being readable still gets its entity listed and
     * its retained config cleared instead of being stranded in Home Assistant.
     */
    val READER_SENSOR_IDS: List<String> = SENSORS.map { it.objectId } - setOf(
        MqttDiscovery.OBJECT_CURRENT_URL,
        MqttDiscovery.OBJECT_SHELL_RESULT,
        MqttDiscovery.OBJECT_MOTION,
        MqttDiscovery.OBJECT_FACE,
        MqttDiscovery.OBJECT_QR,
        SensorReader.CHARGING,
        SensorReader.AC_PLUGGED,
        SensorReader.USB_PLUGGED,
    )

    /** Object ids ordered by the label the user sees, for the settings screen. */
    fun sortedByName(context: Context, choices: List<DiscoveryChoice>): List<DiscoveryChoice> {
        return choices.sortedBy { context.getString(it.displayNameRes) }
    }
}
