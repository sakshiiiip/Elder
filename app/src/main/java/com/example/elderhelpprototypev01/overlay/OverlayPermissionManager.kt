package com.example.elderhelpprototypev01.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Helper object for managing the SYSTEM_ALERT_WINDOW ("draw over other apps") permission.
 * This permission is required for the floating Sahaay overlay to appear above other apps.
 */
object OverlayPermissionManager {

    /**
     * Returns true if Sahaay has permission to draw over other apps.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Returns an Intent that opens the Android system settings screen
     * where the user can grant overlay permission to this app.
     * The caller (Activity) should use startActivity() or startActivityForResult().
     */
    fun buildPermissionSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }
}
