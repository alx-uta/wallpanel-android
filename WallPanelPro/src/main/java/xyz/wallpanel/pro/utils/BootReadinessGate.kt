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

import java.util.Locale

/**
 * Decides when the dashboard may be opened after a reboot.
 *
 * A tablet that starts WallPanel on boot can get there before Android has set its clock,
 * which on some devices sits at 1970 for minutes until the network is up. Home Assistant
 * reads its saved tokens against that clock, finds them expired, discards them and shows
 * the login page. So close to boot the first load waits until the clock is past
 * [EARLIEST_VALID_TIME_MS] and a network connection has stayed up for
 * [NETWORK_STABLE_MS]. There is no timeout: loading with a 1970 clock is exactly the failure
 * this avoids, and the wait ends by itself once the network brings the time.
 *
 * Only a connection is required, not a validated one, so a Home Assistant reachable only
 * on the local network still counts.
 *
 * @param wallClock the wall clock, System.currentTimeMillis in the application
 * @param uptime time since boot, SystemClock.elapsedRealtime in the application
 * @param networkConnected whether a network connection is currently up
 */
class BootReadinessGate(
    private val wallClock: () -> Long,
    private val uptime: () -> Long,
    private val networkConnected: () -> Boolean
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
        val since = connectedSince
        return isClockValid() && since != null && now - since >= NETWORK_STABLE_MS
    }

    fun isClockValid(): Boolean = wallClock() >= EARLIEST_VALID_TIME_MS

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
            if (isClockValid()) "set" else "not set (${wallClock()})",
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
         * 2025-01-01T00:00:00Z, before this code was written, so a clock reading earlier
         * than this has not been set since boot.
         */
        const val EARLIEST_VALID_TIME_MS = 1735689600000L
    }
}
