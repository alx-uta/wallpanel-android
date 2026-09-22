/*
 * Copyright (c) 2026 WallPanel
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

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import org.mozilla.geckoview.GeckoRuntime
import timber.log.Timber
import xyz.wallpanel.pro.utils.AppRestartHelper
import xyz.wallpanel.pro.utils.NotificationUtils
import java.io.File

/**
 * Receives GeckoView's native crash reports.
 *
 * Registering a crash handler is what switches on Gecko's own native crash reporter. Without
 * it a crash in a content process (:tabN) falls through to Android's debuggerd, which records
 * it as an application crash and shows the blocking "Application Error" dialog, even though
 * the browser rebuilds the session and the dashboard is back a few seconds later. With it,
 * Gecko catches the signal, writes a minidump, and the process ends without Android
 * treating it as a crash. The browser is then told through ContentDelegate.onCrash and
 * recovers the same way as before.
 *
 * Gecko requires this service to run in its own process (see the manifest), so it is
 * still alive when the process that crashed is not. Reports are not uploaded anywhere,
 * so the minidumps are deleted rather than left to pile up on a device that runs for months.
 */
class GeckoCrashHandlerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // From API 26 Gecko starts this with startForegroundService(), and a service started
        // that way that never calls startForeground() is killed with a crash of its own.
        enterForeground()
        try {
            if (intent?.action == GeckoRuntime.ACTION_CRASHED) {
                handleCrash(intent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling a GeckoView crash report")
        } finally {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        try {
            val notification = NotificationUtils(applicationContext, resources).createNotification(
                getString(R.string.gecko_crash_notification_title),
                getString(R.string.gecko_crash_notification_message)
            )
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.e(e, "Unable to move the GeckoView crash handler to the foreground")
        }
    }

    private fun handleCrash(intent: Intent) {
        val visibility = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY)
        val minidumpPath = intent.getStringExtra(GeckoRuntime.EXTRA_MINIDUMP_PATH)
        Timber.e(
            "GeckoView %s process crashed (type %s, remote type %s), minidump %s",
            visibility,
            intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE),
            intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE),
            minidumpPath
        )
        deleteReports(minidumpPath, intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH))

        // A content process crash is recovered by the browser itself, which gets onCrash and
        // opens a new session. A MAIN crash means the Gecko parent, which runs in the
        // application's own process, went down and took the browser with it.
        if (visibility == GeckoRuntime.CRASHED_PROCESS_VISIBILITY_MAIN) {
            Timber.e("The browser process crashed, reopening the browser")
            AppRestartHelper.relaunchBrowser(this)
        }
    }

    /**
     * Delete this report, and any earlier ones left behind, for instance by a crash whose
     * report never reached this service.
     */
    private fun deleteReports(minidumpPath: String?, extrasPath: String?) {
        listOfNotNull(minidumpPath, extrasPath).forEach { File(it).delete() }
        val reportDir = minidumpPath?.let { File(it).parentFile } ?: return
        reportDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".dmp") || file.name.endsWith(".extra"))
        }?.forEach { it.delete() }
    }

    companion object {
        private const val NOTIFICATION_ID = 1140
    }
}
