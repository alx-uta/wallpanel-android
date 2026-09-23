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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
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
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_HIDE_SCREENSAVER
import xyz.wallpanel.pro.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_SHOW_SCREENSAVER
import xyz.wallpanel.pro.utils.AppRestartHelper
import xyz.wallpanel.pro.utils.MqttUtils
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_AUDIO
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_BRIGHTNESS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CAMERA
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_CLEAR_CACHE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_EVAL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RELAUNCH
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RELOAD
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_RESTART_APP
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SCREENSAVER
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_FACE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_MOTION
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_QR_CODE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SENSOR_SHELL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SETTINGS
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SPEAK
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_STATE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_TOAST
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_URL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_SHELL
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_VOLUME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_WAKE
import xyz.wallpanel.pro.utils.MqttUtils.Companion.COMMAND_WAKETIME
import xyz.wallpanel.pro.utils.MqttUtils.Companion.VALUE
import xyz.wallpanel.pro.utils.NotificationUtils
import xyz.wallpanel.pro.utils.ScheduledTaskAlarmScheduler
import xyz.wallpanel.pro.utils.ScreenUtils
import xyz.wallpanel.pro.utils.VolumeUtils
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject


// TODO move this to internal class within application, no longer run as service
class WallPanelService : LifecycleService(), MQTTModule.MQTTListener {

    @Inject
    lateinit var configuration: Configuration

    private var cameraReader: CameraReader? = null
    // Whether the camera has been handed to [cameraExecutor] to open and not stopped since.
    // Set from the command threads and from the camera thread's error callback, read on the
    // main looper when a service start reconciles the settings.
    @Volatile
    private var cameraRunning = false
    private var sensorsStarted = false
    private var sensorsFrequency = 0

    @Inject
    lateinit var sensorReader: SensorReader

    @Inject
    lateinit var mqttOptions: MQTTOptions

    @Inject
    lateinit var screenUtils: ScreenUtils

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    @Inject
    lateinit var mqttDiscovery: MqttDiscovery

    @Inject
    lateinit var volumeUtils: VolumeUtils

    // Added on the HTTP server thread, written to on the main thread and dropped from
    // whichever thread a camera command arrives on.
    private val mJpegSockets = CopyOnWriteArrayList<AsyncHttpServerResponse>()
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var audioPlayer: MediaPlayer? = null
    private var audioPlayerBusy: Boolean = false
    private var httpServer: AsyncHttpServer? = null
    private var httpServerPort = 0
    private var mjpegEndpointRegistered = false
    private val mBinder = WallPanelServiceBinder()
    private val motionClearHandler = Handler(Looper.getMainLooper())
    private val appStateClearHandler = Handler(Looper.getMainLooper())
    private val qrCodeClearHandler = Handler(Looper.getMainLooper())
    private val faceClearHandler = Handler(Looper.getMainLooper())
    private val wakeScreenHandler = Handler(Looper.getMainLooper())
    private val mjpegHandler = Handler(Looper.getMainLooper())
    private var textToSpeechModule: TextToSpeechModule? = null
    private var mqttModule: MQTTModule? = null
    // A client opened only to take the entities down after MQTT was switched off, see
    // retractDiscoveryWhileDisabled().
    private var discoveryCleanup: MQTTModule? = null
    private var connectedMqttSettings: String? = null
    private var connectionLiveData: ConnectionLiveData? = null
    private var hasNetwork = AtomicBoolean(true)
    private var motionDetected: Boolean = false
    // Only ever touched on the main looper, see publishApplicationState().
    private var appStatePublished: Boolean = false
    private var appStatePublishPending: Boolean = false
    private var appStatePublishPendingForce: Boolean = false
    private var lastPublishedState: String = ""
    // The topic the retained state went to, which a base topic edit leaves behind.
    private var publishedStateTopic: String? = null
    @Volatile
    private var lastStateRefresh: Long = 0
    // The discovery payloads by topic as last sent to the broker. Written on the discovery
    // thread and read from wherever a republish is considered.
    @Volatile
    private var publishedDiscovery: Map<String, String>? = null
    // Building the payloads walks every entity and reads a preference for each, which on an
    // older device measured 28ms warm and over 100ms cold, so it is kept off the main
    // looper. A single thread also keeps the published snapshot and the advertised topics
    // in step when commands and service starts ask to publish at once.
    private val discoveryExecutor = Executors.newSingleThreadExecutor()
    // Opening and releasing the camera, off the thread a command or a lifecycle callback
    // arrives on. One thread, so a stop and a start cannot overlap.
    private val cameraExecutor = Executors.newSingleThreadExecutor()
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
        filter.addAction(BROADCAST_SCREENSAVER_STARTED)
        filter.addAction(BROADCAST_SCREENSAVER_STOPPED)
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
        // Opening settings normally stops the service, so a reconnect republishes. The
        // launcher shortcut goes straight to the settings screen and leaves the service
        // running, so any setting that changed what the configs say is caught here instead.
        // While the client is down publishDiscovery() turns back without recording what it
        // sent, so building the payloads to compare them would be thrown away work.
        // The same screen can switch these on for the first time, and each does nothing
        // when what it starts is already running or still switched off.
        configureMqtt()
        // The running server holds the port it bound to, and startHttp() leaves a server
        // that exists alone, so a changed port or both endpoints switched off is a stop
        // first. With neither endpoint on, httpEnabled is false and it stays down.
        if (httpServer != null && (!configuration.httpEnabled || httpServerPort != configuration.httpPort)) {
            stopHttp()
        }
        startHttp()
        if (configuration.httpMJPEGEnabled) {
            startMJPEG()
        } else {
            // Streams already running would otherwise keep receiving frames after streaming
            // was switched off, while new requests are turned away.
            stopMJPEG()
        }
        // Not a plain configureCamera(): that one restarts a running camera, which is what
        // a camera command wants and a return from the settings screen does not.
        if (configuration.cameraEnabled && !cameraRunning) {
            configureCamera()
        } else if (!configuration.cameraEnabled && cameraRunning) {
            // Switched off from the settings screen. Without this the camera stays open and
            // motion, face and QR carry on reporting while the state says the camera is off.
            stopCameraCompletely()
        }
        startSensors()
        val mqttSettings = mqttConnectionSettings()
        if (mqttModule != null && mqttSettings != connectedMqttSettings && !configuration.mqttEnabled) {
            // Switched off from the settings screen. The client would otherwise keep its
            // connection and carry on publishing to a broker the user has just stopped
            // using; configureMqtt() builds a new one if it comes back.
            Timber.i("MQTT was switched off, disconnecting the client")
            connectedMqttSettings = mqttSettings
            // Held as the cleanup client until it has sent the removals, so the check below
            // does not open a second one under the same client id, which the broker would
            // answer by dropping this one part way through.
            mqttModule?.let {
                discoveryCleanup = it
                retractDiscoveryAndStop(it)
            }
            mqttModule = null
        } else if (mqttModule != null && mqttSettings != connectedMqttSettings && mqttOptions.isValid) {
            // The settings screen can change the broker or the topics under a running
            // client, which stays on the ones it connected with. Advertising a device on
            // topics that client does not answer on would leave every entity unavailable,
            // so the client is reconnected and its connect callback publishes the configs.
            // A half finished edit -- a broker typed in but no topic yet -- leaves both the
            // client and the recorded settings alone, so a working connection stays up and
            // the reconnect happens once the settings make a connection again.
            Timber.i("The MQTT settings changed, reconnecting the client")
            // Cleared while the old connection is still up: the state message is retained,
            // so a base topic edit would otherwise strand this device's last state on the
            // topic it used to publish to.
            publishedStateTopic?.let { topic ->
                if (topic != "${configuration.mqttBaseTopic}$COMMAND_STATE") {
                    publishMessage(topic, "", true)
                    publishedStateTopic = null
                    lastPublishedState = ""
                }
            }
            connectedMqttSettings = mqttSettings
            mqttModule?.restart()
        } else if (mqttModule?.isConnected == true) {
            submitDiscovery {
                val payloads = discoveryPayloads()
                if (payloads != publishedDiscovery) {
                    publishDiscoveryNow(payloads)
                    publishApplicationState()
                }
            }
        }
        retractDiscoveryWhileDisabled()
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
        stopDiscoveryCleanup()
        if (localBroadCastManager != null) {
            localBroadCastManager?.unregisterReceiver(mBroadcastReceiver)
        }
        unregisterSystemBroadcastReceiver()
        sensorsStarted = false
        // Queued behind whatever the camera thread is doing, so the handle is given back
        // even if a command was opening it as the service went down.
        val reader = cameraReader
        submitCamera { reader?.stopCamera() }
        cameraExecutor.shutdown()
        sensorReader.stopReadings()
        stopHttp()
        stopPowerOptions()
        reconnectHandler.removeCallbacksAndMessages(null)
        mjpegHandler.removeCallbacksAndMessages(null)
        discoveryExecutor.shutdown()
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
        get() {
            val state = JSONObject()
            try {
                state.put(MqttUtils.STATE_CURRENT_URL, appLaunchUrl)
                state.put(MqttUtils.STATE_SCREEN_ON, isScreenOn)
                // What the Keep Screen Awake switch reads. Releasing the lock doesn't blank
                // the display, so screenOn would keep that switch showing on.
                state.put(MqttUtils.STATE_SCREEN_AWAKE, screenWakeLock?.isHeld == true)
                state.put(MqttUtils.STATE_CAMERA, configuration.cameraEnabled)
                // The live screen value and the configured level are not the same number
                // while the screensaver is dimming, so both are reported: a slider that
                // reads the dimmed value back would jump away from whatever was just set.
                state.put(MqttUtils.STATE_BRIGHTNESS, screenUtils.getCurrentScreenBrightness())
                state.put(MqttUtils.STATE_BRIGHTNESS_SETPOINT, configuration.screenBrightness)
                state.put(MqttUtils.STATE_VOLUME, volumeUtils.getVolumePercent())
                state.put(MqttUtils.STATE_SCREENSAVER_ON, isScreenSaverActive)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            return state
        }

    private fun startForeground() {
        // make a continuously running notification
        val notificationUtils = NotificationUtils(applicationContext, application.resources)
        val notification = notificationUtils.createNotification(getString(R.string.wallpanel_service_notification_title), getString(R.string.wallpanel_service_notification_message))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ONGOING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }

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
    }

    private fun handleNetworkDisconnect() {
        mqttModule?.let {
            if (hasNetwork()) {
                it.pause()
            }
        }
        hasNetwork.set(false)
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
        releaseScreenWakeLock()
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

    /**
     * Brings the readings in line with the settings, which the settings screen can change
     * without the service stopping. Restarting them resends the readings that never change,
     * so it happens only when they are not running or the interval moved.
     */
    private fun startSensors() {
        val frequency = configuration.mqttSensorFrequency
        if (configuration.sensorsEnabled && mqttOptions.isValid) {
            if (!sensorsStarted || sensorsFrequency != frequency) {
                sensorsStarted = true
                sensorsFrequency = frequency
                sensorReader.startReadings(frequency, sensorCallback)
            }
        } else if (sensorsStarted) {
            sensorsStarted = false
            sensorReader.stopReadings()
        }
    }

    private fun configureMqtt() {
        if (mqttModule == null && mqttOptions.isValid) {
            mqttModule = MQTTModule(this@WallPanelService.applicationContext, mqttOptions, this@WallPanelService)
            connectedMqttSettings = mqttConnectionSettings()
            lifecycle.addObserver(mqttModule!!)
        }
    }

    /**
     * The settings the running client connected with, as one value to compare against.
     * These are the ones a reconnect is the only way to pick up.
     */
    private fun mqttConnectionSettings(): String {
        return listOf(
            configuration.mqttEnabled.toString(),
            mqttOptions.getBroker(),
            mqttOptions.getPort().toString(),
            mqttOptions.getClientId(),
            mqttOptions.getBaseTopic(),
            mqttOptions.getUsername(),
            mqttOptions.getPassword(),
            mqttOptions.getVersion(),
            mqttOptions.getTlsConnection().toString()
        ).joinToString("|")
    }

    override fun onMQTTConnect() {
        Timber.w("onMQTTConnect")
        // The client is up, so the retry armed by a disconnect has nothing left to do.
        // Leaving the flag set would stop the next disconnect from arming its own.
        reconnectHandler.removeCallbacks(restartMqttRunnable)
        mqttConnecting = false
        if (mqttAlertMessageShown) {
            clearAlertMessage() // clear any dialogs
            mqttAlertMessageShown = false
        }
        clearFaceDetected()
        clearMotionDetected()
        // Discovery first: Home Assistant subscribes to the state topic only after it has
        // built the entities, so state published ahead of the configs arrives nowhere. The
        // sensor payloads are not retained, which is why they wait for the configs to go
        // out rather than being published alongside them.
        publishDiscovery {
            publishApplicationState(force = true)
            if (configuration.sensorsEnabled) {
                sensorReader.refreshSensors()
            }
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

    /**
     * The reader itself is built here so the stream can be observed straight away, while
     * opening the camera is handed to [cameraExecutor]. Opening takes the best part of a
     * second on an older device and the reader serialises its callers, so leaving it to the
     * caller would park the main looper behind a camera command from the broker.
     */
    private fun configureCamera() {
        if (!configuration.cameraEnabled) {
            return
        }
        if (cameraReader == null) {
            cameraReader = CameraReader(applicationContext)
        }
        val reader = cameraReader
        cameraRunning = true
        submitCamera { reader?.startCamera(cameraDetectorCallback, configuration) }
    }

    private fun submitCamera(work: () -> Unit) {
        try {
            // An exception thrown on this thread takes the whole process down, and setting
            // up the detectors is not covered by the camera's own error handling.
            cameraExecutor.execute {
                try {
                    work()
                } catch (e: Exception) {
                    Timber.e(e, "Camera work failed")
                    cameraRunning = false
                    sendToastMessage(getString(R.string.toast_camera_source_error))
                }
            }
        } catch (e: RejectedExecutionException) {
            Timber.d("Not touching the camera, the service is shutting down")
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
    // Does nothing while the server is already running, so the routes are registered once.
    private fun startHttp() {
        if (httpServer != null || !configuration.httpEnabled) {
            return
        }
        // TODO this is a hack to get utf-8 working, we need to switch http server libraries
        val charsetsClass = Charsets::class.java
        val us_ascii = charsetsClass.getDeclaredField("US_ASCII")
        us_ascii.isAccessible = true
        us_ascii.set(Charsets::class.java, Charsets.UTF_8)
        val server = AsyncHttpServer()
        httpServer = server
        httpServerPort = configuration.httpPort

        server.addAction("*", "*") { request, response ->
            Timber.i("Unhandled Request Arrived")
            response.code(404)
            response.send("")
        }
        server.listen(AsyncServer.getDefault(), configuration.httpPort)
        Timber.i("Started HTTP server on " + configuration.httpPort)

        // Registered whenever the HTTP server itself is running, but gated on
        // configuration.httpRestEnabled inside each handler rather than at registration
        // time: the server is only (re)started from onCreate(), so a route that checked
        // the flag just once here would keep answering with its startup value for the
        // life of the process, ignoring the setting being switched off afterward -- the
        // same live-check approach the shell command already uses for its own toggle.
        server.addAction("POST", "/api/command") { request, response ->
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

        server.addAction("GET", "/api/state") { request, response ->
            if (!configuration.httpRestEnabled) {
                response.code(403)
                response.send("REST API is disabled")
                return@addAction
            }
            Timber.i("GET Arrived (/api/state)")
            response.send(state)
        }
        Timber.i("Registered REST endpoints")

        if (configuration.httpMJPEGEnabled) {
            startMJPEG()
        }
    }

    /**
     * Registers the stream endpoint, once per server. Streaming can be switched on after
     * the server is up -- the launcher shortcut opens settings without stopping the
     * service -- so this is reached from the camera commands as well as from startup. The
     * route stays registered after that and turns requests away while the camera is off.
     */
    private fun registerMJPEGEndpoint() {
        val server = httpServer
        if (server == null || mjpegEndpointRegistered) {
            return
        }
        server.addAction("GET", "/camera/stream") { _, response ->
            Timber.i("GET Arrived (/camera/stream)")
            startMJPEG(response)
        }
        mjpegEndpointRegistered = true
        Timber.i("Enabled MJPEG Endpoint")
    }

    private fun stopHttp() {
        httpServer?.let {
            stopMJPEG()
            it.stop()
            httpServer = null
            mjpegEndpointRegistered = false
        }
    }

    // LiveData only takes observers on the main thread, and camera commands arrive on the
    // MQTT and HTTP threads. Observing again with the same observer is a no-op, so this is
    // safe to repeat.
    private fun startMJPEG() {
        registerMJPEGEndpoint()
        mjpegHandler.post {
            cameraReader?.getJpeg()?.observe(this, jpegObserver)
        }
    }

    private val jpegObserver = Observer<ByteArray> { jpeg ->
        for (s in mJpegSockets) {
            if (s.isOpen) {
                val bb = ByteBufferList()
                bb.add(ByteBuffer.wrap("--jpgboundary\r\nContent-Type: image/jpeg\r\n".toByteArray()))
                bb.add(ByteBuffer.wrap(("Content-Length: " + jpeg.size + "\r\n\r\n").toByteArray()))
                bb.add(ByteBuffer.wrap(jpeg))
                bb.add(ByteBuffer.wrap("\r\n".toByteArray()))
                s.write(bb)
            } else {
                mJpegSockets.remove(s)
                Timber.i("MJPEG Session Count is " + mJpegSockets.size)
            }
        }
    }

    // Enable the camera in settings and start it, along with motion, face, QR and streaming
    private fun restartCamera() {
        configuration.cameraEnabled = true
        configureCamera()
        startHttp()
        if (configuration.httpMJPEGEnabled) {
            startMJPEG()
        }
        publishDiscovery()
        publishApplicationState()
    }

    /**
     * Disables the camera in settings and stops it. The HTTP server stays up: it also
     * carries the REST API, and the stream endpoint turns requests away while the camera
     * is off.
     */
    private fun stopCameraCompletely() {
        configuration.cameraEnabled = false
        stopMJPEG()
        val reader = cameraReader
        cameraRunning = false
        submitCamera { reader?.stopCamera() }
        publishDiscovery()
        publishApplicationState()
    }

    // Drops the open streams, so they stop receiving frames. A client that is still
    // connected keeps its connection until it gives up on its own. The endpoint stays
    // registered and answers with a 503 while the camera is off.
    private fun stopMJPEG() {
        for (socket in mJpegSockets) {
            mJpegSockets.remove(socket)
            try {
                socket.end()
            } catch (e: Exception) {
                Timber.w(e, "Could not close an MJPEG stream")
            }
        }
    }

    private fun startMJPEG(response: AsyncHttpServerResponse) {
        // Both are read per request rather than at registration: the route stays registered
        // for the life of the server, while either setting can be switched off from the
        // settings screen without the service stopping.
        if (!configuration.httpMJPEGEnabled) {
            response.code(503)
            response.send("MJPEG streaming is disabled")
            return
        }
        if (!configuration.cameraEnabled) {
            response.code(503)
            response.send("Camera is disabled")
            return
        }
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
                if (enableCamera) {
                    restartCamera()
                } else {
                    stopCameraCompletely()
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
            if (commandJson.has(COMMAND_TOAST)) {
                sendToastMessage(commandJson.getString(COMMAND_TOAST))
            }
            if (commandJson.has(COMMAND_SCREENSAVER)) {
                setScreenSaver(commandJson.getBoolean(COMMAND_SCREENSAVER))
            }
            if (commandJson.has(COMMAND_SETTINGS)) {
                openSettings()
            }
            if (commandJson.has(COMMAND_VOLUME)) {
                setVolume(commandJson.getInt(COMMAND_VOLUME))
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
            publishShellResult(command, exitCode, output)
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute shell command: $command")
            publishShellResult(command, SHELL_EXIT_CODE_FAILED_TO_START, e.message.orEmpty())
        }
    }

    /**
     * Publishes what a shell command actually did, which is the only feedback a caller
     * gets -- the HTTP response only confirms the request parsed as JSON.
     *
     * The reported value is truncated because Home Assistant rejects a state longer than
     * 255 characters outright; the untruncated output stays available as an attribute.
     */
    private fun publishShellResult(command: String, exitCode: Int, output: String) {
        val data = JSONObject()
        try {
            data.put(VALUE, output.take(SHELL_RESULT_MAX_LENGTH))
            data.put("command", command)
            data.put("exitCode", exitCode)
            data.put("output", output.take(SHELL_OUTPUT_MAX_LENGTH))
        } catch (ex: JSONException) {
            ex.printStackTrace()
        }
        publishCommand(COMMAND_SENSOR_SHELL, data)
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

    /**
     * Sets the device's media volume, which is the stream the audio command and the text
     * to speech both play through. This used to attenuate the MediaPlayer instance
     * instead, which the player resets after every clip, so the level never survived
     * more than one playback and never applied to speech at all.
     */
    private fun setVolume(volumePercent: Int) {
        if (volumeUtils.setVolumePercent(volumePercent)) {
            publishApplicationState()
        }
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
        // Acquire screen wake lock with timeout to turn screen on temporarily. A repeat
        // command restarts the timeout, so the lock and the runnable that reports its
        // release always end together.
        releaseScreenWakeLock()
        screenWakeLock?.acquire(wakeTime)
        wakeScreenHandler.removeCallbacks(clearWakeScreenRunnable)
        wakeScreenHandler.postDelayed(clearWakeScreenRunnable, wakeTime)
        sendWakeScreenOn()
        publishApplicationState()
    }

    /**
     * The lock's own timeout releases it from PowerManager's thread, and the timer that
     * reports it released runs on the main looper, while wake commands arrive on the MQTT
     * and HTTP threads. Any of those can let go of the lock between the check here and the
     * release, which throws rather than being a no-op.
     */
    private fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: RuntimeException) {
            Timber.w(e, "The screen wake lock was already released")
        }
    }

    private val clearWakeScreenRunnable = Runnable {
        wakeScreenOff()
    }

    private fun wakeScreenOff() {
        wakeScreenHandler.removeCallbacks(clearWakeScreenRunnable)
        releaseScreenWakeLock()
        sendWakeScreenOff()
        // Releasing the lock lets the display time out; it does not blank it. The state
        // goes out either way so Home Assistant shows what the screen is actually doing
        // rather than what was asked for.
        publishApplicationState()
    }

    private fun changeScreenBrightness(brightness: Int) {
        if (!configuration.useScreenBrightness) {
            Timber.w("Ignoring brightness command: screen brightness control is switched off in the settings")
            return
        }
        if (!screenUtils.canWriteScreenSetting()) {
            Timber.w("Ignoring brightness command: the app is not allowed to modify system settings")
            return
        }
        // Both have to already hold the value for there to be nothing to do. A screensaver
        // dims the display without moving the setpoint, so asking for the setpoint again
        // has to reach the screen; a display that drifted off its setpoint still has to
        // record a command that matches what it happens to be showing.
        if (configuration.screenBrightness != brightness ||
            screenUtils.getCurrentScreenBrightness() != brightness) {
            screenUtils.updateScreenBrightness(brightness)
            sendScreenBrightnessChange()
            publishApplicationState()
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

    /**
     * Asks the browser to show or dismiss the screensaver. Showing it does nothing if no
     * screensaver is configured, so the reported state comes back from the browser rather
     * than being assumed here.
     */
    private fun setScreenSaver(show: Boolean) {
        val action = if (show) BROADCAST_ACTION_SHOW_SCREENSAVER else BROADCAST_ACTION_HIDE_SCREENSAVER
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(Intent(action))
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

    /**
     * Publishes the application state, rate limited to one message per [delay].
     *
     * A change that lands inside the window is remembered rather than dropped, and goes
     * out as a fresh snapshot when the window closes. Home Assistant reads the switches
     * and sliders off this topic, so a dropped update leaves a control showing a value
     * the device no longer has.
     *
     * The message is retained. Home Assistant only subscribes once it has processed the
     * discovery config, so an unretained state published before that point is never seen
     * and every control comes up as unknown.
     */
    private fun publishApplicationState(delay: Int = 300, force: Boolean = false) {
        // Commands arrive on the MQTT and HTTP threads and the sensors publish from their
        // own, so the window's two flags are kept on the main looper along with the timer
        // that closes it. Sharing one thread is what stops an update slipping through
        // between a flag being read and the same flag being set.
        appStateClearHandler.post {
            val payload = state.toString()
            // Every sensor reading asks for a state publish while carrying its own payload,
            // and the state itself moves only when something is commanded, so most of those
            // asks hold the snapshot that is already on the topic. [force] covers a fresh
            // connection, where the retained copy on the broker cannot be taken for granted.
            if (!force && payload == lastPublishedState) {
                return@post
            }
            if (appStatePublished) {
                appStatePublishPending = true
                // A forced publish that lands inside the window has to stay forced, or the
                // replay could decide the snapshot is unchanged and drop it.
                appStatePublishPendingForce = appStatePublishPendingForce || force
                return@post
            }
            appStatePublished = true
            lastPublishedState = payload
            publishedStateTopic = "${configuration.mqttBaseTopic}$COMMAND_STATE"
            publishMessage(publishedStateTopic!!, payload, true)
            appStateClearHandler.postDelayed({ clearPublishApplicationState(delay) }, delay.toLong())
        }
    }

    private fun clearPublishApplicationState(delay: Int = 300) {
        appStatePublished = false
        if (appStatePublishPending) {
            appStatePublishPending = false
            val force = appStatePublishPendingForce
            appStatePublishPendingForce = false
            publishApplicationState(delay, force)
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

    private fun discoveryPayloads(): Map<String, String> {
        return mqttDiscovery.payloads(sensorReader.getSensors())
    }

    /**
     * Publishes the Home Assistant discovery config for every entity the application
     * exposes. Entities whose feature is switched off are published as an empty retained
     * payload, which is how a retained message is cleared -- publishing the empty payload
     * without the retain flag leaves the old config sitting on the broker and the entity
     * alive in Home Assistant.
     */
    /**
     * [then] runs once the configs are on the broker, and not at all when they could not be
     * sent. What follows a publish is state and sensor readings, and Home Assistant drops
     * those until it has the configs.
     */
    private fun publishDiscovery(then: (() -> Unit)? = null) {
        submitDiscovery {
            if (publishDiscoveryNow()) {
                then?.invoke()
            }
        }
    }

    /**
     * Commands arrive on the MQTT and HTTP threads, which carry on running while the
     * service is being torn down, so work handed over after the executor is shut down is
     * dropped rather than thrown back at the caller.
     */
    private fun submitDiscovery(work: () -> Unit) {
        try {
            discoveryExecutor.execute(work)
        } catch (e: RejectedExecutionException) {
            Timber.d("Not publishing discovery, the service is shutting down")
        }
    }

    /**
     * Takes the device out of Home Assistant before the client goes. Switching MQTT off
     * otherwise leaves every retained config on the broker, and Home Assistant keeps the
     * entities, unavailable, for good. Queued behind any publish already under way, so the
     * removal cannot be overtaken by a config going out, and the client is stopped only
     * after it has sent the removals.
     */
    private fun retractDiscoveryAndStop(module: MQTTModule) {
        val stop = Runnable {
            lifecycle.removeObserver(module)
            module.pause()
            if (discoveryCleanup === module) {
                discoveryCleanup = null
            }
        }
        submitDiscovery {
            if (module.isConnected) {
                val advertised = configuration.mqttDiscoveryAdvertisedTopics
                for (topic in advertised) {
                    module.publish(topic, "", true)
                }
                // Recorded as gone only if the connection held for all of them, so topics
                // left behind are still cleared when MQTT comes back.
                if (module.isConnected && advertised.isNotEmpty()) {
                    configuration.mqttDiscoveryAdvertisedTopics = emptySet()
                }
            } else {
                Timber.w("MQTT was switched off while disconnected, discovery configs stay on the broker")
            }
            publishedDiscovery = null
            Handler(Looper.getMainLooper()).post(stop)
        }
        // Shutting down, the queue no longer runs; stop the client straight away.
        if (discoveryExecutor.isShutdown) {
            stop.run()
        }
    }

    /**
     * Opening the settings screen stops this service, so MQTT switched off there arrives as
     * a start with no client, and nothing is left to take the entities down with. What was
     * advertised is still recorded, so a client is opened for just long enough to clear it.
     * Should the broker not answer, the record stays and the next start tries again;
     * switching MQTT back on drops the attempt and the new client takes over.
     */
    private fun retractDiscoveryWhileDisabled() {
        if (configuration.mqttEnabled) {
            stopDiscoveryCleanup()
            return
        }
        if (mqttModule != null || discoveryCleanup != null ||
            configuration.mqttDiscoveryAdvertisedTopics.isEmpty()) {
            return
        }
        val options = MQTTOptions(configuration).apply { connectWhileDisabled = true }
        if (!options.isValid) {
            return
        }
        Timber.i("MQTT is switched off with entities still advertised, connecting to remove them")
        lateinit var module: MQTTModule
        module = MQTTModule(applicationContext, options, object : MQTTModule.MQTTListener {
            override fun onMQTTConnect() {
                retractDiscoveryAndStop(module)
            }

            override fun onMQTTDisconnect() {}

            override fun onMQTTException(message: String) {
                Timber.w("Could not reach the broker to remove the entities: $message")
            }

            // MQTT is switched off, so commands are not taken.
            override fun onMQTTMessage(id: String, topic: String, payload: String) {}
        })
        discoveryCleanup = module
        lifecycle.addObserver(module)
    }

    private fun stopDiscoveryCleanup() {
        discoveryCleanup?.let {
            lifecycle.removeObserver(it)
            it.pause()
        }
        discoveryCleanup = null
    }

    private fun publishDiscoveryNow(prebuilt: Map<String, String>? = null): Boolean {
        // The client drops publishes while it is still connecting. Recording them as sent
        // would leave the retained configs on the broker with nothing left to clear them;
        // the connect callback publishes again once the connection is up.
        if (mqttModule?.isConnected != true) {
            Timber.d("Skipping discovery, the MQTT client is not connected")
            return false
        }
        // Built here unless the caller already has a set to hand, so nothing is built for a
        // connection that turns out to be down.
        val payloads = prebuilt ?: discoveryPayloads()
        val messages = mqttDiscovery.messages(
            payloads,
            configuration.mqttDiscoveryAdvertisedTopics,
            sweepUnrecorded = configuration.mqttDiscoveryUnrecorded
        )
        for ((topic, payload) in messages) {
            publishMessage(topic, payload, true)
        }
        // A connection that goes down part way through leaves the rest of these unsent, and
        // the client drops them without saying so. Recording the result would take the
        // configs that are still on the broker off the list that clears them later, and
        // switching discovery off is exactly when nothing else would clear them.
        if (mqttModule?.isConnected != true) {
            Timber.w("The connection went down while publishing discovery, leaving it unrecorded")
            return false
        }
        // Written only when it moves: storing a string set rewrites the whole preference
        // file and wakes every change listener, and a reconnect advertises the same topics
        // as the connect before it.
        val advertised = mqttDiscovery.advertisedTopics(payloads)
        if (configuration.mqttDiscoveryUnrecorded || advertised != configuration.mqttDiscoveryAdvertisedTopics) {
            configuration.mqttDiscoveryAdvertisedTopics = advertised
        }
        publishedDiscovery = payloads
        return true
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
                Timber.i("Url changed to $appLaunchUrl")
                // Published for a move back to the configured launch url as well, which the
                // Current URL sensor otherwise keeps reporting the page navigated away from.
                // An unchanged snapshot is dropped in publishApplicationState().
                publishApplicationState()
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
            } else if (BROADCAST_SCREENSAVER_STARTED == intent.action) {
                Timber.i("Screensaver started")
                isScreenSaverActive = true
                publishApplicationState()
            } else if (BROADCAST_SCREENSAVER_STOPPED == intent.action) {
                Timber.i("Screensaver stopped")
                isScreenSaverActive = false
                publishApplicationState()
            }
        }
    }

    private val sensorCallback = object : SensorCallback {
        override fun publishSensorData(sensorName: String, sensorData: JSONObject) {
            // The state topic carries no sensor data. These refresh the values a person can
            // change on the device itself, brightness and volume, and a reading cycle brings
            // a dozen of them within a few seconds, so one refresh covers the cycle. Taking
            // each one costs a handful of binder calls on the main looper for a snapshot
            // that is almost always the one already published.
            val now = SystemClock.elapsedRealtime()
            if (now - lastStateRefresh >= STATE_REFRESH_INTERVAL) {
                lastStateRefresh = now
                publishApplicationState()
            }
            val retain = sensorName in SensorReader.DEVICE_INFO_SENSORS
            publishMessage("${configuration.mqttBaseTopic}$COMMAND_SENSOR$sensorName", sensorData.toString(), retain)
        }
    }

    private val cameraDetectorCallback = object : CameraCallback {
        override fun onDetectorError() {
            if (configuration.cameraFaceEnabled || configuration.cameraQRCodeEnabled) {
                sendToastMessage(getString(R.string.error_missing_vision_lib))
            }
        }

        override fun onCameraError() {
            // Cleared so a later service start tries again: the camera may have failed for
            // a permission that has since been granted from the settings screen.
            cameraRunning = false
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
                // Motion must acquire the screen wake lock as well as notify the UI.
                // BROADCAST_SCREEN_WAKE alone only resets the screensaver/inactivity timer
                // and cannot wake a physically sleeping display on modern Android.
                wakeScreenOn(SCREEN_WAKE_TIME)
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
                wakeScreenOn(SCREEN_WAKE_TIME) // temporarily turn on the physical display
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
        const val BROADCAST_SCREENSAVER_STARTED = "BROADCAST_SCREENSAVER_STARTED"
        const val BROADCAST_SCREENSAVER_STOPPED = "BROADCAST_SCREENSAVER_STOPPED"
        const val BROADCAST_CONNTED = "BROADCAST_SCREEN_BRIGHTNESS_CHANGE"
        const val ACTION_RUN_COMMAND = "xyz.wallpanel.pro.action.RUN_COMMAND"
        const val EXTRA_COMMAND_JSON = "EXTRA_COMMAND_JSON"

        /**
         * The process exit is delayed rather than immediate: when a restart is reached from
         * onStartCommand(), a scheduled task, exiting before that call returns kills the
         * process mid Binder-transaction, which the system reads as an incomplete start and
         * redelivers the same command to the relaunched process -- observed on-device as
         * three restarts in a row instead of one. The delay lets onStartCommand() return.
         */
        const val RESTART_EXIT_DELAY_MS = 300L

        // A shell command that never got as far as running has no exit code of its own.
        private const val SHELL_EXIT_CODE_FAILED_TO_START = -1

        // Home Assistant rejects a sensor state longer than this.
        private const val SHELL_RESULT_MAX_LENGTH = 255

        // How often a sensor reading may ask for a fresh application state snapshot.
        private const val STATE_REFRESH_INTERVAL = 5000L

        // The full output rides along as an attribute. Home Assistant keeps attributes in
        // every state it records, so an unbounded one is paid for on every update.
        private const val SHELL_OUTPUT_MAX_LENGTH = 16384
    }
}
