package com.example.quadbrowser

import android.app.Application
import android.os.Build
import android.webkit.WebView

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
    }
}