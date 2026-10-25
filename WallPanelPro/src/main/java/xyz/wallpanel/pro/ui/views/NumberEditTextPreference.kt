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

package xyz.wallpanel.pro.ui.views

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.preference.EditTextPreference

/**
 * A text preference that holds a whole number and opens the number keypad. AndroidX's
 * EditTextPreference ignores `android:inputType` in the preference XML, so the keyboard
 * has to be chosen when the dialog binds its text field.
 */
class NumberEditTextPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditTextPreference(context, attrs) {

    init {
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.setSelection(editText.text.length)
        }
    }
}
