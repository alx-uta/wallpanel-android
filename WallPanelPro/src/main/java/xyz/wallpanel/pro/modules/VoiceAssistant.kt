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

package xyz.wallpanel.pro.modules

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import okhttp3.Credentials
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.network.AssistPipeline
import xyz.wallpanel.pro.network.HomeAssistantSpeech
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.utils.DashboardLogin

/**
 * Push to talk with Home Assistant's Assist. A tap opens the microphone, the speech goes to
 * Home Assistant, and the reply is shown and played.
 *
 * Home Assistant turns the speech into text itself when its pipeline has a speech-to-text
 * engine; the audio is streamed to it and it decides when the speaker has stopped. When it
 * has none, the device's own speech recogniser listens and only the text is sent. The reply
 * is played from the audio file the pipeline makes, or, when it makes none or the file will
 * not play, spoken by [Host.speak].
 *
 * Everything here runs on the main thread.
 */
class VoiceAssistant(
    private val context: Context,
    private val configuration: Configuration,
    private val host: Host,
) {

    enum class State { IDLE, CONNECTING, LISTENING, THINKING, ANSWERING }

    interface Host {
        /** [text] is what to show with it: the words heard, the reply, or what went wrong. */
        fun onVoiceState(state: State, text: String?)
        fun requestMicrophone(onResult: (Boolean) -> Unit)
        /** Speaks a reply that came without audio. */
        fun speak(text: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    // Changes with each conversation turn, so the callbacks of one that was cancelled are ignored
    private var turn = 0
    private var pipeline: AssistPipeline? = null
    private var recorder: Recorder? = null
    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var reply: String? = null
    private var replyAudio: String? = null
    private var continueConversation = false
    private var conversationId: String? = null
    private var conversationAt = 0L
    private var login: Pair<String, String>? = null

    private val stopListening = Runnable { finishListening() }
    // Android's permission dialog is up, which takes the screen from the dashboard for a moment
    private var askingForMicrophone = false

    /** The microphone button: starts, stops listening early, or cancels an answer. */
    fun toggle() {
        when (state) {
            State.IDLE -> start()
            State.LISTENING -> finishListening()
            else -> cancel()
        }
    }

    fun start() {
        if (state != State.IDLE) {
            return
        }
        val baseUrl = HomeAssistantSpeech.baseUrl(configuration.speechHaUrl, configuration.appLaunchUrl)
        val token = configuration.speechHaToken
        if (baseUrl == null || token.isEmpty()) {
            showResult(context.getString(R.string.voice_not_configured))
            return
        }
        val thisTurn = ++turn
        setState(State.CONNECTING, context.getString(R.string.voice_connecting))
        askingForMicrophone = true
        host.requestMicrophone { granted ->
            askingForMicrophone = false
            if (thisTurn != turn) {
                return@requestMicrophone
            }
            if (granted) {
                connect(thisTurn, baseUrl, token)
            } else {
                showResult(context.getString(R.string.voice_no_microphone))
            }
        }
    }

    /** The dashboard went out of view. Only the microphone permission dialog may cover it. */
    fun onHidden() {
        if (!askingForMicrophone) {
            cancel()
        }
    }

    /** Stops whatever is going on, without a word. */
    fun cancel() {
        turn++
        askingForMicrophone = false
        releaseAll()
        setState(State.IDLE, null)
    }

    private fun connect(thisTurn: Int, baseUrl: String, token: String) {
        reply = null
        replyAudio = null
        continueConversation = false
        val recent = SystemClock.elapsedRealtime() - conversationAt < CONVERSATION_MS
        val listener = PipelineListener(thisTurn)
        login = DashboardLogin(configuration.appLaunchUrl, configuration.browserLoginUsername, configuration.browserLoginPassword)
            .credentialsForUrl(baseUrl)
        pipeline = AssistPipeline(
            baseUrl, token, configuration.voicePipeline,
            if (recent) conversationId else null, listener, login
        ).also { it.open() }
    }

    private inner class PipelineListener(private val thisTurn: Int) : AssistPipeline.Listener {
        private fun post(action: () -> Unit) {
            handler.post {
                if (thisTurn == turn) {
                    action()
                }
            }
        }

        override fun onReady(info: AssistPipeline.PipelineInfo) = post { listen(info) }
        override fun onSpeechEnded() = post { finishListening() }
        override fun onTranscript(text: String) = post { heard(text) }
        override fun onReply(text: String, conversationId: String?, continueConversation: Boolean) = post {
            reply = text
            this@VoiceAssistant.continueConversation = continueConversation
            this@VoiceAssistant.conversationId = conversationId
            conversationAt = SystemClock.elapsedRealtime()
            setState(State.THINKING, text)
        }
        override fun onReplyAudio(url: String) = post { replyAudio = url }
        override fun onFinished() = post { answer() }
        override fun onError(error: AssistPipeline.Error, message: String) = post {
            Timber.w("Assist failed (%s): %s", error, message)
            val text = when (error) {
                AssistPipeline.Error.CONNECTION -> context.getString(R.string.voice_connection_failed)
                AssistPipeline.Error.AUTHENTICATION -> context.getString(R.string.voice_auth_failed)
                AssistPipeline.Error.NOTHING_HEARD -> context.getString(R.string.voice_not_heard)
                else -> context.getString(R.string.voice_failed, message)
            }
            // A reply that was already in is still worth saying; a failure in speaking it is
            // what most likely went wrong
            if (reply.isNullOrEmpty()) {
                reply = null
                replyAudio = null
                continueConversation = false
                stopAudioInput()
                showResult(text)
            }
        }
    }

    private fun listen(info: AssistPipeline.PipelineInfo) {
        if (info.speechToText) {
            val newRecorder = Recorder(pipeline ?: return)
            if (!newRecorder.start()) {
                pipeline?.close()
                showResult(context.getString(R.string.voice_microphone_failed))
                return
            }
            recorder = newRecorder
            pipeline?.runAudio(SAMPLE_RATE)
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
            startRecognizer(info.language)
        } else {
            pipeline?.close()
            showResult(context.getString(R.string.voice_no_speech_to_text))
            return
        }
        setState(State.LISTENING, context.getString(R.string.voice_listening))
        handler.removeCallbacks(stopListening)
        handler.postDelayed(stopListening, MAX_LISTEN_MS)
    }

    private fun heard(text: String) {
        if (state == State.LISTENING || state == State.CONNECTING) {
            stopAudioInput()
        }
        setState(State.THINKING, if (text.isEmpty()) context.getString(R.string.voice_thinking) else "“$text”")
    }

    /** The speaker has stopped, or the time is up, or the button was pressed. */
    private fun finishListening() {
        handler.removeCallbacks(stopListening)
        if (state != State.LISTENING) {
            return
        }
        recorder?.stop()
        recorder = null
        pipeline?.endAudio()
        // The recogniser stops by itself and delivers what it heard
        recognizer?.stopListening()
        setState(State.THINKING, context.getString(R.string.voice_thinking))
    }

    private fun stopAudioInput() {
        handler.removeCallbacks(stopListening)
        recorder?.stop()
        recorder = null
        recognizer?.destroy()
        recognizer = null
    }

    private fun answer() {
        stopAudioInput()
        pipeline = null
        val text = reply
        val audio = replyAudio
        if (text == null) {
            if (state != State.IDLE && !lingering) {
                setState(State.IDLE, null)
            }
            return
        }
        if (audio != null) {
            play(audio, text)
        } else {
            speakInstead(text)
        }
    }

    private fun play(url: String, text: String) {
        setState(State.ANSWERING, text)
        val thisTurn = turn
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        // A file Home Assistant fails to make comes back as an error at once, but the player
        // takes half a minute to give up on it
        val giveUp = Runnable {
            if (thisTurn == turn && player === mediaPlayer) {
                Timber.w("The reply from %s did not load, speaking it instead", url)
                speakInstead(text)
            }
        }
        handler.postDelayed(giveUp, REPLY_LOAD_MS)
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val credentials = login
            if (credentials == null) {
                mediaPlayer.setDataSource(url)
            } else {
                val headers = mapOf("Authorization" to Credentials.basic(credentials.first, credentials.second))
                mediaPlayer.setDataSource(context, Uri.parse(url), headers)
            }
            mediaPlayer.setOnPreparedListener {
                handler.removeCallbacks(giveUp)
                it.start()
            }
            mediaPlayer.setOnCompletionListener {
                if (thisTurn == turn) {
                    answered(text)
                }
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Timber.w("Could not play the reply from %s (%d, %d), speaking it instead", url, what, extra)
                handler.removeCallbacks(giveUp)
                if (thisTurn == turn) {
                    speakInstead(text)
                }
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Timber.w(e, "Could not play the reply from %s, speaking it instead", url)
            handler.removeCallbacks(giveUp)
            speakInstead(text)
        }
    }

    private fun speakInstead(text: String) {
        releasePlayer()
        if (text.isNotEmpty()) {
            host.speak(text)
        }
        showResult(text)
    }

    /** The reply was played. Home Assistant may have asked a question back. */
    private fun answered(text: String) {
        releasePlayer()
        if (continueConversation) {
            setState(State.IDLE, null)
            start()
        } else {
            showResult(text)
        }
    }

    private var lingering = false
    private val endLinger = Runnable {
        lingering = false
        if (state == State.IDLE) {
            host.onVoiceState(State.IDLE, null)
        }
    }

    /** Back to idle, leaving [text] on screen a little while. */
    private fun showResult(text: String) {
        releaseAll()
        state = State.IDLE
        lingering = true
        host.onVoiceState(State.IDLE, text)
        handler.removeCallbacks(endLinger)
        handler.postDelayed(endLinger, RESULT_SHOWN_MS)
    }

    private fun setState(newState: State, text: String?) {
        state = newState
        handler.removeCallbacks(endLinger)
        lingering = false
        host.onVoiceState(newState, text)
    }

    private fun startRecognizer(language: String) {
        val speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Timber.w(e, "Could not create the speech recogniser")
            pipeline?.close()
            showResult(context.getString(R.string.voice_no_speech_to_text))
            return
        }
        recognizer = speechRecognizer
        val thisTurn = turn
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (thisTurn == turn) {
                    handler.removeCallbacks(stopListening)
                    if (state == State.LISTENING) {
                        setState(State.THINKING, context.getString(R.string.voice_thinking))
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (thisTurn == turn && !text.isNullOrBlank() && state == State.LISTENING) {
                    host.onVoiceState(State.LISTENING, "“$text”")
                }
            }

            override fun onResults(results: Bundle?) {
                if (thisTurn != turn) {
                    return
                }
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    pipeline?.close()
                    showResult(context.getString(R.string.voice_not_heard))
                    return
                }
                heard(text)
                pipeline?.runText(text)
            }

            override fun onError(error: Int) {
                if (thisTurn != turn) {
                    return
                }
                Timber.w("The speech recogniser failed (%d)", error)
                pipeline?.close()
                showResult(
                    context.getString(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_not_heard
                            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_microphone_failed
                            else -> R.string.voice_no_speech_to_text
                        }
                    )
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (language.isNotEmpty()) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        speechRecognizer.startListening(intent)
    }

    private fun releasePlayer() {
        player?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try {
                it.release()
            } catch (e: Exception) {
                Timber.w(e, "Could not release the reply player")
            }
        }
        player = null
    }

    private fun releaseAll() {
        stopAudioInput()
        pipeline?.close()
        pipeline = null
        releasePlayer()
    }

    /**
     * Reads the microphone on a thread of its own and streams it to [target], for at most
     * [MAX_LISTEN_MS].
     */
    private class Recorder(private val target: AssistPipeline) {
        @Volatile
        private var running = false

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) {
                return false
            }
            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, CHUNK_BYTES * 4)
                )
            } catch (e: Exception) {
                Timber.w(e, "Could not open the microphone")
                return false
            }
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                return false
            }
            try {
                audioRecord.startRecording()
            } catch (e: IllegalStateException) {
                Timber.w(e, "Could not start recording")
                audioRecord.release()
                return false
            }
            running = true
            Thread({
                val buffer = ByteArray(CHUNK_BYTES)
                try {
                    while (running) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read < 0) {
                            Timber.w("The microphone stopped delivering (%d)", read)
                            break
                        }
                        target.sendAudio(buffer, read)
                    }
                } finally {
                    try {
                        audioRecord.stop()
                    } catch (e: IllegalStateException) {
                        // Never started, or already stopped
                    }
                    audioRecord.release()
                }
            }, "VoiceAssistantMic").start()
            return true
        }

        fun stop() {
            running = false
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        // 40 ms of audio
        private const val CHUNK_BYTES = 1280
        private const val MAX_LISTEN_MS = 15_000L
        private const val RESULT_SHOWN_MS = 6_000L
        private const val REPLY_LOAD_MS = 8_000L
        // Within this, a new question carries on the last conversation
        private const val CONVERSATION_MS = 5 * 60_000L
    }
}
