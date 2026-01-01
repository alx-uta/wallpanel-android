package xyz.wallpanel.pro.utils

import android.util.Log
import timber.log.Timber

class CrashlyticsDebugTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        if (priority != Log.ERROR) {
            return
        }
        if (throwable != null) {
            Log.e(tag ?: "WallPanel", message, throwable)
        } else {
            Log.e(tag ?: "WallPanel", message)
        }
    }
}