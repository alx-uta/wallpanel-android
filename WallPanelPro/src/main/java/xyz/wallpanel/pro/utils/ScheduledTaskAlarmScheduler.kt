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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber
import xyz.wallpanel.pro.ScheduledTaskReceiver
import xyz.wallpanel.pro.persistence.Schedule
import xyz.wallpanel.pro.persistence.ScheduleRepository
import java.util.Calendar
import java.util.TimeZone

/**
 * Arms one AlarmManager alarm per enabled schedule. Alarms are inexact
 * ([AlarmManager.setAndAllowWhileIdle]): they survive Doze but need no
 * SCHEDULE_EXACT_ALARM grant, and firing a few minutes late is fine for restart, reload
 * and URL switching.
 *
 * Alarms are dropped by the system on reboot, so BootUpReceiver re-arms them, and
 * WallPanelService does the same on start as a safety net.
 */
object ScheduledTaskAlarmScheduler {

    const val EXTRA_SCHEDULE_ID = "scheduleId"

    /**
     * The next instant this schedule should fire, or null when it never will. An instant
     * exactly equal to [nowMillis] counts as already passed, so the receiver re-arming
     * itself right after firing cannot land on the same instant twice.
     */
    fun nextTriggerMillis(
        schedule: Schedule,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (!schedule.enabled || schedule.days.isEmpty()) {
            return null
        }
        // Today plus a full week, so a schedule whose only day is today but whose time has
        // passed lands on the same weekday next week.
        for (offset in 0..7) {
            val candidate = Calendar.getInstance(zone)
            candidate.timeInMillis = nowMillis
            candidate.add(Calendar.DAY_OF_YEAR, offset)
            candidate.set(Calendar.HOUR_OF_DAY, schedule.hour)
            candidate.set(Calendar.MINUTE, schedule.minute)
            candidate.set(Calendar.SECOND, 0)
            candidate.set(Calendar.MILLISECOND, 0)
            if (schedule.days.contains(candidate.get(Calendar.DAY_OF_WEEK)) && candidate.timeInMillis > nowMillis) {
                return candidate.timeInMillis
            }
        }
        return null
    }

    fun schedule(context: Context, schedule: Schedule) {
        val triggerMillis = nextTriggerMillis(schedule, System.currentTimeMillis())
        if (triggerMillis == null) {
            cancel(context, schedule.id)
            return
        }
        val alarmManager = alarmManager(context) ?: return
        val pendingIntent = pendingIntent(context, schedule.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
            Timber.d("Scheduled task ${schedule.id} (${schedule.action.command}) for ${Calendar.getInstance().apply { timeInMillis = triggerMillis }.time}")
        } catch (e: Exception) {
            Timber.e(e, "Could not schedule task ${schedule.id}")
        }
    }

    fun cancel(context: Context, scheduleId: String) {
        val alarmManager = alarmManager(context) ?: return
        try {
            alarmManager.cancel(pendingIntent(context, scheduleId))
            Timber.d("Cancelled scheduled task $scheduleId")
        } catch (e: Exception) {
            Timber.e(e, "Could not cancel task $scheduleId")
        }
    }

    /**
     * Re-arms every enabled schedule and clears the alarms of the disabled ones.
     */
    fun scheduleAll(context: Context, repository: ScheduleRepository) {
        for (schedule in repository.getAll()) {
            if (schedule.enabled) {
                schedule(context, schedule)
            } else {
                cancel(context, schedule.id)
            }
        }
    }

    private fun alarmManager(context: Context): AlarmManager? =
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun pendingIntent(context: Context, scheduleId: String): PendingIntent {
        val intent = Intent(context.applicationContext, ScheduledTaskReceiver::class.java)
        intent.putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // The request code keeps each schedule's alarm distinct from the others.
        return PendingIntent.getBroadcast(context.applicationContext, scheduleId.hashCode(), intent, flags)
    }
}
