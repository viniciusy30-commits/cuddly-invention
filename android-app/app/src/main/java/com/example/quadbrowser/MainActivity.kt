package com.example.quadbrowser

import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private companion object {
        const val FULLSCREEN_PANE_KEY = "fullscreen_pane_index"
        const val WEBVIEW_STATE_PREFIX = "webview_state_"
        const val WEBVIEW_URL_PREFIX = "webview_url_"
    }

    private data class BrowserPane(
        val container: View,
        val webView: WebView,
        val addressBar: EditText,
        val fullscreenButton: Button,
    )

    private val panes = mutableListOf<BrowserPane>()
    private var fullscreenPaneIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemUiMode()

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
            configureWebView(webView, definition.profileName, addressBar)

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

            savedInstanceState?.getBundle(webViewStateKey(index))?.let { webViewState ->
                webView.restoreState(webViewState)
            }
            savedInstanceState?.getString(webViewUrlKey(index))?.let { restoredUrl ->
                if (addressBar.text.isNullOrBlank()) {
                    addressBar.setText(restoredUrl)
                }
            }

            panes += BrowserPane(container, webView, addressBar, fullscreenButton)
            webView.contentDescription = getString(R.string.webview_description, index + 1)
        }

        savedInstanceState?.getInt(FULLSCREEN_PANE_KEY, -1)?.takeIf { it in panes.indices }?.let {
            fullscreenPaneIndex = it
        }
        applyPaneLayout()
    }

    private fun toggleFullscreen(index: Int) {
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
        applySystemUiMode()
        window.decorView.post { applyPaneLayout() }
    }

    private fun applyPaneLayout() {
        if (panes.isEmpty()) return

        val grid = findViewById<GridLayout>(R.id.browser_grid)
        val selectedIndex = fullscreenPaneIndex

        if (selectedIndex == null) {
            grid.columnCount = 2
            grid.rowCount = 2
            panes.forEachIndexed { index, pane ->
                pane.container.visibility = View.VISIBLE
                pane.container.layoutParams = paneLayoutParams(index, fullscreen = false)
                setFullscreenButtonState(pane, selected = false)
            }
        } else {
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
        val isFullscreen = fullscreenPaneIndex != null
        val isSelected = isFullscreen && selected
        pane.fullscreenButton.text = getString(
            if (isSelected) R.string.fullscreen_exit_symbol else R.string.fullscreen_enter_symbol,
        )
        pane.fullscreenButton.contentDescription = getString(
            if (isSelected) R.string.fullscreen_exit else R.string.fullscreen_enter,
        )
    }

    private fun applySystemUiMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        } else {
            enableImmersiveMode()
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUiMode()
    }

    private fun configureWebView(
        webView: WebView,
        profileName: String,
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
            // WebView.saveState uses fixed internal keys. A separate Bundle
            // prevents one pane from overwriting another during recreation.
            Bundle().also { webViewState ->
                pane.webView.saveState(webViewState)
                outState.putBundle(webViewStateKey(index), webViewState)
            }
            pane.webView.url?.let { url -> outState.putString(webViewUrlKey(index), url) }
        }
        super.onSaveInstanceState(outState)
    }

    private fun webViewStateKey(index: Int): String = "$WEBVIEW_STATE_PREFIX$index"

    override fun onDestroy() {
        panes.forEach { pane ->
            pane.webView.stopLoading()
            pane.webView.destroy()
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