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

package xyz.wallpanel.pro.utils

import android.Manifest
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.ui.activities.BrowserActivityNative
import kotlin.system.exitProcess

/**
 * Restarts the application: the browser is booked to come back, then the process ends.
 *
 * From Android 10 an application in the background may not start an activity, so the alarm
 * that used to bring the browser back is rejected with "Abort background activity starts"
 * and the device is left on the launcher. A full screen intent on a high importance
 * notification is the supported way to show an activity from the background, the same
 * mechanism alarm clocks and incoming calls use, so the relaunch is posted that way and the
 * alarm is kept as a second, cheaper attempt that still works when the application is in
 * the foreground.
 */
object AppRestartHelper {

    const val RESTART_CHANNEL_ID = "xyz.wallpanel.pro.RESTART"
    const val RESTART_NOTIFICATION_ID = 1139

    private const val RELAUNCH_REQUEST_CODE = 1139
    private const val FOREGROUND_REQUEST_CODE = 1140
    private const val ALARM_DELAY_MS = 1000L

    /**
     * Books the relaunch and ends the process.
     *
     * @param exitDelayMillis how long to wait before ending the process. Callers reached from
     * a Binder transaction, such as Service.onStartCommand(), need a short delay: exiting
     * before that call returns reads to the system as an incomplete start and redelivers the
     * same command to the relaunched process, observed on-device as three restarts in a row
     * instead of one. Zero exits immediately, which is what a crash handler needs, since the
     * main looper may no longer run anything that is posted to it.
     */
    @JvmStatic
    @JvmOverloads
    fun restartApplication(context: Context, exitDelayMillis: Long = 0L) {
        Timber.i("Restarting the application")
        relaunchBrowser(context)
        if (exitDelayMillis > 0) {
            Handler(Looper.getMainLooper()).postDelayed({ exitProcess(2) }, exitDelayMillis)
        } else {
            exitProcess(2)
        }
    }

    /**
     * Books the browser to come back without ending the calling process. For callers whose
     * browser process has already gone, such as the GeckoView crash handler, which runs in a
     * process of its own.
     */
    @JvmStatic
    fun relaunchBrowser(context: Context) {
        val appContext = context.applicationContext
        val pendingIntent = createRelaunchPendingIntent(appContext)
        postRestartNotification(appContext, pendingIntent)
        scheduleRelaunchAlarm(appContext, pendingIntent)
    }

    /**
     * Brings the browser in front of whatever app is showing, keeping the page it has. With
     * the right to start activities from the background it opens straight away; without
     * it, the same full screen notification a restart uses is posted, which Android opens
     * on a sleeping screen and otherwise shows for a tap.
     */
    @JvmStatic
    fun bringBrowserToFront(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, BrowserActivityNative::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (OverlayPermission.isGranted(appContext)) {
            try {
                appContext.startActivity(intent)
                return
            } catch (e: Exception) {
                Timber.w(e, "Could not bring the browser to the front directly")
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(appContext, FOREGROUND_REQUEST_CODE, intent, flags, creatorOptions())
        postRestartNotification(appContext, pendingIntent,
                R.string.text_foreground_notification_title, R.string.text_foreground_notification_message)
    }

    /**
     * Clears the relaunch notification, which is left in place on purpose so it can be tapped
     * when the full screen intent does not open the browser on its own.
     */
    @JvmStatic
    fun cancelRestartNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext)
                    .cancel(RESTART_NOTIFICATION_ID)
        } catch (e: Exception) {
            Timber.e(e, "Unable to cancel the restart notification")
        }
    }

    private fun createRelaunchPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BrowserActivityNative::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                or Intent.FLAG_ACTIVITY_CLEAR_TASK
                or Intent.FLAG_ACTIVITY_NEW_TASK)
        // A fresh pending intent each time: updating an existing one keeps the options it
        // was created with, so one left from an older version would miss creatorOptions().
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
        return PendingIntent.getActivity(context, RELAUNCH_REQUEST_CODE, intent, flags, creatorOptions())
    }

    /**
     * Lets the relaunch use this application's own right to start an activity from the
     * background, which the "display over other apps" permission gives it. From target SDK 35
     * a pending intent no longer carries that right unless its creator says so.
     */
    private fun creatorOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(mode)
            .toBundle()
    }

    private fun postRestartNotification(
            context: Context,
            pendingIntent: PendingIntent,
            titleRes: Int = R.string.text_restart_notification_title,
            messageRes: Int = R.string.text_restart_notification_message,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (granted != PackageManager.PERMISSION_GRANTED) {
                // The alarm is then the only attempt left, and the restart must still happen.
                Timber.w("No notification permission, the restart notification is not posted")
                return
            }
        }
        try {
            createRestartChannel(context)
            val notification = NotificationCompat.Builder(context, RESTART_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_name)
                    .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
                    .setContentTitle(context.getString(titleRes))
                    .setContentText(context.getString(messageRes))
                    .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setLocalOnly(true)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(pendingIntent)
                    .setFullScreenIntent(pendingIntent, true)
                    .build()
            NotificationManagerCompat.from(context).notify(RESTART_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.e(e, "Unable to post the restart notification")
        }
    }

    private fun createRestartChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
        val channel = NotificationChannel(
                RESTART_CHANNEL_ID,
                context.getString(R.string.text_restart_channel_name),
                NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.text_restart_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun scheduleRelaunchAlarm(context: Context, pendingIntent: PendingIntent) {
        try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            manager[AlarmManager.RTC, System.currentTimeMillis() + ALARM_DELAY_MS] = pendingIntent
        } catch (e: Exception) {
            Timber.e(e, "Unable to schedule the relaunch alarm")
        }
    }
}
