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

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * One turn of a conversation with Home Assistant's Assist, over its websocket API: speech or
 * text goes in, the reply comes back as text and, when the pipeline speaks, as the address
 * of an audio file.
 *
 * [open] connects and looks up the pipeline, then [Listener.onReady] says whether Home
 * Assistant can turn speech into text itself. If it can, [runAudio] starts the run and
 * [sendAudio] streams 16-bit mono PCM until Home Assistant hears the speaker stop, or
 * [endAudio] is called. If it cannot, [runText] runs it from text recognised elsewhere.
 *
 * Listener calls come on OkHttp's thread. Each instance is good for one run.
 *
 * See https://developers.home-assistant.io/docs/voice/pipelines/
 */
class AssistPipeline(
    private val baseUrl: String,
    private val token: String,
    private val pipeline: String,
    private val conversationId: String?,
    private val listener: Listener,
    // For a Home Assistant behind a proxy that asks for the dashboard's login
    private val login: Pair<String, String>? = null,
    private val client: OkHttpClient = defaultClient,
) {

    data class PipelineInfo(
        val id: String,
        val language: String,
        val speechToText: Boolean,
        val textToSpeech: Boolean,
    )

    interface Listener {
        fun onReady(info: PipelineInfo)
        /** Home Assistant takes audio from here on. */
        fun onListening() {}
        /** Home Assistant heard the speaker stop. */
        fun onSpeechEnded() {}
        fun onTranscript(text: String) {}
        fun onReply(text: String, conversationId: String?, continueConversation: Boolean) {}
        fun onReplyAudio(url: String) {}
        /** The run is over, after an error too. */
        fun onFinished()
        fun onError(error: Error, message: String)
    }

    enum class Error {
        CONNECTION,
        AUTHENTICATION,
        PIPELINE,
        /** Home Assistant heard nothing it could make words of. */
        NOTHING_HEARD,
        RUN,
    }

    private var socket: WebSocket? = null
    private var info: PipelineInfo? = null
    private var nextId = 1
    private var pipelineRequest = 0
    private var runRequest = 0
    // Home Assistant's prefix for audio frames, once the run has started
    private var audioHandler: Int? = null
    // Audio sent before the run was ready for it
    private val pendingAudio = mutableListOf<ByteString>()
    private var pendingAudioBytes = 0
    private var audioEnded = false
    private var finished = false

    fun open() {
        val url = websocketUrl(baseUrl)
        if (url == null) {
            fail(Error.CONNECTION, "Not a web address: $baseUrl")
            return
        }
        val request = Request.Builder().url(url)
        login?.let { (username, password) -> request.header("Authorization", Credentials.basic(username, password)) }
        socket = client.newWebSocket(request.build(), socketListener)
    }

    fun runAudio(sampleRate: Int) {
        run(JSONObject().put("start_stage", "stt").put("input", JSONObject().put("sample_rate", sampleRate)))
    }

    fun runText(text: String) {
        run(JSONObject().put("start_stage", "intent").put("input", JSONObject().put("text", text)))
    }

    @Synchronized
    fun sendAudio(pcm: ByteArray, length: Int) {
        if (finished || audioEnded || length <= 0) {
            return
        }
        val chunk = pcm.toByteString(0, length)
        val handler = audioHandler
        if (handler == null) {
            if (pendingAudioBytes + length <= MAX_PENDING_AUDIO_BYTES) {
                pendingAudio += chunk
                pendingAudioBytes += length
            }
            return
        }
        socket?.send(frame(handler, chunk))
    }

    /** No more audio: the speaker stopped, or ran out of time. */
    @Synchronized
    fun endAudio() {
        if (audioEnded || finished) {
            return
        }
        audioEnded = true
        audioHandler?.let { socket?.send(frame(it, ByteString.EMPTY)) }
    }

    /** Stops the run without telling the listener anything more. */
    @Synchronized
    fun close() {
        finished = true
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    @Synchronized
    private fun run(options: JSONObject) {
        val pipelineInfo = info ?: return
        if (finished) {
            return
        }
        runRequest = nextId++
        options.put("id", runRequest)
            .put("type", "assist_pipeline/run")
            .put("end_stage", if (pipelineInfo.textToSpeech) "tts" else "intent")
            .put("pipeline", pipelineInfo.id)
        conversationId?.let { options.put("conversation_id", it) }
        socket?.send(options.toString())
    }

    private val socketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(JSONObject(text))
            } catch (e: Exception) {
                Timber.w(e, "Unexpected message from Assist: $text")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w(t, "Lost the connection to Home Assistant's Assist")
            fail(Error.CONNECTION, t.message ?: t.javaClass.simpleName)
        }

        // Home Assistant going away, a restart for instance
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            fail(Error.CONNECTION, "Home Assistant closed the connection ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(Error.CONNECTION, "Home Assistant closed the connection ($code)")
        }
    }

    @Synchronized
    private fun handle(message: JSONObject) {
        if (finished) {
            return
        }
        when (message.optString("type")) {
            "auth_required" -> socket?.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            "auth_ok" -> requestPipeline()
            "auth_invalid" -> fail(Error.AUTHENTICATION, message.optString("message"))
            "result" -> handleResult(message)
            "event" -> if (message.optInt("id") == runRequest) {
                message.optJSONObject("event")?.let { handleEvent(it) }
            }
        }
    }

    private fun requestPipeline() {
        pipelineRequest = nextId++
        val request = JSONObject().put("id", pipelineRequest).put("type", "assist_pipeline/pipeline/list")
        socket?.send(request.toString())
    }

    private fun handleResult(message: JSONObject) {
        val id = message.optInt("id")
        if (!message.optBoolean("success")) {
            val error = message.optJSONObject("error")
            val text = error?.optString("message").orEmpty().ifEmpty { error?.optString("code").orEmpty() }
            fail(if (id == pipelineRequest) Error.PIPELINE else Error.RUN, text)
            return
        }
        if (id != pipelineRequest) {
            return
        }
        val result = message.optJSONObject("result") ?: JSONObject()
        val chosen = choosePipeline(result, pipeline)
        if (chosen == null) {
            fail(Error.PIPELINE, if (pipeline.isEmpty()) "Home Assistant has no assistant" else "No assistant called $pipeline")
            return
        }
        val pipelineInfo = PipelineInfo(
            id = chosen.optString("id"),
            language = chosen.optNullableString("stt_language") ?: chosen.optString("language"),
            speechToText = chosen.optNullableString("stt_engine") != null,
            textToSpeech = chosen.optNullableString("tts_engine") != null,
        )
        info = pipelineInfo
        listener.onReady(pipelineInfo)
    }

    private fun handleEvent(event: JSONObject) {
        val data = event.optJSONObject("data") ?: JSONObject()
        when (event.optString("type")) {
            "run-start" -> {
                val runner = data.optJSONObject("runner_data")
                if (runner != null && !runner.isNull("stt_binary_handler_id")) {
                    val handler = runner.optInt("stt_binary_handler_id")
                    audioHandler = handler
                    pendingAudio.forEach { socket?.send(frame(handler, it)) }
                    pendingAudio.clear()
                    pendingAudioBytes = 0
                    if (audioEnded) {
                        socket?.send(frame(handler, ByteString.EMPTY))
                    }
                    listener.onListening()
                }
            }
            "stt-vad-end" -> listener.onSpeechEnded()
            "stt-end" -> {
                val text = data.optJSONObject("stt_output")?.optString("text").orEmpty()
                listener.onTranscript(text)
            }
            "intent-end" -> {
                val output = data.optJSONObject("intent_output") ?: JSONObject()
                val speech = output.optJSONObject("response")?.optJSONObject("speech")
                    ?.optJSONObject("plain")?.optString("speech").orEmpty()
                listener.onReply(
                    speech,
                    output.optNullableString("conversation_id"),
                    output.optBoolean("continue_conversation")
                )
            }
            "tts-end" -> {
                data.optJSONObject("tts_output")?.let { audioAddress(it) }?.let { listener.onReplyAudio(it) }
            }
            "error" -> {
                val code = data.optString("code")
                val error = if (code in NOTHING_HEARD_CODES) Error.NOTHING_HEARD else Error.RUN
                fail(error, data.optString("message").ifEmpty { code })
            }
            "run-end" -> finish()
        }
    }

    /** Under [baseUrl], for the same reason as [HomeAssistantSpeech]. */
    private fun audioAddress(output: JSONObject): String? {
        val url = output.optString("url")
        return when {
            url.startsWith("/") -> baseUrl + url
            url.isNotEmpty() -> url
            else -> null
        }
    }

    @Synchronized
    private fun fail(error: Error, message: String) {
        if (finished) {
            return
        }
        listener.onError(error, message)
        finish()
    }

    @Synchronized
    private fun finish() {
        if (finished) {
            return
        }
        finished = true
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        listener.onFinished()
    }

    private fun frame(handler: Int, audio: ByteString): ByteString {
        val bytes = ByteArray(audio.size + 1)
        bytes[0] = handler.toByte()
        audio.toByteArray().copyInto(bytes, 1)
        return bytes.toByteString()
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        // About five seconds of 16 kHz audio, while the run starts
        private const val MAX_PENDING_AUDIO_BYTES = 160_000
        private val NOTHING_HEARD_CODES = setOf("stt-no-text-recognized", "stt-stream-failed")

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        /** `ws://host:port/api/websocket` for an `http://host:port` [baseUrl]. */
        fun websocketUrl(baseUrl: String): String? {
            val scheme = when {
                baseUrl.startsWith("https://", ignoreCase = true) -> "wss://"
                baseUrl.startsWith("http://", ignoreCase = true) -> "ws://"
                else -> return null
            }
            return scheme + baseUrl.substringAfter("://").trimEnd('/') + "/api/websocket"
        }

        /**
         * The pipeline asked for, by id or by name, or Home Assistant's preferred one when
         * [wanted] is empty. Null when there is no such pipeline.
         */
        fun choosePipeline(result: JSONObject, wanted: String): JSONObject? {
            val pipelines = result.optJSONArray("pipelines") ?: return null
            val all = (0 until pipelines.length()).mapNotNull { pipelines.optJSONObject(it) }
            if (wanted.isEmpty()) {
                val preferred = result.optString("preferred_pipeline")
                return all.firstOrNull { it.optString("id") == preferred } ?: all.firstOrNull()
            }
            return all.firstOrNull { it.optString("id") == wanted }
                ?: all.firstOrNull { it.optString("name").equals(wanted, ignoreCase = true) }
        }

        private fun JSONObject.optNullableString(name: String): String? {
            return if (isNull(name)) null else optString(name).ifEmpty { null }
        }
    }
}
