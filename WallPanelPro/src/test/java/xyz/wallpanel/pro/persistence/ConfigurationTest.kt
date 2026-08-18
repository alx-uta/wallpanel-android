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
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [Configuration] against a real SharedPreferences under Robolectric, without
 * an emulator. Doubles as the smoke test for the Robolectric and MockK setup.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigurationTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var configuration: Configuration

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        configuration = Configuration(context, preferences)
    }

    @Test
    fun `isFirstTime defaults to true on a fresh install`() {
        assertTrue(configuration.isFirstTime)
    }

    @Test
    fun `isFirstTime round-trips through SharedPreferences`() {
        configuration.isFirstTime = false

        assertFalse(configuration.isFirstTime)
        // Confirms the write actually landed in preferences rather than in-memory state.
        assertFalse(Configuration(context, preferences).isFirstTime)
    }

    @Test
    fun `reads a boolean preference that was set externally`() {
        val key = context.getString(xyz.wallpanel.pro.R.string.key_setting_app_preventsleep)
        preferences.edit().putBoolean(key, true).commit()

        assertTrue(configuration.appPreventSleep)
    }

    @Test
    fun `MockK can stand in for SharedPreferences`() {
        val fake = mockk<SharedPreferences>()
        every { fake.getBoolean(any(), any()) } returns false

        assertFalse(Configuration(context, fake).isFirstTime)
    }

    @Test
    fun `geckoViewSuspendSeconds defaults to 30`() {
        assertEquals(30, configuration.geckoViewSuspendSeconds)
    }

    @Test
    fun `geckoViewSuspendSeconds round-trips through SharedPreferences`() {
        val key = context.getString(xyz.wallpanel.pro.R.string.key_use_geckoview_suspend_seconds)
        preferences.edit().putString(key, "10").commit()

        assertEquals(10, configuration.geckoViewSuspendSeconds)
    }

    @Test
    fun `geckoViewSuspendSeconds of 0 disables suspension without falling back to the default`() {
        val key = context.getString(xyz.wallpanel.pro.R.string.key_use_geckoview_suspend_seconds)
        preferences.edit().putString(key, "0").commit()

        assertEquals(0, configuration.geckoViewSuspendSeconds)
    }

    @Test
    fun `geckoViewSuspendSeconds falls back to 30 on a malformed value`() {
        val key = context.getString(xyz.wallpanel.pro.R.string.key_use_geckoview_suspend_seconds)
        preferences.edit().putString(key, "not-a-number").commit()

        assertEquals(30, configuration.geckoViewSuspendSeconds)
    }
}
