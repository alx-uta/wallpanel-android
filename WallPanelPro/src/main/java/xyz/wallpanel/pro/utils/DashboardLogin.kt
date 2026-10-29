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

import java.net.URI

/**
 * The username and password from the settings, for a dashboard behind HTTP basic
 * authentication. They are only ever given to the servers the dashboard addresses name,
 * so a page that links elsewhere cannot collect them by asking. Where the whole address
 * asking is known, as GeckoView gives it, the scheme and port have to match too, so a
 * password meant for an https dashboard does not go out in the clear to a plain http
 * service on the same machine. The WebView only says which host is asking.
 */
class DashboardLogin(launchUrl: String, private val username: String, private val password: String) {

    private val origins: Set<Origin> = launchUrl.lines().mapNotNull { originOf(it.trim()) }.toSet()

    /** The stored username and password for [host], or null when there are none to give it. */
    fun credentialsFor(host: String?): Pair<String, String>? {
        if (username.isEmpty() || host.isNullOrEmpty()) {
            return null
        }
        val normalized = normalizeHost(host)
        return if (origins.any { it.host == normalized }) username to password else null
    }

    /** As [credentialsFor], for the whole address of the page asking. */
    fun credentialsForUrl(url: String?): Pair<String, String>? {
        if (username.isEmpty() || url.isNullOrEmpty()) {
            return null
        }
        val origin = originOf(url) ?: return null
        return if (origin in origins) username to password else null
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    companion object {
        private val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#@]*@")

        /** The host names of every page in [launchUrl], one per line, in lower case. */
        fun hostsOf(launchUrl: String): Set<String> {
            return launchUrl.lines().mapNotNull { originOf(it.trim())?.host }.toSet()
        }

        private fun originOf(url: String): Origin? {
            if (url.isEmpty()) {
                return null
            }
            val withScheme = if (url.contains("://")) url else "http://$url"
            return try {
                val uri = URI(withScheme)
                val scheme = uri.scheme?.lowercase() ?: return null
                val host = uri.host?.let { normalizeHost(it) } ?: return null
                val port = when {
                    uri.port != -1 -> uri.port
                    scheme == "https" -> 443
                    else -> 80
                }
                Origin(scheme, host, port)
            } catch (e: Exception) {
                null
            }
        }

        private fun normalizeHost(host: String): String = host.lowercase().removePrefix("[").removeSuffix("]")

        /**
         * [url] without a `user:password@` part. The address is published to Home
         * Assistant and written to the log, and neither is a place for a password.
         */
        fun withoutCredentials(url: String): String {
            return url.replaceFirst(USER_INFO, "$1")
        }
    }
}
