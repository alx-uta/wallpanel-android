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

package xyz.wallpanel.pro.ui.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.android.support.DaggerAppCompatActivity
import timber.log.Timber
import xyz.wallpanel.pro.AppExceptionHandler
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.network.MQTTOptions
import xyz.wallpanel.pro.network.WallPanelService
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_ALERT_MESSAGE
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_BROWSER_ENGINE_PAUSE
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_BROWSER_ENGINE_RESUME
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_CLEAR_ALERT_MESSAGE
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_EVENT_SCREEN_TOUCH
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_SCREEN_BRIGHTNESS_CHANGE
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE_OFF
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_SCREEN_WAKE_ON
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_SERVICE_STARTED
import xyz.wallpanel.pro.network.WallPanelService.Companion.BROADCAST_TOAST_MESSAGE
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.persistence.RemoteConfig
import xyz.wallpanel.pro.utils.AppExit
import xyz.wallpanel.pro.utils.DashboardLogin
import xyz.wallpanel.pro.utils.DialogUtils
import xyz.wallpanel.pro.utils.Lockdown
import xyz.wallpanel.pro.utils.MqttUtils
import xyz.wallpanel.pro.utils.ScreenUtils
import xyz.wallpanel.pro.utils.VolumeKeys
import javax.inject.Inject


abstract class BaseBrowserActivity : DaggerAppCompatActivity() {

    @Inject
    lateinit var dialogUtils: DialogUtils

    @Inject
    lateinit var configuration: Configuration

    @Inject
    lateinit var mqttOptions: MQTTOptions

    @Inject
    lateinit var screenUtils: ScreenUtils

    var mOnScrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    var wallPanelService: Intent? = null
    private var decorView: View? = null
    private val inactivityHandler: Handler = Handler(Looper.getMainLooper())
    private var userPresent: Boolean = false
    private var hasWakeScreen = false
    private var screenSaverActive = false
    // Shades the page for the dim screensaver when the brightness on screen is not known
    private var dimShade: View? = null
    private var inForeground = false
    // Set while lockdown waits for the window to have focus, see applyLockdown()
    private var lockdownPending = false
    // Set while a config command puts the screensaver up again, which is not a dismissal
    private var reshowingScreenSaver = false
    // Messages from the notification command, newest at the bottom
    private var notificationViews: LinearLayout? = null
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val volumeKeys by lazy {
        VolumeKeys(ViewConfiguration.getLongPressTimeout().toLong()) { key, action ->
            val intent = Intent(WallPanelService.BROADCAST_EVENT_BUTTON)
            intent.putExtra(WallPanelService.BUTTON_KEY, key)
            intent.putExtra(WallPanelService.BUTTON_ACTION, action)
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        }
    }
    var displayProgress = true
    var zoomLevel = 1.0f

    /**
     * Commands from the service. The receiver lives as long as the activity, so commands that
     * change the page (url, reload, eval, cache) and the wake state apply even while the
     * screen is off or another screen is in front. Commands that put something on screen, the
     * screensaver, the settings screen, an alert, are only acted on while the page is what
     * the user sees, since they would otherwise appear over that other screen.
     */
    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val inFront = isInForeground() && !isFinishing
            if (BROADCAST_ACTION_LOAD_URL == intent.action) {
                val url = intent.getStringExtra(BROADCAST_ACTION_LOAD_URL)
                url?.let {
                    onUrlCommand()
                    loadWebViewUrl(url)
                    stopDisconnectTimer()
                }
            } else if (AppExit.BROADCAST_EXIT == intent.action) {
                // A locked task stays on screen with its activities gone
                Lockdown.release(this@BaseBrowserActivity)
                finishAffinity()
            } else if (BROADCAST_ACTION_JS_EXEC == intent.action) {
                val js = intent.getStringExtra(BROADCAST_ACTION_JS_EXEC)
                js?.let {
                    stopDisconnectTimer()
                    evaluateJavascript(js)
                }
            } else if (BROADCAST_ACTION_CLEAR_BROWSER_CACHE == intent.action) {
                clearCache()
            } else if (BROADCAST_ACTION_RELOAD_PAGE == intent.action) {
                stopDisconnectTimer()
                reload()
            } else if (BROADCAST_ACTION_SHOW_SCREENSAVER == intent.action && inFront) {
                showScreenSaver()
            } else if (BROADCAST_ACTION_HIDE_SCREENSAVER == intent.action && !isFinishing) {
                // Dismisses the screensaver and starts the inactivity countdown again, so
                // it comes back on its own the same way it would after a screen touch.
                resetInactivityTimer()
            } else if (BROADCAST_ACTION_OPEN_SETTINGS == intent.action && inFront) {
                openSettings()
            } else if (BROADCAST_TOAST_MESSAGE == intent.action && !isFinishing) {
                val message = intent.getStringExtra(BROADCAST_TOAST_MESSAGE)
                stopDisconnectTimer()
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } else if (BROADCAST_ALERT_MESSAGE == intent.action && inFront) {
                val message = intent.getStringExtra(BROADCAST_ALERT_MESSAGE)
                stopDisconnectTimer()
                message?.let {
                    dialogUtils.showAlertDialog(this@BaseBrowserActivity, message)
                }
            } else if (BROADCAST_CLEAR_ALERT_MESSAGE == intent.action && !isFinishing) {
                dialogUtils.clearDialogs()
                if (hasWakeScreen.not()) {
                    resetInactivityTimer()
                    resetScreenBrightness(false)
                }
            } else if (BROADCAST_SCREEN_WAKE == intent.action && !isFinishing) {
                stopDisconnectTimer()
            } else if (BROADCAST_SCREEN_WAKE_ON == intent.action && !isFinishing) {
                hasWakeScreen = true
                resetScreenBrightness(false)
                clearInactivityTimer()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else if (BROADCAST_SCREEN_WAKE_OFF == intent.action && !isFinishing) {
                hasWakeScreen = false
                resetInactivityTimer()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else if (BROADCAST_BROWSER_ENGINE_PAUSE == intent.action) {
                Timber.d("Screen off, pausing the browser engine")
                pauseBrowserEngine()
            } else if (BROADCAST_BROWSER_ENGINE_RESUME == intent.action && inFront) {
                Timber.d("Screen on, resuming the browser engine")
                resumeBrowserEngine()
            } else if (BROADCAST_ACTION_RELOAD_PAGE == intent.action && !isFinishing) {
                hideScreenSaver()
            } else if (BROADCAST_SERVICE_STARTED == intent.action && !isFinishing) {
                //firstLoadUrl() // load the url after service started
            } else if (BROADCAST_ACTION_CONFIG_CHANGED == intent.action && !isFinishing) {
                val keys = intent.getStringArrayExtra(BROADCAST_ACTION_CONFIG_CHANGED)
                keys?.let { onConfigChanged(it.toSet()) }
            } else if (BROADCAST_ACTION_ASSIST == intent.action && !isFinishing) {
                onAssistCommand(intent.getBooleanExtra(BROADCAST_ACTION_ASSIST, true))
            } else if (BROADCAST_ACTION_NOTIFICATION == intent.action && !isFinishing) {
                val message = intent.getStringExtra(WallPanelService.NOTIFICATION_MESSAGE)
                message?.let {
                    stopDisconnectTimer()
                    showNotification(
                        intent.getStringExtra(WallPanelService.NOTIFICATION_TITLE),
                        it,
                        intent.getLongExtra(WallPanelService.NOTIFICATION_TIMEOUT, 0L)
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything here touches the screen, the brightness or the commands, which
        // belong to the browser that is already up
        if (handOverToRunningBrowser()) {
            return
        }

        displayProgress = configuration.appShowActivity
        zoomLevel = configuration.testZoomLevel

        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        decorView = window.decorView

        lifecycle.addObserver(dialogUtils)

        registerCommandReceiver()

        onUserInteraction()

        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this))
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        // The screen may have been off while the activity was paused, so make sure the
        // engine is running again before anything else touches the page.
        resumeBrowserEngine()
        resetInactivityTimer()
        applyLockdown()
    }

    /**
     * Locks the task down, or lets it go, to match the setting. Only the activity in front
     * can lock its task, so a change made while it is not waits for the next onResume().
     * Locking waits for the window to have focus as well: asked while the lock screen or the
     * notification shade still has it, Android drops the screen pinning prompt without a word.
     */
    private fun applyLockdown() {
        if (AppExit.exiting || isFinishing) {
            return
        }
        if (configuration.appLockdown && !hasWindowFocus()) {
            lockdownPending = true
            return
        }
        lockdownPending = false
        val error = Lockdown.apply(this, configuration.appLockdown, configuration.appLockdownApps)
        if (error != null && configuration.appLockdown) {
            Toast.makeText(this, getString(R.string.toast_lockdown_failed, error), Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        lockdownPending = false
        // The screensaver belongs to the page: counting down while the settings screen or
        // another app is in front would bring it up, and dim the screen, over them.
        inactivityHandler.removeCallbacks(inactivityCallback)
        // Pausing here also covers being backgrounded by another activity.
        pauseBrowserEngine()
    }

    /**
     * Commands are received for as long as the activity exists, not only while it is in
     * front: a url, reload or eval command sent while the screen is off, or while another app
     * or the settings screen is showing, is applied to the page instead of being dropped.
     */
    private fun registerCommandReceiver() {
        val filter = IntentFilter()
        filter.addAction(BROADCAST_ACTION_LOAD_URL)
        filter.addAction(BROADCAST_ACTION_JS_EXEC)
        filter.addAction(BROADCAST_ACTION_CLEAR_BROWSER_CACHE)
        filter.addAction(BROADCAST_ACTION_RELOAD_PAGE)
        filter.addAction(BROADCAST_ACTION_OPEN_SETTINGS)
        filter.addAction(BROADCAST_ACTION_SHOW_SCREENSAVER)
        filter.addAction(BROADCAST_ACTION_HIDE_SCREENSAVER)
        filter.addAction(BROADCAST_SCREEN_BRIGHTNESS_CHANGE)
        filter.addAction(BROADCAST_CLEAR_ALERT_MESSAGE)
        filter.addAction(BROADCAST_ALERT_MESSAGE)
        filter.addAction(BROADCAST_TOAST_MESSAGE)
        filter.addAction(BROADCAST_SCREEN_WAKE)
        filter.addAction(BROADCAST_SCREEN_WAKE_ON)
        filter.addAction(BROADCAST_SCREEN_WAKE_OFF)
        filter.addAction(BROADCAST_BROWSER_ENGINE_PAUSE)
        filter.addAction(BROADCAST_BROWSER_ENGINE_RESUME)
        filter.addAction(BROADCAST_SERVICE_STARTED)
        filter.addAction(AppExit.BROADCAST_EXIT)
        filter.addAction(BROADCAST_ACTION_CONFIG_CHANGED)
        filter.addAction(BROADCAST_ACTION_NOTIFICATION)
        filter.addAction(BROADCAST_ACTION_ASSIST)
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, filter)
    }

    /** Between onResume() and onPause(), i.e. the page is what the user sees. */
    protected fun isInForeground(): Boolean = inForeground

    override fun onStart() {
        super.onStart()
        if (configuration.hardwareAccelerated && Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        }
        applyKeepScreenOn()
        wallPanelService = Intent(this, WallPanelService::class.java)
        // Started again while the application exits, the service would come back after the
        // process has gone
        if (!AppExit.exiting) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(wallPanelService)
            } else {
                startService(wallPanelService)
            }
        }
        resetScreenBrightness(false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(WallPanelService.BROADCAST_BROWSER_SHOWN))
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(WallPanelService.BROADCAST_BROWSER_HIDDEN))
    }

    /**
     * The volume keys go to Home Assistant instead of the volume when that is switched on.
     * The screensaver has a window of its own and passes them on here, see [showScreenSaver].
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleVolumeKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleVolumeKey(event: KeyEvent): Boolean {
        return configuration.sensorsVolumeKeys && volumeKeys.handle(event)
    }

    /** Keeps the screen on for the Prevent Sleep setting, without undoing a wake command. */
    private fun applyKeepScreenOn() {
        if (configuration.appPreventSleep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView?.keepScreenOn = true
        } else {
            if (!hasWakeScreen) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            decorView?.keepScreenOn = false
        }
    }

    /**
     * Brings what is on screen in line with settings the config command changed. Nothing
     * else needs telling: the screensaver reads its settings each time it comes up.
     */
    private fun onConfigChanged(keys: Set<String>) {
        if (MqttUtils.STATE_PREVENT_SLEEP in keys) {
            applyKeepScreenOn()
        }
        if (MqttUtils.STATE_SCREENSAVER_DIM in keys) {
            screenUtils.updateScreenSaverBrightness()
            if (screenSaverActive) {
                resetScreenBrightness(true)
            }
        }
        if (MqttUtils.STATE_DARK_THEME in keys) {
            applyDarkTheme()
        }
        if (MqttUtils.STATE_LOCKDOWN in keys && isInForeground()) {
            applyLockdown()
        }
        if (screenSaverActive && keys.any { it in RemoteConfig.SCREENSAVER_KEYS }) {
            reshowingScreenSaver = true
            try {
                hideScreenSaver()
            } finally {
                reshowingScreenSaver = false
            }
            if (isInForeground()) {
                showScreenSaver()
            }
            if (!screenSaverActive) {
                restartInactivityCountdown()
            }
        } else if (MqttUtils.STATE_INACTIVITY_TIME in keys && !screenSaverActive) {
            restartInactivityCountdown()
        }
    }

    /** Starts the countdown to the screensaver again, at the inactivity time as it is now. */
    private fun restartInactivityCountdown() {
        inactivityHandler.removeCallbacks(inactivityCallback)
        if (isInForeground() && !hasWakeScreen) {
            inactivityHandler.postDelayed(inactivityCallback, configuration.inactivityTime)
        }
    }

    /**
     * Returns true when another browser is already up and this one has handed over to it
     * and is finishing.
     */
    protected open fun handOverToRunningBrowser(): Boolean = false

    /** The dark theme setting changed while the page is up. */
    protected open fun applyDarkTheme() {}

    /** The assist command: start listening, or stop. */
    protected open fun onAssistCommand(start: Boolean) {}

    /** Says [text] the way the speak command would. */
    protected fun speakThroughService(text: String) {
        val intent = Intent(WallPanelService.BROADCAST_ACTION_SPEAK)
        intent.putExtra(WallPanelService.BROADCAST_ACTION_SPEAK, text)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    /**
     * Puts a message over the page until it is touched, or for [timeoutSeconds] when that
     * is above 0. Messages stack, the oldest going once there are [MAX_NOTIFICATIONS].
     */
    private fun showNotification(title: String?, message: String, timeoutSeconds: Long) {
        val container = notificationViews ?: LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
            val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            addContentView(it, params)
            notificationViews = it
        }
        while (container.childCount >= MAX_NOTIFICATIONS) {
            container.removeViewAt(0)
        }
        val text = SpannableStringBuilder()
        if (!title.isNullOrBlank()) {
            text.append(title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("\n")
        }
        text.append(message)
        val margin = dp(12)
        val view = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(NOTIFICATION_BACKGROUND)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(margin, margin, margin, 0)
            }
        }
        val remove = Runnable { container.removeView(view) }
        view.setOnClickListener {
            notificationHandler.removeCallbacks(remove)
            remove.run()
        }
        container.addView(view)
        if (timeoutSeconds > 0) {
            notificationHandler.postDelayed(remove, timeoutSeconds * 1000)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver)
        inactivityHandler.removeCallbacks(inactivityCallback)
        notificationHandler.removeCallbacksAndMessages(null)
        // The screensaver goes with the activity. Left unreported, the service keeps
        // publishing it as on and the Home Assistant switch never comes back down.
        setScreenSaverActive(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onUserInteraction() {
        onWindowFocusChanged(true)
        if (!userPresent) {
            userPresent = true
            resetScreenBrightness(false)
            val intent = Intent(BROADCAST_EVENT_SCREEN_TOUCH)
            intent.putExtra(BROADCAST_EVENT_SCREEN_TOUCH, true)
            val bm = LocalBroadcastManager.getInstance(applicationContext)
            bm.sendBroadcast(intent)
        }
        if (hasWakeScreen.not()) {
            resetInactivityTimer()
        }
    }

    fun setDarkTheme() {
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        if (nightMode == AppCompatDelegate.MODE_NIGHT_NO || nightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun setLightTheme() {
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES || nightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private val inactivityCallback = object : Runnable {
        override fun run() {
            if (!canShowScreenSaver()) {
                // Nothing to cover yet, so wait out another idle period rather than drop the
                // screensaver for the rest of this session
                inactivityHandler.postDelayed(this, configuration.inactivityTime)
                return
            }
            dialogUtils.clearDialogs()
            userPresent = false
            showScreenSaver()
        }
    }

    /**
     * Whether the screensaver may come up. It may not while the browser is still waiting to
     * open the dashboard: a clock screensaver over that reads whatever wrong time the device
     * booted with, which looks like a fault rather than a wait.
     */
    protected open fun canShowScreenSaver(): Boolean = true

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Also called from onUserInteraction(), so the window is asked rather than trusted
        if (hasFocus && lockdownPending && isInForeground() && hasWindowFocus()) {
            applyLockdown()
        }
        val visibility: Int
        if (hasFocus && configuration.fullScreen) {
            visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            decorView?.systemUiVisibility = visibility
        } else if (hasFocus) {
            visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_VISIBLE
            decorView?.systemUiVisibility = visibility
        }
    }

    internal fun resetScreen() {
        val intent = Intent(WallPanelService.BROADCAST_EVENT_SCREEN_TOUCH)
        intent.putExtra(WallPanelService.BROADCAST_EVENT_SCREEN_TOUCH, true)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    fun pageLoadComplete(url: String) {
        onPageLocation(url)
        val intent = Intent(WallPanelService.BROADCAST_EVENT_URL_CHANGE)
        // Published as the Current URL, where a password in it would be on show
        intent.putExtra(WallPanelService.BROADCAST_EVENT_URL_CHANGE, DashboardLogin.withoutCredentials(url))
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
        complete()
    }

    protected fun resetInactivityTimer() {
        hideScreenSaver()
        inactivityHandler.removeCallbacks(inactivityCallback)
        // A command arriving while the page is not in front must not start the countdown,
        // see onPause(). onResume() starts it.
        if (isInForeground()) {
            inactivityHandler.postDelayed(inactivityCallback, configuration.inactivityTime)
        }
    }

    private fun clearInactivityTimer() {
        hideScreenSaver()
        inactivityHandler.removeCallbacks(inactivityCallback)
    }

    fun stopDisconnectTimer() {
        if (userPresent.not()) {
            userPresent = true
            resetScreenBrightness(false)
        }
        if (hasWakeScreen.not()) {
            resetInactivityTimer()
        }
    }

    open fun hideScreenSaver() {
        val wasActive = screenSaverActive
        val dialogDismissed = dialogUtils.hideScreenSaverDialog()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The dim screensaver has no dialog to dismiss, so the tracked flag rather than
        // the dialog is what says whether there is a dimmed screen to restore.
        if (dialogDismissed || screenSaverActive) {
            resetScreenBrightness(false)
        }
        setScreenSaverActive(false)
        onScreenSaverCovering(false)
        if ((wasActive || dialogDismissed) && configuration.browserReloadOnWake && !reshowingScreenSaver) {
            reloadAfterScreenSaver()
        }
    }

    /**
     * The screensaver was dismissed with reloading after it switched on. Every way it goes
     * counts: a touch, a wake command, motion or a face, the screen coming back on.
     */
    protected open fun reloadAfterScreenSaver() {
        reload()
    }

    /** A url command is about to load its page. */
    protected open fun onUrlCommand() {}

    /** The address on view changed, by a load or by in-page navigation. */
    protected open fun onPageLocation(url: String) {}

    /**
     * Show the screen saver only if the alarm isn't triggered. This shouldn't be an issue
     * with the alarm disabled because the disable time will be longer than this.
     */
    open fun showScreenSaver() {
        if (configuration.hasDimScreenSaver) {
            inactivityHandler.removeCallbacks(inactivityCallback)
            resetScreenBrightness(true)
            setScreenSaverActive(true)
        } else if ((configuration.hasClockScreenSaver
                    || configuration.webScreenSaver
                    || configuration.hasScreenSaverWallpaper
                    || configuration.hasDimScreenSaver)
            && !isFinishing
        ) {
            inactivityHandler.removeCallbacks(inactivityCallback)
            dialogUtils.showScreenSaver(
                this@BaseBrowserActivity,
                {
                    dialogUtils.hideScreenSaverDialog()
                    resetScreenBrightness(false)
                    resetInactivityTimer()
                },
                configuration.webScreenSaver,
                configuration.webScreenSaverUrl,
                configuration.hasScreenSaverWallpaper,
                configuration.hasClockScreenSaver,
                configuration.imageRotation.toLong(),
                configuration.appPreventSleep,
                configuration.clockScreenSaverScale,
                configuration.clockScreenSaverMoves,
                configuration.webScreenSaverZoom,
                configuration.browserThirdPartyCookies,
                ::handleVolumeKey
            )
            resetScreenBrightness(true)
            setScreenSaverActive(true)
            onScreenSaverCovering(true)
        }
    }

    /**
     * Called when a screensaver that hides the page comes up or goes away. The dim
     * screensaver leaves the page on view and does not count. Called on every dismissal
     * attempt, so implementations should ignore a repeat of the current state.
     */
    protected open fun onScreenSaverCovering(covering: Boolean) {}

    /**
     * The dim screensaver dims through the window when the system brightness is not the
     * app's to set. That needs no permission, leaves the device's brightness and its
     * automatic mode alone, and lifts as soon as the override is cleared or the page is
     * no longer in front. When the level on screen is not known, there is no lower level
     * to set the window to, so the page is shaded instead.
     */
    open fun resetScreenBrightness(screenSaver: Boolean = false) {
        screenUtils.resetScreenBrightness(screenSaver)
        val dimWindow = screenSaver && configuration.hasDimScreenSaver && !screenUtils.controlsSystemBrightness()
        val levelKnown = !dimWindow || screenUtils.screenBrightnessKnown()
        setWindowBrightness(
            if (dimWindow && levelKnown) screenUtils.screenSaverWindowBrightness()
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        )
        setDimShade(if (dimWindow && !levelKnown) screenUtils.screenSaverShade() else 0f)
    }

    private fun setDimShade(shade: Float) {
        if (shade <= 0f) {
            dimShade?.visibility = View.GONE
            return
        }
        val view = dimShade ?: View(this).also {
            it.setBackgroundColor(Color.BLACK)
            addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dimShade = it
        }
        view.alpha = shade
        view.visibility = View.VISIBLE
    }

    private fun setWindowBrightness(brightness: Float) {
        val params = window.attributes
        if (params.screenBrightness == brightness) {
            return
        }
        params.screenBrightness = brightness
        window.attributes = params
    }

    /**
     * Tells the service the screensaver went up or came down. The service publishes it as
     * state and uses it to decide whether the camera should be processing frames, so it is
     * reported on every transition rather than only when the camera is configured to
     * follow the screensaver.
     */
    private fun setScreenSaverActive(active: Boolean) {
        if (screenSaverActive == active) {
            return
        }
        screenSaverActive = active
        val action = if (active) {
            WallPanelService.BROADCAST_SCREENSAVER_STARTED
        } else {
            WallPanelService.BROADCAST_SCREENSAVER_STOPPED
        }
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(Intent(action))
    }

    protected abstract fun configureWebSettings(userAgent: String)
    protected abstract fun loadWebViewUrl(url: String)
    protected abstract fun evaluateJavascript(js: String)
    protected abstract fun clearCache()
    protected abstract fun reload()
    protected abstract fun complete()
    protected abstract fun openSettings()

    /**
     * Tell the browser engine its content is no longer visible, so it stops compositing and
     * throttles the page's timers. A dashboard left running at full rate behind a dark
     * screen is what gets the browser process killed for background CPU usage.
     */
    protected abstract fun pauseBrowserEngine()

    /**
     * Undo [pauseBrowserEngine] once the content is visible again.
     */
    protected abstract fun resumeBrowserEngine()

    companion object {
        const val BROADCAST_ACTION_LOAD_URL = "BROADCAST_ACTION_LOAD_URL"
        const val BROADCAST_ACTION_JS_EXEC = "BROADCAST_ACTION_JS_EXEC"
        const val BROADCAST_ACTION_CLEAR_BROWSER_CACHE = "BROADCAST_ACTION_CLEAR_BROWSER_CACHE"
        const val BROADCAST_ACTION_RELOAD_PAGE = "BROADCAST_ACTION_RELOAD_PAGE"
        const val BROADCAST_ACTION_OPEN_SETTINGS = "BROADCAST_ACTION_OPEN_SETTINGS"
        const val BROADCAST_ACTION_SHOW_SCREENSAVER = "BROADCAST_ACTION_SHOW_SCREENSAVER"
        const val BROADCAST_ACTION_HIDE_SCREENSAVER = "BROADCAST_ACTION_HIDE_SCREENSAVER"
        const val BROADCAST_ACTION_CONFIG_CHANGED = "BROADCAST_ACTION_CONFIG_CHANGED"
        const val BROADCAST_ACTION_NOTIFICATION = "BROADCAST_ACTION_NOTIFICATION"
        const val BROADCAST_ACTION_ASSIST = "BROADCAST_ACTION_ASSIST"
        private const val MAX_NOTIFICATIONS = 5
        private const val NOTIFICATION_BACKGROUND = 0xE6303030.toInt()
        const val REQUEST_CODE_PERMISSION_AUDIO = 12
        const val REQUEST_CODE_PERMISSION_CAMERA = 13
    }
}