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

package xyz.wallpanel.pro.network

import com.koushikdutta.async.http.body.JSONObjectBody
import com.koushikdutta.async.http.body.StringBody
import com.koushikdutta.async.http.body.UrlEncodedFormBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandJsonTest {

    @Test
    fun `a JSON object is a command`() {
        assertEquals(80, CommandJson.parse("""{"brightness": 80}""")!!.getInt("brightness"))
    }

    @Test
    fun `malformed JSON is no command`() {
        assertNull(CommandJson.parse("""{"brightness": """))
        assertNull(CommandJson.parse("not json"))
        assertNull(CommandJson.parse("{'unclosed"))
    }

    @Test
    fun `JSON that is not an object is no command`() {
        assertNull(CommandJson.parse("[1, 2]"))
        assertNull(CommandJson.parse("42"))
        assertNull(CommandJson.parse("\"reload\""))
    }

    @Test
    fun `an empty payload is no command`() {
        assertNull(CommandJson.parse(null))
        assertNull(CommandJson.parse(""))
        assertNull(CommandJson.parse("  \n"))
    }

    @Test
    fun `a JSON body that did not parse is no command`() {
        // What the HTTP server hands over for application/json it could not read
        assertNull(CommandJson.fromBody(JSONObjectBody()))
    }

    @Test
    fun `a JSON body is read as it is`() {
        val command = JSONObject().put("reload", true)
        assertEquals(command, CommandJson.fromBody(JSONObjectBody(command)))
    }

    @Test
    fun `a text body is parsed`() {
        assertEquals(true, CommandJson.fromBody(StringBody("""{"reload": true}"""))!!.getBoolean("reload"))
        assertNull(CommandJson.fromBody(StringBody("""{"reload": """)))
    }

    @Test
    fun `no body or a form is no command`() {
        assertNull(CommandJson.fromBody(null))
        assertNull(CommandJson.fromBody(UrlEncodedFormBody()))
    }
}
