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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.pro.R

/**
 * Writes the settings to a JSON file and reads them back, to set up another device the same
 * way or this one again after a reinstall.
 *
 * Each setting is stored with its type, so it comes back as the same kind of preference:
 *
 * ```
 * {"app": "WallPanel", "format": 1, "settings": {"setting_app_launchurl": {"type": "string", "value": "http://..."}}}
 * ```
 *
 * [secret] settings are only written when asked for. [identity] settings name the device
 * to the MQTT broker and Home Assistant, and two devices sharing them knock each other off
 * the broker, so an import can leave them as they are. [internal] keys never travel; the
 * application's own bookkeeping is kept out by [Configuration] already.
 */
class SettingsBackup(
    private val secret: Set<String>,
    private val identity: Set<String>,
    private val internal: Set<String>,
) {

    fun export(settings: Map<String, *>, includeSecrets: Boolean, appVersion: String): JSONObject {
        val entries = JSONObject()
        settings.keys.sorted().forEach { key ->
            if (key in internal || (!includeSecrets && key in secret)) {
                return@forEach
            }
            encode(settings[key])?.let { entries.put(key, it) }
        }
        return JSONObject()
            .put(FIELD_APP, APP)
            .put(FIELD_FORMAT, FORMAT)
            .put(FIELD_VERSION, appVersion)
            .put(FIELD_SETTINGS, entries)
    }

    /**
     * The settings in [file] to write, by key. Entries of a type this does not know, or
     * whose value does not fit their type, are left out.
     *
     * @throws IllegalArgumentException when [file] is not a WallPanel settings file.
     */
    fun read(file: JSONObject, keepIdentity: Boolean): Map<String, Any> {
        require(file.optString(FIELD_APP) == APP) { "Not a WallPanel settings file" }
        require(file.optInt(FIELD_FORMAT) == FORMAT) { "Settings file format ${file.opt(FIELD_FORMAT)} is not supported" }
        val entries = file.optJSONObject(FIELD_SETTINGS) ?: throw IllegalArgumentException("The file holds no settings")
        val values = mutableMapOf<String, Any>()
        entries.keys().forEach { key ->
            if (key in internal || (keepIdentity && key in identity)) {
                return@forEach
            }
            entries.optJSONObject(key)?.let { decode(it) }?.let { values[key] = it }
        }
        return values
    }

    private fun encode(value: Any?): JSONObject? {
        val (type, encoded) = when (value) {
            is String -> TYPE_STRING to value
            is Boolean -> TYPE_BOOLEAN to value
            is Int -> TYPE_INT to value
            is Long -> TYPE_LONG to value
            is Float -> TYPE_FLOAT to value.toDouble()
            is Set<*> -> TYPE_STRING_SET to JSONArray(value.filterIsInstance<String>().sorted())
            else -> return null
        }
        return JSONObject().put(FIELD_TYPE, type).put(FIELD_VALUE, encoded)
    }

    private fun decode(entry: JSONObject): Any? {
        val value = entry.opt(FIELD_VALUE) ?: return null
        return when (entry.optString(FIELD_TYPE)) {
            TYPE_STRING -> value as? String
            TYPE_BOOLEAN -> value as? Boolean
            TYPE_INT -> (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()
            TYPE_LONG -> (value as? Number)?.toLong()
            TYPE_FLOAT -> (value as? Number)?.toFloat()
            TYPE_STRING_SET -> (value as? JSONArray)?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it) as? String }.toSet()
            }
            else -> null
        }
    }

    companion object {
        const val APP = "WallPanel"
        const val FORMAT = 1
        private const val FIELD_APP = "app"
        private const val FIELD_FORMAT = "format"
        private const val FIELD_VERSION = "version"
        private const val FIELD_SETTINGS = "settings"
        private const val FIELD_TYPE = "type"
        private const val FIELD_VALUE = "value"
        private const val TYPE_STRING = "string"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_INT = "int"
        private const val TYPE_LONG = "long"
        private const val TYPE_FLOAT = "float"
        private const val TYPE_STRING_SET = "stringSet"

        fun forApp(context: Context): SettingsBackup {
            val secret = setOf(
                context.getString(R.string.key_setting_mqtt_password),
                context.getString(R.string.key_setting_http_token),
                context.getString(R.string.key_setting_speech_ha_token),
                context.getString(R.string.key_setting_browser_login_password),
                Configuration.PREF_SETTINGS_CODE_STRING,
            )
            val identity = setOf(
                context.getString(R.string.key_setting_mqtt_clientid),
                context.getString(R.string.key_setting_mqtt_basetopic),
                context.getString(R.string.key_setting_mqtt_discovery_name),
            )
            return SettingsBackup(secret, identity, emptySet())
        }

        /**
         * The [values] that can be written, and the keys left out. The application reads each
         * setting as one type and anything else throws on every read, which on a setting read
         * at start takes the kiosk down until its data is cleared. So a value has to be of the
         * type already [stored] under its key, or, for a key not stored yet, of the type
         * [expected] for it. A key neither knows is not a setting this version keeps.
         */
        fun compatible(values: Map<String, Any>, stored: Map<String, *>, expected: Map<String, SettingType>): Imported {
            val accepted = mutableMapOf<String, Any>()
            val skipped = mutableListOf<String>()
            values.forEach { (key, value) ->
                val wanted = SettingType.of(stored[key]) ?: expected[key]
                val actual = SettingType.of(value)
                when {
                    wanted == null -> {
                        Timber.w("Not importing $key: not a setting this version keeps")
                        skipped += key
                    }
                    actual != wanted -> {
                        Timber.w("Not importing $key: a $actual where a $wanted is kept")
                        skipped += key
                    }
                    else -> accepted[key] = value
                }
            }
            return Imported(accepted, skipped.sorted())
        }
    }

    /** What [compatible] lets through, and the keys it left out. */
    class Imported(val values: Map<String, Any>, val skipped: List<String>)
}

/** The kinds of value a setting is kept as. */
enum class SettingType {
    STRING, BOOLEAN, INT, LONG, FLOAT, STRING_SET;

    companion object {
        fun of(value: Any?): SettingType? = when (value) {
            is String -> STRING
            is Boolean -> BOOLEAN
            is Int -> INT
            is Long -> LONG
            is Float -> FLOAT
            is Set<*> -> STRING_SET
            else -> null
        }

        /**
         * The type a settings screen element keeps its value as, by its tag in the preference
         * XML, or null for one that keeps none, such as a button or a category.
         */
        fun ofPreference(tag: String): SettingType? = when (tag.substringAfterLast('.')) {
            "SwitchPreference", "SwitchPreferenceCompat", "CheckBoxPreference" -> BOOLEAN
            "EditTextPreference", "NumberEditTextPreference", "ListPreference", "DropDownPreference" -> STRING
            "MultiSelectListPreference" -> STRING_SET
            "SeekBarPreference" -> INT
            else -> null
        }
    }
}
