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

import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import xyz.wallpanel.pro.persistence.Configuration
import timber.log.Timber
import javax.inject.Inject

open class ScreenUtils @Inject
constructor(context: Context, private val configuration: Configuration): ContextWrapper(context) {

    /**
     * Sets the system brightness to the level for the page or for the screensaver. Only
     * while screen brightness control is on and allowed; otherwise the device's own
     * brightness, and its brightness mode, are left as the user set them.
     */
    fun resetScreenBrightness(screenSaver: Boolean = true) {
        if (controlsSystemBrightness()) {
            setDeviceBrightnessControl(screenSaver)
        }
    }

    /** Whether the app sets the system brightness, rather than leaving it to the device. */
    fun controlsSystemBrightness(): Boolean {
        return configuration.useScreenBrightness && canWriteScreenSetting()
    }

    /**
     * The window brightness, from 0 to 1, for the dim screensaver when the system
     * brightness is not the app's to change: the device's current level, less the dim
     * amount.
     */
    fun screenSaverWindowBrightness(): Float {
        val dim = configuration.screenSaverDimValue.coerceIn(0, 100)
        val level = getCurrentScreenBrightness().coerceIn(0, 255)
        return (level * (100 - dim) / 100) / 255f
    }

    /**
     * Whether the system brightness setting is the level on screen. Before Android 9,
     * automatic brightness leaves the setting at the last level the user picked by hand. On
     * a device with a light sensor, that can be far from the level on screen.
     */
    open fun screenBrightnessKnown(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return true
        }
        val mode = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        } catch (e: Exception) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasLightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        return brightnessSettingIsOnScreen(Build.VERSION.SDK_INT, mode, hasLightSensor)
    }

    /**
     * How dark, from 0 to 1, the dim screensaver shades the page when the level on screen
     * is not known: the dim amount, whatever the level.
     */
    fun screenSaverShade(): Float {
        return configuration.screenSaverDimValue.coerceIn(0, 100) / 100f
    }

    fun getCurrentScreenBrightness(): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Works the dimmed level out again from the brightness setpoint, after the dim level was
     * changed by command. The level on screen is not used: with the screensaver up it is
     * already the dimmed one.
     */
    fun updateScreenSaverBrightness() {
        val brightness = configuration.screenBrightness
        val dim = configuration.screenSaverDimValue
        configuration.screenScreenSaverBrightness = if (dim > 0) brightness - (brightness * dim / 100) else brightness
    }

    fun setScreenBrightnessLevels() {
        try {
            val brightness = getCurrentScreenBrightness()
            updateScreenBrightness(brightness)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setDeviceBrightnessControl(screenSaver: Boolean) {
        val canWriteScreenSetting = canWriteScreenSetting()
        if(canWriteScreenSetting) {
            setDeviceBrightnessMode(false)
            val screenBrightness = configuration.screenBrightness
            val screenScreenBrightness = configuration.screenScreenSaverBrightness
            try {
                if (screenBrightness in 0..255 && !screenSaver) {
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, screenBrightness)
                } else if (screenScreenBrightness in 0..255 && screenSaver) {
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, screenScreenBrightness)
                }
            } catch (e: SecurityException) {
                Timber.e(e.message)
            }
        }
    }


    fun updateScreenBrightness(brightness: Int) {
        if(canWriteScreenSetting()) {
            try {
                if (brightness in 0..255) {
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                    configuration.screenBrightness = brightness
                    if(configuration.screenSaverDimValue > 0) {
                        val dimAmount = brightness - (brightness * configuration.screenSaverDimValue/100)
                        configuration.screenScreenSaverBrightness = dimAmount
                    } else {
                        configuration.screenScreenSaverBrightness = brightness
                    }
                }
            } catch (e: SecurityException) {
                Timber.e(e.message)
            }
        }
    }

    /**
     * Runs when the user switches screen brightness control off. The brightness stays where
     * it is, and the device goes back to automatic brightness if that is what it was on
     * before the app took over.
     */
    fun restoreDeviceBrightnessControl() {
        configuration.useScreenBrightness = false
        if (configuration.deviceBrightnessWasAutomatic && canWriteScreenSetting()) {
            setDeviceBrightnessMode(true)
            configuration.deviceBrightnessWasAutomatic = false
        }
    }

    private fun setDeviceBrightnessMode(automatic: Boolean = false) {
        var mode = -1
        try {
            mode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) //this will return integer (0 or 1)
        } catch (e: Settings.SettingNotFoundException) {
            Timber.e(e.message)
        }
        try {
            if(automatic) {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    //reset back to automatic mode
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                }
            } else {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    //Automatic mode, need to be in manual to change brightness
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    configuration.deviceBrightnessWasAutomatic = true
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e.message)
        }
    }

    /**
     * Whether the application may change the system screen brightness. This is not a
     * runtime permission -- it is granted from a system settings screen -- so it can be
     * revoked out from under a setting that is still switched on.
     */
    open fun canWriteScreenSetting(): Boolean {
        var hasPermission = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasPermission = Settings.System.canWrite(applicationContext)
        }
        return hasPermission
    }

    companion object {
        /** See [screenBrightnessKnown]. */
        fun brightnessSettingIsOnScreen(sdkInt: Int, mode: Int, hasLightSensor: Boolean): Boolean {
            return sdkInt >= Build.VERSION_CODES.P ||
                    mode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ||
                    !hasLightSensor
        }
    }
}
