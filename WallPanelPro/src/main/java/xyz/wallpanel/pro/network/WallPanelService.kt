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

    @Inject
    lateinit var mqttDiscovery: MqttDiscovery

    @Inject
    lateinit var volumeUtils: VolumeUtils

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
    // The discovery payloads by topic as last sent to the broker.
    private var publishedDiscovery: Map<String, String>? = null
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
        if (discoveryPayloads() != publishedDiscovery) {
            publishDiscovery()
            publishApplicationState()
        }
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
        cameraReader?.stopCamera()
        sensorReader.stopReadings()
        stopHttp()
        stopPowerOptions()
        reconnectHandler.removeCallbacksAndMessages(null)
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
        // Discovery first: Home Assistant subscribes to the state topic only after it has
        // built the entities, so state published ahead of the configs arrives nowhere.
        publishDiscovery()
        publishApplicationState()
        if (configuration.sensorsEnabled) {
            sensorReader.refreshSensors()
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
            server.addAction("GET", "/camera/stream") { _, response ->
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

    // Observing again with the same observer is a no-op, so this is safe to repeat.
    private fun startMJPEG() {
        cameraReader?.getJpeg()?.observe(this, jpegObserver)
    }

    private val jpegObserver = Observer<ByteArray> { jpeg ->
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
        cameraReader?.stopCamera()
        publishDiscovery()
        publishApplicationState()
    }

    // Ends the open streams. The endpoint itself stays registered.
    private fun stopMJPEG() {
        for (socket in mJpegSockets) {
            try {
                socket.end()
            } catch (e: Exception) {
                Timber.w(e, "Could not close an MJPEG stream")
            }
        }
        mJpegSockets.clear()
    }

    private fun startMJPEG(response: AsyncHttpServerResponse) {
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
        screenWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            it.acquire(wakeTime)
        }
        wakeScreenHandler.removeCallbacks(clearWakeScreenRunnable)
        wakeScreenHandler.postDelayed(clearWakeScreenRunnable, wakeTime)
        sendWakeScreenOn()
        publishApplicationState()
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
        if (configuration.screenBrightness != brightness) {
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
    private fun publishApplicationState(delay: Int = 300) {
        if (appStatePublished) {
            appStatePublishPending = true
            return
        }
        appStatePublished = true
        publishMessage("${configuration.mqttBaseTopic}$COMMAND_STATE", state.toString(), true)
        appStateClearHandler.postDelayed({ clearPublishApplicationState(delay) }, delay.toLong())
    }

    private fun clearPublishApplicationState(delay: Int = 300) {
        appStatePublished = false
        if (appStatePublishPending) {
            appStatePublishPending = false
            publishApplicationState(delay)
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
    private fun publishDiscovery() {
        // The client drops publishes while it is still connecting. Recording them as sent
        // would leave the retained configs on the broker with nothing left to clear them;
        // the connect callback publishes again once the connection is up.
        if (mqttModule?.isConnected != true) {
            Timber.d("Skipping discovery, the MQTT client is not connected")
            return
        }
        val payloads = discoveryPayloads()
        val messages = mqttDiscovery.messages(payloads, configuration.mqttDiscoveryAdvertisedTopics)
        for ((topic, payload) in messages) {
            publishMessage(topic, payload, true)
        }
        configuration.mqttDiscoveryAdvertisedTopics = mqttDiscovery.advertisedTopics(payloads)
        publishedDiscovery = payloads
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
            publishApplicationState()
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

        // The full output rides along as an attribute. Home Assistant keeps attributes in
        // every state it records, so an unbounded one is paid for on every update.
        private const val SHELL_OUTPUT_MAX_LENGTH = 16384
    }
}
