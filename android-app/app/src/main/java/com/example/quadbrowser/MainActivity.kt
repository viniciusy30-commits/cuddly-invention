package com.example.quadbrowser

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {
    private companion object {
        const val FULLSCREEN_PANE_KEY = "fullscreen_pane_index"
        const val WEBVIEW_STATE_PREFIX = "webview_state_"
        const val WEBVIEW_URL_PREFIX = "webview_url_"
        const val PANE_URL_PREFIX = "pane_url_"
        const val PANE_OPEN_PREFIX = "pane_open_"
        const val SETTINGS_PREFS = "quad_browser_settings"
        const val DARK_THEME_KEY = "dark_theme"
    }

    private data class BrowserPane(
        val container: View,
        var webView: WebView,
        val addressBar: EditText,
        val fullscreenButton: ImageButton,
        val closeButton: ImageButton,
        val emptyState: View,
        val reopenButton: Button,
        val profileName: String,
        val webViewId: Int,
        var isOpen: Boolean = true,
    )

    private val panes = mutableListOf<BrowserPane>()
    private lateinit var fullscreenOverlay: FrameLayout
    private var fullscreenPaneIndex: Int? = null
    private var isDarkTheme = false

    override fun onCreate(savedInstanceState: Bundle?) {
        isDarkTheme = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(DARK_THEME_KEY, false)
        AppCompatDelegate.setDefaultNightMode(if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fullscreenOverlay = findViewById(R.id.fullscreen_overlay)
        findViewById<ImageButton>(R.id.fullscreen_exit_overlay).setOnClickListener {
            fullscreenPaneIndex = null
            applyPaneLayout()
        }
        findViewById<ImageButton>(R.id.theme_toggle).apply {
            updateThemeToggle(this)
            setOnClickListener { toggleTheme() }
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showProfileSupportError()
            return
        }

        val definitions = listOf(
            PaneDefinition(R.id.pane_1, R.id.address_1, R.id.go_1, R.id.reload_1, R.id.fullscreen_1, R.id.close_1, R.id.empty_state_1, R.id.reopen_1, R.id.webview_1, "webview1"),
            PaneDefinition(R.id.pane_2, R.id.address_2, R.id.go_2, R.id.reload_2, R.id.fullscreen_2, R.id.close_2, R.id.empty_state_2, R.id.reopen_2, R.id.webview_2, "webview2"),
            PaneDefinition(R.id.pane_3, R.id.address_3, R.id.go_3, R.id.reload_3, R.id.fullscreen_3, R.id.close_3, R.id.empty_state_3, R.id.reopen_3, R.id.webview_3, "webview3"),
            PaneDefinition(R.id.pane_4, R.id.address_4, R.id.go_4, R.id.reload_4, R.id.fullscreen_4, R.id.close_4, R.id.empty_state_4, R.id.reopen_4, R.id.webview_4, "webview4"),
        )

        definitions.forEachIndexed { index, definition ->
            val container = findViewById<View>(definition.paneId)
            val webView = findViewById<WebView>(definition.webViewId)
            val addressBar = findViewById<EditText>(definition.addressId)
            val fullscreenButton = findViewById<ImageButton>(definition.fullscreenButtonId)
            val closeButton = findViewById<ImageButton>(definition.closeButtonId)
            val emptyState = findViewById<View>(definition.emptyStateId)
            val reopenButton = findViewById<Button>(definition.reopenButtonId)

            configureWebView(webView, definition.profileName, index, addressBar)
            findViewById<Button>(definition.goButtonId).setOnClickListener { loadInput(webViewFor(index), addressBar) }
            findViewById<ImageButton>(definition.reloadButtonId).setOnClickListener { webViewFor(index).reload() }
            addressBar.setOnEditorActionListener { _, _, _ -> loadInput(webViewFor(index), addressBar); true }
            fullscreenButton.setOnClickListener { toggleFullscreen(index) }
            closeButton.setOnClickListener { setPaneOpen(index, false) }
            reopenButton.setOnClickListener { setPaneOpen(index, true) }

            val pane = BrowserPane(container, webView, addressBar, fullscreenButton, closeButton, emptyState, reopenButton, definition.profileName, definition.webViewId)
            panes += pane
            restorePaneState(index, pane.webView, addressBar, savedInstanceState)
            val open = savedInstanceState?.getBoolean(paneOpenKey(index))
                ?: getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(paneOpenKey(index), true)
            applyPaneOpenUi(index, open)
            pane.webView.contentDescription = getString(R.string.webview_description, index + 1)
        }

        savedInstanceState?.getInt(FULLSCREEN_PANE_KEY, -1)?.takeIf { it in panes.indices }?.let { fullscreenPaneIndex = it }
        applyPaneLayout()
    }

    private fun webViewFor(index: Int): WebView = panes.getOrNull(index)?.webView ?: findViewById(paneWebViewId(index))

    private fun paneWebViewId(index: Int): Int = when (index) {
        0 -> R.id.webview_1
        1 -> R.id.webview_2
        2 -> R.id.webview_3
        else -> R.id.webview_4
    }

    private fun restorePaneState(index: Int, webView: WebView, addressBar: EditText, savedInstanceState: Bundle?) {
        savedInstanceState?.getBundle(webViewStateKey(index))?.let { runCatching { webView.restoreState(it) } }
        val restoredUrl = savedInstanceState?.getString(webViewUrlKey(index))
            ?: getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(paneUrlKey(index), null)
        val currentUrl = webView.url
        if (currentUrl.isNullOrBlank() && !restoredUrl.isNullOrBlank()) webView.loadUrl(restoredUrl)
        else if (!currentUrl.isNullOrBlank()) {
            addressBar.setText(currentUrl)
            addressBar.setSelection(addressBar.text.length)
        }
    }

    private fun toggleFullscreen(index: Int) {
        if (index !in panes.indices || !panes[index].isOpen) return
        fullscreenPaneIndex = if (fullscreenPaneIndex == index) null else index
        applyPaneLayout()
    }

    private fun setPaneOpen(index: Int, open: Boolean) {
        val pane = panes.getOrNull(index) ?: return
        if (pane.isOpen == open) return
        pane.isOpen = open
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putBoolean(paneOpenKey(index), open).apply()
        if (!open && fullscreenPaneIndex == index) fullscreenPaneIndex = null
        applyPaneOpenUi(index, open)
        applyPaneLayout()
    }

    private fun applyPaneOpenUi(index: Int, open: Boolean) {
        val pane = panes.getOrNull(index) ?: return
        pane.isOpen = open
        pane.webView.visibility = if (open) View.VISIBLE else View.GONE
        pane.emptyState.visibility = if (open) View.GONE else View.VISIBLE
        pane.addressBar.isEnabled = open
        pane.fullscreenButton.visibility = if (open) View.VISIBLE else View.GONE
        pane.closeButton.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun recoverRenderer(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        val oldWebView = pane.webView
        val parent = oldWebView.parent as? ViewGroup
        val childIndex = parent?.indexOfChild(oldWebView) ?: -1
        val oldLayoutParams = oldWebView.layoutParams
        parent?.removeView(oldWebView)
        oldWebView.destroy()

        val replacement = WebView(this).apply {
            id = pane.webViewId
            layoutParams = oldLayoutParams ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (parent != null) parent.addView(replacement, childIndex.coerceAtLeast(0))
        pane.webView = replacement
        configureWebView(replacement, pane.profileName, index, pane.addressBar)
        replacement.contentDescription = getString(R.string.webview_description, index + 1)
        setPaneOpen(index, false)
        Toast.makeText(this, getString(R.string.renderer_recovered, index + 1), Toast.LENGTH_LONG).show()
    }

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
        val selectedIndex = fullscreenPaneIndex?.takeIf { it in panes.indices && panes[it].isOpen }
        fullscreenPaneIndex = selectedIndex
        if (selectedIndex == null) exitFullscreenPane() else enterFullscreenPane(selectedIndex)
    }

    private fun enterFullscreenPane(index: Int) {
        val grid = findViewById<GridLayout>(R.id.browser_grid)
        val pane = panes[index]
        if (pane.container.parent !== fullscreenOverlay) {
            (pane.container.parent as? ViewGroup)?.removeView(pane.container)
            fullscreenOverlay.addView(pane.container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        grid.visibility = View.GONE
        fullscreenOverlay.visibility = View.VISIBLE
        pane.container.visibility = View.VISIBLE
        findViewById<View>(R.id.fullscreen_exit_overlay).apply { visibility = View.VISIBLE; bringToFront() }
        panes.forEachIndexed { paneIndex, browserPane -> setFullscreenButtonState(browserPane, paneIndex == index) }
        fullscreenOverlay.requestLayout()
    }

    private fun exitFullscreenPane() {
        val grid = findViewById<GridLayout>(R.id.browser_grid)
        val selectedIndex = panes.indexOfFirst { it.container.parent === fullscreenOverlay }
        if (selectedIndex >= 0) {
            val pane = panes[selectedIndex]
            fullscreenOverlay.removeView(pane.container)
            grid.addView(pane.container, paneLayoutParams(selectedIndex))
        }
        fullscreenOverlay.visibility = View.GONE
        findViewById<View>(R.id.fullscreen_exit_overlay).visibility = View.GONE
        grid.visibility = View.VISIBLE
        panes.forEachIndexed { index, pane ->
            pane.container.visibility = View.VISIBLE
            applyPaneOpenUi(index, pane.isOpen)
            setFullscreenButtonState(pane, false)
        }
        grid.requestLayout()
    }

    private fun paneLayoutParams(index: Int): GridLayout.LayoutParams = GridLayout.LayoutParams(
        GridLayout.spec(index / 2, 1, 1f), GridLayout.spec(index % 2, 1, 1f),
    ).apply { width = 0; height = 0; setMargins(5, 5, 5, 5) }

    private fun setFullscreenButtonState(pane: BrowserPane, selected: Boolean) {
        pane.fullscreenButton.setImageResource(if (selected) R.drawable.ic_close else R.drawable.ic_expand)
        pane.fullscreenButton.contentDescription = getString(if (selected) R.string.fullscreen_exit else R.string.fullscreen_enter)
    }

    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putBoolean(DARK_THEME_KEY, isDarkTheme).apply()
        AppCompatDelegate.setDefaultNightMode(if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        recreate()
    }

    private fun updateThemeToggle(button: ImageButton) {
        button.setImageResource(if (isDarkTheme) R.drawable.ic_sun else R.drawable.ic_moon)
        button.contentDescription = getString(if (isDarkTheme) R.string.theme_switch_to_light else R.string.theme_switch_to_dark)
    }

    private fun configureWebView(webView: WebView, profileName: String, paneIndex: Int, addressBar: EditText) {
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
        webView.setBackgroundColor(getColor(R.color.pane_background))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings, if (isDarkTheme) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
        webView.setOnLongClickListener { false }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url)
                addressBar.setSelection(addressBar.text.length)
                persistPaneUrl(paneIndex, url)
            }
            override fun onPageFinished(view: WebView, url: String) { persistPaneUrl(paneIndex, url) }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (panes.getOrNull(paneIndex)?.webView === view) recoverRenderer(paneIndex)
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) Toast.makeText(this@MainActivity, getString(R.string.page_load_error, error.description), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistPaneUrl(index: Int, url: String) {
        if (url.isBlank()) return
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putString(paneUrlKey(index), url).apply()
    }

    private fun persistAllPaneUrls() {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().also { editor ->
            panes.forEachIndexed { index, pane ->
                pane.webView.url?.takeIf { it.isNotBlank() }?.let { editor.putString(paneUrlKey(index), it) }
                editor.putBoolean(paneOpenKey(index), pane.isOpen)
            }
        }.apply()
    }

    private fun loadInput(webView: WebView, addressBar: EditText) {
        val input = addressBar.text.toString().trim()
        if (input.isEmpty()) return
        addressBar.clearFocus()
        webView.loadUrl(toDestinationUrl(input))
    }

    private fun toDestinationUrl(input: String): String {
        val parsed = Uri.parse(input)
        val hasHttpScheme = parsed.scheme.equals("http", ignoreCase = true) || parsed.scheme.equals("https", ignoreCase = true)
        if (hasHttpScheme && !parsed.host.isNullOrBlank()) return input
        if (Patterns.WEB_URL.matcher(input).matches() && !input.contains(" ")) return "https://$input"
        return "https://www.google.com/search?q=" + Uri.encode(input)
    }

    private fun showProfileSupportError() {
        Toast.makeText(this, getString(R.string.multi_profile_unavailable), Toast.LENGTH_LONG).show()
        findViewById<View>(R.id.profile_error).visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        fullscreenPaneIndex?.let { outState.putInt(FULLSCREEN_PANE_KEY, it) }
        panes.forEachIndexed { index, pane ->
            outState.putBoolean(paneOpenKey(index), pane.isOpen)
            pane.webView.url?.let { outState.putString(webViewUrlKey(index), it); persistPaneUrl(index, it) }
            Bundle().also { state ->
                runCatching { pane.webView.saveState(state) }
                outState.putBundle(webViewStateKey(index), state)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() { persistAllPaneUrls(); super.onPause() }
    override fun onStop() { persistAllPaneUrls(); super.onStop() }

    private fun webViewStateKey(index: Int): String = "$WEBVIEW_STATE_PREFIX$index"
    private fun webViewUrlKey(index: Int): String = "$WEBVIEW_URL_PREFIX$index"
    private fun paneUrlKey(index: Int): String = "$PANE_URL_PREFIX$index"
    private fun paneOpenKey(index: Int): String = "$PANE_OPEN_PREFIX$index"

    override fun onDestroy() {
        persistAllPaneUrls()
        if (!isChangingConfigurations) panes.forEach { it.webView.stopLoading(); it.webView.destroy() }
        panes.clear()
        super.onDestroy()
    }

    private data class PaneDefinition(
        val paneId: Int,
        val addressId: Int,
        val goButtonId: Int,
        val reloadButtonId: Int,
        val fullscreenButtonId: Int,
        val closeButtonId: Int,
        val emptyStateId: Int,
        val reopenButtonId: Int,
        val webViewId: Int,
        val profileName: String,
    )
}
