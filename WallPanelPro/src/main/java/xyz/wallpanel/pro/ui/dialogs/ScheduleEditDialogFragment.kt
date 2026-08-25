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

package xyz.wallpanel.pro.ui.dialogs

import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.databinding.DialogEditScheduleBinding
import xyz.wallpanel.pro.persistence.Schedule
import xyz.wallpanel.pro.persistence.ScheduleAction
import xyz.wallpanel.pro.utils.DateUtils
import java.util.Calendar

/**
 * Add and edit form for one scheduled task. The dialog owns no storage: it hands the result
 * back to the hosting fragment through the fragment result API, and that fragment writes it.
 */
class ScheduleEditDialogFragment : DialogFragment() {

    private lateinit var binding: DialogEditScheduleBinding

    private var hour: Int = 7
    private var minute: Int = 0
    private val days: MutableSet<Int> = HashSet()

    private val dayToggles: List<ToggleButton> by lazy {
        listOf(
            binding.scheduleDay0,
            binding.scheduleDay1,
            binding.scheduleDay2,
            binding.scheduleDay3,
            binding.scheduleDay4,
            binding.scheduleDay5,
            binding.scheduleDay6
        )
    }

    private val scheduleId: String? get() = arguments?.getString(ARG_ID)
    private val isEdit: Boolean get() = scheduleId != null
    private val shellEnabled: Boolean get() = arguments?.getBoolean(ARG_SHELL_ENABLED, false) ?: false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogEditScheduleBinding.inflate(layoutInflater)

        restoreState(savedInstanceState)
        bindDayToggles()
        bindTimeButton()
        bindActionSpinner()
        binding.scheduleEnabledSwitch.isChecked = arguments?.getBoolean(ARG_ENABLED, true) ?: true

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(if (isEdit) R.string.dialog_title_edit_schedule else R.string.dialog_title_add_schedule)
            .setView(binding.root)
            .setPositiveButton(R.string.button_save, null)
            .setNegativeButton(R.string.button_cancel, null)
        if (isEdit) {
            builder.setNeutralButton(R.string.button_delete, null)
        }
        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        // The buttons are wired here so a failed validation can keep the dialog open.
        val dialog = dialog as? AlertDialog ?: return
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { save() }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { delete() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { dismiss() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_HOUR, hour)
        outState.putInt(STATE_MINUTE, minute)
        outState.putIntArray(STATE_DAYS, days.toIntArray())
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        days.clear()
        if (savedInstanceState != null) {
            hour = savedInstanceState.getInt(STATE_HOUR, 7)
            minute = savedInstanceState.getInt(STATE_MINUTE, 0)
            days.addAll(savedInstanceState.getIntArray(STATE_DAYS)?.toList() ?: Schedule.ALL_DAYS)
            return
        }
        val arguments = arguments
        hour = arguments?.getInt(ARG_HOUR, 7) ?: 7
        minute = arguments?.getInt(ARG_MINUTE, 0) ?: 0
        days.addAll(arguments?.getIntArray(ARG_DAYS)?.toList() ?: Schedule.ALL_DAYS)
    }

    private fun bindTimeButton() {
        showTime()
        binding.scheduleTimeButton.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, pickedHour, pickedMinute ->
                    // Routed through DateUtils so the time picker output is parsed the same
                    // way everywhere in the application.
                    val picked = "${DateUtils.padTimePickerOutput(pickedHour.toString())}:${DateUtils.padTimePickerOutput(pickedMinute.toString())}"
                    hour = DateUtils.getHourFromTimePicker(picked)
                    minute = DateUtils.getMinutesFromTimePicker(picked)
                    showTime()
                },
                hour,
                minute,
                true
            ).show()
        }
    }

    private fun showTime() {
        binding.scheduleTimeButton.text = getString(R.string.schedule_time_format, hour, minute)
    }

    private fun bindDayToggles() {
        val dayNames = resources.getStringArray(R.array.schedule_day_names_short)
        dayToggles.forEachIndexed { index, toggle ->
            val day = Calendar.SUNDAY + index
            val name = dayNames.getOrElse(index) { "" }
            toggle.textOn = name
            toggle.textOff = name
            toggle.text = name
            toggle.isChecked = days.contains(day)
            toggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) days.add(day) else days.remove(day)
            }
        }
    }

    private fun bindActionSpinner() {
        val selected = arguments?.getString(ARG_ACTION)?.let { ScheduleAction.fromCommand(it) }
            ?: ScheduleAction.RESTART_APP
        binding.scheduleActionSpinner.setSelection(selected.ordinal)
        binding.schedulePayload.setText(arguments?.getString(ARG_PAYLOAD).orEmpty())
        showPayloadFor(selected)
        binding.scheduleActionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showPayloadFor(selectedAction())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun selectedAction(): ScheduleAction =
        ScheduleAction.values().getOrElse(binding.scheduleActionSpinner.selectedItemPosition) { ScheduleAction.RESTART_APP }

    private fun showPayloadFor(action: ScheduleAction) {
        binding.schedulePayload.visibility = if (action.requiresPayload()) View.VISIBLE else View.GONE
        binding.schedulePayload.hint = when (action) {
            ScheduleAction.URL -> getString(R.string.schedule_hint_url)
            ScheduleAction.SHELL -> getString(R.string.schedule_hint_shell)
            else -> ""
        }
        binding.scheduleShellWarning.visibility =
            if (action == ScheduleAction.SHELL && !shellEnabled) View.VISIBLE else View.GONE
    }

    private fun save() {
        if (days.isEmpty()) {
            Toast.makeText(requireContext(), R.string.toast_schedule_no_days, Toast.LENGTH_SHORT).show()
            return
        }
        val action = selectedAction()
        val payload = binding.schedulePayload.text.toString().trim()
        if (action.requiresPayload() && payload.isEmpty()) {
            Toast.makeText(requireContext(), R.string.toast_schedule_payload_required, Toast.LENGTH_SHORT).show()
            return
        }
        val result = Bundle()
        result.putString(RESULT_OPERATION, OPERATION_SAVE)
        result.putString(RESULT_ID, scheduleId)
        result.putBoolean(RESULT_ENABLED, binding.scheduleEnabledSwitch.isChecked)
        result.putInt(RESULT_HOUR, hour)
        result.putInt(RESULT_MINUTE, minute)
        result.putIntArray(RESULT_DAYS, days.toIntArray())
        result.putString(RESULT_ACTION, action.command)
        result.putString(RESULT_PAYLOAD, payload)
        parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
        dismiss()
    }

    private fun delete() {
        val result = Bundle()
        result.putString(RESULT_OPERATION, OPERATION_DELETE)
        result.putString(RESULT_ID, scheduleId)
        parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
        dismiss()
    }

    companion object {
        const val TAG = "ScheduleEditDialogFragment"
        const val REQUEST_KEY = "schedule_edit_request"

        const val RESULT_OPERATION = "operation"
        const val RESULT_ID = "id"
        const val RESULT_ENABLED = "enabled"
        const val RESULT_HOUR = "hour"
        const val RESULT_MINUTE = "minute"
        const val RESULT_DAYS = "days"
        const val RESULT_ACTION = "action"
        const val RESULT_PAYLOAD = "payload"

        const val OPERATION_SAVE = "save"
        const val OPERATION_DELETE = "delete"

        private const val ARG_ID = "arg_id"
        private const val ARG_ENABLED = "arg_enabled"
        private const val ARG_HOUR = "arg_hour"
        private const val ARG_MINUTE = "arg_minute"
        private const val ARG_DAYS = "arg_days"
        private const val ARG_ACTION = "arg_action"
        private const val ARG_PAYLOAD = "arg_payload"
        private const val ARG_SHELL_ENABLED = "arg_shell_enabled"

        private const val STATE_HOUR = "state_hour"
        private const val STATE_MINUTE = "state_minute"
        private const val STATE_DAYS = "state_days"

        fun newInstance(schedule: Schedule?, shellEnabled: Boolean): ScheduleEditDialogFragment {
            val arguments = Bundle()
            arguments.putBoolean(ARG_SHELL_ENABLED, shellEnabled)
            if (schedule != null) {
                arguments.putString(ARG_ID, schedule.id)
                arguments.putBoolean(ARG_ENABLED, schedule.enabled)
                arguments.putInt(ARG_HOUR, schedule.hour)
                arguments.putInt(ARG_MINUTE, schedule.minute)
                arguments.putIntArray(ARG_DAYS, schedule.days.toIntArray())
                arguments.putString(ARG_ACTION, schedule.action.command)
                arguments.putString(ARG_PAYLOAD, schedule.payload)
            }
            val fragment = ScheduleEditDialogFragment()
            fragment.arguments = arguments
            return fragment
        }
    }
}
