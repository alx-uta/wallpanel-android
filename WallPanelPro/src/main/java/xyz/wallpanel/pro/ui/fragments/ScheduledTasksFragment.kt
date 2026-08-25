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

package xyz.wallpanel.pro.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.android.support.AndroidSupportInjection
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.databinding.FragmentScheduledTasksBinding
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.persistence.Schedule
import xyz.wallpanel.pro.persistence.ScheduleAction
import xyz.wallpanel.pro.persistence.ScheduleRepository
import xyz.wallpanel.pro.ui.activities.SettingsActivity
import xyz.wallpanel.pro.ui.adapters.ScheduleAdapter
import xyz.wallpanel.pro.ui.dialogs.ScheduleEditDialogFragment
import xyz.wallpanel.pro.utils.ScheduledTaskAlarmScheduler
import java.util.UUID
import javax.inject.Inject

/**
 * Lists the scheduled tasks and hosts the add and edit dialog. This is a plain fragment
 * rather than a preference screen, a preference screen cannot show a list the user adds to
 * and removes from.
 */
class ScheduledTasksFragment : Fragment() {

    @Inject
    lateinit var configuration: Configuration

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    private var binding: FragmentScheduledTasksBinding? = null

    private val adapter: ScheduleAdapter by lazy {
        ScheduleAdapter(
            onEdit = { schedule -> showEditDialog(schedule) },
            onToggle = { schedule, enabled -> saveSchedule(schedule.copy(enabled = enabled), notify = false) },
            onDelete = { schedule -> deleteSchedule(schedule.id) }
        )
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
        setHasOptionsMenu(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.setFragmentResultListener(
            ScheduleEditDialogFragment.REQUEST_KEY,
            this
        ) { _, result -> handleDialogResult(result) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentScheduledTasksBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (activity is SettingsActivity) {
            val actionBar = (activity as SettingsActivity).supportActionBar
            with(actionBar) {
                this?.setDisplayHomeAsUpEnabled(true)
                this?.setDisplayShowHomeEnabled(true)
                this?.title = getString(R.string.title_scheduled_tasks)
            }
        }

        binding?.scheduleList?.let { list ->
            list.layoutManager = LinearLayoutManager(requireContext())
            list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
            list.adapter = adapter
        }

        binding?.addScheduleFab?.setOnClickListener { showEditDialog(null) }

        showSchedules()
    }

    override fun onDestroyView() {
        // The adapter holds the item views, so it is detached before the binding goes away.
        binding?.scheduleList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_scheduled_tasks, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                view?.let { Navigation.findNavController(it).navigate(R.id.settings_action) }
                return true
            }
            R.id.action_add_schedule -> {
                showEditDialog(null)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showEditDialog(schedule: Schedule?) {
        if (childFragmentManager.findFragmentByTag(ScheduleEditDialogFragment.TAG) != null) {
            return
        }
        ScheduleEditDialogFragment.newInstance(schedule, configuration.httpShellEnabled)
            .show(childFragmentManager, ScheduleEditDialogFragment.TAG)
    }

    private fun handleDialogResult(result: Bundle) {
        when (result.getString(ScheduleEditDialogFragment.RESULT_OPERATION)) {
            ScheduleEditDialogFragment.OPERATION_DELETE -> {
                result.getString(ScheduleEditDialogFragment.RESULT_ID)?.let { deleteSchedule(it) }
            }
            ScheduleEditDialogFragment.OPERATION_SAVE -> {
                val action = ScheduleAction.fromCommand(result.getString(ScheduleEditDialogFragment.RESULT_ACTION))
                if (action == null) {
                    return
                }
                val id = result.getString(ScheduleEditDialogFragment.RESULT_ID)
                val days = result.getIntArray(ScheduleEditDialogFragment.RESULT_DAYS)?.toSet().orEmpty()
                val schedule = Schedule(
                    id = id ?: UUID.randomUUID().toString(),
                    enabled = result.getBoolean(ScheduleEditDialogFragment.RESULT_ENABLED, true),
                    hour = result.getInt(ScheduleEditDialogFragment.RESULT_HOUR, 0),
                    minute = result.getInt(ScheduleEditDialogFragment.RESULT_MINUTE, 0),
                    days = days,
                    action = action,
                    payload = result.getString(ScheduleEditDialogFragment.RESULT_PAYLOAD).orEmpty()
                )
                saveSchedule(schedule, notify = true)
            }
        }
    }

    private fun saveSchedule(schedule: Schedule, notify: Boolean) {
        scheduleRepository.save(schedule)
        if (schedule.enabled) {
            ScheduledTaskAlarmScheduler.schedule(requireContext(), schedule)
        } else {
            ScheduledTaskAlarmScheduler.cancel(requireContext(), schedule.id)
        }
        showSchedules()
        if (notify) {
            Toast.makeText(requireContext(), R.string.toast_schedule_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSchedule(id: String) {
        scheduleRepository.delete(id)
        ScheduledTaskAlarmScheduler.cancel(requireContext(), id)
        showSchedules()
        Toast.makeText(requireContext(), R.string.toast_schedule_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun showSchedules() {
        val schedules = scheduleRepository.getAll()
        adapter.setSchedules(schedules)
        binding?.scheduleEmptyText?.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
    }
}
