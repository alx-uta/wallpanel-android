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

import java.io.File
import java.io.FileOutputStream

/**
 * The log on disk: a current file and the one before it, each up to [maxBytes]. When the
 * current one is full it replaces the older one, so the log keeps the newest lines and
 * never grows past twice [maxBytes].
 */
class LogFile(private val directory: File, private val maxBytes: Long = MAX_BYTES) {

    private val current = File(directory, CURRENT_NAME)
    private val older = File(directory, OLDER_NAME)

    @Synchronized
    fun append(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        directory.mkdirs()
        if (current.length() + bytes.size > maxBytes) {
            older.delete()
            if (!current.renameTo(older)) {
                current.delete()
            }
        }
        FileOutputStream(current, true).use { it.write(bytes) }
    }

    /** Every line, oldest first. */
    @Synchronized
    fun lines(): List<String> = listOf(older, current).filter { it.exists() }.flatMap { it.readLines(Charsets.UTF_8) }

    companion object {
        private const val MAX_BYTES = 1024L * 1024L
        private const val CURRENT_NAME = "wallpanel.log"
        private const val OLDER_NAME = "wallpanel.log.1"
    }
}
