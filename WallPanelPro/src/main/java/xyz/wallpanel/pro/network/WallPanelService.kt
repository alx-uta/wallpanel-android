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

package xyz.wallpanel.pro.network

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.*
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.koushikdutta.async.AsyncServer
import com.koushikdutta.async.ByteBufferList
import com.koushikdutta.async.http.body.JSONObjectBody
import com.koushikdutta.async.http.body.StringBody
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.AsyncHttpServerResponse
import com.koushikdutta.async.util.Charsets
import dagger.android.AndroidInjection
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.modules.*
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.persistence.ScheduleRepository
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_CLEAR_BROWSER_CACHE
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_JS_EXEC
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_LOAD_URL
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_OPEN_SETTINGS
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_RELOAD_PAGE
import xyz.wallpanel.pro.utils.AppRestartHelper
import xyz.wallpanel.pro.utils.MqttDiscoverySensors
import xyz.wallpanel.pro.utils.MqttUtils
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_AUDIO
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_BRIGHTNESS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CAMERA
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CLEAR_CACHE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_EVAL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RELAUNCH
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RELOAD
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RESTART_APP
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_FACE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_MOTION
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_QR_CODE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SETTINGS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SPEAK
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_STATE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_URL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SHELL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_VOLUME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_WAKE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_WAKETIME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.VALUE
import xyz.wallpanel.pro.utils.NotificationUtils
import xyz.wallpanel.pro.utils.ScheduledTaskAlarmScheduler
import xyz.wallpanel.pro.utils.ScreenUtils
import java.io.IOException
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject


// TODO move this to internal class within application, no longer run as service
class WallPanelService : LifecycleService(), MQTTModule.MQTTListener {

    @Inject
    lateinit var configuration: Configuration

    private var cameraReader: CameraReader? = null

    @Inject
    lateinit var sensorReader: SensorReader

    @Inject
    lateinit var mqttOptions: MQTTOptions

    @Inject
    lateinit var screenUtils: ScreenUtils

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    private val mJpegSockets = ArrayList<AsyncHttpServerResponse>()
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var audioPlayer: MediaPlayer? = null
    private var audioPlayerBusy: Boolean = false
    private var httpServer: AsyncHttpServer? = null
    private val mBinder = WallPanelServiceBinder()
    private val motionClearHandler = Handler(Looper.getMainLooper())
    private val appStateClearHandler = Handler(Looper.getMainLooper())
    private val qrCodeClearHandler = Handler(Looper.getMainLooper())
    private val faceClearHandler = Handler(Looper.getMainLooper())
    private val wakeScreenHandler = Handler(Looper.getMainLooper())
    private var textToSpeechModule: TextToSpeechModule? = null
    private var mqttModule: MQTTModule? = null
    private var connectionLiveData: ConnectionLiveData? = null
    private var hasNetwork = AtomicBoolean(true)
    private var motionDetected: Boolean = false
    private var appStatePublished: Boolean = false
    private var appStatePublishPending: Boolean = false
    private var qrCodeRead: Boolean = false
    private var isScreenSaverActive: Boolean = false
    private var faceDetected: Boolean = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var appLaunchUrl: String? = null
    private var localBroadCastManager: LocalBroadcastManager? = null
    private var mqttAlertMessageShown = false
    private var mqttConnecting = false
    private var mqttInitConnection = AtomicBoolean(true)
    private var systemReceiverRegistered = false
    @Volatile private var wifiNetworkState = WifiNetworkState()
    @Volatile private var activeIpAddress: String? = null
    private val wifiNetworkStateLock = Any()
    private val networkStateRefreshLock = Any()
    private var wifiNetworkCallbackRegistered = false
    private var activeNetworkCallbackRegistered = false
    private var networkStateRefreshPending = false
    private var wifiSsidUnavailableLogged = false

    private data class WifiNetworkState(
        val network: Network? = null,
        val signal: Int? = null,
        val ssid: String? = null
    )

    private val appVersion: String by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            }
        } catch (e: Exception) {
            Timber.w(e, "Unable to read application version")
            ""
        }
    }

    private val appStateCooldownRunnable = Runnable {
        appStatePublished = false
        if (appStatePublishPending) {
            appStatePublishPending = false
            publishApplicationState()
        }
    }

    private val restartMqttRunnable = Runnable {
        clearAlertMessage() // clear any dialogs
        mqttAlertMessageShown = false
        mqttConnecting = false
        //sendToastMessage(getString(R.string.toast_connect_retry))
        mqttModule?.restart()
    }

    inner class WallPanelServiceBinder : Binder() {
        val service: WallPanelService
            get() = this@WallPanelService
    }

    override fun onCreate() {
        super.onCreate()

        AndroidInjection.inject(this)

        startForeground()
        registerWifiNetworkCallback()
        registerActiveNetworkCallback()

        // prepare the lock types we may use
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        // CPU wake lock - keeps CPU running for background services (MQTT, sensors, camera)
        // Held continuously while service is active
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wallPanel:cpuWakeLock")

        // Screen wake lock - temporarily wakes screen for commands
        // Only held during explicit wake requests
        screenWakeLock = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "wallPanel:screenWakeLock")
        } else {
            pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "wallPanel:screenWakeLock")
        }

        // wifi lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "wallPanel:wifiLock")

        // Some Amazon devices are not seeing this permission so we are trying to check
        val permission = "android.permission.DISABLE_KEYGUARD"
        val checkSelfPermission = ContextCompat.checkSelfPermission(this@WallPanelService, permission)
        if (checkSelfPermission == PackageManager.PERMISSION_GRANTED) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardLock = keyguardManager.newKeyguardLock("ALARM_KEYBOARD_LOCK_TAG")
            keyguardLock!!.disableKeyguard()
        }

        this.appLaunchUrl = configuration.appLaunchUrl

        configureMqtt()
        configurePowerOptions()
        configureCamera()
        startHttp()
        configureAudioPlayer()
        configureTextToSpeech()
        startSensors()

        val filter = IntentFilter()
        filter.addAction(BROADCAST_EVENT_URL_CHANGE)
        filter.addAction(BROADCAST_EVENT_SCREEN_TOUCH)
        filter.addAction(BROADCAST_CAMERA_START_SCREENSAVER)
        filter.addAction(BROADCAST_CAMERA_STOP_SCREENSAVER)
        filter.addAction(BROADCAST_EVENT_MQTT_SENSOR_SETTINGS_CHANGED)
        localBroadCastManager = LocalBroadcastManager.getInstance(this)
        localBroadCastManager?.registerReceiver(mBroadcastReceiver, filter)

        registerSystemBroadcastReceiver()

        // Safety net for the alarms: they are dropped on reboot and on a system initiated
        // clear of the application's data, so they are re-armed whenever the service starts.
        ScheduledTaskAlarmScheduler.scheduleAll(applicationContext, scheduleRepository)
    }

    /**
     * Commands sent from outside the service, currently by the scheduled task receiver,
     * arrive here as an [ACTION_RUN_COMMAND] intent and go through the same command
     * handling as MQTT and HTTP.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_RUN_COMMAND) {
            val command = intent.getStringExtra(EXTRA_COMMAND_JSON)
            if (command.isNullOrEmpty()) {
                Timber.w("Received a run command intent without a command")
            } else {
                Timber.i("Running command from an intent: $command")
                processCommand(command)
            }
        }
        return result
    }

    /**
     * Screen on/off and user present are broadcast by the system, so they have to be
     * registered globally.
     * LocalBroadcastManager only dispatches what the app itself sends
     * through it and never sees these, which is why they went unnoticed until now.
     */
    private fun registerSystemBroadcastReceiver() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        try {
            registerReceiver(mBroadcastReceiver, filter)
            systemReceiverRegistered = true
        } catch (e: Exception) {
            Timber.e(e, "Error registering the system broadcast receiver")
        }
    }

    private fun unregisterSystemBroadcastReceiver() {
        if (systemReceiverRegistered.not()) {
            return
        }
        systemReceiverRegistered = false
        try {
            unregisterReceiver(mBroadcastReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Error unregistering the system broadcast receiver")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttModule?.let {
            it.pause()
            mqttModule = null
        }
        if (localBroadCastManager != null) {
            localBroadCastManager?.unregisterReceiver(mBroadcastReceiver)
        }
        unregisterSystemBroadcastReceiver()
        unregisterWifiNetworkCallback()
        unregisterActiveNetworkCallback()
        cameraReader?.stopCamera()
        sensorReader.stopReadings()
        stopHttp()
        stopPowerOptions()
        reconnectHandler.removeCallbacksAndMessages(null)
        appStateClearHandler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return mBinder
    }

    private val isScreenOn: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH){
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                return powerManager.isScreenOn
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH){
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                for (display in displayManager.displays){
                    return display.state != Display.STATE_OFF
                }
                return false
            }
            return false
        }

    private val state: JSONObject
        get() = JSONObject().also { state ->
            putStateValue(state, MqttUtils.STATE_CURRENT_URL, "") { appLaunchUrl.orEmpty() }
            putStateValue(state, MqttUtils.STATE_SCREEN_ON, false) { isScreenOn }
            putStateValue(state, MqttUtils.STATE_CAMERA, false) { configuration.cameraEnabled }
            putStateValue(state, MqttUtils.STATE_BRIGHTNESS, JSONObject.NULL) { screenUtils.getCurrentScreenBrightness() }
            putStateValue(state, MqttUtils.STATE_ANDROID_VERSION, "") { Build.VERSION.RELEASE.orEmpty() }
            putStateValue(state, MqttUtils.STATE_APP_VERSION, "") { appVersion }
            putStateValue(state, MqttUtils.STATE_DEVICE_OWNER, false) { isDeviceOwner }
            putStateValue(state, MqttUtils.STATE_IP_ADDRESS, "") { activeIpAddress.orEmpty() }
            putStateValue(state, MqttUtils.STATE_MANUFACTURER, "") { Build.MANUFACTURER.orEmpty() }
            putStateValue(state, MqttUtils.STATE_MODEL, "") { Build.MODEL.orEmpty() }
            putStateValue(state, MqttUtils.STATE_SCREEN_SAVER, false) { isScreenSaverActive }
            putStateValue(state, MqttUtils.STATE_STORAGE_FREE, JSONObject.NULL) {
                StatFs(filesDir.absolutePath).availableBytes / (1024 * 1024)
            }
            putStateValue(state, MqttUtils.STATE_UPTIME, JSONObject.NULL) { SystemClock.elapsedRealtime() / 1000 }
            putStateValue(state, MqttUtils.STATE_VOLUME, JSONObject.NULL) { mediaVolume }
            putStateValue(state, MqttUtils.STATE_WIFI_SIGNAL, JSONObject.NULL) { wifiNetworkState.signal }
            putStateValue(state, MqttUtils.STATE_WIFI_SSID, "") { wifiNetworkState.ssid.orEmpty() }
        }

    private fun putStateValue(state: JSONObject, fieldName: String, fallback: Any, value: () -> Any?) {
        try {
            state.put(fieldName, value() ?: fallback)
        } catch (e: Exception) {
            Timber.w(e, "Unable to read MQTT state field: $fieldName")
            state.put(fieldName, fallback)
        }
    }

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return
            }

            val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                networkCapabilities.transportInfo as? WifiInfo
            } else {
                legacyWifiInfo()
            }
            val ssid = wifiInfo?.ssid
                ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
                ?.removeSurrounding("\"")
            val signal = wifiInfo?.rssi?.takeUnless { it == WIFI_RSSI_UNAVAILABLE }

            if (wifiInfo != null && ssid == null && !wifiSsidUnavailableLogged) {
                wifiSsidUnavailableLogged = true
                Timber.w("Wi-Fi SSID is unavailable. Android may require location permission and location services.")
            }

            synchronized(wifiNetworkStateLock) {
                wifiNetworkState = wifiNetworkState.copy(network = network, signal = signal, ssid = ssid)
            }
            scheduleNetworkStateRefresh()
        }

        override fun onLost(network: Network) {
            synchronized(wifiNetworkStateLock) {
                if (wifiNetworkState.network == network) {
                    wifiNetworkState = WifiNetworkState()
                }
            }
            scheduleNetworkStateRefresh()
        }
    }

    private val activeNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkStateRefresh()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            scheduleNetworkStateRefresh()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            scheduleNetworkStateRefresh()

        override fun onLost(network: Network) = scheduleNetworkStateRefresh()
    }

    private fun scheduleNetworkStateRefresh() {
        synchronized(networkStateRefreshLock) {
            if (networkStateRefreshPending) {
                return
            }
            networkStateRefreshPending = true
        }
        appStateClearHandler.post {
            synchronized(networkStateRefreshLock) {
                networkStateRefreshPending = false
            }
            refreshActiveIpAddress()
            publishApplicationState()
        }
    }

    private fun refreshActiveIpAddress() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            activeIpAddress = null
            return
        }
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties = connectivityManager.activeNetwork?.let(connectivityManager::getLinkProperties)
            activeIpAddress = linkProperties?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Timber.w(e, "Unable to read active network IP address")
            activeIpAddress = null
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWifiInfo(): WifiInfo? = try {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
    } catch (e: Exception) {
        Timber.w(e, "Unable to read legacy Wi-Fi information")
        null
    }

    private fun registerWifiNetworkCallback() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, wifiNetworkCallback)
            wifiNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Timber.w(e, "Unable to observe Wi-Fi network state")
        }
    }

    private fun registerActiveNetworkCallback() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, activeNetworkCallback)
            activeNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Timber.w(e, "Unable to observe active network state")
        }
    }

    private fun unregisterWifiNetworkCallback() {
        if (!wifiNetworkCallbackRegistered) {
            return
        }
        wifiNetworkCallbackRegistered = false
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(wifiNetworkCallback)
        } catch (e: Exception) {
            Timber.w(e, "Unable to unregister Wi-Fi network callback")
        }
    }

    private fun unregisterActiveNetworkCallback() {
        if (!activeNetworkCallbackRegistered) {
            return
        }
        activeNetworkCallbackRegistered = false
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(activeNetworkCallback)
        } catch (e: Exception) {
            Timber.w(e, "Unable to unregister active network callback")
        }
    }

    private val isDeviceOwner: Boolean
        get() = try {
            val manager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            manager.isDeviceOwnerApp(packageName)
        } catch (e: Exception) {
            Timber.w(e, "Unable to determine device-owner status")
            false
        }

    private val mediaVolume: Int?
        get() = try {
            val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maximum == 0) 0 else manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maximum
        } catch (e: Exception) {
            Timber.w(e, "Unable to read media volume")
            null
        }

    private fun startForeground() {
        // make a continuously running notification
        val notificationUtils = NotificationUtils(applicationContext, application.resources)
        val notification = notificationUtils.createNotification(getString(R.string.wallpanel_service_notification_title), getString(R.string.wallpanel_service_notification_message))
        startForeground(ONGOING_NOTIFICATION_ID, notification)

        // listen for network connectivity changes
        connectionLiveData = ConnectionLiveData(this)
        connectionLiveData?.observe(this, Observer { connected ->
            if (connected!!) {
                handleNetworkConnect()
            } else {
                handleNetworkDisconnect()
            }
        })

        sendServiceStarted()
    }

    private fun handleNetworkConnect() {
        mqttModule?.let {
            if (!hasNetwork()) {
                it.restart()
            }
        }
        hasNetwork.set(true)
        scheduleNetworkStateRefresh()
    }

    private fun handleNetworkDisconnect() {
        mqttModule?.let {
            if (hasNetwork()) {
                it.pause()
            }
        }
        hasNetwork.set(false)
        scheduleNetworkStateRefresh()
    }

    private fun hasNetwork(): Boolean {
        return hasNetwork.get()
    }

    private fun configurePowerOptions() {
        // Acquire CPU wake lock to keep background services running
        cpuWakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
            }
        }
        if (!wifiLock!!.isHeld) {
            wifiLock!!.acquire()
        }
        try {
            keyguardLock?.disableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Disabling keyguard didn't work")
            ex.printStackTrace()
        }
    }

    private fun stopPowerOptions() {
        Timber.i("Releasing Screen/WiFi Locks")
        // Release CPU wake lock
        cpuWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        // Release screen wake lock if held
        screenWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        try {
            keyguardLock!!.reenableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Enabling keyguard didn't work")
            ex.printStackTrace()
        }
    }

    private fun startSensors() {
        if (configuration.sensorsEnabled && mqttOptions.isValid) {
            sensorReader.startReadings(configuration.mqttSensorFrequency, sensorCallback)
        }
    }

    private fun configureMqtt() {
        if (mqttModule == null && mqttOptions.isValid) {
            mqttModule = MQTTModule(this@WallPanelService.applicationContext, mqttOptions, this@WallPanelService)
            lifecycle.addObserver(mqttModule!!)
        }
    }

    override fun onMQTTConnect() {
        Timber.w("onMQTTConnect")
        if (mqttAlertMessageShown) {
            clearAlertMessage() // clear any dialogs
            mqttAlertMessageShown = false
        }
        clearFaceDetected()
        clearMotionDetected()
        publishApplicationState()
        if (configuration.sensorsEnabled) {
            sensorReader.refreshSensors()
        }
        if (configuration.mqttDiscovery) {
            publishDiscovery()
        }
        mqttInitConnection.set(false)
    }

    override fun onMQTTDisconnect() {
        Timber.e("onMQTTDisconnect")
        handleMQTTDisconnected()
    }

    override fun onMQTTException(message: String) {
        Timber.e("onMQTTException: $message")
        handleMQTTDisconnected()
    }

    private fun handleMQTTDisconnected() {
        if (hasNetwork()) {
            if (mqttInitConnection.get()) {
                mqttInitConnection.set(false)
                sendAlertMessage(getString(R.string.error_mqtt_exception))
                mqttAlertMessageShown = true
            }
            if (!mqttConnecting) {
                reconnectHandler.removeCallbacksAndMessages(null)
                reconnectHandler.postDelayed(restartMqttRunnable, 30000)
                mqttConnecting = true
            }
        }
    }

    override fun onMQTTMessage(id: String, topic: String, payload: String) {
        Timber.i("onMQTTMessage: $id, $topic, $payload")
        processCommand(payload)
    }

    private fun publishCommand(command: String, data: JSONObject) {
        publishMessage("${configuration.mqttBaseTopic}${command}", data.toString(), false)
    }

    private fun publishMessage(topic: String, message: String, retain: Boolean) {
        mqttModule?.publish(topic, message, retain)
    }

    private fun configureCamera() {
        val cameraEnabled = configuration.cameraEnabled
        if (cameraEnabled && cameraReader == null) {
            cameraReader = CameraReader(applicationContext)
            cameraReader?.startCamera(cameraDetectorCallback, configuration)
        } else if (cameraEnabled) {
            cameraReader?.startCamera(cameraDetectorCallback, configuration)
        }
    }

    private fun configureTextToSpeech() {
        if (textToSpeechModule == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeechModule = TextToSpeechModule(applicationContext)
            textToSpeechModule?.let {
                lifecycle.addObserver(it)
            }
        }
    }

    private fun configureAudioPlayer() {
        audioPlayer = MediaPlayer()
        audioPlayer?.setOnPreparedListener { audioPlayer ->
            audioPlayerBusy = false
            audioPlayer.start()
        }
        audioPlayer?.setOnCompletionListener { audioPlayer ->
            if (audioPlayer.isPlaying) {  // should never happen, just in case
                audioPlayer.stop()
            }
            audioPlayer.reset()
            audioPlayerBusy = false
        }
        audioPlayer?.setOnErrorListener { audioPlayer, i, i1 ->
            audioPlayerBusy = false
            false
        }
    }

    // TODO text to speech requies content type 'Content-Type': 'application/json; charset=UTF-8'
    private fun startHttp() {
        if (httpServer == null && configuration.httpEnabled) {
            // TODO this is a hack to get utf-8 working, we need to switch http server libraries
            val charsetsClass = Charsets::class.java
            val us_ascii = charsetsClass.getDeclaredField("US_ASCII")
            us_ascii.isAccessible = true
            us_ascii.set(Charsets::class.java, Charsets.UTF_8)
            httpServer = AsyncHttpServer()

            httpServer?.addAction("*", "*") { request, response ->
                Timber.i("Unhandled Request Arrived")
                response.code(404)
                response.send("")
            }
            httpServer?.listen(AsyncServer.getDefault(), configuration.httpPort)
            Timber.i("Started HTTP server on " + configuration.httpPort)
        }

        if (httpServer != null) {
            // Registered whenever the HTTP server itself is running, but gated on
            // configuration.httpRestEnabled inside each handler rather than at registration
            // time: the server is only (re)started from onCreate(), so a route that checked
            // the flag just once here would keep answering with its startup value for the
            // life of the process, ignoring the setting being switched off afterward -- the
            // same live-check approach the shell command already uses for its own toggle.
            httpServer?.addAction("POST", "/api/command") { request, response ->
                if (!configuration.httpRestEnabled) {
                    response.code(403)
                    response.send("REST API is disabled")
                    return@addAction
                }
                var result = false
                if (request.body is JSONObjectBody) {
                    Timber.i("POST Json Arrived (command)")
                    val body = (request.body as JSONObjectBody).get()
                    result = processCommand(body)
                } else if (request.body is StringBody) {
                    Timber.i("POST String Arrived (command)")
                    result = processCommand((request.body as StringBody).get())
                }
                val j = JSONObject()
                try {
                    j.put("result", result)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
                response.send(j)
            }

            httpServer?.addAction("GET", "/api/state") { request, response ->
                if (!configuration.httpRestEnabled) {
                    response.code(403)
                    response.send("REST API is disabled")
                    return@addAction
                }
                Timber.i("GET Arrived (/api/state)")
                response.send(state)
            }
            Timber.i("Registered REST endpoints")
        }

        if (httpServer != null && configuration.httpMJPEGEnabled) {
            startMJPEG()
            httpServer?.addAction("GET", "/camera/stream") { _, response ->
                Timber.i("GET Arrived (/camera/stream)")
                startMJPEG(response)
            }
            Timber.i("Enabled MJPEG Endpoint")
        }
    }

    private fun stopHttp() {
        httpServer?.let {
            stopMJPEG()
            it.stop()
            httpServer = null
        }
    }

    private fun startMJPEG() {
        cameraReader?.let {
            it.getJpeg().observe(this, Observer { jpeg ->
                if (mJpegSockets.size > 0 && jpeg != null) {
                    var i = 0
                    while (i < mJpegSockets.size) {
                        val s = mJpegSockets[i]
                        val bb = ByteBufferList()
                        if (s.isOpen) {
                            bb.recycle()
                            bb.add(ByteBuffer.wrap("--jpgboundary\r\nContent-Type: image/jpeg\r\n".toByteArray()))
                            bb.add(ByteBuffer.wrap(("Content-Length: " + jpeg.size + "\r\n\r\n").toByteArray()))
                            bb.add(ByteBuffer.wrap(jpeg))
                            bb.add(ByteBuffer.wrap("\r\n".toByteArray()))
                            s.write(bb)
                        } else {
                            mJpegSockets.removeAt(i)
                            i--
                            Timber.i("MJPEG Session Count is " + mJpegSockets.size)
                        }
                        i++
                    }
                }
            })
        }
    }

    // Attempt to restart camera and any optional camera options such as motion and streaming
    private fun restartCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && configuration.cameraPermissionsShown) {
            configuration.cameraEnabled = true
            configureCamera()
            startHttp()
            publishDiscovery()
            publishApplicationState()
        } else {
            configuration.cameraEnabled = true
            configureCamera()
            startHttp()
            publishDiscovery()
            publishApplicationState()
        }
    }

    // Attempt to stop camera and any optional camera options such as motion and streaming
    private fun stopCamera() {
        cameraReader?.stopCamera()
    }

    // Stop camera and disable it permanently in settings
    private fun stopCameraCompletely() {
        configuration.cameraEnabled = false
        stopMJPEG()
        stopHttp()
        cameraReader?.stopCamera()
        publishDiscovery()
        publishApplicationState()
    }

    // TODO we stop entire camera not just streaming
    private fun stopMJPEG() {
        mJpegSockets.clear()
        //cameraReader?.getJpeg()?.removeObservers(this)
        httpServer?.removeAction("GET", "/camera/stream")
    }

    private fun startMJPEG(response: AsyncHttpServerResponse) {
        if (mJpegSockets.size < configuration.httpMJPEGMaxStreams) {
            Timber.i("Starting new MJPEG stream")
            response.headers.add("Cache-Control", "no-cache")
            response.headers.add("Connection", "close")
            response.headers.add("Pragma", "no-cache")
            response.setContentType("multipart/x-mixed-replace; boundary=--jpgboundary")
            response.code(200)
            response.writeHead()
            mJpegSockets.add(response)
        } else {
            Timber.i("MJPEG stream limit was reached, not starting")
            response.send("Max streams exceeded")
            response.end()
        }
        Timber.i("MJPEG Session Count is " + mJpegSockets.size)
    }

    private fun processCommand(commandJson: JSONObject): Boolean {
        try {
            if (commandJson.has(COMMAND_CAMERA)) {
                val enableCamera = commandJson.getBoolean(COMMAND_CAMERA)
                if (!enableCamera) {
                    stopCamera()
                } else if (enableCamera) {
                    restartCamera()
                }
            }
            if (commandJson.has(COMMAND_URL)) {
                browseUrl(commandJson.getString(COMMAND_URL))
            }
            if (commandJson.has(COMMAND_RELAUNCH)) {
                if (commandJson.getBoolean(COMMAND_RELAUNCH)) {
                    browseUrl(configuration.appLaunchUrl)
                }
            }
            if (commandJson.has(COMMAND_WAKE)) {
                if (commandJson.getBoolean(COMMAND_WAKE).or(false)) {
                    val fallback = configuration.inactivityTime/1000 // if no wake time, use inactivity time, convert to seconds
                    val wakeTime = commandJson.optLong(COMMAND_WAKETIME, fallback) * 1000 // convert to milliseconds
                    if(wakeTime > 0) {
                        wakeScreenOn(wakeTime)
                    } else {
                        wakeScreen()
                    }
                } else {
                    wakeScreenOff()
                }
            }
            if (commandJson.has(COMMAND_BRIGHTNESS)) {
                // This will permanently change the screen brightness level
                val brightness = commandJson.getInt(COMMAND_BRIGHTNESS)
                changeScreenBrightness(brightness)
            }
            if (commandJson.has(COMMAND_RELOAD)) {
                if (commandJson.getBoolean(COMMAND_RELOAD)) {
                    reloadPage()
                }
            }
            if (commandJson.has(COMMAND_CLEAR_CACHE)) {
                if (commandJson.getBoolean(COMMAND_CLEAR_CACHE)) {
                    clearBrowserCache()
                }
            }
            if (commandJson.has(COMMAND_EVAL)) {
                evalJavascript(commandJson.getString(COMMAND_EVAL))
            }
            if (commandJson.has(COMMAND_AUDIO)) {
                playAudio(commandJson.getString(COMMAND_AUDIO))
            }
            if (commandJson.has(COMMAND_SPEAK)) {
                speakMessage(commandJson.getString(COMMAND_SPEAK))
            }
            if (commandJson.has(COMMAND_SETTINGS)) {
                openSettings()
            }
            if (commandJson.has(COMMAND_VOLUME)) {
                setVolume((commandJson.getInt(COMMAND_VOLUME).toFloat() / 100))
            }
            if (commandJson.has(COMMAND_SHELL) && configuration.httpShellEnabled) {
                executeShellCommand(commandJson.getString(COMMAND_SHELL))
            }
            // Kept last, it does not return.
            if (commandJson.has(COMMAND_RESTART_APP)) {
                if (commandJson.getBoolean(COMMAND_RESTART_APP)) {
                    restartApplication()
                }
            }
        } catch (ex: JSONException) {
            Timber.e("Invalid JSON passed as a command: " + commandJson.toString())
            return false
        }

        return true
    }

    /**
     * Ends the process and books the browser to come back, the same way the application
     * recovers from an uncaught exception. Alarms held by the system, including the
     * scheduled tasks, are unaffected by the process ending.
     */
    private fun restartApplication() {
        AppRestartHelper.restartApplication(applicationContext, RESTART_EXIT_DELAY_MS)
    }

    private fun executeShellCommand(command: String) {
        try {
            // stderr is merged into stdout so a single reader can drain the process, draining one
            // pipe at a time would deadlock a command that fills the other pipe's buffer.
            val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
            // Drain the output before waiting, otherwise a full pipe buffer blocks the process.
            val output = try {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            } catch (e: Exception) {
                Timber.e(e, "Failed to read output of shell command: $command")
                ""
            }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Timber.i("Shell command [$command] exited with code $exitCode, output: $output")
            } else {
                Timber.w("Shell command [$command] failed with exit code $exitCode, output: $output")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute shell command: $command")
        }
    }

    private fun processCommand(command: String): Boolean {
        return try {
            processCommand(JSONObject(command))
        } catch (ex: JSONException) {
            Timber.e("Invalid JSON passed as a command: $command")
            false
        }
    }

    private fun browseUrl(url: String) {
        val intent = Intent(BROADCAST_ACTION_LOAD_URL)
        intent.putExtra(BROADCAST_ACTION_LOAD_URL, url)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun playAudio(audioUrl: String) {
        if (audioPlayerBusy) {
            audioPlayer?.reset()
        } else if (audioPlayer?.isPlaying == true) {
            audioPlayer?.stop()
            audioPlayer?.reset()
        }
        audioPlayerBusy = true
        try {
            audioPlayer!!.setDataSource(audioUrl)
        } catch (e: IOException) {
            Timber.e("audioPlayer: An error occurred while preparing audio (" + e.message + ")")
            audioPlayerBusy = false
            audioPlayer?.reset()
            return
        }
        audioPlayer?.prepareAsync()
    }

    private fun setVolume(vol: Float) {
        audioPlayer?.setVolume(vol, vol)
    }

    // TODO we need to url decode incoming strings to support other languages
    private fun speakMessage(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeechModule?.speakText(message)
        } else {
            sendAlertMessage("Text to Speech is not supported on this device's version of Android")
        }
    }

    // TODO temporarily wake screen
    private fun wakeScreen() {
        val intent = Intent(BROADCAST_SCREEN_WAKE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    @SuppressLint("WakelockTimeout")
    private fun wakeScreenOn(wakeTime: Long) {
        // Acquire screen wake lock with timeout to turn screen on temporarily
        screenWakeLock?.let {
            if (!it.isHeld) {
                it.acquire(wakeTime)
            }
        }
        wakeScreenHandler.postDelayed(clearWakeScreenRunnable, wakeTime)
        sendWakeScreenOn()
    }

    private val clearWakeScreenRunnable = Runnable {
        wakeScreenOff()
    }

    private fun wakeScreenOff() {
        wakeScreenHandler.removeCallbacks(clearWakeScreenRunnable)
        // Release screen wake lock
        screenWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        sendWakeScreenOff()
    }

    private fun changeScreenBrightness(brightness: Int) {
        if (configuration.screenBrightness != brightness && configuration.useScreenBrightness) {
            screenUtils.updateScreenBrightness(brightness)
            sendScreenBrightnessChange()
        }
    }

    private fun evalJavascript(js: String) {
        val intent = Intent(BROADCAST_ACTION_JS_EXEC)
        intent.putExtra(BROADCAST_ACTION_JS_EXEC, js)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun reloadPage() {
        val intent = Intent(BROADCAST_ACTION_RELOAD_PAGE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun openSettings() {
        val intent = Intent(BROADCAST_ACTION_OPEN_SETTINGS)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun clearBrowserCache() {
        val intent = Intent(BROADCAST_ACTION_CLEAR_BROWSER_CACHE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun publishMotionDetected() {
        val delay = (configuration.motionResetTime * 1000).toLong()
        if (!motionDetected) {
            val data = JSONObject()
            try {
                data.put(VALUE, true)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            motionDetected = true
            publishCommand(COMMAND_SENSOR_MOTION, data)
            motionClearHandler.postDelayed({ clearMotionDetected() }, delay)
        }
    }

    private fun publishApplicationState() {
        if (!appStatePublished) {
            appStatePublished = true
            try {
                publishCommand(COMMAND_STATE, state)
            } catch (e: Exception) {
                Timber.e(e, "Unable to publish MQTT application state")
            }
            appStateClearHandler.postDelayed(appStateCooldownRunnable, APP_STATE_PUBLISH_THROTTLE_MS)
        } else {
            appStatePublishPending = true
        }
    }

    private fun publishFaceDetected() {
        if (!faceDetected) {
            val data = JSONObject()
            try {
                data.put(MqttUtils.VALUE, true)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            faceDetected = true
            publishCommand(COMMAND_SENSOR_FACE, data)

        }
        faceClearHandler.removeCallbacksAndMessages(null)
        faceClearHandler.postDelayed({ clearFaceDetected() }, 3000)
    }

    private fun getDeviceDiscoveryDef(): JSONObject {
        val deviceJson = JSONObject()
        deviceJson.put("identifiers", listOf("wallpanel_${configuration.mqttClientId}"))
        deviceJson.put("name", configuration.mqttDiscoveryDeviceName)
        deviceJson.put("manufacturer", Build.MANUFACTURER.toLowerCase().capitalize())
        deviceJson.put("model", Build.MODEL)
        return deviceJson
    }

    private fun getSensorDiscoveryDef(displayName: String, stateTopic: String, deviceClass: String?, unit: String?, sensorId: String, valueTemplate: String = "{{ value_json.value | float }}", entityCategory: String? = null, stateClass: String? = null, icon: String? = null): JSONObject {
        val discoveryDef = JSONObject()
        if (configuration.mqttLegacyDiscoveryEntities) {
            discoveryDef.put("name", "${configuration.mqttDiscoveryDeviceName} ${displayName}")
        } else {
            discoveryDef.put("name", displayName)
        }
        val originDef = JSONObject()
        originDef.put("name", "WallPanel")
        originDef.put("sw", appVersion)
        originDef.put("url", "https://wallpanel.xyz")
        discoveryDef.put("origin", originDef)
        discoveryDef.put("state_topic", "${configuration.mqttBaseTopic}${stateTopic}")
        if (unit != null) {
            discoveryDef.put("unit_of_measurement", unit)
        }
        discoveryDef.put("value_template", valueTemplate)
        if (deviceClass != null) {
            discoveryDef.put("device_class", deviceClass)
        }
        if (entityCategory != null) {
            discoveryDef.put("entity_category", entityCategory)
        }
        if (stateClass != null) {
            discoveryDef.put("state_class", stateClass)
        }
        if (icon != null) {
            discoveryDef.put("icon", icon)
        }
        discoveryDef.put("unique_id", "wallpanel_${configuration.mqttClientId}_${sensorId}")
        discoveryDef.put("device", getDeviceDiscoveryDef())
        discoveryDef.put("availability_topic", "${configuration.mqttBaseTopic}connection")

        return discoveryDef
    }

    private fun publishStateSensorDiscovery(displayName: Int, fieldName: String, deviceClass: String? = null, unit: String? = null, numeric: Boolean = false, diagnostic: Boolean = false, icon: String? = null, valueTemplate: String? = null) {
        val defaultValueTemplate = if (numeric) {
            "{{ value_json.$fieldName if value_json.$fieldName is number else '' }}"
        } else {
            "{{ value_json.$fieldName }}"
        }
        val discovery = getSensorDiscoveryDef(
            getString(displayName),
            COMMAND_STATE,
            deviceClass,
            unit,
            fieldName,
            valueTemplate ?: defaultValueTemplate,
            if (diagnostic) "diagnostic" else null,
            if (numeric) "measurement" else null,
            icon
        )
        publishOrClearDiscovery(fieldName, discovery)
    }

    private fun publishStateBinarySensorDiscovery(displayName: Int, fieldName: String, deviceClass: String?, diagnostic: Boolean = false) {
        val discovery = getBinarySensorDiscoveryDef(getString(displayName), COMMAND_STATE, fieldName, deviceClass, fieldName)
        if (diagnostic) {
            discovery.put("entity_category", "diagnostic")
        }
        publishOrClearDiscovery(fieldName, discovery)
    }

    private fun clearDiscovery(sensorId: String) {
        val component = MqttDiscoverySensors.componentFor(sensorId).mqttComponent
        publishMessage(
            "${configuration.mqttDiscoveryTopic}/$component/${configuration.mqttClientId}/$sensorId/config",
            "",
            true
        )
    }

    private fun publishOrClearDiscovery(sensorId: String, discovery: JSONObject) {
        val component = MqttDiscoverySensors.componentFor(sensorId).mqttComponent
        if (configuration.mqttDiscoverySensorEnabled(sensorId)) {
            publishMessage(
                "${configuration.mqttDiscoveryTopic}/$component/${configuration.mqttClientId}/$sensorId/config",
                discovery.toString(),
                true
            )
        } else {
            clearDiscovery(sensorId)
        }
    }

    private fun publishStateSensorDiscoveries() {
        publishStateSensorDiscovery(R.string.mqtt_sensor_android_version, MqttUtils.STATE_ANDROID_VERSION, diagnostic = true, icon = "mdi:android")
        publishStateSensorDiscovery(R.string.mqtt_sensor_app_version, MqttUtils.STATE_APP_VERSION, diagnostic = true)
        publishStateSensorDiscovery(R.string.mqtt_sensor_brightness, MqttUtils.STATE_BRIGHTNESS, numeric = true)
        publishStateSensorDiscovery(R.string.mqtt_sensor_current_url, MqttUtils.STATE_CURRENT_URL, icon = "mdi:web")
        publishStateSensorDiscovery(R.string.mqtt_sensor_ip_address, MqttUtils.STATE_IP_ADDRESS, diagnostic = true, icon = "mdi:ip-network")
        publishStateSensorDiscovery(R.string.mqtt_sensor_manufacturer, MqttUtils.STATE_MANUFACTURER, diagnostic = true)
        publishStateSensorDiscovery(R.string.mqtt_sensor_model, MqttUtils.STATE_MODEL, diagnostic = true)
        publishStateSensorDiscovery(R.string.mqtt_sensor_storage_free, MqttUtils.STATE_STORAGE_FREE, "data_size", "MB", true, true)
        publishStateSensorDiscovery(R.string.mqtt_sensor_uptime, MqttUtils.STATE_UPTIME, "duration", "s", true, true)
        publishStateSensorDiscovery(R.string.mqtt_sensor_volume, MqttUtils.STATE_VOLUME, null, "%", true, false, "mdi:volume-high")
        publishStateSensorDiscovery(R.string.mqtt_sensor_wifi_signal, MqttUtils.STATE_WIFI_SIGNAL, "signal_strength", "dBm", true, true)
        publishStateSensorDiscovery(R.string.mqtt_sensor_wifi_ssid, MqttUtils.STATE_WIFI_SSID, diagnostic = true)
        publishStateBinarySensorDiscovery(R.string.mqtt_sensor_device_owner, MqttUtils.STATE_DEVICE_OWNER, null, true)
        publishStateBinarySensorDiscovery(R.string.mqtt_sensor_screen, MqttUtils.STATE_SCREEN_ON, "power")
        publishStateBinarySensorDiscovery(R.string.mqtt_sensor_screensaver, MqttUtils.STATE_SCREEN_SAVER, "running")
    }

    private fun clearStateSensorDiscoveries() {
        listOf(
            MqttUtils.STATE_ANDROID_VERSION, MqttUtils.STATE_APP_VERSION, MqttUtils.STATE_BRIGHTNESS,
            MqttUtils.STATE_CURRENT_URL, MqttUtils.STATE_IP_ADDRESS, MqttUtils.STATE_MANUFACTURER,
            MqttUtils.STATE_MODEL, MqttUtils.STATE_STORAGE_FREE, MqttUtils.STATE_UPTIME,
            MqttUtils.STATE_VOLUME, MqttUtils.STATE_WIFI_SIGNAL, MqttUtils.STATE_WIFI_SSID
        ).forEach(::clearDiscovery)
        listOf(MqttUtils.STATE_DEVICE_OWNER, MqttUtils.STATE_SCREEN_ON, MqttUtils.STATE_SCREEN_SAVER)
            .forEach(::clearDiscovery)
    }

    private fun getBinarySensorDiscoveryDef(displayName: String, stateTopic: String, fieldName: String, deviceClass: String?, sensorId: String): JSONObject {
        val discoveryDef = JSONObject()
        if (configuration.mqttLegacyDiscoveryEntities) {
            discoveryDef.put("name", "${configuration.mqttDiscoveryDeviceName} ${displayName}")
        } else {
            discoveryDef.put("name", displayName)
        }
        val originDef = JSONObject()
        originDef.put("name", "WallPanel")
        originDef.put("sw", appVersion)
        originDef.put("url", "https://wallpanel.xyz")
        discoveryDef.put("origin", originDef)
        discoveryDef.put("state_topic", "${configuration.mqttBaseTopic}${stateTopic}")
        discoveryDef.put("payload_on", "ON")
        discoveryDef.put("payload_off", "OFF")
        discoveryDef.put("value_template", "{{ 'ON' if value_json.${fieldName} else 'OFF' }}")
        if (deviceClass != null) {
            discoveryDef.put("device_class", deviceClass)
        }
        discoveryDef.put("unique_id", "wallpanel_${configuration.mqttClientId}_${sensorId}")
        discoveryDef.put("device", getDeviceDiscoveryDef())
        discoveryDef.put("availability_topic", "${configuration.mqttBaseTopic}connection")

        return discoveryDef
    }

    private fun publishDiscovery() {
        if (configuration.sensorsEnabled) {
            val batteryDiscovery = getSensorDiscoveryDef(getString(R.string.mqtt_sensor_battery_level), "sensor/battery", "battery", "%", "battery")
            publishOrClearDiscovery("battery", batteryDiscovery)
            val usbPluggedDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_usb_plugged), "sensor/battery", "usbPlugged", "power", "usbPlugged")
            publishOrClearDiscovery("usbPlugged", usbPluggedDiscovery)
            val acPluggedDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_ac_plugged), "sensor/battery", "acPlugged", "power", "acPlugged")
            publishOrClearDiscovery("acPlugged", acPluggedDiscovery)
            val chargeDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_charging), "sensor/battery", "charging", "battery_charging", "charging")
            publishOrClearDiscovery("charging", chargeDiscovery)
            val sensors = sensorReader.getSensors()
            for (sensor in sensors) {
                if (sensor.sensorType != null) {
                    val sensorDiscoveryDef = getSensorDiscoveryDef(sensor.displayName!!, "sensor/${sensor.sensorType!!}", sensor.deviceClass, sensor.unit, sensor.sensorType!!)
                    publishOrClearDiscovery(sensor.sensorType!!, sensorDiscoveryDef)
                }
            }
            publishStateSensorDiscoveries()

        } else {
            clearDiscovery("battery")
            clearDiscovery("usbPlugged")
            clearDiscovery("acPlugged")
            clearDiscovery("charging")
            val sensors = sensorReader.getSensors()
            for (sensor in sensors) {
                if (sensor.sensorType != null) {
                    clearDiscovery(sensor.sensorType!!)
                }
            }
            clearStateSensorDiscoveries()
        }

        if (configuration.cameraFaceEnabled && configuration.cameraEnabled) {
            val faceDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_face_detected), COMMAND_SENSOR_FACE, "value", "occupancy", "face")
            publishOrClearDiscovery("face", faceDiscovery)
        } else {
            clearDiscovery("face")
        }

        if (configuration.cameraMotionEnabled && configuration.cameraEnabled) {
            val motionDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_motion_detected), COMMAND_SENSOR_MOTION, "value", "motion", "motion")
            publishOrClearDiscovery("motion", motionDiscovery)
        } else {
            clearDiscovery("motion")
        }

        if (configuration.cameraQRCodeEnabled && configuration.cameraEnabled) {
            val qrDiscovery = JSONObject()
            qrDiscovery.put("topic", "${configuration.mqttBaseTopic}${COMMAND_SENSOR_QR_CODE}")
            qrDiscovery.put("value_template", "{{ value_json.value }}")
            qrDiscovery.put("device", getDeviceDiscoveryDef())
            publishOrClearDiscovery("qr", qrDiscovery)
        } else {
            clearDiscovery("qr")
        }
    }

    private fun clearMotionDetected() {
        if (motionDetected) {
            motionDetected = false
            val data = JSONObject()
            try {
                data.put(VALUE, false)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            publishCommand(COMMAND_SENSOR_MOTION, data)
        }
    }

    private fun clearFaceDetected() {
        if (faceDetected) {
            val data = JSONObject()
            try {
                data.put(VALUE, false)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            faceDetected = false
            publishCommand(MqttUtils.COMMAND_SENSOR_FACE, data)
        }
    }

    private fun publishQrCode(data: String) {
        if (!qrCodeRead) {
            val jdata = JSONObject()
            try {
                jdata.put(VALUE, data)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            qrCodeRead = true
            sendToastMessage(getString(R.string.toast_qr_code_read))
            publishCommand(COMMAND_SENSOR_QR_CODE, jdata)
            qrCodeClearHandler.postDelayed({ clearQrCodeRead() }, 5000)
        }
    }

    private fun clearQrCodeRead() {
        if (qrCodeRead) {
            qrCodeRead = false
        }
    }

    private fun sendAlertMessage(message: String) {
        val intent = Intent(BROADCAST_ALERT_MESSAGE)
        intent.putExtra(BROADCAST_ALERT_MESSAGE, message)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun clearAlertMessage() {
        val intent = Intent(BROADCAST_CLEAR_ALERT_MESSAGE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendWakeScreenOn() {
        val intent = Intent(BROADCAST_SCREEN_WAKE_ON)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendWakeScreenOff() {
        val intent = Intent(BROADCAST_SCREEN_WAKE_OFF)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    /**
     * Tell the browser activity the display is off so it can stop the page from running at
     * full foreground rate, which is what gets the browser process killed for background
     * CPU usage after a few minutes of screen-off.
     */
    private fun sendBrowserEnginePause() {
        val intent = Intent(BROADCAST_BROWSER_ENGINE_PAUSE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendBrowserEngineResume() {
        val intent = Intent(BROADCAST_BROWSER_ENGINE_RESUME)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendScreenBrightnessChange() {
        val intent = Intent(BROADCAST_SCREEN_BRIGHTNESS_CHANGE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendToastMessage(message: String) {
        val intent = Intent(BROADCAST_TOAST_MESSAGE)
        intent.putExtra(BROADCAST_TOAST_MESSAGE, message)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendServiceStarted() {
        val intent = Intent(BROADCAST_SERVICE_STARTED)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    // TODO don't change the user settings when receiving command
    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BROADCAST_EVENT_URL_CHANGE == intent.action) {
                appLaunchUrl = intent.getStringExtra(BROADCAST_EVENT_URL_CHANGE)
                if (appLaunchUrl != configuration.appLaunchUrl) {
                    Timber.i("Url changed to $appLaunchUrl")
                    publishApplicationState()
                }
            } else if (Intent.ACTION_SCREEN_OFF == intent.action) {
                Timber.i("Screen turned off")
                publishApplicationState()
                sendBrowserEnginePause()
            } else if (Intent.ACTION_SCREEN_ON == intent.action) {
                Timber.i("Screen turned on")
                publishApplicationState()
                sendBrowserEngineResume()
            } else if (Intent.ACTION_USER_PRESENT == intent.action) {
                Timber.i("User present")
                publishApplicationState()
            } else if (BROADCAST_EVENT_SCREEN_TOUCH == intent.action) {
                Timber.i("Screen touched")
            } else if (BROADCAST_CAMERA_START_SCREENSAVER == intent.action) {
                Timber.i("Screensaver started - enabling camera processing")
                isScreenSaverActive = true
                publishApplicationState()
            } else if (BROADCAST_CAMERA_STOP_SCREENSAVER == intent.action) {
                Timber.i("Screensaver stopped - disabling camera processing")
                isScreenSaverActive = false
                publishApplicationState()
            } else if (BROADCAST_EVENT_MQTT_SENSOR_SETTINGS_CHANGED == intent.action) {
                Timber.i("MQTT discovery sensor settings changed - republishing discovery")
                if (configuration.mqttDiscovery) {
                    publishDiscovery()
                }
            }
        }
    }

    private val sensorCallback = object : SensorCallback {
        override fun publishSensorData(sensorName: String, sensorData: JSONObject) {
            publishApplicationState()
            publishCommand(COMMAND_SENSOR + sensorName, sensorData)
        }
    }

    private val cameraDetectorCallback = object : CameraCallback {
        override fun onDetectorError() {
            if (configuration.cameraFaceEnabled || configuration.cameraQRCodeEnabled) {
                sendToastMessage(getString(R.string.error_missing_vision_lib))
            }
        }

        override fun onCameraError() {
            sendToastMessage(getString(R.string.toast_camera_source_error))
        }

        override fun onMotionDetected() {
            // Skip motion detection if screensaver is not active and the feature is enabled
            if (configuration.cameraOnlyWhenScreenSaver && !isScreenSaverActive) {
                return
            }
            
            Timber.i("Motion detected")
            if (configuration.cameraMotionWake) {
                configurePowerOptions()
                wakeScreen()
            }
            publishMotionDetected()
        }

        override fun onTooDark() {
            // Timber.i("Too dark for motion detection")
        }

        override fun onFaceDetected() {
            // Skip face detection if screensaver is not active and the feature is enabled
            if (configuration.cameraOnlyWhenScreenSaver && !isScreenSaverActive) {
                return
            }
            
            Timber.i("Face detected")
            if (configuration.cameraFaceWake) {
                configurePowerOptions()
                wakeScreen() // temp turn on screen
            }
            publishFaceDetected()
        }

        override fun onQRCode(data: String) {
            // Skip QR code processing if screensaver is not active and the feature is enabled
            if (configuration.cameraOnlyWhenScreenSaver && !isScreenSaverActive) {
                return
            }
            
            publishQrCode(data)
        }
    }

    companion object {
        const val ONGOING_NOTIFICATION_ID = 1
        const val BROADCAST_EVENT_URL_CHANGE = "BROADCAST_EVENT_URL_CHANGE"
        const val BROADCAST_EVENT_SCREEN_TOUCH = "BROADCAST_EVENT_SCREEN_TOUCH"
        const val SCREEN_WAKE_TIME = 30000L
        const val BROADCAST_ALERT_MESSAGE = "BROADCAST_ALERT_MESSAGE"
        const val BROADCAST_CLEAR_ALERT_MESSAGE = "BROADCAST_CLEAR_ALERT_MESSAGE"
        const val BROADCAST_TOAST_MESSAGE = "BROADCAST_TOAST_MESSAGE"
        const val BROADCAST_SERVICE_STARTED = "BROADCAST_SERVICE_STARTED"
        const val BROADCAST_SCREEN_WAKE = "BROADCAST_SCREEN_WAKE"
        const val BROADCAST_SCREEN_WAKE_ON = "BROADCAST_SCREEN_WAKE_ON"
        const val BROADCAST_SCREEN_WAKE_OFF = "BROADCAST_SCREEN_WAKE_OFF"
        const val BROADCAST_SCREEN_BRIGHTNESS_CHANGE = "BROADCAST_SCREEN_BRIGHTNESS_CHANGE"
        const val BROADCAST_BROWSER_ENGINE_PAUSE = "BROADCAST_BROWSER_ENGINE_PAUSE"
        const val BROADCAST_BROWSER_ENGINE_RESUME = "BROADCAST_BROWSER_ENGINE_RESUME"
        const val BROADCAST_CAMERA_START_SCREENSAVER = "BROADCAST_CAMERA_START_SCREENSAVER"
        const val BROADCAST_CAMERA_STOP_SCREENSAVER = "BROADCAST_CAMERA_STOP_SCREENSAVER"
        const val BROADCAST_EVENT_MQTT_SENSOR_SETTINGS_CHANGED = "BROADCAST_EVENT_MQTT_SENSOR_SETTINGS_CHANGED"
        const val BROADCAST_CONNTED = "BROADCAST_SCREEN_BRIGHTNESS_CHANGE"
        const val ACTION_RUN_COMMAND = "xyz.wallpanel.pro.action.RUN_COMMAND"
        const val EXTRA_COMMAND_JSON = "EXTRA_COMMAND_JSON"
        const val APP_STATE_PUBLISH_THROTTLE_MS = 300L
        const val WIFI_RSSI_UNAVAILABLE = -127

        /**
         * The process exit is delayed rather than immediate: when a restart is reached from
         * onStartCommand(), a scheduled task, exiting before that call returns kills the
         * process mid Binder-transaction, which the system reads as an incomplete start and
         * redelivers the same command to the relaunched process -- observed on-device as
         * three restarts in a row instead of one. The delay lets onStartCommand() return.
         */
        const val RESTART_EXIT_DELAY_MS = 300L
    }
}
