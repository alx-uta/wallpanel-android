/*
 * Copyright (c) 2022 WallPanel
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

import android.Manifest
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.widget.EditText
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import timber.log.Timber
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.ui.views.WebClientCallback
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Maps GeckoView's ProgressDelegate, PermissionDelegate and PromptDelegate onto
 * WebChromeClient-like behaviour.
 *
 * [context] must be the activity: the prompts are dialogs shown over the page.
 */
class GeckoWebChromeClientAdapter(
    private val context: Context,
    private val callback: WebClientCallback
) : GeckoSession.ProgressDelegate, GeckoSession.PermissionDelegate, GeckoSession.PromptDelegate {

    var snackbar: Snackbar? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var geckoView: android.view.View? = null
    // Prompts on screen and not yet answered
    private val openPrompts = mutableSetOf<PromptAnswer>()
    // The page loading now
    private var startedUrl: String? = null

    fun setGeckoView(view: android.view.View) {
        geckoView = view
    }

    // ProgressDelegate implementation
    /**
     * A new page takes the old page's prompts with it. Gecko withdraws them without always
     * saying so, which would leave a dialog over the new page answering nothing.
     */
    @UiThread
    override fun onPageStart(session: GeckoSession, url: String) {
        openPrompts.toList().forEach { it.withdraw() }
        startedUrl = url
        callback.geckoPageStarted()
    }

    @UiThread
    override fun onPageStop(session: GeckoSession, success: Boolean) {
        snackbar?.dismiss()
        // As the WebView does when a page finishes: a page of the user's that loaded shows
        // the server can be reached again, and ends any retry still waiting.
        if (success && startedUrl != GeckoWebClientAdapter.ERROR_PAGE && startedUrl != "about:blank") {
            callback.isConnected = true
        }
        if (success && callback.isConnected) {
            callback.stopReloadDelay()
        }
        callback.complete()
    }

    @UiThread
    override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (progress == 100) {
            snackbar?.dismiss()
            callback.complete()
            return
        }

        if (callback.displayProgress() && geckoView != null) {
            val text = context.getString(R.string.text_loading_percent, progress.toString(), "")
            if (snackbar == null) {
                snackbar = Snackbar.make(geckoView!!, text, Snackbar.LENGTH_INDEFINITE)
            } else {
                snackbar?.setText(text)
            }
            snackbar?.show()
        }
    }

    @UiThread
    override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
    ) {
    }

    @UiThread
    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
    }

    // PermissionDelegate implementation
    @UiThread
    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int>? {
        return when (perm.permission) {
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION,
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION,
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ->
                GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            else ->
                GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
    }

    @UiThread
    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback
    ) {
        val wantsVideo = !video.isNullOrEmpty()
        val wantsAudio = !audio.isNullOrEmpty()
        // Gecko asks for the device even after the Android permission was refused, and
        // granting it then hands the page a camera or microphone that delivers nothing.
        // Refusing lets the page tell the user instead.
        if ((!wantsVideo && !wantsAudio) ||
            (wantsVideo && !hasPermission(Manifest.permission.CAMERA)) ||
            (wantsAudio && !hasPermission(Manifest.permission.RECORD_AUDIO))
        ) {
            callback.reject()
            return
        }
        callback.grant(video?.firstOrNull(), audio?.firstOrNull())
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Gecko asks for the Android permissions behind a page's request, the microphone for a
     * voice assistant for instance, before it opens the device. They are asked for for real:
     * granting without them hands the page a microphone that records silence.
     */
    @UiThread
    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        if (permissions.isNullOrEmpty()) {
            callback.grant()
            return
        }
        this.callback.requestAndroidPermissions(arrayOf(*permissions)) { granted ->
            if (granted) callback.grant() else callback.reject()
        }
    }

    // PromptDelegate implementation. Each prompt is answered exactly once: Gecko throws on a
    // second answer, and that exception lands on the main thread and ends the application.

    @UiThread
    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: AlertPrompt
    ): GeckoResult<PromptResponse>? {
        return showPrompt(prompt) { builder, answer ->
            builder.setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> answer { prompt.dismiss() } }
        }
    }

    @UiThread
    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: ButtonPrompt
    ): GeckoResult<PromptResponse>? {
        return showPrompt(prompt) { builder, answer ->
            builder.setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer { prompt.confirm(ButtonPrompt.Type.POSITIVE) }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    answer { prompt.confirm(ButtonPrompt.Type.NEGATIVE) }
                }
        }
    }

    @UiThread
    override fun onTextPrompt(
        session: GeckoSession,
        prompt: TextPrompt
    ): GeckoResult<PromptResponse>? {
        val input = EditText(context).apply {
            setText(prompt.defaultValue.orEmpty())
            setSelectAllOnFocus(true)
        }
        return showPrompt(prompt) { builder, answer ->
            builder.setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer { prompt.confirm(input.text.toString()) }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> answer { prompt.dismiss() } }
        }
    }

    /**
     * Native `<select>` lists and page menus.
     */
    @UiThread
    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: ChoicePrompt
    ): GeckoResult<PromptResponse>? {
        val choices = flatten(prompt.choices)
        if (choices.isEmpty()) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val labels = choices.map { it.label }.toTypedArray()
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            val checked = choices.map { it.selected }.toBooleanArray()
            return showPrompt(prompt) { builder, answer ->
                builder.setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        answer { prompt.confirm(choices.filterIndexed { i, _ -> checked[i] }.toTypedArray()) }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> answer { prompt.dismiss() } }
            }
        }
        val current = choices.indexOfFirst { it.selected }
        return showPrompt(prompt) { builder, answer ->
            builder.setSingleChoiceItems(labels, current) { dialog, which ->
                answer { prompt.confirm(choices[which]) }
                dialog.dismiss()
            }
        }
    }

    /**
     * Date and time inputs. The other kinds (month, week, date and time together) have no
     * platform picker and are left to the page.
     */
    @UiThread
    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: DateTimePrompt
    ): GeckoResult<PromptResponse>? {
        if (callback.isFinishing()) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val result = GeckoResult<PromptResponse>()
        val answer = PromptAnswer(prompt, result)
        val dialog: Dialog = when (prompt.type) {
            DateTimePrompt.Type.DATE -> {
                val date = parseDate(prompt.defaultValue) ?: Calendar.getInstance()
                DatePickerDialog(context, { _, year, month, day ->
                    answer.answer { prompt.confirm(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)) }
                }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH))
            }
            DateTimePrompt.Type.TIME -> {
                val time = parseTime(prompt.defaultValue)
                TimePickerDialog(context, { _, hour, minute ->
                    answer.answer { prompt.confirm(String.format(Locale.US, "%02d:%02d", hour, minute)) }
                }, time.first, time.second, DateFormat.is24HourFormat(context))
            }
            else -> return GeckoResult.fromValue(prompt.dismiss())
        }
        dialog.setOnDismissListener { answer.answer { prompt.dismiss() } }
        answer.closeWhenWithdrawn(dialog)
        dialog.show()
        return result
    }

    /**
     * A page or resource behind HTTP authentication, basic auth for instance. The dashboard's
     * own server gets the username and password from the settings, once: when it turns them
     * down, the user is asked.
     */
    @UiThread
    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: AuthPrompt
    ): GeckoResult<PromptResponse>? {
        val options = prompt.authOptions
        val passwordOnly = options.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val previousFailed = options.flags and AuthPrompt.AuthOptions.Flags.PREVIOUS_FAILED != 0
        val proxy = options.flags and AuthPrompt.AuthOptions.Flags.PROXY != 0
        val stored = if (proxy) null else callback.storedLoginForUrl(options.uri)
        if (stored != null && !previousFailed && !callback.isFinishing()) {
            val (username, password) = stored
            return GeckoResult.fromValue(
                if (passwordOnly) prompt.confirm(password) else prompt.confirm(username, password)
            )
        }
        val fields = LoginFields(context, options.username?.takeIf { it.isNotEmpty() } ?: stored?.first, passwordOnly)
        return showPrompt(prompt) { builder, answer ->
            builder.setMessage(prompt.message)
                .setView(fields.view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answer {
                        if (passwordOnly) {
                            prompt.confirm(fields.password)
                        } else {
                            prompt.confirm(fields.username, fields.password)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> answer { prompt.dismiss() } }
        }
    }

    @UiThread
    override fun onColorPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ColorPrompt
    ): GeckoResult<PromptResponse>? {
        return GeckoResult.fromValue(prompt.dismiss())
    }

    /**
     * A page's file upload field. Folders cannot be picked through Android's document picker
     * as files, so a folder field is dismissed.
     */
    @UiThread
    override fun onFilePrompt(
        session: GeckoSession,
        prompt: FilePrompt
    ): GeckoResult<PromptResponse>? {
        if (callback.isFinishing() || prompt.type == FilePrompt.Type.FOLDER) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val result = GeckoResult<PromptResponse>()
        val answer = PromptAnswer(prompt, result)
        val mimeTypes = prompt.mimeTypes.orEmpty().filter { it.contains('/') }.toTypedArray()
        callback.pickFiles(mimeTypes, prompt.type == FilePrompt.Type.MULTIPLE) { picked ->
            if (picked.isNullOrEmpty()) {
                answer.answer { prompt.dismiss() }
                return@pickFiles
            }
            // Gecko reads the upload from a file path and rejects the content URIs the picker
            // returns, so the files are copied to the cache first, off the main thread.
            Thread {
                val files = copyForUpload(picked)
                mainHandler.post {
                    answer.answer {
                        when {
                            files.isEmpty() -> prompt.dismiss()
                            files.size == 1 -> prompt.confirm(context, files[0])
                            else -> prompt.confirm(context, files.toTypedArray())
                        }
                    }
                }
            }.start()
        }
        return result
    }

    /**
     * Copies picked files into the cache and returns file URIs for them. The copies of the
     * previous upload are deleted first, so they do not pile up on a kiosk.
     */
    private fun copyForUpload(uris: Array<Uri>): List<Uri> {
        val dir = File(context.cacheDir, UPLOAD_DIRECTORY)
        dir.deleteRecursively()
        dir.mkdirs()
        return uris.mapIndexedNotNull { index, uri ->
            try {
                val name = displayName(uri)?.replace('/', '_') ?: "upload"
                // Each file in a folder of its own, so two picked files with the same name do
                // not overwrite each other and the page still sees the original names.
                val file = File(File(dir, index.toString()).apply { mkdirs() }, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@mapIndexedNotNull null
                Uri.fromFile(file)
            } catch (e: Exception) {
                Timber.e(e, "Unable to read the picked file $uri")
                null
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment
    }

    /**
     * Pop-ups the page opened without a tap stay blocked. Links meant for a new window open
     * in this one, see GeckoWebClientAdapter.onNewSession.
     */
    @UiThread
    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.PopupPrompt
    ): GeckoResult<PromptResponse>? {
        return GeckoResult.fromValue(prompt.dismiss())
    }

    @UiThread
    override fun onSharePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.SharePrompt
    ): GeckoResult<PromptResponse>? {
        return GeckoResult.fromValue(prompt.dismiss())
    }

    /**
     * Resubmitting a form on reload is allowed. Nobody is at a kiosk to answer, and a
     * reload command should reload.
     */
    @UiThread
    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
    ): GeckoResult<PromptResponse>? {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    /**
     * Leaving a page that asks "are you sure" is allowed, so the url and relaunch commands
     * are never held up by a question nobody is there to answer.
     */
    @UiThread
    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
    ): GeckoResult<PromptResponse>? {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    /**
     * Shows [prompt] as a dialog. [build] adds the message and buttons, and answers through
     * the function it is given, which does nothing after the first answer. Closing the
     * dialog any other way dismisses the prompt.
     */
    private fun showPrompt(
        prompt: BasePrompt,
        build: (AlertDialog.Builder, (() -> PromptResponse) -> Unit) -> Unit
    ): GeckoResult<PromptResponse> {
        if (callback.isFinishing()) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val result = GeckoResult<PromptResponse>()
        val answer = PromptAnswer(prompt, result)
        val builder = AlertDialog.Builder(context)
        if (!prompt.title.isNullOrEmpty()) {
            builder.setTitle(prompt.title)
        }
        build(builder, answer::answer)
        builder.setOnDismissListener { answer.answer { prompt.dismiss() } }
        val dialog = builder.create()
        answer.closeWhenWithdrawn(dialog)
        try {
            dialog.show()
        } catch (e: Exception) {
            Timber.e(e, "Unable to show the page's prompt")
            answer.answer { prompt.dismiss() }
        }
        return result
    }

    /**
     * Answers [prompt] through [result] at most once, and not at all once the page has
     * withdrawn it (by navigating away, for instance), which also closes [dialog].
     */
    private inner class PromptAnswer(
        private val prompt: BasePrompt,
        private val result: GeckoResult<PromptResponse>
    ) {
        private var done = false
        private var dialog: Dialog? = null

        fun answer(response: () -> PromptResponse) {
            if (!done && !prompt.isComplete) {
                done = true
                openPrompts.remove(this)
                result.complete(response())
            }
        }

        fun closeWhenWithdrawn(dialog: Dialog) {
            this.dialog = dialog
            openPrompts.add(this)
            prompt.setDelegate(object : PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: BasePrompt) {
                    withdraw()
                }
            })
        }

        /** Closes the dialog without answering the prompt, which the page no longer awaits. */
        fun withdraw() {
            done = true
            openPrompts.remove(this)
            dialog?.dismiss()
        }
    }

    private fun flatten(choices: Array<out ChoicePrompt.Choice>): List<ChoicePrompt.Choice> {
        return choices.flatMap { choice ->
            val items = choice.items
            when {
                items != null -> flatten(items)
                choice.separator || choice.disabled -> emptyList()
                else -> listOf(choice)
            }
        }
    }

    private fun parseDate(value: String?): Calendar? {
        val parts = value?.split("-")?.mapNotNull { it.toIntOrNull() } ?: return null
        if (parts.size != 3) {
            return null
        }
        return Calendar.getInstance().apply { set(parts[0], parts[1] - 1, parts[2]) }
    }

    private fun parseTime(value: String?): Pair<Int, Int> {
        val parts = value?.split(":")?.mapNotNull { it.take(2).toIntOrNull() }
        if (parts != null && parts.size >= 2) {
            return Pair(parts[0], parts[1])
        }
        val now = Calendar.getInstance()
        return Pair(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    }

    companion object {
        private const val UPLOAD_DIRECTORY = "uploads"
    }
}
