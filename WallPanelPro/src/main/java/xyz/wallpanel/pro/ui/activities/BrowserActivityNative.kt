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

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Browser
import android.view.*
import android.webkit.*
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import xyz.wallpanel.pro.databinding.ActivityBrowserBinding
import xyz.wallpanel.pro.network.ConnectionLiveData
import xyz.wallpanel.pro.ui.fragments.CodeBottomSheetFragment
import xyz.wallpanel.pro.utils.AppRestartHelper
import xyz.wallpanel.pro.utils.BootReadinessGate
import xyz.wallpanel.pro.utils.InternalWebChromeClient
import xyz.wallpanel.pro.ui.views.WebClientCallback
import xyz.wallpanel.pro.utils.InternalWebClient
import xyz.wallpanel.pro.utils.OverlayPermission
import xyz.wallpanel.pro.utils.GeckoWebClientAdapter
import xyz.wallpanel.pro.utils.GeckoWebChromeClientAdapter
import xyz.wallpanel.pro.ui.views.GeckoViewWrapper
import xyz.wallpanel.pro.BuildConfig
import xyz.wallpanel.pro.R
import org.mozilla.geckoview.GeckoSession
import timber.log.Timber
import java.net.URISyntaxException
import java.util.*
import java.util.concurrent.TimeUnit


class BrowserActivityNative : BaseBrowserActivity(), LifecycleObserver, WebClientCallback {

    private var webView: WebView? = null
    private var geckoViewWrapper: GeckoViewWrapper? = null
    private var geckoClientAdapter: GeckoWebClientAdapter? = null
    private var usingGeckoView = false

    private lateinit var binding: ActivityBrowserBinding
    private var playlistHandler: Handler? = null
    private var codeBottomSheet: CodeBottomSheetFragment? = null
    private var webSettings: WebSettings? = null
    private val calendar: Calendar = Calendar.getInstance()
    private val reconnectionHandler = Handler(Looper.getMainLooper())
    private var connectionLiveData: ConnectionLiveData? = null
    override var isConnected = true
    // When the WebView's renderer last died, to slow down a page that keeps killing it
    private var lastRendererDeath = 0L
    private var awaitingReconnect = false
    private var browserEnginePaused = false
    private var pendingGeckoReloadOnResume = false
    // A clock, wallpaper or web screensaver is hiding the page
    private var screenSaverCovering = false
    // How far the GeckoView page is scrolled down, for the pull-to-refresh gesture
    private var geckoScrollY = 0
    // A page element, a video for instance, is showing fullscreen
    private var pageFullScreen = false
    // The page's upload field waiting on the file picker
    private var pendingFilePick: ((Array<Uri>?) -> Unit)? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val onResult = pendingFilePick ?: return@registerForActivityResult
        pendingFilePick = null
        val data = result.data
        val uris = if (result.resultCode != RESULT_OK || data == null) {
            null
        } else {
            val clip = data.clipData
            if (clip != null) {
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        }
        onResult(uris)
    }
    // Android permission requests made for a page, by request code
    private val pagePermissionRequests = mutableMapOf<Int, (Boolean) -> Unit>()
    private var nextPagePermissionRequestCode = PERMISSIONS_REQUEST_PAGE_FIRST

    private val reloadPageRunnable = Runnable {
        initWebPageLoad()
    }

    // Set while the first load after a reboot waits for the clock and the network
    private var bootGate: BootReadinessGate? = null
    // A URL sent by command during that wait, loaded in place of the launch URL afterwards
    private var urlDeferredByBootGate: String? = null
    // A url command that arrived while the GeckoView page was waiting for the screen to load
    private var urlDeferredBySuspend: String? = null

    private val bootGateRunnable = object : Runnable {
        override fun run() {
            val gate = bootGate ?: return
            if (isFinishing) {
                return
            }
            if (!gate.isReady()) {
                reconnectionHandler.postDelayed(this, BOOT_GATE_POLL_MS)
                return
            }
            if (gate.gaveUp) {
                Timber.w(
                    "Waited long enough (%s), loading the dashboard anyway, Home Assistant may ask to log in",
                    gate.describe()
                )
            } else {
                Timber.i("Device ready (%s), loading the dashboard", gate.describe())
            }
            bootGate = null
            binding.bootWaitText.visibility = View.GONE
            // The screensaver was held off while the gate waited, so start that clock again
            resetInactivityTimer()
            val deferredUrl = urlDeferredByBootGate
            urlDeferredByBootGate = null
            if (deferredUrl == null) {
                initWebPageLoad()
            } else {
                prepareWebPageLoad()
                loadWebViewUrl(deferredUrl)
            }
        }
    }

    // Android kills a cached GeckoView content process for excessive background CPU after
    // about 5 minutes; setActive(false) alone doesn't stop a websocket-driven dashboard from
    // burning that CPU. Rather than wait to be killed, tear the session down deliberately
    // once the screen has been off longer than the configured grace period.
    private val geckoSuspendRunnable = Runnable {
        // Only a dark screen: with the screen on, the page is behind something the user is
        // using, a file picker for the page's upload field or a permission dialog, and tearing
        // the session down would reload the page under it. A screen that goes off later pauses
        // the engine again, which schedules this once more.
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!isFinishing && usingGeckoView && browserEnginePaused && !screenOn) {
            geckoViewWrapper?.suspend()
            onGeckoSessionReplaced()
            pendingGeckoReloadOnResume = true
        }
    }

    // To save current index
    private var playlistIndex = 0

    private val playlistRunnable = object : Runnable {
        override fun run() {
            // TODO: allow users to set their own value in settings
            val offset = 60L - calendar.get(Calendar.SECOND)
            val urls: List<String> = configuration.appLaunchUrl.lines()
            // Avoid IndexOutOfBound
            playlistIndex = (playlistIndex + 1) % urls.size
            if (urls.isNotEmpty() && urls.size >= playlistIndex) {
                loadWebViewUrl(urls[playlistIndex])
                playlistHandler?.postDelayed(this, TimeUnit.SECONDS.toMillis(offset))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG && configuration.isFirstTime) {
            // Only set debug defaults on first run, don't overwrite user settings
            configuration.mqttBroker = BuildConfig.BROKER
            configuration.mqttServerPort = BuildConfig.BROKER_PORT
            configuration.mqttTlsEnabled = BuildConfig.BROKER_TLS
            configuration.mqttClientId = BuildConfig.CLIENT_ID
            configuration.mqttBaseTopic = BuildConfig.BASE_TOPIC
            configuration.mqttUsername = BuildConfig.BROKER_USERNAME
            configuration.mqttPassword = BuildConfig.BROKER_PASS
            configuration.mqttEnabled = BuildConfig.BROKER.isNotBlank()
            configuration.appLaunchUrl = BuildConfig.HASS_URL
            configuration.isFirstTime = false
            configuration.settingsCode = BuildConfig.CODE.toString()
            configuration.hasClockScreenSaver = true
        }

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        try {
            setContentView(binding.root)
        } catch (e: Exception) {
            Timber.e(e.message)
            AlertDialog.Builder(this@BrowserActivityNative)
                .setMessage(getString(R.string.dialog_missing_webview_warning))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        binding.launchSettingsFab.setOnClickListener {
            if (configuration.isFirstTime || !configuration.settingsCodeRequired) {
                openSettings()
            } else {
                showCodeBottomSheet()
            }
        }

        configureConnection()
        configureWebView(binding.root)
        loadWhenDeviceReady()
        requestNotificationPermissions()
        requestOverlayPermission()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })
    }

    /**
     * The back key moves back through the page's history, or out of a fullscreen video. With
     * nowhere to go back to it does nothing: leaving the browser is not something a kiosk
     * should do on a single key press.
     */
    private fun goBack() {
        if (usingGeckoView) {
            val session = geckoViewWrapper?.geckoSession
            when {
                pageFullScreen -> session?.exitFullScreen()
                geckoClientAdapter?.canGoBack == true -> session?.goBack()
            }
        } else if (webView?.canGoBack() == true) {
            webView?.goBack()
        }
    }

    /**
     * Asked once, on devices where it matters: without it, a crash or restart while the screen
     * is on leaves the device on the launcher, see [OverlayPermission]. It can be granted
     * later from the settings screen.
     */
    private fun requestOverlayPermission() {
        if (configuration.overlayPermissionShown || OverlayPermission.isGranted(this)) {
            return
        }
        configuration.overlayPermissionShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_overlay_permission_title)
            .setMessage(R.string.dialog_overlay_permission_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> OverlayPermission.openSettings(this) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // The browser is back, so the notification offering to reopen it has served its purpose
        AppRestartHelper.cancelRestartNotification(this)
    }

    /**
     * A restart posts a notification to bring the browser back, which on API 33 and above is
     * silently dropped without this permission. It is asked for once, and the application
     * carries on either way.
     */
    private fun requestNotificationPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || configuration.notificationPermissionsShown) {
            return
        }
        configuration.notificationPermissionsShown = true
        if (PackageManager.PERMISSION_DENIED == ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSIONS_REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pagePermissionRequests.remove(requestCode)?.let { onResult ->
            // An empty result means the request was cancelled
            onResult(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            return
        }
        if (requestCode == PERMISSIONS_REQUEST_NOTIFICATIONS) {
            // If request is cancelled, the result arrays are empty.
            if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Timber.d("Notification permission was not granted")
                Toast.makeText(
                    this,
                    R.string.toast_notification_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        
        // Log device info for debugging
        val deviceInfo = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        if (configuration.useDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            setLightTheme()
        }

        if (configuration.hardwareAccelerated && !usingGeckoView) {
            // chromium, enable hardware acceleration (only for WebView)
            webView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } else if (!usingGeckoView) {
            // older android version, disable hardware acceleration
            webView?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        // Note: GeckoView handles hardware acceleration internally
        if (usingGeckoView) {
            GeckoViewWrapper.setDarkTheme(configuration.useDarkTheme)
        }

        if (configuration.browserRefresh) {
            binding.swipeContainer.isEnabled = true
            binding.swipeContainer.setOnRefreshListener {
                initWebPageLoad()
            }
            if (usingGeckoView) {
                // Gecko scrolls the page inside its view, so the view's own scroll position
                // stays at zero; the page's, reported by the scroll delegate, is what counts.
                binding.swipeContainer.setOnChildScrollUpCallback { _, _ -> geckoScrollY > 0 }
            } else {
                mOnScrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
                    binding.swipeContainer.isEnabled = webView?.scrollY == 0
                }
                binding.swipeContainer.viewTreeObserver.addOnScrollChangedListener(mOnScrollChangedListener)
            }
        } else {
            binding.swipeContainer.isEnabled = false
        }

        setupSettingsButton()

        if (configuration.hasSettingsUpdates()) {
            initWebPageLoad()
        }
    }

    override fun onStop() {
        super.onStop()
        if (mOnScrollChangedListener != null && configuration.browserRefresh) {
            binding.swipeContainer.viewTreeObserver.removeOnScrollChangedListener(mOnScrollChangedListener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectionHandler.removeCallbacks(bootGateRunnable)
        codeBottomSheet?.dismiss()
        codeBottomSheet = null
        // Closing the session lets Gecko drop its references to this activity's view, which
        // otherwise outlive it.
        geckoViewWrapper?.destroy()
        geckoViewWrapper = null
        // Answered as refused, so the page is not left waiting on a request that is gone
        val unanswered = pagePermissionRequests.values.toList()
        pagePermissionRequests.clear()
        unanswered.forEach { it(false) }
        pendingFilePick?.invoke(null)
        pendingFilePick = null
    }

    /**
     * Stop the page from running while nothing is on screen. Gecko keeps a session it was
     * never told about compositing and running the page's timers at full foreground rate,
     * and the WebView does the same, so a live dashboard keeps burning CPU behind a dark
     * screen until Android kills the process for background CPU usage.
     */
    override fun pauseBrowserEngine() {
        browserEnginePaused = true
        if (usingGeckoView) {
            geckoViewWrapper?.setActive(false)
            reconnectionHandler.removeCallbacks(geckoSuspendRunnable)
            val suspendDelaySeconds = configuration.geckoViewSuspendSeconds
            if (suspendDelaySeconds > 0) {
                reconnectionHandler.postDelayed(
                    geckoSuspendRunnable,
                    TimeUnit.SECONDS.toMillis(suspendDelaySeconds.toLong())
                )
            }
        } else {
            webView?.onPause()
            webView?.pauseTimers()
        }
    }

    override fun resumeBrowserEngine() {
        browserEnginePaused = false
        if (usingGeckoView) {
            reconnectionHandler.removeCallbacks(geckoSuspendRunnable)
            geckoViewWrapper?.setActive(!screenSaverCovering)
            reloadGeckoIfPending()
        } else {
            webView?.onResume()
            webView?.resumeTimers()
        }
    }

    /**
     * Bring the page back after either a deliberate suspend() (no session at all) or a
     * recreateGeckoSession() that deferred its reload while the screen was off (an empty
     * but open session).
     * Either way the page needs loading once the screen wakes: the dashboard, or the url a
     * command asked for in the meantime.
     */
    private fun reloadGeckoIfPending() {
        if (!pendingGeckoReloadOnResume) {
            return
        }
        pendingGeckoReloadOnResume = false
        val deferredUrl = urlDeferredBySuspend
        urlDeferredBySuspend = null
        val wrapper = geckoViewWrapper ?: return
        if (wrapper.geckoSession == null) {
            wrapper.recreateSession()
            onGeckoSessionReplaced()
        }
        if (deferredUrl == null) {
            initWebPageLoad()
        } else {
            prepareWebPageLoad()
            loadWebViewUrl(deferredUrl)
        }
    }

    /**
     * The page state reported by the old session went with it. A new session reports none of
     * it until it changes, and a fullscreen left standing would keep the settings button
     * hidden, pull-to-refresh off and the back key exiting a fullscreen that is not there.
     */
    private fun onGeckoSessionReplaced() {
        geckoScrollY = 0
        geckoClientAdapter?.onSessionReplaced()
        onPageFullScreen(false)
    }

    /**
     * The service is left running: MQTT and HTTP stay reachable while the settings are open,
     * and the service picks up their changes when the screen closes. On a device with a PIN or
     * pattern lock, the device credential is asked for here, over the dashboard, before the
     * settings open. Opened behind the lock screen instead, they would leave the kiosk there if
     * nobody unlocked it.
     */
    override fun openSettings() {
        hideScreenSaver()
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager.isKeyguardLocked && keyguardManager.isKeyguardSecure) {
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    startActivity(SettingsActivity.createStartIntent(this@BrowserActivityNative))
                }
            })
            return
        }
        startActivity(SettingsActivity.createStartIntent(this))
    }

    override fun loadWebViewUrl(url: String) {
        if (url.startsWith("intent:")) {
            openExternally(url)
        } else if (bootGate != null) {
            Timber.i("Holding $url until the device is ready")
            urlDeferredByBootGate = url
        } else if (usingGeckoView && pendingGeckoReloadOnResume) {
            // The session is closed, or open and left empty until the screen wakes, which
            // loads the dashboard over anything loaded now. The url is loaded then instead.
            Timber.i("Holding $url until the screen wakes")
            urlDeferredBySuspend = url
        } else {
            if (usingGeckoView) {
                geckoViewWrapper?.loadUrl(url)
            } else {
                webView?.loadUrl(url)
            }
        }
    }

    override fun evaluateJavascript(js: String) {
        if (usingGeckoView) {
            geckoViewWrapper?.evaluateJavascript(js)
        } else {
            webView?.evaluateJavascript(js, null)
        }
    }

    override fun clearCache() {
        if (usingGeckoView) {
            GeckoViewWrapper.clearRuntimeCaches()
        } else {
            webView?.clearCache(true)
            webView?.clearHistory()
            webView?.clearFormData()
            
            try {
                applicationContext.cacheDir.deleteRecursively()
                applicationContext.cacheDir.mkdir()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing cache directory")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    override fun reload() {
        if (usingGeckoView) {
            geckoViewWrapper?.reload()
        } else {
            webView?.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    // TODO handle deprecated web settings
    override fun configureWebSettings(userAgent: String) {
        if (usingGeckoView) {
            // GeckoView settings are handled in GeckoViewWrapper
            geckoViewWrapper?.setUserAgent(userAgent)
        } else {
            if (webSettings == null) {
                webSettings = webView?.settings
            }
            webSettings?.javaScriptEnabled = true
            webSettings?.domStorageEnabled = true
            
            @Suppress("DEPRECATION")
            webSettings?.databaseEnabled = true
            @Suppress("DEPRECATION")
            webSettings?.saveFormData = true
            
            webSettings?.javaScriptCanOpenWindowsAutomatically = true
            
            // Enable caching for better performance
            webSettings?.cacheMode = WebSettings.LOAD_DEFAULT
            
            webSettings?.allowFileAccess = true
            
            @Suppress("DEPRECATION")
            webSettings?.allowFileAccessFromFileURLs = true
            
            webSettings?.allowContentAccess = true
            webSettings?.setSupportZoom(true)
            webSettings?.loadWithOverviewMode = true
            webSettings?.useWideViewPort = true
            
            @Suppress("DEPRECATION")
            webSettings?.pluginState = WebSettings.PluginState.ON
            @Suppress("DEPRECATION")
            webSettings?.setRenderPriority(WebSettings.RenderPriority.HIGH)
            
            webSettings?.mediaPlaybackRequiresUserGesture = false

            if (userAgent.isNotEmpty()) {
                webSettings?.userAgentString = userAgent
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                webSettings?.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            // Apply dark mode for WebView
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+) - Use native forceDark API
                @Suppress("DEPRECATION")
                if (configuration.useDarkTheme) {
                    webSettings?.forceDark = WebSettings.FORCE_DARK_ON
                } else {
                    webSettings?.forceDark = WebSettings.FORCE_DARK_OFF
                }
            }
        }
    }

    override fun complete() {
        if (binding.swipeContainer.isRefreshing && configuration.browserRefresh) {
            binding.swipeContainer.isRefreshing = false
        }
    }

    override fun displayProgress(): Boolean {
        return displayProgress
    }

    override fun startReloadDelay() {
        awaitingReconnect = true
        playlistHandler?.removeCallbacksAndMessages(null)
        reconnectionHandler.postDelayed(reloadPageRunnable, 30000)
    }

    override fun stopReloadDelay() {
        awaitingReconnect = false
        reconnectionHandler.removeCallbacks(reloadPageRunnable)
    }

    override fun replaceDeadWebView(deadView: WebView) {
        val parent = deadView.parent as? ViewGroup
        val index = parent?.indexOfChild(deadView) ?: -1
        val layoutParams = deadView.layoutParams
        parent?.removeView(deadView)
        deadView.destroy()
        if (webView !== deadView) {
            return
        }
        webView = null
        webSettings = null
        if (parent == null || isFinishing) {
            return
        }
        // Built after the WebView's callback has returned
        reconnectionHandler.post {
            if (isFinishing) {
                return@post
            }
            val freshView = try {
                WebView(this)
            } catch (e: Exception) {
                Timber.e(e, "Unable to create a new WebView, restarting the application")
                AppRestartHelper.restartApplication(this)
                return@post
            }
            freshView.id = R.id.activity_browser_webview_native
            parent.addView(freshView, index, layoutParams)
            configureSystemWebView(freshView)
            val layerType = if (configuration.hardwareAccelerated) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE
            freshView.setLayerType(layerType, null)
            if (browserEnginePaused) {
                freshView.onPause()
            }
            val now = SystemClock.elapsedRealtime()
            val diedRecently = lastRendererDeath != 0L && now - lastRendererDeath < RENDERER_DEATH_WINDOW_MS
            lastRendererDeath = now
            if (diedRecently) {
                // A page that brings the renderer down as soon as it loads would otherwise
                // keep the device busy doing nothing else
                Timber.w("The WebView renderer died again within a minute, loading the page later")
                startReloadDelay()
            } else {
                initWebPageLoad()
            }
        }
    }

    /**
     * Recover from the death of the GeckoView content process, which Android kills after a
     * few minutes of screen-off for background CPU usage.
     */
    override fun recreateGeckoSession() {
        if (!usingGeckoView || isFinishing) {
            return
        }
        // Post so the session is rebuilt after the GeckoView delegate callback has returned
        reconnectionHandler.post {
            if (isFinishing) {
                return@post
            }
            val wrapper = geckoViewWrapper
            if (wrapper == null) {
                Timber.e("Unable to recreate the GeckoSession, GeckoView is not initialized")
                return@post
            }
            if (wrapper.recreateSession()) {
                onGeckoSessionReplaced()
                // A fresh session starts out visible, so a kill that happens while the
                // screen is off would otherwise leave the page running at full rate again
                wrapper.setActive(!browserEnginePaused && !screenSaverCovering)
                if (browserEnginePaused) {
                    // Reloading the dashboard behind a dark screen re-arms the CPU burn that
                    // got the content process killed, so the empty session is left alone until
                    // the screen comes back
                    pendingGeckoReloadOnResume = true
                } else {
                    initWebPageLoad()
                }
            }
        }
    }

    /**
     * Stops a hidden page from running at full rate, which is also what releases the screen
     * wake lock GeckoView holds while a video plays; a live camera card would otherwise keep
     * the display on for good. The page picks up where it was when the screensaver goes.
     */
    override fun onScreenSaverCovering(covering: Boolean) {
        if (covering == screenSaverCovering) {
            return
        }
        screenSaverCovering = covering
        if (usingGeckoView && !browserEnginePaused) {
            geckoViewWrapper?.setActive(!covering)
        }
    }

    override fun openExternally(url: String): Boolean {
        val launchIntent = if (url.startsWith("intent:")) {
            try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    // An intent from a web page may only reach browsable activities
                    selector?.let { selector ->
                        selector.addCategory(Intent.CATEGORY_BROWSABLE)
                        selector.component = null
                    }
                    putExtra(Browser.EXTRA_APPLICATION_ID, packageName)
                }
            } catch (ex: URISyntaxException) {
                Timber.e("Bad URI $url: ${ex.message}")
                dialogUtils.showAlertDialog(this, resources.getString(R.string.dialog_message_invalid_intent))
                return false
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(launchIntent)
            true
        } catch (e: ActivityNotFoundException) {
            Timber.w("No app opens $url")
            Toast.makeText(this, R.string.toast_no_app_for_link, Toast.LENGTH_SHORT).show()
            false
        } catch (e: SecurityException) {
            Timber.w(e, "Not allowed to open $url")
            Toast.makeText(this, R.string.toast_no_app_for_link, Toast.LENGTH_SHORT).show()
            false
        }
    }

    override fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        val requestCode = nextPagePermissionRequestCode++
        if (nextPagePermissionRequestCode > PERMISSIONS_REQUEST_PAGE_LAST) {
            nextPagePermissionRequestCode = PERMISSIONS_REQUEST_PAGE_FIRST
        }
        pagePermissionRequests[requestCode] = onResult
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
    }

    /**
     * Gecko draws the fullscreen element across the whole view itself. What is left is the
     * application's own chrome: the settings button is hidden, the system bars too even when
     * the dashboard is not set to fullscreen, and pull-to-refresh is off so a swipe on a video
     * does not reload the page.
     */
    override fun onPageFullScreen(fullScreen: Boolean) {
        if (fullScreen == pageFullScreen) {
            return
        }
        pageFullScreen = fullScreen
        if (fullScreen) {
            binding.launchSettingsFab.visibility = View.GONE
            binding.swipeContainer.isEnabled = false
        } else {
            setupSettingsButton()
            binding.swipeContainer.isEnabled = configuration.browserRefresh
        }
        onWindowFocusChanged(hasWindowFocus())
    }

    override fun geckoPageStarted() {
        geckoScrollY = 0
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pageFullScreen) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    override fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, onResult: (Array<Uri>?) -> Unit) {
        pendingFilePick?.invoke(null)
        pendingFilePick = onResult
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (mimeTypes.size == 1) mimeTypes[0] else "*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        if (mimeTypes.size > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        try {
            filePicker.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No file picker on this device")
            pendingFilePick = null
            onResult(null)
        }
    }

    private fun configureConnection() {
        connectionLiveData = ConnectionLiveData(this)
        connectionLiveData?.observe(this) { connected ->
            if (connected && isConnected.not()) {
                isConnected = true
                if (awaitingReconnect) { // reload the page if there was error initially loading page due to network disconnect
                    stopReloadDelay()
                    initWebPageLoad()
                } else if (configuration.browserRefreshDisconnect) { // reload page on network reconnect
                    initWebPageLoad()
                }
            } else if (connected.not()) {
                isConnected = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureWebView(@Suppress("UNUSED_PARAMETER") view: ViewGroup) {
        // Determine which engine to use
        usingGeckoView = configuration.useGeckoView

        if (usingGeckoView) {
            try {
                // Hide WebView, show GeckoView
                binding.activityBrowserWebviewNative.visibility = View.GONE
                binding.activityBrowserGeckoview.visibility = View.VISIBLE

                // Initialize GeckoRuntime (if not already initialized)
                GeckoViewWrapper.initializeRuntime(this, zoomLevel, configuration.useDarkTheme)

                // Initialize GeckoView wrapper
                geckoViewWrapper = GeckoViewWrapper(this, binding.activityBrowserGeckoview)

                // Configure delegates
                configureGeckoViewDelegates()

                // Unused, and left in place it would share the WebView renderer of a web
                // screensaver with no client to handle that renderer's death
                binding.activityBrowserWebviewNative.let { unused ->
                    (unused.parent as? ViewGroup)?.removeView(unused)
                    unused.destroy()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize GeckoView, falling back to WebView")
                // Fallback to WebView
                usingGeckoView = false
                configuration.useGeckoView = false
                configureSystemWebView()
                Toast.makeText(this, R.string.toast_geckoview_failed, Toast.LENGTH_LONG).show()
            }
        } else {
            // Hide GeckoView, show WebView
            binding.activityBrowserGeckoview.visibility = View.GONE
            binding.activityBrowserWebviewNative.visibility = View.VISIBLE

            configureSystemWebView()
        }
    }

    private fun configureSystemWebView(view: WebView = binding.activityBrowserWebviewNative) {
        webView = view
        webSettings = null
        webView?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        // Force links and redirects to open in the WebView instead of in a browser
        configureWebChromeClient()
        configureWebViewClient()

        webView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetScreen()
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
                MotionEvent.ACTION_UP -> if (!v.hasFocus()) {
                    v.requestFocus()
                }
            }
            false
        }
    }

    private fun configureGeckoViewDelegates() {
        val clientAdapter = GeckoWebClientAdapter(this)
        geckoClientAdapter = clientAdapter
        val geckoChromeAdapter = GeckoWebChromeClientAdapter(this, this)
        // Where the "show browser activity" loading progress is shown
        geckoChromeAdapter.setGeckoView(binding.activityBrowserGeckoview)
        geckoViewWrapper?.setScrollDelegate(object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                geckoScrollY = scrollY
            }
        })
        geckoViewWrapper?.setNavigationDelegate(clientAdapter)
        // The content delegate reports crashes and OS kills of the content process
        geckoViewWrapper?.setContentDelegate(clientAdapter)
        geckoViewWrapper?.setProgressDelegate(geckoChromeAdapter)
        geckoViewWrapper?.setPermissionDelegate(geckoChromeAdapter)
        geckoViewWrapper?.setPromptDelegate(geckoChromeAdapter)

        binding.activityBrowserGeckoview.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetScreen()
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
                MotionEvent.ACTION_UP -> if (!v.hasFocus()) {
                    v.requestFocus()
                }
            }
            false
        }
    }

    private fun configureWebChromeClient() {
        webView?.webChromeClient = InternalWebChromeClient(resources = resources, callback = this)
    }

    private fun configureWebViewClient() {
        webView?.webViewClient = InternalWebClient(resources = resources, callback = this, configuration)
    }

    private fun initWebPageLoad() {
        if (bootGate != null) {
            // The gate loads the page itself once it opens
            complete()
            return
        }
        prepareWebPageLoad()
        // check if we are using playlist
        if (configuration.appLaunchUrl.lines().size == 1) {
            loadWebViewUrl(configuration.appLaunchUrl)
        } else {
            startPlaylist()
        }
    }

    private fun prepareWebPageLoad() {
        binding.progressView.visibility = View.GONE
        if (usingGeckoView) {
            binding.activityBrowserGeckoview.visibility = View.VISIBLE
        } else {
            webView?.visibility = View.VISIBLE
        }
        // set user agent
        configureWebSettings(configuration.browserUserAgent)
        // set zoom level
        if (zoomLevel != 0.0f && !usingGeckoView) {
            val zoomPercent = (zoomLevel * 100).toInt()
            webView?.setInitialScale(zoomPercent)
        }
    }

    /**
     * Load the dashboard, or right after a reboot hold it back until the clock is set and
     * the network is up, see [BootReadinessGate].
     */
    private fun loadWhenDeviceReady() {
        val gate = BootReadinessGate(
            System::currentTimeMillis,
            SystemClock::elapsedRealtime,
            ::isNetworkConnected,
            BootReadinessGate.earliestValidTimeOf(this)
        )
        if (!gate.appliesTo(configuration.appLaunchUrl)) {
            initWebPageLoad()
            return
        }
        bootGate = gate
        gate.isReady() // starts timing the connection, if there is one
        Timber.i("Waiting for the device to be ready before loading the dashboard (%s)", gate.describe())
        binding.bootWaitText.visibility = View.VISIBLE
        reconnectionHandler.postDelayed(bootGateRunnable, BOOT_GATE_POLL_MS)
    }

    override fun canShowScreenSaver(): Boolean = bootGate == null

    @Suppress("DEPRECATION")
    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return connectivityManager?.activeNetworkInfo?.isConnected == true
    }

    private fun startPlaylist() {
        playlistHandler = Handler(Looper.getMainLooper())
        playlistHandler?.postDelayed(playlistRunnable, 10)
    }

    private fun showCodeBottomSheet() {
        codeBottomSheet = CodeBottomSheetFragment.newInstance(configuration.settingsCode,
            object : CodeBottomSheetFragment.OnAlarmCodeFragmentListener {
                override fun onComplete(code: String) {
                    codeBottomSheet?.dismiss()
                    codeBottomSheet = null
                    openSettings()
                }

                override fun onCodeError() {
                    Toast.makeText(
                        this@BrowserActivityNative,
                        R.string.toast_code_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onCancel() {
                    codeBottomSheet?.dismiss()
                    codeBottomSheet = null
                }
            })
        codeBottomSheet?.show(supportFragmentManager, codeBottomSheet?.tag)
    }

    private fun setupSettingsButton() {
        // Set the location and transparency of the fab button
        val params: CoordinatorLayout.LayoutParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 16
        params.leftMargin = 16
        params.rightMargin = 16
        params.bottomMargin = 16
        when (configuration.settingsLocation) {
            0 -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
            }
            1 -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
            }
            2 -> {
                params.gravity = Gravity.TOP or Gravity.END
            }
            3 -> {
                params.gravity = Gravity.TOP or Gravity.START
            }
        }
        binding.launchSettingsFab.layoutParams = params
        when {
            configuration.settingsDisabled -> {
                binding.launchSettingsFab.visibility = View.GONE
            }
            configuration.settingsTransparent -> {
                binding.launchSettingsFab.visibility = View.VISIBLE
                binding.launchSettingsFab.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.transparent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    binding.launchSettingsFab.compatElevation = 0f
                }
                binding.launchSettingsFab.imageAlpha = 0
            }
            else -> {
                binding.launchSettingsFab.visibility = View.VISIBLE
                binding.launchSettingsFab.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.colorAccent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    binding.launchSettingsFab.compatElevation = 4f
                }
                binding.launchSettingsFab.imageAlpha = 180
            }
        }
    }

    companion object {
        const val PERMISSIONS_REQUEST_NOTIFICATIONS = 220
        // Request codes for permissions a page asks for, taken in turn
        private const val PERMISSIONS_REQUEST_PAGE_FIRST = 300
        private const val PERMISSIONS_REQUEST_PAGE_LAST = 399
        private const val BOOT_GATE_POLL_MS = 1000L
        private const val RENDERER_DEATH_WINDOW_MS = 60_000L
    }
}
