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
import xyz.wallpanel.pro.R
import javax.inject.Inject

/**
 * Stores the scheduled tasks as one JSON array under a single preference key.
 */
class ScheduleRepository @Inject
constructor(private val context: Context, private val sharedPreferences: SharedPreferences) {

    fun getAll(): List<Schedule> {
        val stored = sharedPreferences.getString(scheduleKey(), "").orEmpty()
        return ScheduleSerializer.fromJsonArray(stored)
    }

    fun getEnabled(): List<Schedule> = getAll().filter { it.enabled }

    fun get(id: String): Schedule? = getAll().firstOrNull { it.id == id }

    /**
     * Replaces the schedule carrying the same id, or appends it when the id is new.
     */
    fun save(schedule: Schedule) {
        val schedules = getAll().toMutableList()
        val index = schedules.indexOfFirst { it.id == schedule.id }
        if (index >= 0) {
            schedules[index] = schedule
        } else {
            schedules.add(schedule)
        }
        write(schedules)
    }

    fun delete(id: String) {
        write(getAll().filterNot { it.id == id })
    }

    private fun write(schedules: List<Schedule>) {
        sharedPreferences.edit()
            .putString(scheduleKey(), ScheduleSerializer.toJsonArray(schedules))
            .apply()
    }

    private fun scheduleKey(): String = context.getString(R.string.key_setting_schedules)
}
