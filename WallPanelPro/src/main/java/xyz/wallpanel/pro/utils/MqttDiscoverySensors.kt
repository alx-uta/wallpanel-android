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

import xyz.wallpanel.pro.R

/**
 * Single source of truth for per-sensor MQTT Discovery toggles.
 *
 * The [id] must match the object_id used in the discovery topic
 * (`<discoveryTopic>/<component>/<clientId>/<id>/config`) and the
 * key suffix used by [xyz.wallpanel.pro.persistence.Configuration.mqttDiscoverySensorEnabled]
 * (`setting_mqtt_discovery_sensor_<id>`).
 */
enum class DiscoveryComponent(val mqttComponent: String) {
    SENSOR("sensor"),
    BINARY_SENSOR("binary_sensor"),
    TAG("tag")
}

data class DiscoverySensor(
    val id: String,
    val titleResId: Int,
    val component: DiscoveryComponent
)

object MqttDiscoverySensors {
    // Battery (sensor/battery state topic)
    const val ID_BATTERY = "battery"
    const val ID_USB_PLUGGED = "usbPlugged"
    const val ID_AC_PLUGGED = "acPlugged"
    const val ID_CHARGING = "charging"

    // Camera
    const val ID_FACE = "face"
    const val ID_MOTION = "motion"
    const val ID_QR = "qr"

    val HARDWARE_SENSORS: List<DiscoverySensor> = listOf(
        DiscoverySensor(ID_BATTERY, R.string.mqtt_sensor_battery_level, DiscoveryComponent.SENSOR),
        DiscoverySensor(ID_USB_PLUGGED, R.string.mqtt_sensor_usb_plugged, DiscoveryComponent.BINARY_SENSOR),
        DiscoverySensor(ID_AC_PLUGGED, R.string.mqtt_sensor_ac_plugged, DiscoveryComponent.BINARY_SENSOR),
        DiscoverySensor(ID_CHARGING, R.string.mqtt_sensor_charging, DiscoveryComponent.BINARY_SENSOR),
        DiscoverySensor("temperature", R.string.mqtt_sensor_temperature, DiscoveryComponent.SENSOR),
        DiscoverySensor("light", R.string.mqtt_sensor_light, DiscoveryComponent.SENSOR),
        DiscoverySensor("magneticField", R.string.mqtt_sensor_magnetic_field, DiscoveryComponent.SENSOR),
        DiscoverySensor("pressure", R.string.mqtt_sensor_pressure, DiscoveryComponent.SENSOR),
        DiscoverySensor("humidity", R.string.mqtt_sensor_humidity, DiscoveryComponent.SENSOR),
        DiscoverySensor("cpuUsage", R.string.mqtt_sensor_cpu_usage, DiscoveryComponent.SENSOR),
        DiscoverySensor("memoryUsage", R.string.mqtt_sensor_memory_usage, DiscoveryComponent.SENSOR)
    )

    val STATE_SENSORS: List<DiscoverySensor> = listOf(
        DiscoverySensor(MqttUtils.STATE_SCREEN_ON, R.string.mqtt_sensor_screen, DiscoveryComponent.BINARY_SENSOR),
        DiscoverySensor(MqttUtils.STATE_SCREEN_SAVER, R.string.mqtt_sensor_screensaver, DiscoveryComponent.BINARY_SENSOR),
        DiscoverySensor(MqttUtils.STATE_BRIGHTNESS, R.string.mqtt_sensor_brightness, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_VOLUME, R.string.mqtt_sensor_volume, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_WIFI_SIGNAL, R.string.mqtt_sensor_wifi_signal, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_WIFI_SSID, R.string.mqtt_sensor_wifi_ssid, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_IP_ADDRESS, R.string.mqtt_sensor_ip_address, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_STORAGE_FREE, R.string.mqtt_sensor_storage_free, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_UPTIME, R.string.mqtt_sensor_uptime, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_CURRENT_URL, R.string.mqtt_sensor_current_url, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_ANDROID_VERSION, R.string.mqtt_sensor_android_version, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_APP_VERSION, R.string.mqtt_sensor_app_version, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_MANUFACTURER, R.string.mqtt_sensor_manufacturer, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_MODEL, R.string.mqtt_sensor_model, DiscoveryComponent.SENSOR),
        DiscoverySensor(MqttUtils.STATE_DEVICE_OWNER, R.string.mqtt_sensor_device_owner, DiscoveryComponent.BINARY_SENSOR)
    )

    val CAMERA_SENSORS: List<DiscoverySensor> = listOf(
        DiscoverySensor(ID_FACE, R.string.mqtt_sensor_face_detected, DiscoveryComponent.BINARY_SENSOR),
        DiscoverySensor(ID_MOTION, R.string.mqtt_sensor_motion_detected, DiscoveryComponent.BINARY_SENSOR),
        DiscoverySensor(ID_QR, R.string.mqtt_sensor_qr_code, DiscoveryComponent.TAG)
    )

    val ALL: List<DiscoverySensor> = HARDWARE_SENSORS + STATE_SENSORS + CAMERA_SENSORS

    private val byId = ALL.associateBy(DiscoverySensor::id)

    fun componentFor(sensorId: String): DiscoveryComponent =
        requireNotNull(byId[sensorId]) { "Missing MQTT Discovery sensor definition for '$sensorId'" }.component

    /** Hardware sensors that may be absent on a given device. */
    val HARDWARE_SENSOR_IDS: Set<String> = setOf(
        "temperature", "light", "magneticField", "pressure", "humidity"
    )
}
