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

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAssistantSpeechTest {

    private val requests = mutableListOf<Request>()

    /** Answers each path with a canned response, recording what was asked. */
    private fun client(vararg answers: Pair<String, Pair<Int, String>>): OkHttpClient {
        val byPath = answers.toMap()
        return OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val (code, body) = byPath[request.url.encodedPath] ?: (404 to "")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    }

    private fun bodyOf(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    @Test
    fun `base url comes from the dashboard when not set`() {
        assertEquals("http://192.168.8.246:8123",
            HomeAssistantSpeech.baseUrl("", "http://192.168.8.246:8123/lovelace/0"))
        assertEquals("https://ha.example.com",
            HomeAssistantSpeech.baseUrl("", "\nhttps://ha.example.com/dashboard\nhttps://other/"))
    }

    @Test
    fun `a configured base url wins and gets a scheme`() {
        assertEquals("http://ha.local:8123", HomeAssistantSpeech.baseUrl(" ha.local:8123/ ", "http://dash/"))
        assertEquals("https://ha.local", HomeAssistantSpeech.baseUrl("https://ha.local:443", ""))
    }

    @Test
    fun `no address is null`() {
        assertNull(HomeAssistantSpeech.baseUrl("", ""))
        assertNull(HomeAssistantSpeech.baseUrl("", "file:///sdcard/index.html"))
    }

    @Test
    fun `the audio address is built on the base url, not the one home assistant reports`() {
        val speech = HomeAssistantSpeech("http://10.0.0.2:8123", "secret", "tts.google", "en",
            client("/api/tts_get_url" to (200 to """{"url": "http://homeassistant.local:8123/api/tts_proxy/a.mp3", "path": "/api/tts_proxy/a.mp3"}""")))
        assertEquals("http://10.0.0.2:8123/api/tts_proxy/a.mp3", speech.audioUrl("Hello"))
        val request = requests.single()
        assertEquals("Bearer secret", request.header("Authorization"))
        val body = bodyOf(request)
        assertEquals("tts.google", body.getString("engine_id"))
        assertEquals("Hello", body.getString("message"))
        assertEquals("en", body.getString("language"))
    }

    @Test
    fun `without an engine the first one home assistant has is used`() {
        val speech = HomeAssistantSpeech("http://ha", "t", "", "",
            client(
                "/api/states" to (200 to """[{"entity_id": "light.kitchen"}, {"entity_id": "tts.piper"}, {"entity_id": "tts.google"}]"""),
                "/api/tts_get_url" to (200 to """{"path": "/api/tts_proxy/b.mp3"}"""),
            ))
        assertEquals("http://ha/api/tts_proxy/b.mp3", speech.audioUrl("Hi"))
        val body = bodyOf(requests.last())
        assertEquals("tts.google", body.getString("engine_id"))
        assertEquals(false, body.has("language"))
    }

    @Test
    fun `a refusal is null so the device speaks instead`() {
        val speech = HomeAssistantSpeech("http://ha", "bad", "tts.google", "",
            client("/api/tts_get_url" to (401 to "")))
        assertNull(speech.audioUrl("Hi"))
    }
}
