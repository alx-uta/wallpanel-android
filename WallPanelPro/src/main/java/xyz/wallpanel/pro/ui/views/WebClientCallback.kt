package xyz.wallpanel.pro.ui.views

import android.webkit.PermissionRequest

interface WebClientCallback {
    fun askForWebkitPermission(permission: String, requestCode: Int)

    fun complete()

    fun pageLoadComplete(url: String)

    fun setWebkitPermissionRequest(request: PermissionRequest?)

    var isConnected: Boolean

    fun isFinishing(): Boolean

    fun displayProgress(): Boolean

    fun startReloadDelay()

    fun stopReloadDelay()

    /**
     * Rebuild the GeckoView session after its content process died and reload the page
     */
    fun recreateGeckoSession()

    fun certPermissionsShown() : Boolean

}