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

package xyz.wallpanel.pro.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.wallpanel.pro.persistence.Schedule
import xyz.wallpanel.pro.persistence.ScheduleAction
import java.util.Calendar
import java.util.TimeZone

/**
 * Covers the trigger-time maths of the task scheduler. This is the pure part, so it runs on
 * the JVM with a fixed time zone and no Android framework.
 */
class ScheduledTaskAlarmSchedulerTest {

    private val zone: TimeZone = TimeZone.getTimeZone("UTC")

    // 1 January 2024 is a Monday, which keeps the weekday expectations below readable.
    private fun at(day: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.clear()
        calendar.set(2024, Calendar.JANUARY, day, hour, minute, 0)
        return calendar.timeInMillis
    }

    private fun schedule(
        hour: Int,
        minute: Int,
        days: Set<Int> = Schedule.ALL_DAYS,
        enabled: Boolean = true
    ) = Schedule(
        id = "test",
        enabled = enabled,
        hour = hour,
        minute = minute,
        days = days,
        action = ScheduleAction.RELOAD
    )

    @Test
    fun `a time later today fires today`() {
        val now = at(1, 8, 0)

        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(schedule(9, 30), now, zone)

        assertEquals(at(1, 9, 30), next)
    }

    @Test
    fun `a time that has already passed fires tomorrow`() {
        val now = at(1, 10, 0)

        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(schedule(9, 30), now, zone)

        assertEquals(at(2, 9, 30), next)
    }

    @Test
    fun `now exactly at the scheduled instant counts as already passed`() {
        val now = at(1, 9, 30)

        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(schedule(9, 30), now, zone)

        assertEquals(at(2, 9, 30), next)
    }

    @Test
    fun `a single weekday fires on the next matching day`() {
        // Monday 1 January, looking for Wednesday.
        val now = at(1, 8, 0)

        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(
            schedule(9, 30, days = setOf(Calendar.WEDNESDAY)), now, zone
        )

        assertEquals(at(3, 9, 30), next)
    }

    @Test
    fun `a saturday-only schedule wraps into the following week`() {
        // Saturday 6 January, after the scheduled time.
        val now = at(6, 10, 0)

        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(
            schedule(9, 30, days = setOf(Calendar.SATURDAY)), now, zone
        )

        assertEquals(at(13, 9, 30), next)
    }

    @Test
    fun `a saturday-only schedule still fires today when the time is ahead`() {
        val now = at(6, 8, 0)

        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(
            schedule(9, 30, days = setOf(Calendar.SATURDAY)), now, zone
        )

        assertEquals(at(6, 9, 30), next)
    }

    @Test
    fun `a disabled schedule never triggers`() {
        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(
            schedule(9, 30, enabled = false), at(1, 8, 0), zone
        )

        assertNull(next)
    }

    @Test
    fun `a schedule with no days never triggers`() {
        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(
            schedule(9, 30, days = emptySet()), at(1, 8, 0), zone
        )

        assertNull(next)
    }

    @Test
    fun `midnight on a weekday-only schedule resolves to the start of that day`() {
        // Friday 5 January at 23:00, weekdays only, so the next slot is Monday 8 January.
        val now = at(5, 23, 0)

        val next = ScheduledTaskAlarmScheduler.nextTriggerMillis(
            schedule(
                0, 0,
                days = setOf(
                    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                    Calendar.THURSDAY, Calendar.FRIDAY
                )
            ),
            now, zone
        )

        assertEquals(at(8, 0, 0), next)
    }
}
