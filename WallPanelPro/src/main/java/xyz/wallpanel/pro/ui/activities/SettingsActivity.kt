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

package xyz.wallpanel.pro.ui.activities


import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import xyz.wallpanel.pro.R
import xyz.wallpanel.pro.network.WallPanelService
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.ui.fragments.CodeBottomSheetFragment
import xyz.wallpanel.pro.utils.AppExit
import xyz.wallpanel.pro.utils.DialogUtils
import dagger.android.support.DaggerAppCompatActivity
import timber.log.Timber
import javax.inject.Inject

/**
 * The settings screen. It asks for the security code itself, so every way in is covered:
 * the dashboard button, the `settings` command, the Home Assistant Settings button and the
 * launcher shortcut. Nothing outside the application can start it.
 */
class SettingsActivity : DaggerAppCompatActivity(), CodeBottomSheetFragment.OnAlarmCodeFragmentListener {

    @Inject
    lateinit var configuration: Configuration

    @Inject
    lateinit var dialogUtils: DialogUtils

    // Whether the code has been entered, or is not needed. Kept across a rotation.
    private var unlocked = false

    public override fun onCreate(savedInstance: Bundle?) {
        super.onCreate(savedInstance)

        supportActionBar?.show()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = (getString(R.string.title_settings))

        lifecycle.addObserver(dialogUtils)

        unlocked = savedInstance?.getBoolean(STATE_UNLOCKED) == true ||
                configuration.isFirstTime || !configuration.settingsCodeRequired
        if (unlocked) {
            showSettings()
        } else if (savedInstance == null) {
            // After a rotation the code sheet comes back by itself
            CodeBottomSheetFragment.newInstance(configuration.settingsCode)
                .show(supportFragmentManager, CODE_SHEET_TAG)
        }
    }

    // The settings are only inflated once the code is in, so nothing shows behind the sheet
    private fun showSettings() {
        setContentView(R.layout.activity_settings)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_UNLOCKED, unlocked)
    }

    override fun onComplete(code: String) {
        if (unlocked) {
            return
        }
        unlocked = true
        showSettings()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            settingsScreenStarted(this)
        }
    }

    override fun onCodeError() {
        Toast.makeText(this, R.string.toast_code_invalid, Toast.LENGTH_SHORT).show()
    }

    override fun onCancel() {
        if (!unlocked) {
            finish()
        }
    }

    /**
     * The camera is lent out while this screen is on view, not for as long as it exists: a
     * settings screen left behind another app would otherwise keep the camera, and motion
     * and face detection with it, switched off. Nothing is lent out while the code is
     * still being asked for.
     */
    override fun onStart() {
        super.onStart()
        if (unlocked) {
            settingsScreenStarted(this)
        }
    }

    override fun onStop() {
        super.onStop()
        if (unlocked) {
            settingsScreenStopped(this)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val PERMISSIONS_REQUEST_WRITE_SETTINGS = 200
        private const val STATE_UNLOCKED = "unlocked"
        private const val CODE_SHEET_TAG = "settings_code"

        // Settings screens stopped for a rotation, by class name, whose next start is the
        // same screen coming back
        private val screensChangingConfiguration = mutableSetOf<String>()

        /**
         * Called from onStart() of a screen that needs the camera lent out, the settings and
         * the camera test. A rotation recreates the screen, and it is not reported as the
         * screen closing and opening again: in between, the service would take the camera
         * back from under the camera test, and reopen it for nothing under the settings.
         */
        fun settingsScreenStarted(activity: Activity) {
            if (!screensChangingConfiguration.remove(activity.javaClass.name)) {
                notifySettingsShown(activity, true)
            }
        }

        /** Called from onStop(), see [settingsScreenStarted]. */
        fun settingsScreenStopped(activity: Activity) {
            if (activity.isChangingConfigurations) {
                screensChangingConfiguration.add(activity.javaClass.name)
            } else {
                notifySettingsShown(activity, false)
            }
        }

        /**
         * Tells the service a settings screen came into or left view. The service keeps
         * running while the settings are open and only lends out the camera, for the camera
         * test, see WallPanelService.onStartCommand(). The screen calling is in front, which
         * is what Android requires to start a service.
         */
        private fun notifySettingsShown(context: Context, shown: Boolean) {
            // Started again now, the service would come back after the process has gone
            if (AppExit.exiting) {
                return
            }
            val action = if (shown) WallPanelService.ACTION_SETTINGS_OPENED else WallPanelService.ACTION_SETTINGS_CLOSED
            try {
                context.startService(Intent(context, WallPanelService::class.java).setAction(action))
            } catch (e: IllegalStateException) {
                Timber.w(e, "Unable to tell the service about the settings screen")
            }
        }
        fun createStartIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }
}