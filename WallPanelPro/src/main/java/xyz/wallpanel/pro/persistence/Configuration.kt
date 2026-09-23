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

package xyz.wallpanel.pro.persistence

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.modules.CameraProfile
import xyz.wallpanel.pro.modules.CameraResolution
import xyz.wallpanel.pro.network.DiscoveryCatalog
import javax.inject.Inject

class Configuration @Inject
constructor(private val context: Context, private val sharedPreferences: SharedPreferences) {

    // APP
    var isFirstTime: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_FIRST_TIME, true)
        set(value) = this.sharedPreferences.edit().putBoolean(PREF_FIRST_TIME, value).apply()

    val appPreventSleep: Boolean
        get() = getBoolPref(R.string.key_setting_app_preventsleep,
                R.string.default_setting_app_preventsleep)

    // TODO we have to migrate this due to an error when entering codes with leading 0 value
    var settingsCode: String
        get() {
            val prev = this.sharedPreferences.getInt(PREF_SETTINGS_CODE, 0)
            val cur = this.sharedPreferences.getString(PREF_SETTINGS_CODE_STRING, "1234").orEmpty()
            return if(prev > 0) {
                val preStr = String.format("%04d", prev) // pad to 4 with 0's leading
                this.sharedPreferences.edit().putInt(PREF_SETTINGS_CODE, 0).apply()
                this.sharedPreferences.edit().putString(PREF_SETTINGS_CODE_STRING, preStr).apply()
                prev.toString()
            } else {
                cur
            }
        }
        set(value) = this.sharedPreferences.edit().putString(PREF_SETTINGS_CODE_STRING, value).apply()

    val settingsCodeRequired: Boolean
        get() = getBoolPref(
            R.string.key_setting_settings_code_required,
            R.string.default_setting_settings_code_required
        )

    var fullScreen: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_FULL_SCREEN, true)
        set(value) = this.sharedPreferences.edit().putBoolean(PREF_FULL_SCREEN, value).apply()

    var useDarkTheme: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_DARK_THEME, false)
        set(value) = this.sharedPreferences.edit().putBoolean(PREF_DARK_THEME, value).apply()

    var settingsTransparent: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_SETTINGS_TRANSPARENT, false)
        set(value) = this.sharedPreferences.edit().putBoolean(PREF_SETTINGS_TRANSPARENT, value).apply()

    var settingsDisabled: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_SETTINGS_DISABLE, false)
        set(value) = this.sharedPreferences.edit().putBoolean(PREF_SETTINGS_DISABLE, value).apply()

    var cameraPermissionsShown: Boolean
        get() = sharedPreferences.getBoolean(PREF_CAMERA_PERMISSIONS, false)
        set(value) {
            sharedPreferences.edit().putBoolean(PREF_CAMERA_PERMISSIONS, value).apply()
        }

    var shellPermissionsShown: Boolean
        get() = sharedPreferences.getBoolean(PREF_SHELL_PERMISSIONS, false)
        set(value) {
            sharedPreferences.edit().putBoolean(PREF_SHELL_PERMISSIONS, value).apply()
        }

    var notificationPermissionsShown: Boolean
        get() = sharedPreferences.getBoolean(PREF_NOTIFICATION_PERMISSIONS, false)
        set(value) {
            sharedPreferences.edit().putBoolean(PREF_NOTIFICATION_PERMISSIONS, value).apply()
        }

    var cameraRotate: Float
        get() = this.sharedPreferences.getString(PREF_CAMERA_ROTATE, "0f")?.trim()?.toFloatOrNull() ?: 0f
        set(value) = this.sharedPreferences.edit().putString(PREF_CAMERA_ROTATE, value.toString()).apply()

    var appLaunchUrl: String
        get() = getStringPref(R.string.key_setting_app_launchurl,
                R.string.default_setting_app_launchurl)
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_app_launchurl), value).apply()
            settingsUpdated()
        }

    val appShowActivity: Boolean
        get() = getBoolPref(R.string.key_setting_app_showactivity,
                R.string.default_setting_app_showactivity)

    var cameraEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_camera_enabled, R.string.default_setting_camera_enabled)
        set(value) {
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_setting_camera_enabled), value).apply()
        }

    var cameraId: Int
        get() = this.sharedPreferences.getInt(context.getString(R.string.setting_camera_cameraid), 0)
        set(value) {
            this.sharedPreferences.edit().putInt(context.getString(R.string.setting_camera_cameraid), value).apply()
        }

    var cameraMotionEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_camera_motionenabled, R.string.default_setting_camera_motionenabled)
        set(value) {
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_setting_camera_motionenabled), value).apply()
        }

    var cameraMotionLeniency: Int
        get() = sharedPreferences.getInt(PREF_CAMERA_MOTION_LATENCY, 20)
        set(value) {
            sharedPreferences.edit().putInt(PREF_CAMERA_MOTION_LATENCY, value).apply()
        }

    val cameraMotionMinLuma: Int
        get() = getIntPref(R.string.key_setting_camera_motionminluma, R.string.default_setting_camera_motionminluma)


    val cameraMotionWake: Boolean
        get() = getBoolPref(R.string.key_setting_camera_motionwake,
                R.string.default_setting_camera_motionwake)

    val cameraMotionBright: Boolean
        get() = getBoolPref(R.string.key_setting_camera_motionbright,
                R.string.default_setting_camera_motionbright)

    var cameraFaceEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_camera_faceenabled,
                R.string.default_setting_camera_faceenabled)
        set(value) {
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_setting_camera_faceenabled), value).apply()
        }

    val cameraFaceWake: Boolean
        get() = getBoolPref(R.string.key_setting_camera_facewake,
                R.string.default_setting_camera_facewake)

    var cameraFaceSize: Int
        get() = sharedPreferences.getInt(PREF_CAMERA_FACE_SIZE, 0)
        set(value) {
            sharedPreferences.edit().putInt(PREF_CAMERA_FACE_SIZE, value).apply()
        }

    val cameraFaceRotation: Boolean
        get() = getBoolPref(R.string.key_setting_camera_facerotation,
                R.string.default_setting_camera_facerotation)

    var cameraQRCodeEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_camera_qrcodeenabled,
                R.string.default_setting_camera_qrcodeenabled)
        set(value) {
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_setting_camera_qrcodeenabled), value).apply()
        }

    val motionResetTime: Int
        get() = getIntPref(R.string.key_setting_motion_clear, R.string.default_motion_clear)

    val httpEnabled: Boolean
        get() = httpRestEnabled || httpMJPEGEnabled

    val httpPort: Int
        get() = getIntPref(R.string.key_setting_http_port, R.string.default_setting_http_port)

    val httpRestEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_http_restenabled,
                R.string.default_setting_http_restenabled)

    val httpShellEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_http_shellenabled,
                R.string.default_setting_http_shellenabled)

    val httpMJPEGEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_http_mjpegenabled,
                R.string.default_setting_http_mjpegenabled)

    fun setHttpMJPEGEnabled(value: Boolean?) {
        sharedPreferences.edit().putBoolean(context.getString(R.string.key_setting_http_mjpegenabled), value!!).apply()
    }

    val httpMJPEGMaxStreams: Int
        get() = getIntPref(R.string.key_setting_http_mjpegmaxstreams, R.string.default_setting_http_mjpegmaxstreams)

    /**
     * The most frames per second the stream sends. It never sends more than the camera takes.
     *
     * Until it is set, it follows the rate the stream used to be held to, which came from a
     * fixed pause after each frame picked by the camera FPS: about 2 frames a second at 10
     * or below, 3 at 15, 4 at 20, and no limit above that.
     */
    var httpMJPEGFps: Int
        get() {
            val stored = sharedPreferences.getString(context.getString(R.string.key_setting_http_mjpegfps), null)
                ?.trim()?.toIntOrNull()
            val fps = stored ?: when {
                cameraFPS <= 10 -> 2
                cameraFPS <= 15 -> 3
                cameraFPS <= 20 -> 4
                else -> CameraProfile.MAX_FPS
            }
            return fps.coerceIn(CameraProfile.MIN_FPS, CameraProfile.MAX_FPS)
        }
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_http_mjpegfps), value.toString()).apply()
        }

    var mqttEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_mqtt_enabled, R.string.default_setting_mqtt_enabled)
        set(value) {
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_setting_mqtt_enabled), value).apply()
        }

    var mqttVersion: String
        get() = getStringPref(R.string.key_setting_mqtt_version, R.string.default_setting_mqtt_version)
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_mqtt_version), value).apply()
        }

    var mqttTlsEnabled: Boolean
        get() = sharedPreferences.getBoolean(context.getString(R.string.key_setting_mqtt_tls_enabled), false)
        set(value) =
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_setting_mqtt_tls_enabled), value).apply()

    var mqttBroker: String
        get() = getStringPref(R.string.key_setting_mqtt_servername, R.string.default_setting_mqtt_servername)
        set(value) =
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_mqtt_servername), value).apply()

    var mqttServerPort: Int
        get() = getIntPref(R.string.key_setting_mqtt_serverport, R.string.default_setting_mqtt_serverport)
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_mqtt_serverport), value.toString()).apply()
        }

    var mqttBaseTopic: String
        get() = getStringPref(R.string.key_setting_mqtt_basetopic,
                R.string.default_setting_mqtt_basetopic)
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_mqtt_basetopic), value).apply()
        }

    var mqttClientId: String
        get() = getStringPref(R.string.key_setting_mqtt_clientid,
                R.string.default_setting_mqtt_clientid)
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_mqtt_clientid), value).apply()
        }

    var mqttUsername: String
        get() = getStringPref(R.string.key_setting_mqtt_username,
                R.string.default_setting_mqtt_username)
        set(value) =
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_mqtt_username), value).apply()

    var mqttPassword: String
        get() = getStringPref(R.string.key_setting_mqtt_password,
                R.string.default_setting_mqtt_password)
        set(value) =
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_mqtt_password), value).apply()

    val mqttSensorFrequency: Int
        get() = getIntPref(R.string.key_setting_mqtt_sensorfrequency, R.string.default_setting_mqtt_sensorfrequency)

    val mqttDiscovery: Boolean
        get() = getBoolPref(R.string.key_setting_mqtt_discovery, R.string.default_setting_mqtt_home_assistant_discovery)

    val mqttDiscoveryTopic: String
        get() = getStringPref(R.string.key_setting_mqtt_discovery_topic, R.string.default_setting_mqtt_discovery_topic)

    val mqttDiscoveryDeviceName: String
        get() = getStringPref(R.string.key_setting_mqtt_discovery_name, R.string.default_setting_mqtt_home_assistant_name)

    val mqttLegacyDiscoveryEntities: Boolean
        get() = getBoolPref(R.string.key_setting_mqtt_discovery_legacy_entities, R.string.default_setting_mqtt_discovery_legacy_entities)

    /**
     * The discovery topics that held a config after the last publish. The configs are
     * retained, so this is what lets the application clear them after discovery is switched
     * off, or after the client id or discovery topic they were published under has changed.
     */
    var mqttDiscoveryAdvertisedTopics: Set<String>
        get() = sharedPreferences.getStringSet(PREF_MQTT_DISCOVERY_ADVERTISED_TOPICS, null)?.toSet().orEmpty()
        set(value) = sharedPreferences.edit().putStringSet(PREF_MQTT_DISCOVERY_ADVERTISED_TOPICS, value.toSet()).apply()

    /**
     * True until the first publish records what went to the broker. An upgrade from a
     * version that did not keep this list arrives here, and whatever that version left
     * retained is unknown, which is what [mqttDiscoveryAdvertisedTopics] being empty
     * cannot distinguish on its own.
     */
    val mqttDiscoveryUnrecorded: Boolean
        get() = !sharedPreferences.contains(PREF_MQTT_DISCOVERY_ADVERTISED_TOPICS)

    /**
     * Which controls and sensors may be advertised. Both read and write the set the user
     * ticked; what is stored underneath is the complement, the ids they unticked.
     *
     * Storing the exclusions is what lets an entity added to [DiscoveryCatalog] in a later
     * version reach devices that are already configured. An id nobody has ever unticked is
     * published, so the catalogue can grow without every existing device having to visit
     * the settings screen again.
     */
    var mqttDiscoveryControlIds: Set<String>
        get() = DiscoveryCatalog.ALL_CONTROL_IDS - excludedIds(R.string.key_setting_mqtt_discovery_control_ids_excluded)
        set(value) = setExcludedIds(
            R.string.key_setting_mqtt_discovery_control_ids_excluded,
            DiscoveryCatalog.ALL_CONTROL_IDS - value
        )

    var mqttDiscoverySensorIds: Set<String>
        get() = DiscoveryCatalog.ALL_SENSOR_IDS - excludedIds(R.string.key_setting_mqtt_discovery_sensor_ids_excluded)
        set(value) = setExcludedIds(
            R.string.key_setting_mqtt_discovery_sensor_ids_excluded,
            DiscoveryCatalog.ALL_SENSOR_IDS - value
        )

    val mqttDiscoveryControls: Boolean
        get() = getBoolPref(R.string.key_setting_mqtt_discovery_controls, R.string.default_setting_mqtt_discovery_controls)

    val sensorsEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_sensors_enabled,
                R.string.default_setting_sensors_value)

    val hardwareAccelerated: Boolean
        get() = getBoolPref(R.string.key_hadware_accelerated_enabled,
                R.string.default_hardware_accelerated_value)

    var browserUserAgent: String
        get() = getStringPref(R.string.key_setting_browser_user_agent,
                R.string.default_browser_user_agent)
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_browser_user_agent), value).apply()
            settingsUpdated()
        }

    var browserRefreshDisconnect: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_BROWSER_REFRESH_DISCONNECT, true)
        set(value) {
            sharedPreferences.edit().putBoolean(PREF_BROWSER_REFRESH_DISCONNECT, value).apply()
        }

    var browserRefresh: Boolean
        get() = this.sharedPreferences.getBoolean(context.getString(R.string.key_pref_browser_refresh), true)
        set(value) {
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_pref_browser_refresh), value).apply()
        }

    var useGeckoView: Boolean
        get() = getBoolPref(R.string.key_use_geckoview, R.string.default_use_geckoview)
        set(value) {
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_use_geckoview), value).apply()
            settingsUpdated()
        }

    // How long the screen can stay off before the GeckoView session is torn down to stop
    // background CPU use.
    val geckoViewSuspendSeconds: Int
        get() = try {
            getStringPref(
                R.string.key_use_geckoview_suspend_seconds,
                R.string.default_use_geckoview_suspend_seconds
            ).trim().toInt()
        } catch (e: Exception) {
            30
        }

    val cameraFPS: Float
        get() = try {
            getStringPref(R.string.key_setting_camera_fps, R.string.default_camera_fps).trim().toFloat()
        } catch (e: Exception) {
            15.0F
        }

    val cameraMotionFrameSkip: Int
        get() = try {
            getStringPref(R.string.key_setting_camera_motionframeskip, R.string.default_camera_motionframeskip).trim().toInt()
        } catch (e: Exception) {
            5
        }

    val cameraOnlyWhenScreenSaver: Boolean
        get() = getBoolPref(R.string.key_setting_camera_only_screensaver, R.string.default_camera_only_screensaver)

    /**
     * The resolution the camera normally runs at. Until one is picked in the settings this
     * follows the low resolution switch it replaced, which chose between 320x240 and
     * 640x480.
     */
    var cameraResolution: CameraResolution
        get() = CameraResolution.parse(sharedPreferences.getString(context.getString(R.string.key_setting_camera_resolution), null))
            ?: if (getBoolPref(R.string.key_setting_camera_low_resolution, R.string.default_camera_low_resolution)) {
                CameraResolution.LOW
            } else {
                CameraResolution.STANDARD
            }
        set(value) {
            sharedPreferences.edit().putString(context.getString(R.string.key_setting_camera_resolution), value.toString()).apply()
        }

    val cameraIdleProfile: CameraProfile
        get() = CameraProfile(cameraResolution, cameraFPS)

    val cameraBoostEnabled: Boolean
        get() = getBoolPref(R.string.key_setting_camera_boost_enabled, R.string.default_setting_camera_boost_enabled)

    val cameraBoostProfile: CameraProfile
        get() {
            val resolution = CameraResolution.parse(getStringPref(R.string.key_setting_camera_boost_resolution, R.string.default_setting_camera_boost_resolution))
                ?: CameraResolution.STANDARD
            val fps = getIntPref(R.string.key_setting_camera_boost_fps, R.string.default_setting_camera_boost_fps)
                .coerceIn(CameraProfile.MIN_FPS, CameraProfile.MAX_FPS)
            return CameraProfile(resolution, fps.toFloat())
        }

    /** Seconds without motion before a boosted camera goes back to [cameraIdleProfile]. */
    val cameraBoostHoldSeconds: Int
        get() = getIntPref(R.string.key_setting_camera_boost_hold, R.string.default_setting_camera_boost_hold)
            .coerceAtLeast(1)

    val testZoomLevel: Float
        get() {
            val value = sharedPreferences.getString(context.getString(R.string.key_setting_test_zoomlevel), "1.0")
            return value?.toFloatOrNull()?: 1.0F
        }

    var inactivityTime: Long
        get() = sharedPreferences.getLong(context.getString(R.string.key_screensaver_inactivity_time), 30000)
        set(value) {
            sharedPreferences.edit().putLong(context.getString(R.string.key_screensaver_inactivity_time), value).apply()
        }

    var screenSaverDimValue: Int
        get() = sharedPreferences.getInt(context.getString(R.string.key_screensaver_dim_value), 25)
        set(value) {
            sharedPreferences.edit().putInt(context.getString(R.string.key_screensaver_dim_value), value).apply()
        }

    var settingsLocation: Int
        get() = sharedPreferences.getInt(PREF_SETTINGS_LOCATION, 0)
        set(value) {
            sharedPreferences.edit().putInt(PREF_SETTINGS_LOCATION, value).apply()
        }

    var imageRotation: Int
        get() = sharedPreferences.getInt(PREF_IMAGE_ROTATION, ROTATE_TIME_IN_MINUTES)
        set(value) = this.sharedPreferences.edit().putInt(PREF_IMAGE_ROTATION, value).apply()

    var hasBlankScreenSaver: Boolean
        get() = getBoolPref(R.string.key_screensaver_blank, R.string.default_screensaver_blank)
        set(value) =
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_screensaver_blank), value).apply()

    var hasDimScreenSaver: Boolean
        get() = getBoolPref(R.string.key_screensaver_dim, R.string.default_screensaver_dim)
        set(value) =
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_screensaver_dim), value).apply()

    var hasClockScreenSaver: Boolean
        get() = getBoolPref(R.string.key_screensaver, R.string.default_screensaver)
        set(value) =
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_screensaver), value).apply()

    var hasScreenSaverWallpaper: Boolean
        get() = getBoolPref(R.string.key_screensaver_wallpaper, R.string.default_screensaver_wallpaper)
        set(value) =
            sharedPreferences.edit().putBoolean(context.getString(R.string.key_screensaver_wallpaper), value).apply()

    var webScreenSaver: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_WEB_SCREENSAVER, false)
        set(value) = this.sharedPreferences.edit().putBoolean(PREF_WEB_SCREENSAVER, value).apply()

    var webScreenSaverUrl: String
        get() = sharedPreferences.getString(PREF_WEB_SCREENSAVER_URL, WEB_SCREEN_SAVER).orEmpty()
        set(value) = this.sharedPreferences.edit().putString(PREF_WEB_SCREENSAVER_URL, value).apply()

    var screenBrightness: Int
        get() = sharedPreferences.getInt(context.getString(R.string.key_setting_screen_brightness), 150)
        set(value) {
            sharedPreferences.edit().putInt(context.getString(R.string.key_setting_screen_brightness), value).apply()
        }

    var screenScreenSaverBrightness: Int
        get() = sharedPreferences.getInt(context.getString(R.string.key_setting_screensaver_brightness), (255*.25).toInt())
        set(value) {
            sharedPreferences.edit().putInt(context.getString(R.string.key_setting_screensaver_brightness), value).apply()
        }

    var useScreenBrightness: Boolean
        get() = this.sharedPreferences.getBoolean(PREF_SCREEN_BRIGHTNESS, false)
        set(value) = this.sharedPreferences.edit().putBoolean(PREF_SCREEN_BRIGHTNESS, value).apply()

    val ignoreSSLErrors: Boolean
        get() = getBoolPref(R.string.key_setting_ignore_ssl_errors,
                R.string.default_setting_ignore_ssl_errors)

    fun hasCameraDetections(): Boolean {
        return cameraEnabled && (cameraMotionEnabled || cameraQRCodeEnabled || cameraFaceEnabled || httpMJPEGEnabled)
    }

    /**
     * A numeric setting, falling back to its default when the stored text will not parse.
     *
     * The settings screen restricts these fields to a number pad, which stops letters but
     * not a pasted value or one too large for the type. Every one of these is read while
     * the service is starting, so a value that throws takes the kiosk down on launch and
     * leaves no way back into the settings to correct it.
     */
    private fun getIntPref(resId: Int, defId: Int): Int {
        val value = getStringPref(resId, defId).trim().toIntOrNull()
        if (value != null) {
            return value
        }
        Timber.w("Setting ${context.getString(resId)} is not a whole number, using the default")
        return context.getString(defId).trim().toIntOrNull() ?: 0
    }

    private fun getStringPref(resId: Int, defId: Int): String {
        val def = context.getString(defId)
        val pref = sharedPreferences.getString(context.getString(resId), "")
        return if (pref!!.isEmpty()) def else pref
    }

    private fun getBoolPref(resId: Int, defId: Int): Boolean {
        return sharedPreferences.getBoolean(
                context.getString(resId),
                java.lang.Boolean.valueOf(context.getString(defId))
        )
    }

    /**
     * A copy, never the set the preferences hold: [SharedPreferences.getStringSet] hands
     * out its own live instance and mutating it corrupts the cache.
     */
    private fun excludedIds(keyRes: Int): Set<String> {
        val stored = sharedPreferences.getStringSet(context.getString(keyRes), null)
        return stored?.toSet().orEmpty()
    }

    private fun setExcludedIds(keyRes: Int, excluded: Set<String>) {
        sharedPreferences.edit().putStringSet(context.getString(keyRes), excluded.toSet()).apply()
    }

    fun hasSettingsUpdates(): Boolean {
        val updates = sharedPreferences.getBoolean(PREF_BROWSER_SETTINGS_UPDATED, false)
        sharedPreferences.edit().putBoolean(PREF_BROWSER_SETTINGS_UPDATED, false).apply()
        return updates
    }

    private fun settingsUpdated() {
        sharedPreferences.edit().putBoolean(PREF_BROWSER_SETTINGS_UPDATED, true).apply()
    }

    companion object {
        private const val PREF_BROWSER_SETTINGS_UPDATED = "pref_browser_settings_updated"
        private const val PREF_DARK_THEME = "pref_dark_theme"
        private const val PREF_FULL_SCREEN = "pref_full_screen"
        private const val PREF_SETTINGS_CODE = "pref_settings_code"
        private const val PREF_SETTINGS_CODE_STRING = "pref_settings_code_string"
        private const val PREF_SETTINGS_TRANSPARENT = "pref_settings_transparent"
        private const val PREF_SETTINGS_DISABLE = "pref_settings_disable"
        private const val PREF_SETTINGS_LOCATION = "pref_settings_location"
        const val PREF_FIRST_TIME = "pref_first_time"
        const val PREF_CAMERA_PERMISSIONS = "pref_camera_permissions"
        const val PREF_SHELL_PERMISSIONS = "pref_shell_permissions"
        const val PREF_NOTIFICATION_PERMISSIONS = "pref_notification_permissions"
        const val PREF_CAMERA_ROTATE = "pref_camera_rotate"
        const val PREF_BROWSER_REFRESH_DISCONNECT = "pref_browser_refresh_disconnect"
        const val PREF_SCREEN_BRIGHTNESS = "pref_use_screen_brightness"
        const val PREF_MQTT_DISCOVERY_ADVERTISED_TOPICS = "pref_mqtt_discovery_advertised_topics"
        const val PREF_SCREENSAVER_DIM_VALUE = "pref_screensaver_dim_value"
        private val ROTATE_TIME_IN_MINUTES = 15
        const val PREF_IMAGE_ROTATION = "pref_image_rotation"
        private val PREF_CAMERA_FACE_SIZE = "pref_camera_face_size"
        private val PREF_CAMERA_MOTION_LATENCY = "pref_camera_motion_latency"
        private val PREF_WEB_SCREENSAVER_URL = "pref_web_screensaver_url"
        private val PREF_WEB_SCREENSAVER = "pref_web_screensaver"
        const val WEB_SCREEN_SAVER = "https://wallpanel.xyz"
    }
}
