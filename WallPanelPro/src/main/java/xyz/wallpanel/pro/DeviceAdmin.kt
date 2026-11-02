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

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import xyz.wallpanel.pro.network.WallPanelService
import xyz.wallpanel.pro.utils.AppExit

/**
 * WallPanel as a device admin, which lets it turn the screen off, and as the device owner,
 * which [xyz.wallpanel.pro.utils.Lockdown] builds the full lockdown and reboot on. The
 * Screen Off and Reboot entities depend on which it is, so the service is told to look again.
 */
class DeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Timber.i("WallPanel is now a device admin")
        refreshService(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Timber.i("WallPanel is no longer a device admin")
        refreshService(context)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Timber.i("Lock task mode on for $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Timber.i("Lock task mode off")
    }

    /** A running service rebuilds its discovery configs on a start and republishes any change. */
    private fun refreshService(context: Context) {
        if (AppExit.exiting) {
            return
        }
        try {
            context.startService(Intent(context, WallPanelService::class.java))
        } catch (e: IllegalStateException) {
            // Not running and not allowed to start from the background; it reads the new
            // state when it next starts
            Timber.d("The service is not running to republish discovery")
        }
    }
}
