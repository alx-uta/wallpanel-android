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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val backup = SettingsBackup(
        secret = setOf("mqtt_password", "code"),
        identity = setOf("client_id"),
        internal = setOf("first_time", "c"),
    )

    private val settings: Map<String, Any?> = mapOf(
        "url" to "http://ha.local:8123/",
        "fullscreen" to true,
        "location" to 2,
        "big" to 12345678901L,
        "rotate" to 90.5f,
        "sensors" to setOf("b", "a"),
        "mqtt_password" to "pw",
        "code" to "1234",
        "client_id" to "kitchen",
        "first_time" to false,
        "c" to true,
    )

    @Test
    fun `settings come back as they went, with their types`() {
        val file = JSONObject(backup.export(settings, includeSecrets = true, appVersion = "1.0").toString())
        val read = backup.read(file, keepIdentity = false)
        assertEquals(settings - "first_time" - "c", read)
        assertTrue(read["location"] is Int)
        assertTrue(read["big"] is Long)
        assertTrue(read["rotate"] is Float)
    }

    @Test
    fun `secrets stay behind unless asked for`() {
        val entries = backup.export(settings, includeSecrets = false, appVersion = "1.0").getJSONObject("settings")
        assertFalse(entries.has("mqtt_password"))
        assertFalse(entries.has("code"))
        assertTrue(entries.has("url"))
    }

    @Test
    fun `internal bookkeeping never travels`() {
        val entries = backup.export(settings, includeSecrets = true, appVersion = "1.0").getJSONObject("settings")
        assertFalse(entries.has("first_time"))
        assertFalse(entries.has("c"))
        // Nor is it taken from a file that has it
        entries.put("c", JSONObject().put("type", "boolean").put("value", true))
        val file = JSONObject().put("app", "WallPanel").put("format", 1).put("settings", entries)
        assertFalse(backup.read(file, keepIdentity = false).containsKey("c"))
    }

    @Test
    fun `the device can keep its own identity`() {
        val file = backup.export(settings, includeSecrets = true, appVersion = "1.0")
        assertFalse(backup.read(file, keepIdentity = true).containsKey("client_id"))
        assertEquals("kitchen", backup.read(file, keepIdentity = false)["client_id"])
    }

    @Test
    fun `entries that do not fit their type are skipped`() {
        val entries = JSONObject()
            .put("a", JSONObject().put("type", "int").put("value", "seven"))
            .put("b", JSONObject().put("type", "int").put("value", 2.5))
            .put("c2", JSONObject().put("type", "colour").put("value", "red"))
            .put("d", JSONObject().put("type", "boolean").put("value", false))
            .put("e", "not an entry")
        val file = JSONObject().put("app", "WallPanel").put("format", 1).put("settings", entries)
        assertEquals(mapOf("d" to false), backup.read(file, keepIdentity = false))
    }

    @Test
    fun `a stored setting keeps its type`() {
        val stored = mapOf("dark" to false, "url" to "http://a/", "sensors" to setOf("x"))
        val values = mapOf("dark" to "true", "url" to "http://b/", "sensors" to setOf("y"), "new" to 3)
        assertEquals(
            mapOf("url" to "http://b/", "sensors" to setOf("y"), "new" to 3),
            SettingsBackup.compatible(values, stored)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `another app's file is refused`() {
        backup.read(JSONObject().put("app", "Fully").put("format", 1).put("settings", JSONObject()), keepIdentity = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a newer format is refused`() {
        backup.read(JSONObject().put("app", "WallPanel").put("format", 2).put("settings", JSONObject()), keepIdentity = true)
    }
}
