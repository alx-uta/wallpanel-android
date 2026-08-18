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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.view.*
import android.webkit.*
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import xyz.wallpanel.pro.databinding.ActivityBrowserBinding
import xyz.wallpanel.pro.network.ConnectionLiveData
import xyz.wallpanel.pro.ui.fragments.CodeBottomSheetFragment
import xyz.wallpanel.pro.utils.InternalWebChromeClient
import xyz.wallpanel.pro.ui.views.WebClientCallback
import xyz.wallpanel.pro.utils.InternalWebClient
import xyz.wallpanel.pro.utils.GeckoWebClientAdapter
import xyz.wallpanel.pro.utils.GeckoWebChromeClientAdapter
import xyz.wallpanel.pro.ui.views.GeckoViewWrapper
import xyz.wallpanel.pro.BuildConfig
import xyz.wallpanel.pro.R
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
    private var certPermissionsShown = false
    private var playlistHandler: Handler? = null
    private var codeBottomSheet: CodeBottomSheetFragment? = null
    private var webSettings: WebSettings? = null
    private val calendar: Calendar = Calendar.getInstance()
    private val reconnectionHandler = Handler(Looper.getMainLooper())
    private var connectionLiveData: ConnectionLiveData? = null
    override var isConnected = true
    private var webkitPermissionRequest: PermissionRequest? = null
    private var awaitingReconnect = false
    private var browserEnginePaused = false
    private var pendingGeckoReloadOnResume = false

    private val reloadPageRunnable = Runnable {
        initWebPageLoad()
    }

    // Android kills a cached GeckoView content process for excessive background CPU after
    // about 5 minutes; setActive(false) alone doesn't stop a websocket-driven dashboard from
    // burning that CPU. Rather than wait to be killed, tear the session down deliberately
    // once the screen has been off longer than the configured grace period.
    private val geckoSuspendRunnable = Runnable {
        if (!isFinishing && usingGeckoView && browserEnginePaused) {
            geckoViewWrapper?.suspend()
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
            if (configuration.isFirstTime) {
                openSettings()
            } else {
                showCodeBottomSheet()
            }
        }

        configureConnection()
        configureWebView(binding.root)
        initWebPageLoad()
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

        if (configuration.browserRefresh) {
            binding.swipeContainer.setOnRefreshListener {
                initWebPageLoad()
            }
            mOnScrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
                if (usingGeckoView) {
                    binding.swipeContainer.isEnabled = binding.activityBrowserGeckoview.scrollY == 0
                } else {
                    binding.swipeContainer.isEnabled = webView?.scrollY == 0
                }
            }
            binding.swipeContainer.viewTreeObserver.addOnScrollChangedListener(mOnScrollChangedListener)
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
        codeBottomSheet?.dismiss()
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
            geckoViewWrapper?.setActive(true)
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
     * Either way the dashboard needs loading once the screen wakes.
     */
    private fun reloadGeckoIfPending() {
        if (!pendingGeckoReloadOnResume) {
            return
        }
        pendingGeckoReloadOnResume = false
        val wrapper = geckoViewWrapper ?: return
        if (wrapper.geckoSession == null && wrapper.recreateSession()) {
            wrapper.geckoSession?.let { session ->
                geckoClientAdapter?.setGeckoSession(session)
            }
        }
        initWebPageLoad()
    }

    override fun openSettings() {
        hideScreenSaver()
        // Stop our service for performance reasons and to pick up changes
        stopService(wallPanelService)
        val intent = SettingsActivity.createStartIntent(this)
        startActivity(intent)
    }

    override fun loadWebViewUrl(url: String) {
        if (url.startsWith("intent:")) {
            val launchIntent: Intent
            try {
                launchIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (ex: URISyntaxException) {
                Timber.e("Bad URI $url: $ex.message")
                val context = if (usingGeckoView) binding.activityBrowserGeckoview.context else webView?.context
                context?.let {
                    dialogUtils.showAlertDialog(it, resources.getString(R.string.dialog_message_invalid_intent))
                }
                return
            }
            val selector = launchIntent.selector
            if (selector != null) {
                selector.addCategory(Intent.CATEGORY_BROWSABLE)
                selector.setComponent(null)
            }
            val context = if (usingGeckoView) binding.activityBrowserGeckoview.context else webView?.context
            launchIntent.putExtra(Browser.EXTRA_APPLICATION_ID, context?.packageName)
            context?.startActivity(launchIntent)
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
            geckoViewWrapper?.clearCache(true)
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

    override fun setWebkitPermissionRequest(request: PermissionRequest?) {
        webkitPermissionRequest = request
    }

    override fun displayProgress(): Boolean {
        return displayProgress
    }

    override fun startReloadDelay() {
        awaitingReconnect = true
        playlistHandler?.removeCallbacksAndMessages(null)
        reconnectionHandler.postDelayed(reloadPageRunnable, 30000)
    }

    override fun certPermissionsShown(): Boolean {
        return certPermissionsShown
    }

    override fun stopReloadDelay() {
        awaitingReconnect = false
        reconnectionHandler.removeCallbacks(reloadPageRunnable)
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
                wrapper.geckoSession?.let { session ->
                    geckoClientAdapter?.setGeckoSession(session)
                }
                // A fresh session starts out visible, so a kill that happens while the
                // screen is off would otherwise leave the page running at full rate again
                wrapper.setActive(browserEnginePaused.not())
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

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun askForWebkitPermission(permission: String, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
            }
        } else {
            webkitPermissionRequest?.grant(webkitPermissionRequest?.resources)
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
                GeckoViewWrapper.initializeRuntime(this)

                // Initialize GeckoView wrapper
                geckoViewWrapper = GeckoViewWrapper(this, binding.activityBrowserGeckoview)

                // Configure delegates
                configureGeckoViewDelegates()
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize GeckoView, falling back to WebView")
                // Fallback to WebView
                usingGeckoView = false
                configuration.useGeckoView = false
                configureSystemWebView()
                Toast.makeText(this, "GeckoView failed, using WebView", Toast.LENGTH_LONG).show()
            }
        } else {
            // Hide GeckoView, show WebView
            binding.activityBrowserGeckoview.visibility = View.GONE
            binding.activityBrowserWebviewNative.visibility = View.VISIBLE

            configureSystemWebView()
        }
    }

    private fun configureSystemWebView() {
        webView = binding.activityBrowserWebviewNative
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
        val clientAdapter = GeckoWebClientAdapter(resources, this, configuration)
        val geckoChromeAdapter = GeckoWebChromeClientAdapter(resources, this)
        geckoClientAdapter = clientAdapter

        // Pass the GeckoSession to the adapter for JavaScript execution
        geckoViewWrapper?.geckoSession?.let { session ->
            clientAdapter.setGeckoSession(session)
        }

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
        binding.progressView.visibility = View.GONE
        if (usingGeckoView) {
            binding.activityBrowserGeckoview.visibility = View.VISIBLE
        } else {
            binding.activityBrowserWebviewNative.visibility = View.VISIBLE
        }
        // set user agent
        configureWebSettings(configuration.browserUserAgent)
        // set zoom level
        if (zoomLevel != 0.0f && !usingGeckoView) {
            val zoomPercent = (zoomLevel * 100).toInt()
            webView?.setInitialScale(zoomPercent)
        }
        // check if we are using playlist
        if (configuration.appLaunchUrl.lines().size == 1) {
            loadWebViewUrl(configuration.appLaunchUrl)
        } else {
            startPlaylist()
        }
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

}
