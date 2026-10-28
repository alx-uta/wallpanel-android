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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTrackerTest {

    private val launchUrl = "http://ha.local:8123"

    @Test
    fun `nothing is away before a page has reported where it is`() {
        val tracker = DashboardTracker()
        assertFalse(tracker.isAway())
        tracker.onDashboardLoad(launchUrl)
        assertFalse(tracker.isAway())
    }

    @Test
    fun `redirects while the dashboard loads count as the dashboard`() {
        val tracker = DashboardTracker()
        tracker.onDashboardLoad(launchUrl)
        tracker.onLocation("http://ha.local:8123/auth/authorize?client_id=x")
        tracker.onLocation("http://ha.local:8123/lovelace/0")
        assertFalse(tracker.isAway())
    }

    @Test
    fun `a page reached after a touch is away`() {
        val tracker = DashboardTracker()
        tracker.onDashboardLoad(launchUrl)
        tracker.onLocation("http://ha.local:8123/lovelace/0")
        tracker.onNavigationRequested()
        tracker.onLocation("http://ha.local:8123/lovelace/lights")
        assertTrue(tracker.isAway())
        // Coming back to it by hand is the dashboard again
        tracker.onLocation("http://ha.local:8123/lovelace/0")
        assertFalse(tracker.isAway())
    }

    @Test
    fun `a trailing slash is the same address`() {
        val tracker = DashboardTracker()
        tracker.onDashboardLoad("http://ha.local:8123/")
        tracker.onNavigationRequested()
        tracker.onLocation("http://ha.local:8123")
        assertFalse(tracker.isAway())
    }

    @Test
    fun `loading the dashboard again forgets the addresses of the last one`() {
        val tracker = DashboardTracker()
        tracker.onDashboardLoad(launchUrl)
        tracker.onLocation("http://ha.local:8123/lovelace/0")
        tracker.onNavigationRequested()
        tracker.onDashboardLoad("http://other.local/")
        tracker.onNavigationRequested()
        tracker.onLocation("http://ha.local:8123/lovelace/0")
        assertTrue(tracker.isAway())
    }
}
