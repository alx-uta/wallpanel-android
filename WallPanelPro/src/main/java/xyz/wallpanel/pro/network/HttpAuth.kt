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

import okio.ByteString.Companion.decodeBase64
import java.security.MessageDigest

/**
 * Checks the access token on a request to the HTTP server.
 *
 * The token is accepted three ways, because the clients differ in what they can send:
 * - `Authorization: Bearer <token>`, for scripts and Home Assistant's REST commands;
 * - HTTP basic authentication with the token as the password and any user name, which is
 *   what a browser asks for and what MJPEG clients such as Home Assistant's `mjpeg`
 *   camera and go2rtc support;
 * - a `token` query parameter, for clients that can only be given a URL.
 */
object HttpAuth {

    const val QUERY_PARAMETER = "token"
    const val REALM = "WallPanel"

    /**
     * Whether a request carrying this Authorization header and token parameter may go
     * through. Every request does while no token is set.
     */
    fun isAuthorized(token: String, authorizationHeader: String?, queryToken: String?): Boolean {
        if (token.isEmpty()) {
            return true
        }
        // Both are compared, rather than stopping at the first one present, so the time
        // taken does not say which of them was there.
        val fromHeader = matches(token, credentialFrom(authorizationHeader))
        val fromQuery = matches(token, queryToken)
        return fromHeader or fromQuery
    }

    /** The token a Bearer or Basic Authorization header carries, or null for any other. */
    fun credentialFrom(header: String?): String? {
        val value = header?.trim().orEmpty()
        val space = value.indexOf(' ')
        if (space <= 0) {
            return null
        }
        val scheme = value.substring(0, space)
        val parameter = value.substring(space + 1).trim()
        return when {
            scheme.equals("Bearer", ignoreCase = true) -> parameter
            scheme.equals("Basic", ignoreCase = true) -> {
                val decoded = parameter.decodeBase64()?.utf8() ?: return null
                // user:password, and the user name cannot hold a colon
                val colon = decoded.indexOf(':')
                if (colon < 0) null else decoded.substring(colon + 1)
            }
            else -> null
        }
    }

    // MessageDigest.isEqual takes the same time however much of the two matches
    private fun matches(token: String, candidate: String?): Boolean {
        if (candidate == null) {
            return false
        }
        return MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }
}
