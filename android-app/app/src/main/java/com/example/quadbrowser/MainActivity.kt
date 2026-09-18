package com.example.quadbrowser

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private companion object {
        const val FULLSCREEN_PANE_KEY = "fullscreen_pane_index"
        const val WEBVIEW_STATE_PREFIX = "webview_state_"
        const val WEBVIEW_URL_PREFIX = "webview_url_"
        const val PANE_URL_PREFIX = "pane_url_"
        const val SETTINGS_PREFS = "quad_browser_settings"
        const val DARK_THEME_KEY = "dark_theme"
    }

    private data class BrowserPane(
        val container: View,
        val webView: WebView,
        val addressBar: EditText,
        val fullscreenButton: Button,
    )

    private val panes = mutableListOf<BrowserPane>()
    private var fullscreenPaneIndex: Int? = null
    private var isDarkTheme = false

    override fun onCreate(savedInstanceState: Bundle?) {
        isDarkTheme = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .getBoolean(DARK_THEME_KEY, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO,
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.theme_toggle).apply {
            updateThemeToggle(this)
            setOnClickListener { toggleTheme() }
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showProfileSupportError()
            return
        }

        val paneDefinitions = listOf(
            PaneDefinition(R.id.pane_1, R.id.address_1, R.id.go_1, R.id.reload_1, R.id.fullscreen_1, R.id.webview_1, "webview1"),
            PaneDefinition(R.id.pane_2, R.id.address_2, R.id.go_2, R.id.reload_2, R.id.fullscreen_2, R.id.webview_2, "webview2"),
            PaneDefinition(R.id.pane_3, R.id.address_3, R.id.go_3, R.id.reload_3, R.id.fullscreen_3, R.id.webview_3, "webview3"),
            PaneDefinition(R.id.pane_4, R.id.address_4, R.id.go_4, R.id.reload_4, R.id.fullscreen_4, R.id.webview_4, "webview4"),
        )

        paneDefinitions.forEachIndexed { index, definition ->
            val container = findViewById<View>(definition.paneId)
            val webView = findViewById<WebView>(definition.webViewId)
            val addressBar = findViewById<EditText>(definition.addressId)
            val fullscreenButton = findViewById<Button>(definition.fullscreenButtonId)
            configureWebView(webView, definition.profileName, index, addressBar)

            findViewById<Button>(definition.goButtonId).setOnClickListener {
                loadInput(webView, addressBar)
            }
            findViewById<Button>(definition.reloadButtonId).setOnClickListener {
                webView.reload()
            }
            addressBar.setOnEditorActionListener { _, _, _ ->
                loadInput(webView, addressBar)
                true
            }
            fullscreenButton.setOnClickListener {
                toggleFullscreen(index)
            }

            restorePaneState(index, webView, addressBar, savedInstanceState)


            panes += BrowserPane(container, webView, addressBar, fullscreenButton)
            webView.contentDescription = getString(R.string.webview_description, index + 1)
        }

        savedInstanceState?.getInt(FULLSCREEN_PANE_KEY, -1)?.takeIf { it in panes.indices }?.let {
            fullscreenPaneIndex = it
        }
        applyPaneLayout()
    }

    private fun restorePaneState(
        index: Int,
        webView: WebView,
        addressBar: EditText,
        savedInstanceState: Bundle?,
    ) {
        savedInstanceState?.getBundle(webViewStateKey(index))?.let { webViewState ->
            webView.restoreState(webViewState)
        }

        val restoredUrl = savedInstanceState?.getString(webViewUrlKey(index))
            ?: getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                .getString(paneUrlKey(index), null)
        val currentUrl = webView.url

        if (currentUrl.isNullOrBlank() && !restoredUrl.isNullOrBlank()) {
            webView.loadUrl(restoredUrl)
        } else if (!currentUrl.isNullOrBlank()) {
            addressBar.setText(currentUrl)
            addressBar.setSelection(addressBar.text.length)
        }
    }

    private fun toggleFullscreen(index: Int) {
          if (index !in panes.indices) return

          // In-app focus mode only: keep system bars, orientation, window flags,
          // and the Activity lifecycle unchanged.
          fullscreenPaneIndex = if (fullscreenPaneIndex == index) null else index
          applyPaneLayout()
      }

        /**
     * Keep the existing WebViews alive while Android resizes a freeform or
     * floating window. Recreating the Activity here can restore the wrong
     * WebView snapshot into every quadrant.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { applyPaneLayout() }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        window.decorView.post { applyPaneLayout() }
    }

    private fun applyPaneLayout() {
        if (panes.isEmpty()) return

        val grid = findViewById<GridLayout>(R.id.browser_grid)
        val appToolbar = findViewById<View>(R.id.app_toolbar)
        val selectedIndex = fullscreenPaneIndex
        val gridParams = grid.layoutParams as? ViewGroup.MarginLayoutParams

        if (selectedIndex == null) {
            appToolbar.visibility = View.VISIBLE
            gridParams?.let {
                it.topMargin = resources.getDimensionPixelSize(R.dimen.app_toolbar_height)
                grid.layoutParams = it
            }
            grid.columnCount = 2
            grid.rowCount = 2
            panes.forEachIndexed { index, pane ->
                pane.container.visibility = View.VISIBLE
                pane.container.layoutParams = paneLayoutParams(index, fullscreen = false)
                setFullscreenButtonState(pane, selected = false)
            }
        } else {
            appToolbar.visibility = View.GONE
            gridParams?.let {
                it.topMargin = 0
                grid.layoutParams = it
            }
            // Hide the other children before changing the grid to one cell so
            // GridLayout never tries to place stale positions in a 1x1 grid.
            panes.forEach { pane -> pane.container.visibility = View.GONE }
            grid.columnCount = 1
            grid.rowCount = 1
            panes.forEachIndexed { index, pane ->
                val selected = index == selectedIndex
                if (selected) {
                    pane.container.visibility = View.VISIBLE
                    pane.container.layoutParams = paneLayoutParams(index, fullscreen = true)
                }
                setFullscreenButtonState(pane, selected)
            }
        }

        grid.requestLayout()
    }

    private fun paneLayoutParams(index: Int, fullscreen: Boolean): GridLayout.LayoutParams {
        val row = if (fullscreen) 0 else index / 2
        val column = if (fullscreen) 0 else index % 2
        return GridLayout.LayoutParams(
            GridLayout.spec(row, 1, 1f),
            GridLayout.spec(column, 1, 1f),
        ).apply {
            width = 0
            height = 0
            setMargins(1, 1, 1, 1)
        }
    }

    private fun setFullscreenButtonState(pane: BrowserPane, selected: Boolean) {
        pane.fullscreenButton.text = getString(
            if (selected) R.string.fullscreen_exit_symbol else R.string.fullscreen_enter_symbol,
        )
        pane.fullscreenButton.contentDescription = getString(
            if (selected) R.string.fullscreen_exit else R.string.fullscreen_enter,
        )
    }

    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(DARK_THEME_KEY, isDarkTheme)
            .apply()
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO,
        )
        recreate()
    }

    private fun updateThemeToggle(button: Button) {
        button.text = getString(if (isDarkTheme) R.string.theme_light else R.string.theme_dark)
        button.contentDescription = getString(
            if (isDarkTheme) R.string.theme_switch_to_light else R.string.theme_switch_to_dark,
        )
    }

    private fun configureWebView(
        webView: WebView,
        profileName: String,
        paneIndex: Int,
        addressBar: EditText,
    ) {
        // This must happen before the WebView loads content or is used.
        WebViewCompat.setProfile(webView, profileName)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.setOnLongClickListener { false }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url)
                addressBar.setSelection(addressBar.text.length)
                persistPaneUrl(paneIndex, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                persistPaneUrl(paneIndex, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.page_load_error, error.description),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun persistPaneUrl(index: Int, url: String) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putString(paneUrlKey(index), url)
            .apply()
    }

    private fun loadInput(webView: WebView, addressBar: EditText) {
        val input = addressBar.text.toString().trim()
        if (input.isEmpty()) return

        addressBar.clearFocus()
        webView.loadUrl(toDestinationUrl(input))
    }

    private fun toDestinationUrl(input: String): String {
        val parsed = Uri.parse(input)
        val hasHttpScheme = parsed.scheme.equals("http", ignoreCase = true) ||
            parsed.scheme.equals("https", ignoreCase = true)

        if (hasHttpScheme && !parsed.host.isNullOrBlank()) {
            return input
        }

        if (Patterns.WEB_URL.matcher(input).matches() && !input.contains(" ")) {
            return "https://$input"
        }

        return "https://www.google.com/search?q=${Uri.encode(input)}"
    }

    private fun showProfileSupportError() {
        val message = getString(R.string.multi_profile_unavailable)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        findViewById<View>(R.id.profile_error).visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        fullscreenPaneIndex?.let { outState.putInt(FULLSCREEN_PANE_KEY, it) }
        panes.forEachIndexed { index, pane ->
            pane.webView.url?.let { url ->
                outState.putString(webViewUrlKey(index), url)
                persistPaneUrl(index, url)
            }
            // Each WebView receives its own Bundle. This avoids the fixed
            // internal keys used by WebView.saveState colliding between panes.
            Bundle().also { webViewState ->
                pane.webView.saveState(webViewState)
                outState.putBundle(webViewStateKey(index), webViewState)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        panes.forEachIndexed { index, pane ->
            pane.webView.url?.let { persistPaneUrl(index, it) }
        }
        super.onPause()
    }

    private fun webViewStateKey(index: Int): String = "$WEBVIEW_STATE_PREFIX$index"

    private fun webViewUrlKey(index: Int): String = "$WEBVIEW_URL_PREFIX$index"

    private fun paneUrlKey(index: Int): String = "$PANE_URL_PREFIX$index"

    override fun onDestroy() {
        // Android keeps the existing WebViews during a handled configuration
        // change. Destroying them here would force every pane to reload when a
        // floating window is entered or resized.
        if (!isChangingConfigurations) {
            panes.forEach { pane ->
                pane.webView.stopLoading()
                pane.webView.destroy()
            }
        }
        panes.clear()
        super.onDestroy()
    }

    private data class PaneDefinition(
        val paneId: Int,
        val addressId: Int,
        val goButtonId: Int,
        val reloadButtonId: Int,
        val fullscreenButtonId: Int,
        val webViewId: Int,
        val profileName: String,
    )
}