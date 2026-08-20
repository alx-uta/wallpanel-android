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

package xyz.wallpanel.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.pro.network.WallPanelService
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.persistence.Schedule
import xyz.wallpanel.pro.persistence.ScheduleAction
import xyz.wallpanel.pro.persistence.ScheduleRepository
import xyz.wallpanel.pro.utils.ScheduledTaskAlarmScheduler

/**
 * Runs one scheduled task and re-arms its next occurrence. The receiver is created by the
 * system, so it builds what it needs from the default preferences rather than from Dagger.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(ScheduledTaskAlarmScheduler.EXTRA_SCHEDULE_ID)
        if (scheduleId.isNullOrEmpty()) {
            Timber.w("Scheduled task alarm arrived without a schedule id")
            return
        }

        val applicationContext = context.applicationContext
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val schedule = ScheduleRepository(applicationContext, sharedPreferences).get(scheduleId)
        if (schedule == null) {
            Timber.w("Scheduled task $scheduleId no longer exists, dropping the alarm")
            return
        }
        if (!schedule.enabled) {
            Timber.i("Scheduled task $scheduleId is disabled, skipping it")
            return
        }

        val configuration = Configuration(applicationContext, sharedPreferences)
        val command = buildCommand(schedule, configuration)
        if (command != null) {
            runCommand(applicationContext, command)
        }

        // Alarms are one shot, so the next occurrence is armed right after this one fires.
        ScheduledTaskAlarmScheduler.schedule(applicationContext, schedule)
    }

    private fun buildCommand(schedule: Schedule, configuration: Configuration): JSONObject? {
        if (schedule.action.requiresPayload() && schedule.payload.isEmpty()) {
            Timber.w("Scheduled task ${schedule.id} has no payload for ${schedule.action.command}")
            return null
        }
        if (schedule.action == ScheduleAction.SHELL && !configuration.httpShellEnabled) {
            Timber.w("Scheduled task ${schedule.id} wants a shell command, but shell commands are disabled")
            return null
        }
        val command = JSONObject()
        when (schedule.action) {
            ScheduleAction.URL, ScheduleAction.SHELL -> command.put(schedule.action.command, schedule.payload)
            else -> command.put(schedule.action.command, true)
        }
        return command
    }

    private fun runCommand(context: Context, command: JSONObject) {
        val intent = Intent(context, WallPanelService::class.java)
        intent.action = WallPanelService.ACTION_RUN_COMMAND
        intent.putExtra(WallPanelService.EXTRA_COMMAND_JSON, command.toString())
        try {
            ContextCompat.startForegroundService(context, intent)
            Timber.i("Dispatched scheduled command $command")
        } catch (e: Exception) {
            Timber.e(e, "Could not dispatch the scheduled command $command")
        }
    }
}
