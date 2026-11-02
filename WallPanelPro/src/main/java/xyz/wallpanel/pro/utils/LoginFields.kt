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

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import xyz.wallpanel.pro.R

/** The username and password boxes of a login prompt, for either browser engine. */
class LoginFields(context: Context, username: String?, passwordOnly: Boolean) {

    private val usernameField = EditText(context).apply {
        hint = context.getString(R.string.hint_username)
        setText(username.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private val passwordField = EditText(context).apply {
        hint = context.getString(R.string.hint_password)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
        if (!passwordOnly) {
            addView(usernameField)
        }
        addView(passwordField)
    }

    val username: String
        get() = usernameField.text.toString()

    val password: String
        get() = passwordField.text.toString()
}
