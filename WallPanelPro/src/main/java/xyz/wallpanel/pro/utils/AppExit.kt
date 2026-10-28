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

package xyz.wallpanel.pro.utils

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import timber.log.Timber
import xyz.wallpanel.pro.network.WallPanelService
import kotlin.system.exitProcess

/**
 * Leaves the application: the browser and the settings close, the service stops, so MQTT,
 * the HTTP server and the camera go with it, and the process ends. Nothing brings it back
 * until it is opened again, the device restarts with start on boot set, or a scheduled task
 * fires.
 *
 * When WallPanel is the device's home app, Android would open it again straight away, so
 * the system's home app setting is opened instead for another one to be picked.
 */
object AppExit {

    const val BROADCAST_EXIT = "xyz.wallpanel.pro.BROADCAST_EXIT"

    // Long enough for the service to be destroyed, which gives back the camera and the locks,
    // before the process ends.
    private const val EXIT_DELAY_MS = 1500L

    /**
     * Set once an exit has started. The screens that are closing start the service again
     * from their lifecycle callbacks, and a service started at that point would be brought
     * back by the system after the process ends.
     */
    @Volatile
    var exiting = false
        private set

    /** Must be called on the main thread. [activity] is the screen asking, if any. */
    fun exit(context: Context, activity: Activity? = null) {
        if (exiting) {
            return
        }
        exiting = true
        Timber.i("Exiting the application")
        val appContext = context.applicationContext
        AppRestartHelper.cancelRestartNotification(appContext)
        if (isDefaultHome(appContext)) {
            openHomeSettings(activity ?: appContext)
        }
        // The browser closes itself and every screen above it
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(Intent(BROADCAST_EXIT))
        activity?.finishAffinity()
        appContext.stopService(Intent(appContext, WallPanelService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, EXIT_DELAY_MS)
    }

    /**
     * From Android 11 the other launchers are hidden from this application, so resolving the
     * home intent finds WallPanel alone and reads as it being the home app. The home role
     * says which app really is, from Android 10.
     */
    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            return try {
                roles?.isRoleHeld(RoleManager.ROLE_HOME) == true
            } catch (e: Exception) {
                false
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            null
        }
        return resolved?.activityInfo?.packageName == context.packageName
    }

    private fun openHomeSettings(context: Context) {
        for (action in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("No screen for $action")
            } catch (e: SecurityException) {
                Timber.w(e, "Not allowed to open $action")
            }
        }
    }
}
