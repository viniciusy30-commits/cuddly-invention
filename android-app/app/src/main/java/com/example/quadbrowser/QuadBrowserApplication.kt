package com.example.quadbrowser

import android.app.Application
import android.os.Build
import android.webkit.WebView
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