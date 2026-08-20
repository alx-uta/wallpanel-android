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

package xyz.wallpanel.pro.ui.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.databinding.ItemScheduleBinding
import xyz.wallpanel.pro.persistence.Schedule
import java.util.Calendar

/**
 * Lists the scheduled tasks. A row shows the time, a readable summary of the days and the
 * action, an enabled switch and a delete button.
 */
class ScheduleAdapter(
    private val onEdit: (Schedule) -> Unit,
    private val onToggle: (Schedule, Boolean) -> Unit,
    private val onDelete: (Schedule) -> Unit
) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

    private var schedules: List<Schedule> = emptyList()

    // The list is short and only changes when the user adds, edits or deletes a task, so a
    // full rebind is cheaper than tracking item level changes.
    @SuppressLint("NotifyDataSetChanged")
    fun setSchedules(schedules: List<Schedule>) {
        this.schedules = schedules
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(schedules[position])
    }

    override fun getItemCount(): Int = schedules.size

    inner class ViewHolder(private val binding: ItemScheduleBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(schedule: Schedule) {
            val context = binding.root.context
            binding.scheduleTime.text = formatTime(context, schedule)
            binding.scheduleSummary.text = formatSummary(context, schedule)

            // Cleared first so rebinding a recycled row does not report a change the user
            // never made.
            binding.scheduleEnabled.setOnCheckedChangeListener(null)
            binding.scheduleEnabled.isChecked = schedule.enabled
            binding.scheduleEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(schedule, isChecked)
            }

            binding.scheduleRow.setOnClickListener { onEdit(schedule) }
            binding.scheduleDelete.setOnClickListener { onDelete(schedule) }
        }
    }

    companion object {

        fun formatTime(context: Context, schedule: Schedule): String =
            context.getString(R.string.schedule_time_format, schedule.hour, schedule.minute)

        /**
         * For example "Every day · Restart Application" or "Mon, Fri · Reload Page".
         */
        fun formatSummary(context: Context, schedule: Schedule): String {
            val separator = context.getString(R.string.schedule_summary_separator)
            val actionNames = context.resources.getStringArray(R.array.schedule_action_names)
            val parts = ArrayList<String>()
            parts.add(formatDays(context, schedule.days))
            parts.add(actionNames.getOrElse(schedule.action.ordinal) { schedule.action.command })
            if (schedule.payload.isNotEmpty()) {
                parts.add(schedule.payload)
            }
            if (!schedule.enabled) {
                parts.add(context.getString(R.string.schedule_summary_disabled))
            }
            return parts.joinToString(separator)
        }

        fun formatDays(context: Context, days: Set<Int>): String {
            if (days.containsAll(Schedule.ALL_DAYS)) {
                return context.getString(R.string.schedule_summary_every_day)
            }
            val dayNames = context.resources.getStringArray(R.array.schedule_day_names_short)
            return (Calendar.SUNDAY..Calendar.SATURDAY)
                .filter { day -> days.contains(day) }
                .joinToString(", ") { day -> dayNames.getOrElse(day - 1) { "" } }
        }
    }
}
