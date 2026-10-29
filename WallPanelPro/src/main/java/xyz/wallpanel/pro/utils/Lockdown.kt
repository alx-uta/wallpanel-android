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
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import timber.log.Timber
import xyz.wallpanel.pro.DeviceAdmin
import xyz.wallpanel.pro.ui.activities.BrowserActivityNative

/**
 * Lockdown keeps WallPanel's task on screen as a locked task.
 *
 * As an ordinary app that is screen pinning: Android asks to confirm, hides Home and Recents,
 * and holding Back and Recents together unpins. As the device owner, set once with
 * `adb shell dpm set-device-owner`, WallPanel allows its own task, so the lock comes up with no
 * prompt and no way out but the settings, a command or `adb`. The device owner also becomes
 * the home app that cannot be changed, so a reboot lands on the dashboard rather than a
 * launcher, and may reboot the device.
 *
 * Turning the screen off only needs WallPanel to be a device admin, which the user grants
 * from the settings screen. A device owner is always one.
 */
object Lockdown {

    // What the device owner was last told, so a resume that changes nothing does not rewrite
    // the device policy
    private var appliedOwnerPolicy: String? = null

    fun admin(context: Context): ComponentName = ComponentName(context.applicationContext, DeviceAdmin::class.java)

    private fun policyManager(context: Context): DevicePolicyManager? {
        return context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    }

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            policyManager(context)?.isDeviceOwnerApp(context.packageName) == true
        } catch (e: Exception) {
            false
        }
    }

    fun isAdminActive(context: Context): Boolean {
        return try {
            policyManager(context)?.isAdminActive(admin(context)) == true
        } catch (e: Exception) {
            false
        }
    }

    /** Whether any task is locked or pinned, which while WallPanel is in front is its own. */
    fun isLocked(context: Context): Boolean {
        val activityManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            activityManager.isInLockTaskMode
        }
    }

    /** Whether the screen off command can work, see [screenOff]. */
    fun canTurnScreenOff(context: Context): Boolean = isAdminActive(context)

    /** Whether the reboot command can work, see [reboot]. */
    fun canReboot(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDeviceOwner(context)
    }

    /**
     * Brings [activity]'s task in line with [enabled]: locked, or let go. Called while the
     * activity is resumed, since a task can only be locked from its activity in front.
     * Returns the reason the lock could not be taken, or null.
     */
    fun apply(activity: Activity, enabled: Boolean, allowedApps: String): String? {
        if (isDeviceOwner(activity)) {
            applyOwnerPolicy(activity, enabled, parseApps(allowedApps))
        }
        val locked = isLocked(activity)
        return try {
            if (enabled && !locked) {
                Timber.i("Locking the task down")
                activity.startLockTask()
            } else if (!enabled && locked) {
                Timber.i("Letting go of the locked task")
                activity.stopLockTask()
            }
            null
        } catch (e: Exception) {
            // Thrown when the task cannot be locked, such as while another app's task is
            // pinned or the activity is not the one in front
            Timber.w(e, "Could not change lockdown")
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * Lets go of the lock from [activity], which has to be in the locked task. Used on the
     * way out, where the lock would otherwise keep the closing task on screen.
     */
    fun release(activity: Activity) {
        if (!isLocked(activity)) {
            return
        }
        try {
            activity.stopLockTask()
        } catch (e: Exception) {
            Timber.w(e, "Could not let go of the locked task")
        }
    }

    /**
     * Lifts what the device owner set for lockdown, the allowed tasks and the fixed home app,
     * for the application exiting. The next time the browser comes up it sets them again.
     */
    fun liftOwnerPolicy(context: Context) {
        if (isDeviceOwner(context)) {
            applyOwnerPolicy(context, false, emptyList())
        }
    }

    /**
     * Turns the screen off the way the power button does. The lock screen comes up if one is
     * set, and the dashboard shows over it again when the screen is woken.
     */
    fun screenOff(context: Context): Boolean {
        if (!isAdminActive(context)) {
            Timber.w("Not turning the screen off: WallPanel is not a device admin")
            return false
        }
        return try {
            policyManager(context)?.lockNow()
            true
        } catch (e: SecurityException) {
            Timber.w(e, "Not allowed to turn the screen off")
            false
        }
    }

    fun reboot(context: Context): Boolean {
        if (!canReboot(context)) {
            Timber.w("Not rebooting: WallPanel is not the device owner, or Android is older than 7.0")
            return false
        }
        return try {
            Timber.i("Rebooting the device")
            policyManager(context)?.reboot(admin(context))
            true
        } catch (e: Exception) {
            // Refused during a call, among others
            Timber.w(e, "Could not reboot")
            false
        }
    }

    /**
     * Whether [intent] may open while locked down. Android refuses any other app's task then,
     * without a word, unless the device owner allowed it, so the browser says so instead.
     *
     * From Android 11 most apps are hidden from WallPanel, and a link more than one app opens
     * resolves to the system's chooser, so the app it goes to is not always known. Such a
     * link is let through when the device owner allows some app, which may be the one.
     */
    fun mayOpen(context: Context, intent: Intent, allowedApps: String): Boolean {
        if (!isLocked(context)) {
            return true
        }
        val target = intent.`package` ?: intent.component?.packageName ?: try {
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        if (target == context.packageName) {
            return true
        }
        if (!isDeviceOwner(context)) {
            return false
        }
        if (target == null || target == SYSTEM_PACKAGE) {
            return parseApps(allowedApps).isNotEmpty()
        }
        return try {
            policyManager(context)?.isLockTaskPermitted(target) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gives up the device owner, or the device admin, along with everything the owner set:
     * the allowed tasks and the fixed home app. A locked task has to be let go of from its
     * activity first, see [release].
     */
    fun removeAdmin(context: Context): Boolean {
        val policy = policyManager(context) ?: return false
        return try {
            if (isDeviceOwner(context)) {
                liftOwnerPolicy(context)
                @Suppress("DEPRECATION")
                policy.clearDeviceOwnerApp(context.packageName)
                appliedOwnerPolicy = null
            }
            if (isAdminActive(context)) {
                policy.removeActiveAdmin(admin(context))
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Could not give up device admin")
            false
        }
    }

    /**
     * The tasks the device owner allows to lock, WallPanel's and [apps], and WallPanel as the
     * fixed home app, both only while lockdown is on. Taking WallPanel off the list ends a
     * lock that is on, from wherever this is called. From Android 9 a locked task has no Home
     * button unless it is allowed, so it is while there are other apps to come back from.
     */
    private fun applyOwnerPolicy(context: Context, enabled: Boolean, apps: List<String>) {
        val packages = if (enabled) (listOf(context.packageName) + apps).distinct() else emptyList()
        val policyKey = packages.joinToString(",")
        if (policyKey == appliedOwnerPolicy) {
            return
        }
        val policy = policyManager(context) ?: return
        val admin = admin(context)
        try {
            policy.setLockTaskPackages(admin, packages.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val home = if (packages.size > 1) DevicePolicyManager.LOCK_TASK_FEATURE_HOME else 0
                policy.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or home)
            }
            if (enabled) {
                val home = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                policy.addPersistentPreferredActivity(admin, home, ComponentName(context, BrowserActivityNative::class.java))
            } else {
                policy.clearPackagePersistentPreferredActivities(admin, context.packageName)
            }
            appliedOwnerPolicy = policyKey
        } catch (e: Exception) {
            Timber.e(e, "Could not set the lockdown policy")
        }
    }

    /**
     * Package names from the Apps Allowed in Lockdown setting, separated by commas, spaces or
     * new lines. Anything that is not a package name is left out.
     */
    fun parseApps(value: String): List<String> {
        return value.split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { PACKAGE_NAME.matches(it) }
            .distinct()
    }

    // The package of the system's chooser, which a link more than one app opens resolves to
    private const val SYSTEM_PACKAGE = "android"

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}
