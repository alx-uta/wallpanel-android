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
import java.net.URLEncoder

/**
 * Takes secrets out of a line of the log before it reaches the disk or leaves the device.
 *
 * The values of the settings that hold a secret are replaced wherever they appear. Patterns
 * catch what a setting does not hold: tokens in a web address, a web address's login, a
 * password or token named in JSON or in a header, and JSON web tokens, the form of a Home
 * Assistant access token. Every pass leaves its own output as it found it, so a line can be
 * redacted again when it is exported, with the secrets of that moment.
 */
object LogRedactor {

    const val HIDDEN = "[hidden]"

    /** A shorter value would hide ordinary words and numbers without protecting anything. */
    private const val MIN_VALUE_LENGTH = 3

    private val AUTH_HEADER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val JSON_WEB_TOKEN = Regex("""\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}(\.[A-Za-z0-9_-]*)?""")
    private val URL_LOGIN = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s/?#]+@""")
    private val URL_QUERY = Regex("""(?i)\b([a-z][a-z0-9+.-]*://[^\s?#"'<>]*)[?#][^\s"'<>]*""")
    private val NAMED_SECRET = Regex(
        """(?i)((?:password|passwd|pwd|username|secret|token|api[_-]?key|authorization|cookie|settings_code)[\w.-]*\\?["']?\s*[:=]\s*)""" +
            """(?!\[hidden])(\\"(?:(?!\\").)*\\"|"(?:[^"\\]|\\.)*"|'[^']*'|[^\s,;&}\]]+)"""
    )

    fun redact(line: String, hiddenValues: Collection<String>): String {
        var result = line
        hiddenValues.filter { it.length >= MIN_VALUE_LENGTH }.sortedByDescending { it.length }.forEach { value ->
            result = result.replace(value, HIDDEN)
            val encoded = URLEncoder.encode(value, "UTF-8")
            result = result.replace(encoded, HIDDEN).replace(encoded.replace("+", "%20"), HIDDEN)
            result = result.replace(JSONObject.quote(value).removeSurrounding("\""), HIDDEN)
        }
        result = AUTH_HEADER.replace(result) { "${it.groupValues[1]} $HIDDEN" }
        result = JSON_WEB_TOKEN.replace(result, HIDDEN)
        if ("://" in result) {
            result = URL_LOGIN.replace(result) { "${it.groupValues[1]}$HIDDEN@" }
            result = URL_QUERY.replace(result) { "${it.groupValues[1]}?$HIDDEN" }
        }
        return NAMED_SECRET.replace(result) { "${it.groupValues[1]}$HIDDEN" }
    }

    /**
     * What a command asks for, which is what a log needs of it. The values stay out: they
     * hold scripts, spoken text, web addresses and settings.
     */
    fun commandNames(command: JSONObject): String = command.keys().asSequence().toList().sorted().joinToString(", ")
}
