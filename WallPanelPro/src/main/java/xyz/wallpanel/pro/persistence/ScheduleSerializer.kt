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
import timber.log.Timber
import java.util.UUID

/**
 * Encodes the schedule list as a single JSON array so it fits in one preference value.
 * Decoding drops entries it cannot understand and keeps the rest, so one bad or
 * newer-than-this-build entry never takes the whole list with it.
 */
object ScheduleSerializer {

    private const val KEY_ID = "id"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_DAYS = "days"
    private const val KEY_ACTION = "action"
    private const val KEY_PAYLOAD = "payload"

    fun toJsonArray(schedules: List<Schedule>): String {
        val array = JSONArray()
        for (schedule in schedules) {
            val days = JSONArray()
            for (day in schedule.days.sorted()) {
                days.put(day)
            }
            val json = JSONObject()
            json.put(KEY_ID, schedule.id)
            json.put(KEY_ENABLED, schedule.enabled)
            json.put(KEY_HOUR, schedule.hour)
            json.put(KEY_MINUTE, schedule.minute)
            json.put(KEY_DAYS, days)
            json.put(KEY_ACTION, schedule.action.command)
            json.put(KEY_PAYLOAD, schedule.payload)
            array.put(json)
        }
        return array.toString()
    }

    fun fromJsonArray(json: String): List<Schedule> {
        if (json.isEmpty()) {
            return emptyList()
        }
        val schedules = ArrayList<Schedule>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i)
                if (entry == null) {
                    Timber.w("Skipping schedule entry at index $i, it is not an object")
                    continue
                }
                val action = ScheduleAction.fromCommand(entry.optString(KEY_ACTION))
                if (action == null) {
                    Timber.w("Skipping schedule with unknown action ${entry.optString(KEY_ACTION)}")
                    continue
                }
                schedules.add(
                    Schedule(
                        id = entry.optString(KEY_ID, "").ifEmpty { UUID.randomUUID().toString() },
                        enabled = entry.optBoolean(KEY_ENABLED, true),
                        hour = entry.optInt(KEY_HOUR, 0),
                        minute = entry.optInt(KEY_MINUTE, 0),
                        days = readDays(entry),
                        action = action,
                        payload = entry.optString(KEY_PAYLOAD, "")
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not read the stored schedules, starting from an empty list")
            return emptyList()
        }
        return schedules
    }

    /**
     * An absent day list means the schedule predates the field or was written by hand, and
     * every day is the sensible reading. An empty list is a deliberate choice and stays empty.
     */
    private fun readDays(entry: JSONObject): Set<Int> {
        val days = entry.optJSONArray(KEY_DAYS) ?: return Schedule.ALL_DAYS
        val values = LinkedHashSet<Int>()
        for (i in 0 until days.length()) {
            values.add(days.optInt(i))
        }
        return values
    }
}
