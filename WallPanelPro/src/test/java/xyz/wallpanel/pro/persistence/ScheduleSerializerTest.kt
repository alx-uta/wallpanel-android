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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Covers the JSON encoding of scheduled tasks. Runs on the JVM against the real org.json,
 * no emulator and no Robolectric.
 */
class ScheduleSerializerTest {

    @Test
    fun `round-trips a single schedule`() {
        val schedule = Schedule(
            id = "abc-123",
            enabled = false,
            hour = 7,
            minute = 5,
            days = setOf(Calendar.MONDAY, Calendar.FRIDAY),
            action = ScheduleAction.URL,
            payload = "https://wallpanel.xyz"
        )

        val decoded = ScheduleSerializer.fromJsonArray(ScheduleSerializer.toJsonArray(listOf(schedule)))

        assertEquals(listOf(schedule), decoded)
    }

    @Test
    fun `round-trips many schedules and preserves order`() {
        val schedules = listOf(
            Schedule(id = "one", hour = 6, minute = 0, action = ScheduleAction.RESTART_APP),
            Schedule(id = "two", hour = 12, minute = 30, action = ScheduleAction.RELOAD),
            Schedule(id = "three", hour = 23, minute = 59, action = ScheduleAction.SHELL, payload = "echo hi")
        )

        val decoded = ScheduleSerializer.fromJsonArray(ScheduleSerializer.toJsonArray(schedules))

        assertEquals(schedules, decoded)
        assertEquals(listOf("one", "two", "three"), decoded.map { it.id })
    }

    @Test
    fun `skips an entry with an unrecognized action instead of throwing`() {
        val json = JSONArray()
        json.put(
            JSONObject()
                .put("id", "future")
                .put("hour", 8)
                .put("minute", 0)
                .put("action", "somethingFromANewerVersion")
        )
        json.put(
            JSONObject()
                .put("id", "known")
                .put("hour", 9)
                .put("minute", 15)
                .put("action", "reload")
        )

        val decoded = ScheduleSerializer.fromJsonArray(json.toString())

        assertEquals(1, decoded.size)
        assertEquals("known", decoded[0].id)
        assertEquals(ScheduleAction.RELOAD, decoded[0].action)
    }

    @Test
    fun `applies defaults for fields omitted from the json`() {
        val json = JSONArray().put(
            JSONObject()
                .put("id", "minimal")
                .put("hour", 22)
                .put("minute", 45)
                .put("action", "clearCache")
        )

        val decoded = ScheduleSerializer.fromJsonArray(json.toString())

        assertEquals(1, decoded.size)
        val schedule = decoded[0]
        assertTrue(schedule.enabled)
        assertEquals(Schedule.ALL_DAYS, schedule.days)
        assertEquals("", schedule.payload)
    }

    @Test
    fun `defaults survive a round trip`() {
        val schedule = Schedule(id = "defaults", hour = 3, minute = 0, action = ScheduleAction.RELAUNCH)

        val decoded = ScheduleSerializer.fromJsonArray(ScheduleSerializer.toJsonArray(listOf(schedule)))

        assertEquals(listOf(schedule), decoded)
        assertTrue(decoded[0].enabled)
        assertEquals(Schedule.ALL_DAYS, decoded[0].days)
        assertEquals("", decoded[0].payload)
    }

    @Test
    fun `an explicitly empty day set is not replaced by the default`() {
        val json = JSONArray().put(
            JSONObject()
                .put("id", "nodays")
                .put("hour", 1)
                .put("minute", 0)
                .put("days", JSONArray())
                .put("action", "reload")
        )

        val decoded = ScheduleSerializer.fromJsonArray(json.toString())

        assertEquals(emptySet<Int>(), decoded[0].days)
    }

    @Test
    fun `an empty list encodes and decodes to an empty list`() {
        assertEquals(emptyList<Schedule>(), ScheduleSerializer.fromJsonArray(ScheduleSerializer.toJsonArray(emptyList())))
    }

    @Test
    fun `malformed json decodes to an empty list instead of throwing`() {
        assertEquals(emptyList<Schedule>(), ScheduleSerializer.fromJsonArray("not json at all"))
        assertEquals(emptyList<Schedule>(), ScheduleSerializer.fromJsonArray(""))
    }
}
