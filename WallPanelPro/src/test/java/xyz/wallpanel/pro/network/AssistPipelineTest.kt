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

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays Home Assistant's side of the websocket conversation, as recorded from a real
 * Home Assistant 2026.8.
 */
class AssistPipelineTest {

    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<Any>()
    private var serverSocket: WebSocket? = null
    private val events = LinkedBlockingQueue<String>()
    private val done = CountDownLatch(1)
    private var pipeline: AssistPipeline? = null

    private val homeAssistant = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket = webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.8.1"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.put(JSONObject(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            received.put(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private val listener = object : AssistPipeline.Listener {
        override fun onReady(info: AssistPipeline.PipelineInfo) {
            events.put("ready ${info.id} ${info.language} stt=${info.speechToText} tts=${info.textToSpeech}")
        }
        override fun onListening() { events.put("listening") }
        override fun onSpeechEnded() { events.put("speech ended") }
        override fun onTranscript(text: String) { events.put("heard $text") }
        override fun onReply(text: String, conversationId: String?, continueConversation: Boolean) {
            events.put("reply $text $conversationId $continueConversation")
        }
        override fun onReplyAudio(url: String) { events.put("audio $url") }
        override fun onFinished() {
            events.put("finished")
            done.countDown()
        }
        override fun onError(error: AssistPipeline.Error, message: String) { events.put("error $error $message") }
    }

    @Before
    fun setUp() {
        server.enqueue(MockResponse().withWebSocketUpgrade(homeAssistant))
        server.start()
    }

    @After
    fun tearDown() {
        pipeline?.close()
        serverSocket?.close(1000, null)
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun open(pipelineName: String = "", conversationId: String? = null): AssistPipeline {
        return AssistPipeline(baseUrl(), "token", pipelineName, conversationId, listener).also {
            pipeline = it
            it.open()
        }
    }

    private fun nextJson(): JSONObject = received.poll(5, TimeUnit.SECONDS) as JSONObject
    private fun nextBytes(): ByteString = received.poll(5, TimeUnit.SECONDS) as ByteString
    private fun nextEvent(): String? = events.poll(5, TimeUnit.SECONDS)

    private fun authenticate(stt: Boolean, tts: Boolean = true) {
        assertEquals("auth", nextJson().getString("type"))
        serverSocket!!.send("""{"type":"auth_ok","ha_version":"2026.8.1"}""")
        val list = nextJson()
        assertEquals("assist_pipeline/pipeline/list", list.getString("type"))
        val sttEngine = if (stt) "\"stt.whisper\"" else "null"
        val ttsEngine = if (tts) "\"cloud\"" else "null"
        serverSocket!!.send(
            """{"id":${list.getInt("id")},"type":"result","success":true,"result":{"pipelines":[
              {"id":"other","name":"Other","language":"de","stt_engine":null,"tts_engine":null},
              {"id":"p1","name":"Home Assistant","language":"en","stt_engine":$sttEngine,"stt_language":${if (stt) "\"en-GB\"" else "null"},"tts_engine":$ttsEngine}
            ],"preferred_pipeline":"p1"}}"""
        )
    }

    private fun event(id: Int, type: String, data: String = "null") {
        serverSocket!!.send("""{"id":$id,"type":"event","event":{"type":"$type","data":$data}}""")
    }

    @Test
    fun `speech is streamed to Home Assistant and the reply comes back`() {
        val assist = open()
        authenticate(stt = true)
        assertEquals("ready p1 en-GB stt=true tts=true", nextEvent())

        // Audio sent before the run has started is held, then sent behind the handler id
        assist.runAudio(16000)
        assist.sendAudio(byteArrayOf(1, 2, 3), 3)
        val run = nextJson()
        assertEquals("assist_pipeline/run", run.getString("type"))
        assertEquals("stt", run.getString("start_stage"))
        assertEquals("tts", run.getString("end_stage"))
        assertEquals("p1", run.getString("pipeline"))
        assertEquals(16000, run.getJSONObject("input").getInt("sample_rate"))
        val id = run.getInt("id")
        serverSocket!!.send("""{"id":$id,"type":"result","success":true,"result":null}""")
        event(id, "run-start", """{"pipeline":"p1","runner_data":{"stt_binary_handler_id":7,"timeout":300}}""")
        assertEquals("listening", nextEvent())
        assertEquals(ByteString.of(7, 1, 2, 3), nextBytes())

        assist.sendAudio(byteArrayOf(4, 5), 2)
        assertEquals(ByteString.of(7, 4, 5), nextBytes())

        event(id, "stt-vad-end", """{"timestamp":1200}""")
        assertEquals("speech ended", nextEvent())
        assist.endAudio()
        assertEquals(ByteString.of(7), nextBytes())
        // Nothing more goes once the audio has ended
        assist.sendAudio(byteArrayOf(9), 1)

        event(id, "stt-end", """{"stt_output":{"text":"turn on the kitchen light"}}""")
        event(id, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Turned on the light"}}},"conversation_id":"c1","continue_conversation":false}}""")
        event(id, "tts-end", """{"tts_output":{"url":"/api/tts_proxy/abc.mp3","mime_type":"audio/mpeg"}}""")
        event(id, "run-end")
        assertEquals("heard turn on the kitchen light", nextEvent())
        assertEquals("reply Turned on the light c1 false", nextEvent())
        assertEquals("audio ${baseUrl()}/api/tts_proxy/abc.mp3", nextEvent())
        assertEquals("finished", nextEvent())
        assertTrue(received.poll(200, TimeUnit.MILLISECONDS) == null)
    }

    @Test
    fun `text recognised on the device runs from the intent stage`() {
        val assist = open(pipelineName = "home assistant", conversationId = "c0")
        authenticate(stt = false, tts = false)
        assertEquals("ready p1 en stt=false tts=false", nextEvent())
        assist.runText("what time is it")
        val run = nextJson()
        assertEquals("intent", run.getString("start_stage"))
        assertEquals("intent", run.getString("end_stage"))
        assertEquals("what time is it", run.getJSONObject("input").getString("text"))
        assertEquals("c0", run.getString("conversation_id"))
        val id = run.getInt("id")
        event(id, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"8:42 PM"}}},"conversation_id":"c0","continue_conversation":true}}""")
        event(id, "run-end")
        assertEquals("reply 8:42 PM c0 true", nextEvent())
        assertEquals("finished", nextEvent())
    }

    @Test
    fun `nothing heard is its own error`() {
        val assist = open()
        authenticate(stt = true)
        nextEvent()
        assist.runAudio(16000)
        val id = nextJson().getInt("id")
        event(id, "error", """{"code":"stt-no-text-recognized","message":"No text recognized"}""")
        assertEquals("error NOTHING_HEARD No text recognized", nextEvent())
        assertEquals("finished", nextEvent())
    }

    @Test
    fun `a refused token is reported`() {
        open()
        assertEquals("auth", nextJson().getString("type"))
        serverSocket!!.send("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
        assertEquals("error AUTHENTICATION Invalid access token or password", nextEvent())
        assertEquals("finished", nextEvent())
    }

    @Test
    fun `an unknown pipeline is reported`() {
        open(pipelineName = "nope")
        authenticate(stt = true)
        assertEquals("error PIPELINE No assistant called nope", nextEvent())
        assertEquals("finished", nextEvent())
    }

    @Test
    fun `a refused run is reported`() {
        val assist = open()
        authenticate(stt = true)
        nextEvent()
        assist.runAudio(16000)
        val id = nextJson().getInt("id")
        serverSocket!!.send("""{"id":$id,"type":"result","success":false,"error":{"code":"validation-error","message":"the pipeline does not support speech-to-text"}}""")
        assertEquals("error RUN the pipeline does not support speech-to-text", nextEvent())
        assertEquals("finished", nextEvent())
    }

    @Test
    fun `a closed connection is reported once`() {
        open()
        nextJson()
        serverSocket!!.close(1001, "going away")
        assertTrue(nextEvent()!!.startsWith("error CONNECTION"))
        assertEquals("finished", nextEvent())
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `websocket addresses`() {
        assertEquals("ws://ha.local:8123/api/websocket", AssistPipeline.websocketUrl("http://ha.local:8123"))
        assertEquals("wss://ha.example.com/api/websocket", AssistPipeline.websocketUrl("https://ha.example.com/"))
        assertNull(AssistPipeline.websocketUrl("ftp://ha.local"))
    }
}
