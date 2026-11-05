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

/**
 * Tells whether the page on view is still the dashboard, for returning to it after the
 * device has been left alone.
 *
 * The launch URL is rarely the address the page ends up on: Home Assistant redirects
 * `http://host:8123` to its default dashboard, and to the login page and back. So every
 * address the page moves through after the dashboard is loaded counts as the dashboard,
 * until someone touches the screen or a command loads a page. Addresses after that are
 * somewhere else.
 */
class DashboardTracker {

    private val dashboardUrls = mutableSetOf<String>()
    private var landing = false

    /** The address on view, as last reported by the browser. */
    var currentUrl: String? = null
        private set

    /** The dashboard is being loaded from its launch URL. */
    fun onDashboardLoad(launchUrl: String) {
        dashboardUrls.clear()
        dashboardUrls += normalize(launchUrl)
        landing = true
    }

    /** The browser reports the address on view, which in-page navigation also changes. */
    fun onLocation(url: String) {
        currentUrl = url
        if (landing) {
            dashboardUrls += normalize(url)
        }
    }

    /** A touch, or a command that loads a page: whatever comes next was asked for. */
    fun onNavigationRequested() {
        landing = false
    }

    /** Whether a page other than the dashboard is on view. */
    fun isAway(): Boolean {
        val url = currentUrl ?: return false
        return dashboardUrls.isNotEmpty() && normalize(url) !in dashboardUrls
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/')
}
