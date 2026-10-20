/*
 * Copyright (c) 2022 Wallpanel
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

package xyz.wallpanel.pro.ui.fragments

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.databinding.FragmentCodeBottomSheetBinding

/**
 * The keypad that asks for the settings code. The host activity is the listener, which is
 * what lets the sheet come back after a rotation: the fragment is then rebuilt with no
 * arguments but its saved ones.
 */
class CodeBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var alarmListener: OnAlarmCodeFragmentListener
    private val currentCode: String
        get() = arguments?.getString(ARG_CODE).orEmpty()

    private var codeComplete = false
    private var enteredCode = ""
    private lateinit var binding: FragmentCodeBottomSheetBinding
    private val handler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    private val delayRunnable = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if(codeComplete) {
                onComplete(enteredCode)
            } else {
                onError()
            }
        }
    }

    interface OnAlarmCodeFragmentListener {
        fun onComplete(code: String)
        fun onCodeError()
        fun onCancel()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        alarmListener = context as OnAlarmCodeFragmentListener
    }

    // Back, or a touch outside the sheet
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        alarmListener.onCancel()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.closeCodeButton.setOnClickListener {
            onCancel()
        }
        binding.include.button0.setOnClickListener {
            addPinCode("0")
        }
        binding.include.button1.setOnClickListener {
            addPinCode("1")
        }
        binding.include.button2.setOnClickListener {
            addPinCode("2")
        }
        binding.include.button3.setOnClickListener {
            addPinCode("3")
        }
        binding.include.button4.setOnClickListener {
            addPinCode("4")
        }
        binding.include.button5.setOnClickListener {
            addPinCode("5")
        }
        binding.include.button6.setOnClickListener {
            addPinCode("6")
        }
        binding.include.button7.setOnClickListener {
            addPinCode("7")
        }
        binding.include.button8.setOnClickListener {
            addPinCode("8")
        }
        binding.include.button9.setOnClickListener {
            addPinCode("9")
        }
        binding.include.buttonDel.setOnClickListener {
            removePinCode()
        }
        binding.include.buttonDel.setOnClickListener {
            removePinCode()
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val dialog = dialog as BottomSheetDialog
                val bottomSheetResId = com.google.android.material.R.id.design_bottom_sheet
                val bottomSheet = dialog.findViewById<View>(bottomSheetResId) as FrameLayout?
                val behavior = BottomSheetBehavior.from(bottomSheet!!)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        })
    }

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?, @Nullable savedInstanceState: Bundle?): View? {
        binding = FragmentCodeBottomSheetBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    private fun onCancel() {
        enteredCode = ""
        showFilledPins(0)
        alarmListener.onCancel()
        dismissAllowingStateLoss()
    }

    private fun onComplete(code: String) {
        alarmListener.onComplete(code)
        // The code is checked half a second after the last digit, by when the screen may
        // have gone off and the activity saved its state
        dismissAllowingStateLoss()
    }

    private fun onError() {
        codeComplete = false
        alarmListener.onCodeError()
        enteredCode = ""
        showFilledPins(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(delayRunnable)
    }

    private fun addPinCode(code: String) {
        if (codeComplete) return
        enteredCode += code
        showFilledPins(enteredCode.length)
        if (enteredCode.length == MAX_CODE_LENGTH && enteredCode == currentCode) {
            codeComplete = true
            handler.postDelayed(delayRunnable, 500)
        } else if (enteredCode.length == MAX_CODE_LENGTH) {
            handler.postDelayed(delayRunnable, 500)
        }
    }

    private fun removePinCode() {
        if (codeComplete) return
        if (enteredCode.isNotEmpty()) {
            enteredCode = enteredCode.substring(0, enteredCode.length - 1)
            showFilledPins(enteredCode.length)
        }
    }

    private fun showFilledPins(pinsShown: Int) {
        if (binding.pinCode1 != null && binding.pinCode2 != null && binding.pinCode3 != null && binding.pinCode4 != null) {
            when (pinsShown) {
                1 -> {
                    binding.pinCode1.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode2.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                    binding.pinCode3.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                    binding.pinCode4.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                }
                2 -> {
                    binding.pinCode1.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode2.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode3.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                    binding.pinCode4.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                }
                3 -> {
                    binding.pinCode1.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode2.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode3.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode4.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                }
                4 -> {
                    binding.pinCode1.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode2.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode3.setImageResource(R.drawable.ic_radio_button_checked_black)
                    binding.pinCode4.setImageResource(R.drawable.ic_radio_button_checked_black)
                }
                else -> {
                    binding.pinCode1.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                    binding.pinCode2.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                    binding.pinCode3.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                    binding.pinCode4.setImageResource(R.drawable.ic_radio_button_unchecked_black)
                }
            }
        }
    }

    companion object {
        private  val MAX_CODE_LENGTH = 4
        private const val ARG_CODE = "code"

        fun newInstance(code: String): CodeBottomSheetFragment {
            return CodeBottomSheetFragment().apply {
                arguments = Bundle().apply { putString(ARG_CODE, code) }
            }
        }
    }
}