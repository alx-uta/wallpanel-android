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

import com.koushikdutta.async.http.body.AsyncHttpRequestBody
import com.koushikdutta.async.http.body.JSONObjectBody
import com.koushikdutta.async.http.body.StringBody
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a command sent over HTTP or MQTT. Only a JSON object is a command; anything else,
 * malformed JSON included, comes back as null for the caller to turn down.
 */
object CommandJson {

    /** The command [text] holds, or null when it is empty or not a JSON object. */
    fun parse(text: String?): JSONObject? {
        if (text.isNullOrBlank()) {
            return null
        }
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * The command in the body of a request to /api/command. A body sent as
     * application/json that does not parse still reaches the handler, with no object in
     * it, so that is null here as well.
     */
    fun fromBody(body: AsyncHttpRequestBody<*>?): JSONObject? {
        return when (body) {
            is JSONObjectBody -> body.get()
            is StringBody -> parse(body.get())
            else -> null
        }
    }
}
