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
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import dagger.android.support.AndroidSupportInjection
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.network.WallPanelService
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.ui.activities.SettingsActivity
import xyz.wallpanel.pro.utils.MqttDiscoverySensors

class SensorsSettingsFragment : BaseSettingsFragment() {

    private var sensorsPreference: SwitchPreference? = null
    private var mqttPublishFrequency: EditTextPreference? = null

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_sensors)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        if ((activity as? SettingsActivity)?.supportActionBar != null) {
            (activity as SettingsActivity).supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            (activity as SettingsActivity).supportActionBar!!.setDisplayShowHomeEnabled(true)
            (activity as SettingsActivity).supportActionBar!!.title = getString(R.string.title_sensor_settings)
        }
        
        // Modern MenuProvider API
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_help, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    android.R.id.home -> {
                        Navigation.findNavController(requireView()).navigate(R.id.settings_action)
                        true
                    }
                    R.id.action_help -> {
                        showSupport()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        sensorsPreference = findPreference<SwitchPreference>(getString(R.string.key_setting_sensors_enabled)) as SwitchPreference
        mqttPublishFrequency = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_sensorfrequency)) as EditTextPreference

        bindPreferenceSummaryToValue(sensorsPreference!!)
        bindPreferenceSummaryToValue(mqttPublishFrequency!!)

        populateHardwareSensors()
        populateStateSensors()
        populateCameraSensors()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when {
            key == getString(R.string.key_setting_sensors_enabled) -> {
                updateHardwareSensorsEnabled()
                notifyDiscoverySensorSettingsChanged()
            }
            key?.startsWith(Configuration.PREF_MQTT_DISCOVERY_SENSOR_PREFIX) == true -> {
                notifyDiscoverySensorSettingsChanged()
            }
        }
    }

    private fun populateHardwareSensors() {
        val category = findPreference<PreferenceCategory>(PREF_CATEGORY_HARDWARE_SENSORS) ?: return
        val sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensorsEnabledKey = getString(R.string.key_setting_sensors_enabled)

        for (sensor in MqttDiscoverySensors.HARDWARE_SENSORS) {
            val key = discoverySensorKey(sensor.id)
            if (category.findPreference<Preference>(key) != null) {
                continue
            }
            val toggle = SwitchPreference(requireContext())
            toggle.key = key
            toggle.title = getString(sensor.titleResId)
            toggle.setDefaultValue(true)
            toggle.isChecked = configuration.mqttDiscoverySensorEnabled(sensor.id)

            val (summary, isSupported) = getHardwareSensorSummaryAndAvailability(sensorManager, sensor.id)
            if (summary != null) {
                toggle.summary = summary
            }

            category.addPreference(toggle)
            try {
                toggle.dependency = sensorsEnabledKey
            } catch (e: Exception) {
                Timber.w(e, "Could not register dependency for toggle $key")
                toggle.isEnabled = configuration.sensorsEnabled && isSupported
            }
            if (!isSupported) {
                toggle.isEnabled = false
            }
        }
    }

    private fun populateStateSensors() {
        val category = findPreference<PreferenceCategory>(PREF_CATEGORY_STATE_SENSORS) ?: return
        val sensorsEnabledKey = getString(R.string.key_setting_sensors_enabled)

        for (sensor in MqttDiscoverySensors.STATE_SENSORS) {
            val key = discoverySensorKey(sensor.id)
            if (category.findPreference<Preference>(key) != null) {
                continue
            }
            val toggle = SwitchPreference(requireContext())
            toggle.key = key
            toggle.title = getString(sensor.titleResId)
            toggle.setDefaultValue(true)
            toggle.isChecked = configuration.mqttDiscoverySensorEnabled(sensor.id)

            category.addPreference(toggle)
            try {
                toggle.dependency = sensorsEnabledKey
            } catch (e: Exception) {
                Timber.w(e, "Could not register dependency for toggle $key")
                toggle.isEnabled = configuration.sensorsEnabled
            }
        }
    }

    private fun populateCameraSensors() {
        val category = findPreference<PreferenceCategory>(PREF_CATEGORY_CAMERA_SENSORS) ?: return

        for (sensor in MqttDiscoverySensors.CAMERA_SENSORS) {
            val key = discoverySensorKey(sensor.id)
            if (category.findPreference<Preference>(key) != null) {
                continue
            }
            val toggle = SwitchPreference(requireContext())
            toggle.key = key
            toggle.title = getString(sensor.titleResId)
            toggle.setDefaultValue(true)
            toggle.isChecked = configuration.mqttDiscoverySensorEnabled(sensor.id)

            category.addPreference(toggle)
        }
    }

    private fun updateHardwareSensorsEnabled() {
        val category = findPreference<PreferenceCategory>(PREF_CATEGORY_HARDWARE_SENSORS) ?: return
        val sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensorsEnabled = configuration.sensorsEnabled
        for (sensor in MqttDiscoverySensors.HARDWARE_SENSORS) {
            val pref = category.findPreference<SwitchPreference>(discoverySensorKey(sensor.id)) ?: continue
            val (_, isSupported) = getHardwareSensorSummaryAndAvailability(sensorManager, sensor.id)
            if (!isSupported) {
                pref.isEnabled = false
            } else {
                pref.isEnabled = sensorsEnabled
            }
        }
    }

    private fun getHardwareSensorSummaryAndAvailability(
        sensorManager: SensorManager?,
        sensorId: String
    ): Pair<String?, Boolean> {
        return when (sensorId) {
            MqttDiscoverySensors.ID_BATTERY -> Pair(getString(R.string.pref_summary_battery), true)
            "cpuUsage" -> Pair(getString(R.string.pref_summary_cpu), true)
            "memoryUsage" -> Pair(getString(R.string.pref_summary_memory), true)
            "temperature" -> querySensor(sensorManager, Sensor.TYPE_AMBIENT_TEMPERATURE)
            "light" -> querySensor(sensorManager, Sensor.TYPE_LIGHT)
            "magneticField" -> querySensor(sensorManager, Sensor.TYPE_MAGNETIC_FIELD)
            "pressure" -> querySensor(sensorManager, Sensor.TYPE_PRESSURE)
            "humidity" -> querySensor(sensorManager, Sensor.TYPE_RELATIVE_HUMIDITY)
            else -> Pair(null, true)
        }
    }

    private fun querySensor(sensorManager: SensorManager?, type: Int): Pair<String, Boolean> {
        if (sensorManager == null) {
            return Pair(getString(R.string.summary_setting_sensors_default), false)
        }
        return try {
            val list = sensorManager.getSensorList(type)
            if (list.isNotEmpty()) {
                Pair(list[0].name, true)
            } else {
                Pair(getString(R.string.summary_setting_sensors_default), false)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error querying sensor for type $type")
            Pair(getString(R.string.summary_setting_sensors_default), false)
        }
    }

    private fun discoverySensorKey(sensorId: String): String =
        "${Configuration.PREF_MQTT_DISCOVERY_SENSOR_PREFIX}$sensorId"

    private fun notifyDiscoverySensorSettingsChanged() {
        try {
            LocalBroadcastManager.getInstance(requireContext().applicationContext)
                .sendBroadcast(Intent(WallPanelService.BROADCAST_EVENT_MQTT_SENSOR_SETTINGS_CHANGED))
        } catch (e: Exception) {
            Timber.w(e, "Unable to notify service about discovery sensor change")
        }
    }

    companion object {
        const val PREF_CATEGORY_HARDWARE_SENSORS = "pref_category_hardware_sensors"
        const val PREF_CATEGORY_STATE_SENSORS = "pref_category_state_sensors"
        const val PREF_CATEGORY_CAMERA_SENSORS = "pref_category_camera_sensors"
    }
}