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

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.webkit.WebView
import androidx.annotation.RequiresApi
import timber.log.Timber
import xyz.wallpanel.pro.BuildConfig
import xyz.wallpanel.pro.persistence.Configuration
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The log a user can send: what the device and application are, how the application last
 * ended, the settings that are on/off switches and numbers, then the kept log. See
 * [LogFileTree] for how the log is kept and [LogRedactor] for what is taken out of it.
 *
 * [install] once, from the application. Reports are built on a thread of their own.
 */
object DiagnosticLog {

    /** Lives in the cache, under the name log_paths.xml gives FileProvider. */
    private const val SHARE_DIRECTORY = "shared-log"
    private const val LOG_DIRECTORY = "logs"
    private const val MAX_EXITS = 10
    private const val MEGABYTE = 1024 * 1024

    private lateinit var appContext: Context
    private lateinit var configuration: Configuration
    private lateinit var file: LogFile
    private var tree: LogFileTree? = null
    private val startedAt = SystemClock.elapsedRealtime()

    private val exporter: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "DiagnosticLog").apply { isDaemon = true } }
    }

    fun install(context: Context, configuration: Configuration) {
        appContext = context.applicationContext
        this.configuration = configuration
        file = LogFile(File(appContext.noBackupFilesDir, LOG_DIRECTORY))
        tree = LogFileTree(file, configuration::logHiddenValues, ::memorySummary).also {
            it.start()
            Timber.plant(it)
        }
        Timber.i("WallPanel ${BuildConfig.VERSION_NAME} started")
    }

    /**
     * For a process about to die, which would take the queued lines with it. Never throws,
     * since a full disk must not stop the crash handler from restarting the application.
     */
    fun flush() {
        try {
            tree?.flush()
        } catch (e: Throwable) {
            // Those lines are lost, and there is nowhere left to say so
        }
    }

    fun fileName(): String {
        return "wallpanel-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
    }

    /** Runs [task] on the report thread, where [writeReport] and friends belong. */
    fun inBackground(task: () -> Unit) {
        exporter.execute {
            try {
                task()
            } catch (e: Throwable) {
                Timber.e(e, "Could not run a log export")
            }
        }
    }

    fun report(): ByteArray = ByteArrayOutputStream().also { writeReport(it) }.toByteArray()

    /** Writes the report to a file another application can be given. It replaces the last one. */
    fun writeShareFile(): File {
        val directory = File(appContext.cacheDir, SHARE_DIRECTORY)
        directory.deleteRecursively()
        directory.mkdirs()
        return File(directory, fileName()).also { target -> target.outputStream().use { writeReport(it) } }
    }

    fun writeReport(stream: OutputStream) {
        flush()
        val hidden = configuration.logHiddenValues()
        val writer = stream.bufferedWriter(Charsets.UTF_8)
        val line = { text: String ->
            writer.write(LogRedactor.redact(text, hidden))
            writer.write("\n")
        }
        header().forEach(line)
        file.lines().forEach(line)
        writer.flush()
    }

    private fun header(): List<String> {
        val lines = mutableListOf(
            "WallPanel log, ${Date()}",
            "App: ${BuildConfig.VERSION_NAME}, ${BuildConfig.BUILD_TYPE}, up ${minutes(SystemClock.elapsedRealtime() - startedAt)}",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "up ${minutes(SystemClock.elapsedRealtime())}",
            "Browser: ${if (configuration.useGeckoView) "GeckoView" else "WebView"}, " +
                "GeckoView ${org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION}, WebView ${webViewVersion()}",
            "Memory: ${memorySummary()}",
            "",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lines += "How the application last ended:"
            lines += recentExits()
            lines += ""
        }
        lines += "Settings:"
        lines += configuration.allSettings()
            .filter { (key, value) -> (value is Boolean || value is Number) && !key.contains("code", ignoreCase = true) }
            .toSortedMap().map { (key, value) -> "$key=$value" }
        lines += ""
        lines += "Log:"
        return lines
    }

    private fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val system = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(system)
        return "Java heap ${(runtime.totalMemory() - runtime.freeMemory()) / MEGABYTE} of ${runtime.maxMemory() / MEGABYTE} MB, " +
            "native heap ${Debug.getNativeHeapAllocatedSize() / MEGABYTE} MB, " +
            "${system.availMem / MEGABYTE} of ${system.totalMem / MEGABYTE} MB free on the device, " +
            "${Thread.activeCount()} threads"
    }

    private fun minutes(millis: Long): String {
        val minutes = millis / 60_000
        return "${minutes / 1440}d ${minutes / 60 % 24}h ${minutes % 60}m"
    }

    // The provider answers from another process, and may be missing or being updated
    private fun webViewVersion(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "unknown"
        }
        return try {
            WebView.getCurrentWebViewPackage()?.versionName ?: "none"
        } catch (e: Exception) {
            "unknown"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recentExits(): List<String> {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return try {
            manager.getHistoricalProcessExitReasons(null, 0, 0).filter { worthShowing(it) }.take(MAX_EXITS).map { exit ->
                "${Date(exit.timestamp)}: ${exitReason(exit.reason)} (${exit.processName}, " +
                    "${exit.pss / 1024} MB) ${exit.description.orEmpty()}".trimEnd()
            }
        } catch (e: Exception) {
            listOf("Unknown: ${e.message}")
        }
    }

    // One event ends a handful of processes, and browser helpers end all the time
    @RequiresApi(Build.VERSION_CODES.R)
    private fun worthShowing(exit: ApplicationExitInfo): Boolean {
        return exit.processName == appContext.packageName || when (exit.reason) {
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED -> true
            else -> false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depends on died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much resource use"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by Android"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_OTHER -> "killed by Android"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "application disabled"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "application updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        else -> "reason $reason"
    }
}
