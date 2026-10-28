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

package xyz.wallpanel.pro.persistence

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteConfigTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var configuration: Configuration
    private lateinit var remoteConfig: RemoteConfig

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        configuration = Configuration(context, preferences)
        remoteConfig = RemoteConfig(configuration)
    }

    private fun apply(json: String) = remoteConfig.apply(JSONObject(json))

    @Test
    fun `screensaver mode sets the switches the settings screen keeps`() {
        apply("""{"screenSaverMode": "web"}""")
        assertTrue(configuration.webScreenSaver)
        assertFalse(configuration.hasDimScreenSaver)
        assertEquals(ScreenSaverMode.WEB, ScreenSaverMode.of(configuration))

        apply("""{"screenSaverMode": "dim"}""")
        assertTrue(configuration.hasDimScreenSaver)
        assertFalse(configuration.webScreenSaver)
        assertEquals(ScreenSaverMode.DIM, ScreenSaverMode.of(configuration))

        apply("""{"screenSaverMode": "off"}""")
        assertEquals(ScreenSaverMode.OFF, ScreenSaverMode.of(configuration))
        assertFalse(configuration.hasClockScreenSaver)
    }

    @Test
    fun `the clock stays on the wallpaper`() {
        apply("""{"screenSaverMode": "clock"}""")
        apply("""{"screenSaverMode": "wallpaper"}""")
        assertTrue(configuration.hasClockScreenSaver)
        assertTrue(configuration.hasScreenSaverWallpaper)
        assertEquals(ScreenSaverMode.WALLPAPER, ScreenSaverMode.of(configuration))
    }

    @Test
    fun `inactivity time is taken in seconds and stored in milliseconds`() {
        assertEquals(setOf("inactivityTime"), apply("""{"inactivityTime": 120}"""))
        assertEquals(120_000L, configuration.inactivityTime)
        // As Home Assistant may render it, and as text
        apply("""{"inactivityTime": 90.0}""")
        assertEquals(90_000L, configuration.inactivityTime)
        apply("""{"inactivityTime": "45"}""")
        assertEquals(45_000L, configuration.inactivityTime)
    }

    @Test
    fun `values out of range are ignored and the rest still applies`() {
        configuration.inactivityTime = 30_000L
        configuration.screenSaverDimValue = 25
        val applied = apply("""{"inactivityTime": 1, "screenSaverDim": 100, "preventSleep": true, "darkTheme": "maybe", "volume": 3}""")
        assertEquals(setOf("preventSleep"), applied)
        assertEquals(30_000L, configuration.inactivityTime)
        assertEquals(25, configuration.screenSaverDimValue)
        assertTrue(configuration.appPreventSleep)
        assertFalse(configuration.useDarkTheme)
    }

    @Test
    fun `booleans are taken as json or as text`() {
        apply("""{"darkTheme": "ON", "preventSleep": "false"}""")
        assertTrue(configuration.useDarkTheme)
        assertFalse(configuration.appPreventSleep)
    }

    @Test
    fun `an empty screensaver url is ignored`() {
        val before = configuration.webScreenSaverUrl
        assertTrue(apply("""{"screenSaverUrl": "  "}""").isEmpty())
        assertEquals(before, configuration.webScreenSaverUrl)
        apply("""{"screenSaverUrl": " http://ha.local/photos "}""")
        assertEquals("http://ha.local/photos", configuration.webScreenSaverUrl)
    }

    @Test
    fun `state reports what the entities read back`() {
        apply("""{"screenSaverMode": "dim", "inactivityTime": 60, "screenSaverDim": 40, "preventSleep": true, "darkTheme": true}""")
        val state = JSONObject()
        remoteConfig.putState(state)
        assertEquals("dim", state.getString("screenSaverMode"))
        assertEquals(60L, state.getLong("inactivityTime"))
        assertEquals(40, state.getInt("screenSaverDim"))
        assertTrue(state.getBoolean("preventSleep"))
        assertTrue(state.getBoolean("darkTheme"))
    }
}
