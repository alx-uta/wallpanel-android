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

package xyz.wallpanel.pro.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.ui.activities.BrowserActivityNative
import xyz.wallpanel.pro.ui.activities.SettingsActivity
import xyz.wallpanel.pro.ui.fragments.SettingsFragment
import xyz.wallpanel.pro.utils.LauncherShortcuts

@RunWith(RobolectricTestRunner::class)
class SettingsShortcutTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Settings open without asking for the code, so the preferences are on screen
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(context.getString(R.string.key_setting_settings_code_required), false)
            .commit()
    }

    @Test
    fun shortcutOpensTheSettingsOverTheDashboard() {
        val intents = LauncherShortcuts.settingsShortcutIntents(context)

        assertEquals(2, intents.size)
        assertEquals(BrowserActivityNative::class.java.name, intents[0].component?.className)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            intents[0].flags
        )
        assertEquals(SettingsActivity::class.java.name, intents[1].component?.className)
    }

    @Test
    fun publishedShortcutCarriesTheDashboardUnderTheSettings() {
        LauncherShortcuts.createShortcuts(context)

        val shortcut = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single()
        val classes = shortcut.intents!!.map { it.component?.className }
        assertEquals(
            listOf(BrowserActivityNative::class.java.name, SettingsActivity::class.java.name),
            classes
        )
    }

    @Test
    @Config(sdk = [24])
    fun summariesLeaveOutTheShortcutBeforeAndroid71() {
        assertSummaries(
            R.string.preference_summary_settings_button_disable,
            R.string.preference_summary_settings_code_required
        )
    }

    @Test
    @Config(sdk = [25])
    fun summariesMentionTheShortcutFromAndroid71() {
        assertSummaries(
            R.string.preference_summary_settings_button_disable_shortcut,
            R.string.preference_summary_settings_code_required_shortcut
        )
    }

    private fun assertSummaries(disableSummary: Int, codeSummary: Int) {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val host = activity.supportFragmentManager.findFragmentById(R.id.my_nav_host_fragment)!!
        val fragment = host.childFragmentManager.fragments.filterIsInstance<SettingsFragment>().single()

        val disable = fragment.findPreference<Preference>(SettingsFragment.PREF_SETTINGS_BUTTON_DISABLE)!!
        val code = fragment.findPreference<Preference>(context.getString(R.string.key_setting_settings_code_required))!!
        assertEquals(context.getString(disableSummary), disable.summary)
        assertEquals(context.getString(codeSummary), code.summary)
    }
}
