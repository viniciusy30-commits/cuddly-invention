package com.example.quadbrowser

import android.content.Context
import java.util.UUID

/**
 * Generates a random identifier the first time the app runs on a device and
 * persists it in private app storage. This identifier survives app restarts
 * but is reset if the app is uninstalled/reinstalled or its storage is
 * cleared — it is a per-installation license key, not a hardware fingerprint.
 */
object DeviceIdentity {
    private const val PREFS_NAME = "quad_browser_identity"
    private const val KEY_DEVICE_ID = "device_id"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }
}
