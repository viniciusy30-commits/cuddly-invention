package com.example.quadbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

    /**
     * A fingerprint tied to the physical device rather than to this one app
     * installation — it is the same before and after an uninstall/reinstall,
     * which is exactly what getOrCreateDeviceId() is NOT. It is built from a
     * handful of hardware/build identifiers plus the device's Android ID,
     * hashed together. This is used to recognize "this same phone already
     * used its free trial" even if someone reinstalls the app hoping to get
     * a fresh 24h period.
     *
     * IMPORTANT — this is a deterrent, not a lock: ANDROID_ID can change on
     * a factory reset (or, rarely, differs across user profiles on the same
     * device), and none of these values are secret or unspoofable on a
     * rooted device. It raises the effort needed to abuse the free trial; it
     * does not make it impossible. A determined person with a rooted/
     * emulated device can still get around it.
     */
    @SuppressLint("HardwareIds")
    fun getHardwareFingerprint(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) {
            ""
        }
        val raw = listOf(
            Build.BOARD, Build.BRAND, Build.DEVICE, Build.MANUFACTURER,
            Build.MODEL, Build.PRODUCT, Build.FINGERPRINT, androidId,
        ).joinToString("|")

        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Callback-based twin of fetchPublicIpAddress above: runs the network
     * call on a plain background thread (no coroutine scope involved) and
     * delivers the result back on the main thread. Used by the registration
     * flow in AccessControlGate.listen(), which now runs entirely on
     * Firebase Task callbacks rather than a coroutine scope.
     */
    fun fetchPublicIpAddressAsync(onResult: (String?) -> Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val ip = fetchPublicIpAddress()
            mainHandler.post { onResult(ip) }
        }.start()
    }

    /**
     * Looks up this device's current public IP address by asking a plain,
     * free HTTP endpoint (api.ipify.org) — Firestore never exposes the
     * client's IP to the app itself, only to Firebase's own server-side
     * logs, which the app has no access to. Returns null on any failure
     * (no internet, endpoint down, etc.) — callers must treat a null IP as
     * "unknown", never as a reason to block access.
     *
     * Must be called off the main thread.
     */
    fun fetchPublicIpAddress(): String? {
        return try {
            val connection = URL("https://api.ipify.org").openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"
            val ip = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }.trim()
            connection.disconnect()
            ip.takeIf { it.isNotBlank() && it.length <= 45 }
        } catch (_: Exception) {
            null
        }
    }
}
