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
         * The [values] that can go over [stored]: a key already stored keeps its type, since
         * the application reads it as that type and anything else would throw on every read.
         */
        fun compatible(values: Map<String, Any>, stored: Map<String, *>): Map<String, Any> {
            return values.filter { (key, value) ->
                val current = stored[key] ?: return@filter true
                val fits = when (current) {
                    is Set<*> -> value is Set<*>
                    else -> current.javaClass == value.javaClass
                }
                if (!fits) {
                    Timber.w("Not importing $key: a ${value.javaClass.simpleName} where a ${current.javaClass.simpleName} is kept")
                }
                fits
            }
        }
    }
}
