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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {

    private fun redact(line: String, vararg hidden: String) = LogRedactor.redact(line, hidden.toList())

    @Test
    fun `a known value is hidden wherever it turns up`() {
        assertEquals("connect failed for [hidden] at broker", redact("connect failed for hunter2 at broker", "hunter2"))
    }

    @Test
    fun `a known value is hidden in its encoded forms`() {
        assertEquals("http://a/x/[hidden]/y", redact("http://a/x/p%40ss%20w/y", "p@ss w"))
        assertEquals("said [hidden] once", redact("said a\\\"b once", "a\"b"))
    }

    @Test
    fun `the longest value wins`() {
        assertEquals("[hidden]", redact("hunter2extra", "hunter2", "hunter2extra"))
    }

    @Test
    fun `very short values are left alone`() {
        assertEquals("error 12 at 12:12", redact("error 12 at 12:12", "12", ""))
    }

    @Test
    fun `a web address keeps its host and path and loses the rest`() {
        assertEquals(
            "Url changed to http://ha.local:8123/lovelace/0?[hidden]",
            redact("Url changed to http://ha.local:8123/lovelace/0?kiosk&auth=abc")
        )
        assertEquals("Holding https://a.b/c?[hidden]", redact("Holding https://a.b/c#token=abc"))
    }

    @Test
    fun `a login in a web address is hidden`() {
        assertEquals("tcp://[hidden]@broker:1883", redact("tcp://bob:secret@broker:1883"))
        assertEquals("http://[hidden]@ha.local/a", redact("http://bob:p@ss123@ha.local/a"))
    }

    @Test
    fun `an authorization header is hidden`() {
        assertFalse(redact("Authorization: Bearer abcdef123456").contains("abcdef123456"))
        assertFalse(redact("Authorization: Basic Ym9iOnNlY3JldA==").contains("Ym9iOnNlY3JldA"))
    }

    @Test
    fun `a Home Assistant token is hidden without a name next to it`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJhYmMifQ.c2lnbmF0dXJlLWhlcmU"
        assertEquals("sent [hidden] to it", redact("sent $token to it"))
    }

    @Test
    fun `a named secret is hidden in JSON and in key value pairs`() {
        assertEquals("""{"setting_mqtt_password":[hidden],"x":1}""", redact("""{"setting_mqtt_password":"a b,c","x":1}"""))
        assertEquals("""{"access_token": [hidden]}""", redact("""{"access_token": "abc"}"""))
        assertEquals("password=[hidden]&user=bob", redact("password=abc&user=bob"))
        assertEquals("""{\"token\":[hidden]}""", redact("""{\"token\":\"abc\"}"""))
        assertEquals("Username: [hidden]", redact("Username: bob"))
        assertEquals("""{"pref_settings_code":[hidden]}""", redact("""{"pref_settings_code":"1234"}"""))
        assertEquals("""{"password":[hidden]}""", redact("""{"password":"a\"b"}"""))
    }

    @Test
    fun `ordinary lines are left alone`() {
        val line = "Refused GET /api/state without a valid access token, code 401, Broker: tcp://192.168.8.1:1883"
        assertEquals(line, redact(line))
    }

    @Test
    fun `redacting twice changes nothing more`() {
        val once = redact("""token: abc {"password":"x"} http://u:p@h/a?b=c Bearer abcdef123 hunter2""", "hunter2")
        assertEquals(once, redact(once, "hunter2"))
    }

    @Test
    fun `a command is reduced to its names`() {
        val command = JSONObject("""{"speak":"my pin is 1234","eval":"fetch('x')"}""")
        assertEquals("eval, speak", LogRedactor.commandNames(command))
    }
}
