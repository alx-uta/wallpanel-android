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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber
import java.util.Locale

/**
 * Decides when the dashboard may be opened after a reboot.
 *
 * A tablet that starts WallPanel on boot can get there before Android has set its clock,
 * which on some devices sits at 1970 for minutes until the network is up. Home Assistant
 * reads its saved tokens against that clock, finds them expired, discards them and shows
 * the login page. So close to boot the first load waits until the clock is believable and
 * a network connection has stayed up for [NETWORK_STABLE_MS]. Waiting for the clock is not
 * on a timer, since loading with a 1970 clock is exactly the failure this avoids, and the
 * wait ends by itself once the network brings the time. It does give up after
 * [CLOCK_WAIT_LIMIT_MS] of unbroken network, by which point Android has had every chance to
 * set the clock and the bound itself is the more likely thing to be wrong. A kiosk showing a
 * login page beats one showing a spinner for good.
 *
 * Only a connection is required, not a validated one, so a Home Assistant reachable only
 * on the local network still counts.
 *
 * @param wallClock the wall clock, System.currentTimeMillis in the application
 * @param uptime time since boot, SystemClock.elapsedRealtime in the application
 * @param networkConnected whether a network connection is currently up
 * @param earliestValidTime the clock cannot legitimately read earlier than this, see
 * [earliestValidTimeOf]
 */
class BootReadinessGate(
    private val wallClock: () -> Long,
    private val uptime: () -> Long,
    private val networkConnected: () -> Boolean,
    private val earliestValidTime: Long
) {

    private var connectedSince: Long? = null

    /**
     * Whether a launch now has to wait. Outside [BOOT_WINDOW_MS] the clock has had ample
     * time to be set, and a launch URL that never goes over the network has nothing to lose.
     */
    fun appliesTo(launchUrls: String): Boolean {
        return uptime() < BOOT_WINDOW_MS && launchUrls.lines().any { isNetworkUrl(it) }
    }

    /**
     * Whether the dashboard may be loaded now. Called repeatedly while waiting, since each call
     * also tracks how long the network has been up.
     */
    fun isReady(): Boolean {
        val now = uptime()
        if (networkConnected()) {
            if (connectedSince == null) {
                connectedSince = now
            }
        } else {
            connectedSince = null
        }
        val connectedFor = connectedSince?.let { now - it } ?: return false
        if (connectedFor < NETWORK_STABLE_MS) {
            return false
        }
        return isClockValid() || connectedFor >= CLOCK_WAIT_LIMIT_MS
    }

    fun isClockValid(): Boolean = wallClock() >= earliestValidTime

    /**
     * What is still missing, for the log.
     */
    fun describe(): String {
        val since = connectedSince
        val network = if (since == null) "down" else "up for ${(uptime() - since) / 1000} s"
        return String.format(
            Locale.US,
            "%d s after boot, clock %s, network %s",
            uptime() / 1000,
            if (isClockValid()) "set" else "not set (${wallClock()}, before ${earliestValidTime})",
            network
        )
    }

    private fun isNetworkUrl(url: String): Boolean {
        val trimmed = url.trim().lowercase(Locale.US)
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    companion object {
        /** How long after boot a launch is still treated as a boot launch */
        const val BOOT_WINDOW_MS = 10 * 60 * 1000L

        /** How long a connection has to stay up before it is trusted */
        const val NETWORK_STABLE_MS = 5000L

        /**
         * How long to wait for the clock, with the network up the whole time, before loading
         * anyway. A clock that is merely late is set within a couple of minutes of the
         * network arriving; one that is still wrong after this is more likely to mean a
         * bound that cannot be met, such as an application installed while the clock was
         * running ahead.
         */
        const val CLOCK_WAIT_LIMIT_MS = 10 * 60 * 1000L

        /**
         * The earliest time the clock can honestly read: when this application was installed
         * or last updated, which Android records for every package. It needs no maintenance
         * and tightens with each update the user installs, unlike a date written into the
         * source. Where that is missing, the date the system image was built is a weaker
         * bound, and a device with neither is left with the network check alone.
         *
         * A device whose clock was already wrong when the application was installed carries
         * that wrong time as its bound, which only makes the check weaker, never stricter:
         * the wait still ends.
         */
        @JvmStatic
        fun earliestValidTimeOf(context: Context): Long {
            val installed = try {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.e(e, "Unable to read the install time")
                0L
            }
            return if (installed > 0L) installed else Build.TIME
        }
    }
}
