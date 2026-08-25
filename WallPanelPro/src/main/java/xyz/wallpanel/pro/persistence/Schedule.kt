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

import java.util.Calendar
import java.util.UUID

/**
 * A user defined task that runs at a time of day on a set of weekdays. [days] holds
 * [Calendar.DAY_OF_WEEK] values so the trigger maths composes directly with [Calendar].
 */
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = ALL_DAYS,
    val action: ScheduleAction,
    val payload: String = ""
) {
    companion object {
        val ALL_DAYS: Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
    }
}

/**
 * The command each schedule dispatches. [command] is the key WallPanelService already
 * understands, so a schedule reuses the same handling as an MQTT or HTTP command.
 */
enum class ScheduleAction(val command: String) {
    RESTART_APP("restartApp"),
    RELOAD("reload"),
    CLEAR_CACHE("clearCache"),
    RELAUNCH("relaunch"),
    URL("url"),
    SHELL("shell");

    /**
     * True when the action needs the user supplied [Schedule.payload] to mean anything.
     */
    fun requiresPayload(): Boolean = this == URL || this == SHELL

    companion object {
        fun fromCommand(command: String?): ScheduleAction? = values().firstOrNull { it.command == command }
    }
}
