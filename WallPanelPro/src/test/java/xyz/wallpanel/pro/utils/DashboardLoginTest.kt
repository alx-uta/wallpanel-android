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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardLoginTest {

    private val login = DashboardLogin(
        "http://ha.local:8123/lovelace/0\n\nhttps://Grafana.example.com/d/abc", "kiosk", " secret "
    )

    @Test
    fun `the dashboard's servers get the login`() {
        assertEquals("kiosk" to " secret ", login.credentialsFor("ha.local"))
        assertEquals("kiosk" to " secret ", login.credentialsFor("grafana.EXAMPLE.com"))
        assertEquals("kiosk" to " secret ", login.credentialsForUrl("https://grafana.example.com/api/x"))
        assertEquals("kiosk" to " secret ", login.credentialsForUrl("http://ha.local:8123"))
        assertEquals("kiosk" to " secret ", login.credentialsForUrl("https://grafana.example.com:443"))
    }

    @Test
    fun `a whole address has to match the scheme and port too`() {
        // A password meant for https is not sent in the clear
        assertNull(login.credentialsForUrl("http://grafana.example.com/"))
        assertNull(login.credentialsForUrl("http://ha.local:8080/"))
        assertNull(login.credentialsForUrl("https://ha.local:8123/"))
    }

    @Test
    fun `an IPv6 host matches with or without brackets`() {
        val v6 = DashboardLogin("http://[fd00::10]:8123/", "u", "p")
        assertEquals("u" to "p", v6.credentialsFor("[fd00::10]"))
        assertEquals("u" to "p", v6.credentialsFor("fd00::10"))
        assertEquals("u" to "p", v6.credentialsForUrl("http://[fd00::10]:8123"))
    }

    @Test
    fun `any other server does not`() {
        assertNull(login.credentialsFor("evil.example.com"))
        assertNull(login.credentialsForUrl("http://ha.local.evil.example.com/"))
        assertNull(login.credentialsFor(null))
        assertNull(login.credentialsFor(""))
    }

    @Test
    fun `no username means nothing is given`() {
        assertNull(DashboardLogin("http://ha.local:8123/", "", "pw").credentialsFor("ha.local"))
    }

    @Test
    fun `an address without a scheme still names its host`() {
        assertEquals(setOf("192.168.1.5"), DashboardLogin.hostsOf("192.168.1.5:8123/lovelace"))
        assertEquals(setOf("::1"), DashboardLogin.hostsOf("http://[::1]:8123/"))
    }

    @Test
    fun `credentials are taken out of an address`() {
        assertEquals("http://ha.local:8123/x?a=b@c", DashboardLogin.withoutCredentials("http://user:pw@ha.local:8123/x?a=b@c"))
        assertEquals("https://ha.local/", DashboardLogin.withoutCredentials("https://user@ha.local/"))
        assertEquals("http://ha.local/x?mail=a@b", DashboardLogin.withoutCredentials("http://ha.local/x?mail=a@b"))
        assertEquals("about:blank", DashboardLogin.withoutCredentials("about:blank"))
    }
}
