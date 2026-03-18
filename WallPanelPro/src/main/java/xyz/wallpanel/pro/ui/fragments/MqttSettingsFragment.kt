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
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.*
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.preference.SwitchPreference
import androidx.preference.EditTextPreference
import androidx.navigation.Navigation
import androidx.preference.ListPreference
import androidx.preference.Preference
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.network.MQTTOptions
import xyz.wallpanel.pro.modules.MQTTModule
import xyz.wallpanel.pro.ui.activities.SettingsActivity
import dagger.android.support.AndroidSupportInjection
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject

class MqttSettingsFragment : BaseSettingsFragment(), SharedPreferences.OnSharedPreferenceChangeListener  {

    @Inject
    lateinit var mqttOptions: MQTTOptions

    private var mqttPreference: SwitchPreference? = null
    private var mqttVersion: ListPreference? = null
    private var mqttBrokerAddress: EditTextPreference? = null
    private var mqttBrokerPort: EditTextPreference? = null
    private var mqttClientId: EditTextPreference? = null
    private var mqttBaseTopic: EditTextPreference? = null
    private var mqttUsername: EditTextPreference? = null
    private var mqttPassword: EditTextPreference? = null
    private var mqttDiscovery: SwitchPreference? = null
    private var mqttDiscoveryTopic: EditTextPreference? = null
    private var mqttDiscoveryDeviceName: EditTextPreference? = null
    private var mqttDiscoveryLegacyEntities: SwitchPreference? = null
    private var mqttTestConnection: Preference? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val testExecutor = Executors.newSingleThreadExecutor()
    private var testMqttModule: MQTTModule? = null
    private var isTestingConnection = false

    private val sslPreference: SwitchPreference by lazy {
        findPreference<SwitchPreference>(PREF_TLS_CONNECTION) as SwitchPreference
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_mqtt)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        if((activity as? SettingsActivity)?.supportActionBar != null) {
            (activity as SettingsActivity).supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            (activity as SettingsActivity).supportActionBar!!.setDisplayShowHomeEnabled(true)
            (activity as SettingsActivity).supportActionBar!!.title = getString(R.string.title_mqtt_settings)
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

        mqttPreference = findPreference<SwitchPreference>(getString(R.string.key_setting_mqtt_enabled)) as SwitchPreference
        mqttVersion = findPreference<ListPreference>(PREF_MQTT_VERSION) as ListPreference
        mqttBrokerAddress = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_servername)) as EditTextPreference
        mqttBrokerPort = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_serverport)) as EditTextPreference
        mqttClientId = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_clientid)) as EditTextPreference
        mqttBaseTopic = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_basetopic)) as EditTextPreference
        mqttUsername = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_username)) as EditTextPreference
        mqttPassword = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_password)) as EditTextPreference
        mqttDiscovery = findPreference<SwitchPreference>(getString(R.string.key_setting_mqtt_discovery)) as SwitchPreference
        mqttDiscoveryTopic = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_discovery_topic)) as EditTextPreference
        mqttDiscoveryDeviceName = findPreference<EditTextPreference>(getString(R.string.key_setting_mqtt_discovery_name)) as EditTextPreference
        mqttDiscoveryLegacyEntities = findPreference<SwitchPreference>(getString(R.string.key_setting_mqtt_discovery_legacy_entities)) as SwitchPreference
        mqttTestConnection = findPreference<Preference>(getString(R.string.key_setting_mqtt_test_connection)) as Preference

        mqttPassword?.setOnBindEditTextListener {editText ->
            // mask password in edit dialog
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        // Set up test connection button click listener
        mqttTestConnection?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            testMqttConnection()
            true
        }

        bindPreferenceSummaryToValue(mqttPreference!!)
        bindPreferenceSummaryToValue(mqttBrokerAddress!!)
        bindPreferenceSummaryToValue(mqttBrokerPort!!)
        bindPreferenceSummaryToValue(mqttClientId!!)
        bindPreferenceSummaryToValue(mqttBaseTopic!!)
        bindPreferenceSummaryToValue(mqttUsername!!)
        bindPreferenceSummaryToValue(mqttPassword!!)
        bindPreferenceSummaryToValue(mqttDiscovery!!)
        bindPreferenceSummaryToValue(mqttDiscoveryTopic!!)
        bindPreferenceSummaryToValue(mqttDiscoveryDeviceName!!)
        bindPreferenceSummaryToValue(mqttDiscoveryLegacyEntities!!)

        mqttVersion?.setDefaultValue(configuration.mqttVersion)
        mqttVersion?.value = configuration.mqttVersion
        mqttVersion?.summary = getString(R.string.pref_mqtt_version_summary, configuration.mqttVersion)

    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PREF_TLS_CONNECTION -> {
                val checked = sslPreference.isChecked
                mqttOptions.setTlsConnection(checked)
            }
            PREF_MQTT_VERSION -> {
                val version = mqttVersion?.value
                if (version != null) {
                    configuration.mqttVersion = version
                    mqttVersion?.summary = getString(R.string.pref_mqtt_version_summary, version)
                }
            }
        }
    }
    
    private fun testMqttConnection() {
        if (isTestingConnection) {
            Timber.d("Test already in progress")
            return
        }
        
        try {
            Timber.d("=== Starting MQTT connection test ===")
            isTestingConnection = true
            
            dialogUtils.showAlertDialogToDismiss(
                requireActivity() as SettingsActivity,
                "Testing Connection",
                "Connecting to MQTT broker...\nThis may take a few seconds."
            )
            
            testExecutor.execute {
                try {
                    val testOptions = MQTTOptions(configuration)
                    Timber.d("Test options created: broker=${testOptions.getBroker()}, port=${testOptions.getPort()}")
                    
                    // Quick validation
                    val validationErrors = mutableListOf<String>()
                    if (testOptions.getBroker().isEmpty()) validationErrors.add("Broker address is empty")
                    if (testOptions.getPort() <= 0) validationErrors.add("Invalid port")
                    if (testOptions.getClientId().isEmpty()) validationErrors.add("Client ID is empty")
                    
                    if (validationErrors.isNotEmpty()) {
                        mainHandler.post {
                            showTestResult(false, "Validation Failed", validationErrors.joinToString("\n"))
                            isTestingConnection = false
                        }
                        return@execute
                    }
                    
                    // Create test listener
                    val testListener = object : MQTTModule.MQTTListener {
                        override fun onMQTTConnect() {
                            Timber.d("MQTT Connected successfully!")
                            mainHandler.post {
                                showTestResult(true, "Connection Successful", 
                                    "Successfully connected to MQTT broker!\n\n" +
                                    "Broker: ${testOptions.getBroker()}\n" +
                                    "Port: ${testOptions.getPort()}\n" +
                                    "Client ID: ${testOptions.getClientId()}\n" +
                                    "TLS: ${if (testOptions.getTlsConnection()) "Enabled" else "Disabled"}\n" +
                                    "Version: ${testOptions.getVersion()}\n" +
                                    "Auth: ${if (testOptions.getUsername().isNotEmpty()) "Yes" else "No"}")
                                // Disconnect after successful test
                                mainHandler.postDelayed({ cleanupTest() }, 1000)
                            }
                        }
                        
                        override fun onMQTTDisconnect() {
                            Timber.d("MQTT Disconnected")
                        }
                        
                        override fun onMQTTException(message: String) {
                            val fullError = "=== MQTT CONNECTION TEST ERROR ===\n" +
                                "Broker: ${testOptions.getBroker()}\n" +
                                "Port: ${testOptions.getPort()}\n" +
                                "Client ID: ${testOptions.getClientId()}\n" +
                                "TLS: ${testOptions.getTlsConnection()}\n" +
                                "Version: ${testOptions.getVersion()}\n" +
                                "Username: ${testOptions.getUsername()}\n" +
                                "Error: $message\n" +
                                "==================================="
                            Timber.e(fullError)
                            mainHandler.post {
                                showTestResult(false, "Connection Failed", 
                                    "Broker: ${testOptions.getBroker()}:${testOptions.getPort()}\n" +
                                    "Client ID: ${testOptions.getClientId()}\n" +
                                    "TLS: ${testOptions.getTlsConnection()}\n" +
                                    "MQTT Version: ${testOptions.getVersion()}\n" +
                                    "Username: ${if (testOptions.getUsername().isNotEmpty()) testOptions.getUsername() else "(none)"}\n\n" +
                                    "ERROR:\n$message")
                                cleanupTest()
                            }
                        }
                        
                        override fun onMQTTMessage(id: String, topic: String, payload: String) {
                            Timber.d("Test received message: $topic")
                        }
                    }
                    
                    // Use MQTTModule to test connection
                    Timber.d("Creating MQTTModule with version ${testOptions.getVersion()}")
                    testMqttModule = MQTTModule(requireContext().applicationContext, testOptions, testListener)
                    testMqttModule?.restart() // Start the connection
                    
                    // Set timeout
                    mainHandler.postDelayed({
                        if (isTestingConnection) {
                            Timber.w("Connection test timeout")
                            showTestResult(false, "Connection Timeout", 
                                "Failed to connect within 15 seconds.\n\n" +
                                "Please check:\n" +
                                "- Broker address is correct\n" +
                                "- Port is correct\n" +
                                "- Broker is running and accessible\n" +
                                "- Network connection is available\n" +
                                "- Firewall allows connection")
                            cleanupTest()
                        }
                    }, 15000)
                    
                } catch (e: Throwable) {
                    val fullError = "=== MQTT CONNECTION TEST EXCEPTION ===\n" +
                        "Exception Type: ${e.javaClass.simpleName}\n" +
                        "Message: ${e.message}\n" +
                        "Cause: ${e.cause?.message ?: "None"}\n" +
                        "Stack Trace:\n${e.stackTraceToString()}\n" +
                        "======================================="
                    Timber.e(e, fullError)
                    mainHandler.post {
                        val errorDetail = buildString {
                            appendLine("Exception: ${e.javaClass.simpleName}")
                            appendLine()
                            if (e.message != null) {
                                appendLine("Message: ${e.message}")
                                appendLine()
                            }
                            if (e.cause != null) {
                                appendLine("Cause: ${e.cause?.javaClass?.simpleName}")
                                appendLine("  ${e.cause?.message}")
                                appendLine()
                            }
                            appendLine("Stack trace (first 10 lines):")
                            val stackLines = e.stackTraceToString().lines().take(10)
                            stackLines.forEach { appendLine("  $it") }
                        }
                        showTestResult(false, "Test Error", errorDetail)
                        cleanupTest()
                    }
                }
            }
            
        } catch (e: Throwable) {
            val criticalError = "=== MQTT TEST CRITICAL ERROR ===\n" +
                "Exception Type: ${e.javaClass.simpleName}\n" +
                "Message: ${e.message}\n" +
                "Cause: ${e.cause?.message ?: "None"}\n" +
                "Stack Trace:\n${e.stackTraceToString()}\n" +
                "================================="
            Timber.e(e, criticalError)
            val errorDetail = buildString {
                appendLine("Critical Error: ${e.javaClass.simpleName}")
                appendLine()
                if (e.message != null) {
                    appendLine(e.message!!)
                    appendLine()
                }
                if (e.cause != null) {
                    appendLine("Caused by: ${e.cause?.javaClass?.simpleName}")
                    appendLine("  ${e.cause?.message}")
                }
            }
            showTestResult(false, "Critical Error", errorDetail)
            isTestingConnection = false
        }
    }
    
    private fun showTestResult(success: Boolean, title: String, message: String) {
        try {
            dialogUtils.showAlertDialogToDismiss(
                requireActivity() as SettingsActivity,
                title,
                message
            )
        } catch (e: Exception) {
            Timber.e(e, "Cannot show test result dialog")
        }
    }
    
    private fun cleanupTest() {
        Timber.d("Cleaning up test connection")
        isTestingConnection = false
        
        testExecutor.execute {
            try {
                testMqttModule?.pause()
                testMqttModule = null
                Timber.d("Test MQTT module cleaned up")
            } catch (e: Exception) {
                Timber.e(e, "Error cleaning up test MQTT module")
            }
        }
    }
    
    override fun onDestroyView() {
        cleanupTest()
        super.onDestroyView()
    }
    
    override fun onDestroy() {
        testExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PREF_TLS_CONNECTION = "pref_tls_connection"
        const val PREF_MQTT_VERSION = "pref_mqtt_version"
    }
}