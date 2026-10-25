/*
 * Copyright (c) 2026 WallPanel
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
import android.provider.Settings
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.wallpanel.pro.persistence.Configuration

@RunWith(RobolectricTestRunner::class)
class ScreenUtilsTest {

    private lateinit var context: Context
    private lateinit var configuration: Configuration
    private var canWrite = true
    private lateinit var screenUtils: ScreenUtils

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        configuration = Configuration(context, preferences)
        screenUtils = object : ScreenUtils(context, configuration) {
            override fun canWriteScreenSetting(): Boolean = canWrite
        }
        setSystem(Settings.System.SCREEN_BRIGHTNESS, 200)
    }

    private fun setSystem(name: String, value: Int) {
        Settings.System.putInt(context.contentResolver, name, value)
    }

    private fun getSystem(name: String): Int = Settings.System.getInt(context.contentResolver, name)

    @Test
    fun `with brightness control off the screensaver leaves an automatic device alone`() {
        configuration.useScreenBrightness = false
        setSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)

        screenUtils.resetScreenBrightness(true)
        screenUtils.resetScreenBrightness(false)

        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, getSystem(Settings.System.SCREEN_BRIGHTNESS_MODE))
        assertEquals(200, getSystem(Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test
    fun `with brightness control off a manual device is not switched to automatic`() {
        configuration.useScreenBrightness = false
        setSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

        screenUtils.resetScreenBrightness(true)
        screenUtils.resetScreenBrightness(false)

        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, getSystem(Settings.System.SCREEN_BRIGHTNESS_MODE))
        assertEquals(200, getSystem(Settings.System.SCREEN_BRIGHTNESS))
        assertFalse(screenUtils.controlsSystemBrightness())
    }

    @Test
    fun `window brightness is the device level less the dim amount`() {
        configuration.screenSaverDimValue = 25
        assertEquals(150 / 255f, screenUtils.screenSaverWindowBrightness(), 0.001f)
        configuration.screenSaverDimValue = 100
        assertEquals(0f, screenUtils.screenSaverWindowBrightness(), 0.001f)
    }

    @Test
    fun `switching control off restores automatic only when the app had turned it off`() {
        setSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        configuration.useScreenBrightness = true
        screenUtils.resetScreenBrightness(false)
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, getSystem(Settings.System.SCREEN_BRIGHTNESS_MODE))
        assertTrue(configuration.deviceBrightnessWasAutomatic)

        screenUtils.restoreDeviceBrightnessControl()
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, getSystem(Settings.System.SCREEN_BRIGHTNESS_MODE))
        assertFalse(configuration.useScreenBrightness)
        assertFalse(configuration.deviceBrightnessWasAutomatic)

        // A device that was on manual brightness stays on it
        setSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        configuration.useScreenBrightness = true
        screenUtils.resetScreenBrightness(false)
        screenUtils.restoreDeviceBrightnessControl()
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, getSystem(Settings.System.SCREEN_BRIGHTNESS_MODE))
    }

    @Test
    fun `with control on the screensaver sets the dimmed level and the page the setpoint`() {
        configuration.useScreenBrightness = true
        configuration.screenBrightness = 180
        configuration.screenScreenSaverBrightness = 45

        screenUtils.resetScreenBrightness(true)
        assertEquals(45, getSystem(Settings.System.SCREEN_BRIGHTNESS))
        screenUtils.resetScreenBrightness(false)
        assertEquals(180, getSystem(Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test
    fun `nothing is written without the permission`() {
        configuration.useScreenBrightness = true
        canWrite = false
        setSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)

        screenUtils.resetScreenBrightness(true)

        assertEquals(200, getSystem(Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, getSystem(Settings.System.SCREEN_BRIGHTNESS_MODE))
    }

    @Test
    fun `the brightness setting is the level on screen except under automatic brightness before Android 9`() {
        val auto = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val manual = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        assertFalse(ScreenUtils.brightnessSettingIsOnScreen(27, auto, true))
        assertTrue(ScreenUtils.brightnessSettingIsOnScreen(27, auto, false))
        assertTrue(ScreenUtils.brightnessSettingIsOnScreen(27, manual, true))
        assertTrue(ScreenUtils.brightnessSettingIsOnScreen(21, manual, true))
        assertTrue(ScreenUtils.brightnessSettingIsOnScreen(28, auto, true))
    }

    @Test
    fun `the shade is the dim amount`() {
        configuration.screenSaverDimValue = 25
        assertEquals(0.25f, screenUtils.screenSaverShade(), 0.001f)
        configuration.screenSaverDimValue = 150
        assertEquals(1f, screenUtils.screenSaverShade(), 0.001f)
    }
}
