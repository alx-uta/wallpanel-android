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

package xyz.wallpanel.pro

import android.R.attr
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process.myPid
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDex
import androidx.preference.PreferenceManager
import dagger.android.AndroidInjector
import dagger.android.support.DaggerApplication
import org.mozilla.geckoview.GeckoRuntime
import timber.log.Timber
import xyz.wallpanel.pro.di.DaggerApplicationComponent
import xyz.wallpanel.pro.utils.ReleaseTree
import xyz.wallpanel.pro.utils.LauncherShortcuts
import xyz.wallpanel.pro.utils.WallpanelDebugTree


class WallPanel : DaggerApplication() {


    override fun applicationInjector(): AndroidInjector<out DaggerApplication> {
        return DaggerApplicationComponent.builder().create(this)
    }

    override fun onCreate() {
        super.onCreate()
        
        // Apply dark theme setting on app startup
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val useDarkTheme = sharedPreferences.getBoolean("pref_dark_theme", false)
        if (useDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        
        if (BuildConfig.DEBUG) {
            // Gives clickable links to the issue in the Android Studio Logcat
            Timber.plant(WallpanelDebugTree())
            // Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        strictMode()
        LauncherShortcuts.createShortcuts(this)
    }

    private fun strictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork() // or .detectAll() for all detectable problems
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    /**
     * GeckoView hands a crash report to [GeckoCrashHandlerService] with
     * startForegroundService() on the application context, on the main thread, and does not
     * catch the refusal Android 12 and above give an application that is in the background.
     * Uncaught, that refusal would take down the browser while it is recovering from the crash.
     * Losing the report is harmless, since the browser's recovery does not depend on it.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun startForegroundService(service: Intent): ComponentName? {
        return try {
            super.startForegroundService(service)
        } catch (e: IllegalStateException) {
            if (service.action != GeckoRuntime.ACTION_CRASHED) {
                throw e
            }
            Timber.w(e, "Unable to start the GeckoView crash handler")
            null
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    /**
     * Logged only. The runtime already collects under memory pressure, so forcing a
     * collection here only adds work on the main thread, and throwing for a level this code
     * does not know would crash the application the first time Android sends one.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Timber.i("Memory trim requested, level $level")
    }
}
