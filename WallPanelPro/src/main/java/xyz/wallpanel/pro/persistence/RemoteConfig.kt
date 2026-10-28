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

import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_DARK_THEME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_INACTIVITY_TIME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_PREVENT_SLEEP
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_SCREENSAVER_DIM
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_SCREENSAVER_MODE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.STATE_SCREENSAVER_URL

/**
 * The screensaver as one choice. The settings screen keeps it as four switches, of which
 * dim, web and wallpaper exclude each other and the clock can sit on top of the wallpaper.
 */
enum class ScreenSaverMode(val id: String) {
    OFF("off"),
    CLOCK("clock"),
    DIM("dim"),
    WALLPAPER("wallpaper"),
    WEB("web");

    companion object {
        fun parse(value: String): ScreenSaverMode? = values().firstOrNull { it.id == value.trim().lowercase() }

        /** In the order the browser picks one when more than one switch is on. */
        fun of(configuration: Configuration): ScreenSaverMode = when {
            configuration.hasDimScreenSaver -> DIM
            configuration.webScreenSaver -> WEB
            configuration.hasScreenSaverWallpaper -> WALLPAPER
            configuration.hasClockScreenSaver -> CLOCK
            else -> OFF
        }
    }
}

/**
 * The settings the `config` command may change, which are the ones a day and night routine
 * wants to move: the screensaver, its timeout and dim level, keeping the screen on, and the
 * theme. Anything else in the command is ignored and logged, and so is a value that does
 * not fit, without stopping the rest of the command.
 */
class RemoteConfig(private val configuration: Configuration) {

    /** Applies what [settings] holds and returns the keys that took. */
    fun apply(settings: JSONObject): Set<String> {
        val applied = mutableSetOf<String>()
        for (key in settings.keys()) {
            val value = settings.opt(key)
            val ok = when (key) {
                STATE_SCREENSAVER_MODE -> (value as? String)?.let { ScreenSaverMode.parse(it) }?.let { setMode(it); true }
                STATE_INACTIVITY_TIME -> number(value)
                    ?.takeIf { it in MIN_INACTIVITY_SECONDS..MAX_INACTIVITY_SECONDS }
                    ?.let { configuration.inactivityTime = it * 1000L; true }
                STATE_SCREENSAVER_DIM -> number(value)
                    ?.takeIf { it in 0..MAX_DIM }
                    ?.let { configuration.screenSaverDimValue = it.toInt(); true }
                STATE_PREVENT_SLEEP -> bool(value)?.let { configuration.appPreventSleep = it; true }
                STATE_DARK_THEME -> bool(value)?.let { configuration.useDarkTheme = it; true }
                STATE_SCREENSAVER_URL -> (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { configuration.webScreenSaverUrl = it; true }
                else -> {
                    Timber.w("The config command cannot change $key")
                    continue
                }
            }
            if (ok == true) {
                applied += key
            } else {
                Timber.w("Ignoring config $key: $value is not a value it takes")
            }
        }
        return applied
    }

    /** Adds the current values to the application state, where the Home Assistant entities read them. */
    fun putState(state: JSONObject) {
        state.put(STATE_SCREENSAVER_MODE, ScreenSaverMode.of(configuration).id)
        state.put(STATE_INACTIVITY_TIME, configuration.inactivityTime / 1000)
        state.put(STATE_SCREENSAVER_DIM, configuration.screenSaverDimValue)
        state.put(STATE_PREVENT_SLEEP, configuration.appPreventSleep)
        state.put(STATE_DARK_THEME, configuration.useDarkTheme)
    }

    /**
     * Sets the switches the same way the settings screen does. The clock is left alone for
     * dim and web, which never show it, so going back to the wallpaper keeps a clock on it.
     */
    private fun setMode(mode: ScreenSaverMode) {
        configuration.hasDimScreenSaver = mode == ScreenSaverMode.DIM
        configuration.webScreenSaver = mode == ScreenSaverMode.WEB
        configuration.hasScreenSaverWallpaper = mode == ScreenSaverMode.WALLPAPER
        when (mode) {
            ScreenSaverMode.OFF -> configuration.hasClockScreenSaver = false
            ScreenSaverMode.CLOCK -> configuration.hasClockScreenSaver = true
            else -> {}
        }
    }

    // Home Assistant renders numbers into the payload, people typing a command send either
    private fun number(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Double -> value.takeIf { it % 1.0 == 0.0 }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "on" -> true
            "false", "off" -> false
            else -> null
        }
        else -> null
    }

    companion object {
        const val MIN_INACTIVITY_SECONDS = 5L
        const val MAX_INACTIVITY_SECONDS = 86_400L
        // The settings screen offers up to 99: at 100 the dim screensaver is a black screen
        const val MAX_DIM = 99L

        /** Keys that change the screensaver on view, which has to be put up again to show it. */
        val SCREENSAVER_KEYS = setOf(STATE_SCREENSAVER_MODE, STATE_SCREENSAVER_URL, STATE_PREVENT_SLEEP)
    }
}
