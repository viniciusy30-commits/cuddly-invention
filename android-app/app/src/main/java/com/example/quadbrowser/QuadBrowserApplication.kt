package com.example.quadbrowser

import android.app.Application
import android.os.Build
import android.webkit.WebView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * WebView.setDataDirectorySuffix() is process-wide. It must be called once,
 * before the first WebView is created. Per-pane isolation is provided by the
 * four AndroidX WebKit multi-profile names in MainActivity.
 */
class QuadBrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("quad-browser")
        }

        // FIX (root cause of "new devices never appear in the admin panel"):
        // by default the Firestore SDK talks to its backend over gRPC. On
        // some devices/networks (Motorola has several long-standing reports
        // of this — see firebase-android-sdk issues) that gRPC stream never
        // connects and never fails either: no exception, no timeout, no
        // error of any kind. docRef.set()/get() just hang forever, which is
        // exactly what the on-screen diagnostic log showed (stuck right
        // after "enviando registro..."). Forcing long-polling makes the SDK
        // use plain HTTP requests instead of a persistent gRPC stream, which
        // works over the same networks/proxies that silently break gRPC.
        // This must be set once, here, before any Firestore call happens
        // anywhere else in the app (AccessControlGate, contacts listener).
        val firestore = FirebaseFirestore.getInstance()
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder(firestore.firestoreSettings)
            .setForceLongPolling(true)
            .build()

        // TEMPORARY diagnostic crash catcher — writes any uncaught crash to a
        // plain text file so it can be read on-screen (see MainActivity's
        // crash report screen) without a computer or logcat. Safe to remove
        // once the "device never reaches the admin panel" issue is found.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_LOG_FILE_NAME).writeText(sw.toString())
            } catch (_: Exception) {
                // If we can't even write the crash log, fall through to the
                // previous handler below — never swallow the crash silently.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_LOG_FILE_NAME = "last_crash.txt"
    }
}