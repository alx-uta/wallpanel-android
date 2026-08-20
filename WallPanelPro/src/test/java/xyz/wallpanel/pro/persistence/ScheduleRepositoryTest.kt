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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

/**
 * Exercises [ScheduleRepository] against a real SharedPreferences under Robolectric, in the
 * same shape as [ConfigurationTest].
 */
@RunWith(RobolectricTestRunner::class)
class ScheduleRepositoryTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var repository: ScheduleRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
        repository = ScheduleRepository(context, preferences)
    }

    @Test
    fun `getAll is empty on a fresh install`() {
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun `saves and reads back a schedule`() {
        val schedule = Schedule(hour = 7, minute = 0, action = ScheduleAction.RESTART_APP)

        repository.save(schedule)

        assertEquals(listOf(schedule), repository.getAll())
    }

    @Test
    fun `saving keeps insertion order`() {
        val first = Schedule(id = "first", hour = 7, minute = 0, action = ScheduleAction.RELOAD)
        val second = Schedule(id = "second", hour = 8, minute = 0, action = ScheduleAction.CLEAR_CACHE)

        repository.save(first)
        repository.save(second)

        assertEquals(listOf("first", "second"), repository.getAll().map { it.id })
    }

    @Test
    fun `saving an existing id upserts rather than duplicating`() {
        val schedule = Schedule(id = "same", hour = 7, minute = 0, action = ScheduleAction.RELOAD)
        repository.save(schedule)

        repository.save(schedule.copy(hour = 9, minute = 45, days = setOf(Calendar.MONDAY)))

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals(9, all[0].hour)
        assertEquals(45, all[0].minute)
        assertEquals(setOf(Calendar.MONDAY), all[0].days)
    }

    @Test
    fun `deletes by id and leaves the others alone`() {
        repository.save(Schedule(id = "keep", hour = 7, minute = 0, action = ScheduleAction.RELOAD))
        repository.save(Schedule(id = "drop", hour = 8, minute = 0, action = ScheduleAction.RELOAD))

        repository.delete("drop")

        assertEquals(listOf("keep"), repository.getAll().map { it.id })
    }

    @Test
    fun `deleting an unknown id changes nothing`() {
        repository.save(Schedule(id = "keep", hour = 7, minute = 0, action = ScheduleAction.RELOAD))

        repository.delete("never-existed")

        assertEquals(listOf("keep"), repository.getAll().map { it.id })
    }

    @Test
    fun `getEnabled filters out disabled schedules`() {
        repository.save(Schedule(id = "on", hour = 7, minute = 0, action = ScheduleAction.RELOAD))
        repository.save(Schedule(id = "off", enabled = false, hour = 8, minute = 0, action = ScheduleAction.RELOAD))

        assertEquals(listOf("on"), repository.getEnabled().map { it.id })
    }

    @Test
    fun `a new repository instance sees the persisted schedules`() {
        val schedule = Schedule(id = "persisted", hour = 6, minute = 30, action = ScheduleAction.URL, payload = "https://wallpanel.xyz")
        repository.save(schedule)

        // Confirms the write actually landed in preferences rather than in-memory state.
        assertEquals(listOf(schedule), ScheduleRepository(context, preferences).getAll())
    }
}
