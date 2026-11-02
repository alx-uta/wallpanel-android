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

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps info, warnings and errors in a [LogFile], in every build, so a device that stopped
 * working days ago can still say what happened.
 *
 * Logging only puts the line on a queue, and a thread of its own redacts it and writes it
 * in batches, so the callers never wait for the disk. A full queue loses lines rather than
 * blocking, and says so in the log. A line that keeps repeating is written once and counted,
 * so it does not push the history out of the file.
 *
 * Between batches the thread adds a [heartbeat] line every hour, which shows how memory went
 * over the days, and by its absence when the application stopped running.
 */
class LogFileTree(
    private val file: LogFile,
    private val hiddenValues: () -> Collection<String>,
    private val heartbeat: () -> String,
) : Timber.Tree() {

    private class Entry(val time: Long, val priority: Int, val tag: String, val message: String)

    private val queue = LinkedBlockingQueue<Entry>(QUEUE_SIZE)
    private val queued = Semaphore(0)
    private val lost = AtomicInteger()
    private val writeLock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private var lastLine = ""
    private var lastRepeat = 0L
    private var repeats = 0

    fun start() {
        Thread({ run() }, "LogFileTree").apply { isDaemon = true }.start()
    }

    override fun isLoggable(tag: String?, priority: Int) = priority >= Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (queue.offer(Entry(System.currentTimeMillis(), priority, tag ?: caller(), message))) {
            queued.release()
        } else {
            lost.incrementAndGet()
        }
    }

    /** Writes everything logged so far, from any thread. A full disk throws. */
    fun flush() {
        write(endRepeats = true)
    }

    private fun run() {
        var nextHeartbeat = System.currentTimeMillis() + HEARTBEAT_MS
        while (true) {
            try {
                val wait = (nextHeartbeat - System.currentTimeMillis()).coerceAtLeast(0)
                if (queued.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
                    queued.drainPermits()
                    write()
                    // Lines logged meanwhile go out together, in one write
                    Thread.sleep(BATCH_MS)
                }
                if (System.currentTimeMillis() >= nextHeartbeat) {
                    nextHeartbeat = System.currentTimeMillis() + HEARTBEAT_MS
                    log(Log.INFO, TAG, heartbeat(), null)
                }
            } catch (e: Throwable) {
                // Logging it would only come back here. The next batch tries again.
            }
        }
    }

    private fun write(endRepeats: Boolean = false) {
        synchronized(writeLock) {
            val hidden = hiddenValues()
            val text = StringBuilder()
            generateSequence { queue.poll() }.forEach { entry ->
                add(entry, hidden, text)
                if (text.length >= MAX_WRITE_CHARS) {
                    file.append(text.toString())
                    text.setLength(0)
                }
            }
            if (endRepeats) {
                closeRepeats(text)
                lastLine = ""
            }
            val count = lost.getAndSet(0)
            if (count > 0) {
                text.append(timeFormat.format(Date())).append(" W LogFileTree: Lost ").append(count).append(" lines, logging too fast\n")
            }
            if (text.isNotEmpty()) {
                file.append(text.toString())
            }
        }
    }

    private fun add(entry: Entry, hidden: Collection<String>, text: StringBuilder) {
        val message = shorten(LogRedactor.redact(entry.message, hidden).trimEnd())
        val line = "${letter(entry.priority)} ${entry.tag}: $message"
        if (line == lastLine) {
            repeats++
            lastRepeat = entry.time
            return
        }
        closeRepeats(text)
        lastLine = line
        text.append(timeFormat.format(Date(entry.time))).append(' ').append(line).append('\n')
    }

    private fun closeRepeats(text: StringBuilder) {
        if (repeats > 0) {
            text.append(timeFormat.format(Date(lastRepeat))).append(" I LogFileTree: The line above repeated ").append(repeats).append(" more times\n")
            repeats = 0
        }
    }

    // A stack trace ends on its cause, so both ends of a long message stay
    private fun shorten(message: String): String {
        if (message.length <= MAX_MESSAGE_CHARS) {
            return message
        }
        val cut = message.length - MAX_MESSAGE_CHARS
        return "${message.take(MAX_MESSAGE_CHARS / 2)}\n[$cut characters cut]\n${message.takeLast(MAX_MESSAGE_CHARS / 2)}"
    }

    private fun letter(priority: Int) = when (priority) {
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> 'A'
    }

    // The class that logged, for a line such as Timber.e(e.message) that does not say
    private fun caller(): String {
        val frame = Throwable().stackTrace.firstOrNull {
            !it.className.startsWith("timber.log.") && it.className != LogFileTree::class.java.name
        }
        return frame?.className?.substringAfterLast('.')?.substringBefore('$') ?: TAG
    }

    companion object {
        private const val TAG = "WallPanel"
        private const val QUEUE_SIZE = 2_000
        private const val BATCH_MS = 1_000L
        private const val MAX_MESSAGE_CHARS = 8_000
        private const val MAX_WRITE_CHARS = 64 * 1024
        private const val HEARTBEAT_MS = 60 * 60 * 1_000L
    }
}
