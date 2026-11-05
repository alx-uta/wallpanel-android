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

import androidx.annotation.WorkerThread
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Turns a message into audio with Home Assistant's text to speech, for devices whose own
 * engine is missing or speaks badly. Home Assistant renders the message and hands back the
 * address of the file, which the audio player then plays like any other.
 *
 * See https://www.home-assistant.io/integrations/tts/#rest-api
 */
class HomeAssistantSpeech(
    private val baseUrl: String,
    private val token: String,
    private val engine: String,
    private val language: String,
    private val client: OkHttpClient = defaultClient,
) {

    /**
     * The address of the spoken [message], or null when Home Assistant could not be asked
     * or would not say it. Blocks on the network.
     */
    @WorkerThread
    fun audioUrl(message: String): String? {
        return try {
            val engineId = engine.ifEmpty { firstEngine() }
            if (engineId == null) {
                Timber.w("Home Assistant has no text-to-speech entity to speak with")
                return null
            }
            val body = JSONObject().apply {
                put("engine_id", engineId)
                put("message", message)
                if (language.isNotEmpty()) {
                    put("language", language)
                }
            }
            val request = authorised("$baseUrl/api/tts_get_url")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Timber.w("Home Assistant refused to speak (${response.code}): $text")
                    return null
                }
                resolve(JSONObject(text))
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not reach Home Assistant to speak")
            null
        }
    }

    /**
     * The file's address under [baseUrl]. Home Assistant also returns a full address, built
     * from its own idea of where it lives, which is often one this device cannot reach.
     */
    private fun resolve(response: JSONObject): String? {
        val path = response.optString("path")
        if (path.startsWith("/")) {
            return baseUrl + path
        }
        return response.optString("url").takeIf { it.isNotEmpty() }
    }

    private fun firstEngine(): String? {
        val request = authorised("$baseUrl/api/states").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Home Assistant refused the state list (${response.code})")
                return null
            }
            val states = JSONArray(response.body?.string().orEmpty())
            return (0 until states.length())
                .map { states.getJSONObject(it).optString("entity_id") }
                .sorted()
                .firstOrNull { it.startsWith(ENGINE_DOMAIN) }
        }
    }

    private fun authorised(url: String): Request.Builder {
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val ENGINE_DOMAIN = "tts."

        // Speaking a long message takes the engine a few seconds, a cloud one longer
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Where Home Assistant is: the address in the settings, or else the scheme, host and
         * port of the dashboard, which is nearly always served by Home Assistant itself.
         * Null when neither gives a web address.
         */
        fun baseUrl(configured: String, launchUrl: String): String? {
            val source = configured.trim().ifEmpty {
                launchUrl.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            }
            if (source.isEmpty()) {
                return null
            }
            val withScheme = if (source.contains("://")) source else "http://$source"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            val defaultPort = (url.scheme == "http" && url.port == 80) || (url.scheme == "https" && url.port == 443)
            val port = if (defaultPort) "" else ":${url.port}"
            val host = if (url.host.contains(':')) "[${url.host}]" else url.host
            return "${url.scheme}://$host$port"
        }
    }
}
