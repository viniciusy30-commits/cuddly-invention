package com.example.quadbrowser

import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import kotlin.math.abs
import kotlin.math.roundToLong
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
        const val GOOGLE_ACCOUNT_PICKER_REQUEST = 2301
        const val NOTIFICATION_PERMISSION_REQUEST = 4101
    }

    private data class ClickPoint(var x: Float, var y: Float, var intervalMs: Long = 1000L)

    private data class BrowserPane(
        val container: View,
        val thumbnailHost: FrameLayout,
        var webView: WebView,
        val clickLayer: FrameLayout,
        val titleView: TextView,
        val subtitleView: TextView,
        val navigateButton: ImageButton,
        val reloadButton: ImageButton,
        val fullscreenButton: ImageButton,
        val autoClickButton: ImageButton,
        val closeButton: ImageButton,
        val emptyState: View,
        val reopenButton: Button,
        val profileName: String,
        val webViewId: Int,
        var isOpen: Boolean = true,
        var pendingUrl: String? = null,
        var lastUrl: String? = null,
        var lastTitle: String? = null,
        var autoClickRunnable: Runnable? = null,
        var autoClickPoints: MutableList<ClickPoint> = mutableListOf(),
        var autoClickIndex: Int = 0,
        var isAutoClickEditing: Boolean = false,
        var isAutoClicking: Boolean = false,
        var selectedGoogleAccount: String? = null,
        var editorPlayPauseButton: TextView? = null,
    )

    private val panes = mutableListOf<BrowserPane>()
    private lateinit var fullscreenOverlay: FrameLayout
    private var fullscreenPaneIndex: Int? = null
    private var pendingGoogleAccountRequest: Pair<Int, String>? = null
    private var isDarkTheme = false
    private var isActivityVisible = false
    private val autoClickHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        isDarkTheme = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(DARK_THEME_KEY, false)
        AppCompatDelegate.setDefaultNightMode(if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotificationPermissionIfNeeded()

        fullscreenOverlay = findViewById(R.id.fullscreen_overlay)
findViewById<ImageButton>(R.id.theme_toggle).apply {
            updateThemeToggle(this)
            setOnClickListener { toggleTheme() }
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showProfileSupportError()
            return
        }

        val definitions = listOf(
            PaneDefinition(R.id.pane_1, R.id.pane_title_1, R.id.pane_subtitle_1, R.id.navigate_1, R.id.reload_1, R.id.fullscreen_1, R.id.auto_click_1, R.id.close_1, R.id.empty_state_1, R.id.reopen_1, R.id.webview_1, "webview1"),
            PaneDefinition(R.id.pane_2, R.id.pane_title_2, R.id.pane_subtitle_2, R.id.navigate_2, R.id.reload_2, R.id.fullscreen_2, R.id.auto_click_2, R.id.close_2, R.id.empty_state_2, R.id.reopen_2, R.id.webview_2, "webview2"),
            PaneDefinition(R.id.pane_3, R.id.pane_title_3, R.id.pane_subtitle_3, R.id.navigate_3, R.id.reload_3, R.id.fullscreen_3, R.id.auto_click_3, R.id.close_3, R.id.empty_state_3, R.id.reopen_3, R.id.webview_3, "webview3"),
            PaneDefinition(R.id.pane_4, R.id.pane_title_4, R.id.pane_subtitle_4, R.id.navigate_4, R.id.reload_4, R.id.fullscreen_4, R.id.auto_click_4, R.id.close_4, R.id.empty_state_4, R.id.reopen_4, R.id.webview_4, "webview4"),
        )

        definitions.forEachIndexed { index, definition ->
              val grid = findViewById<GridLayout>(R.id.browser_grid)
              val container = findViewById<View>(definition.paneId)
              val thumbnailHost = FrameLayout(this).apply {
                  clipChildren = true
                  clipToPadding = true
              }
              grid.removeView(container)
              grid.addView(thumbnailHost, index, paneLayoutParams(index))
              thumbnailHost.addView(container, FrameLayout.LayoutParams(1, 1))
              val webView = findViewById<WebView>(definition.webViewId)
              val clickLayer = FrameLayout(this).apply {
                  visibility = View.GONE
                  isClickable = false
              }
              (webView.parent as? FrameLayout)?.addView(clickLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
             val pane = BrowserPane(
                   container = container,
                   thumbnailHost = thumbnailHost,
                  webView = webView,
                  clickLayer = clickLayer,
                  titleView = findViewById(definition.titleId),
                subtitleView = findViewById(definition.subtitleId),
                navigateButton = findViewById(definition.navigateButtonId),
                reloadButton = findViewById(definition.reloadButtonId),
                fullscreenButton = findViewById(definition.fullscreenButtonId),
                 autoClickButton = findViewById(definition.autoClickButtonId),
                closeButton = findViewById(definition.closeButtonId),
                emptyState = findViewById(definition.emptyStateId),
                reopenButton = findViewById(definition.reopenButtonId),
                profileName = definition.profileName,
                webViewId = definition.webViewId,
            )
            panes += pane
            configureClickLayer(index)
            configureWebView(pane.webView, pane.profileName, index)
            pane.navigateButton.setOnClickListener { showNavigationDialog(index) }
            pane.reloadButton.setOnClickListener {
                  if (pane.isOpen) {
                      val currentUrl = pane.webView.url ?: pane.lastUrl
                      if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
                          pane.webView.stopLoading()
                          pane.webView.loadUrl(currentUrl)
                      } else {
                          pane.webView.reload()
                      }
                      pane.reloadButton.animate().rotationBy(360f).setDuration(450L).start()
                  }
              }
            pane.fullscreenButton.setOnClickListener { toggleFullscreen(index) }
             pane.autoClickButton.setOnClickListener {
                if (pane.isAutoClickEditing) {
                    hideAutoClickerEditor(index, stop = false)
                } else {
                    beginAutoClickerEditor(index)
                }
            }
            pane.closeButton.setOnClickListener { setPaneOpen(index, false) }
            pane.reopenButton.setOnClickListener { setPaneOpen(index, true) }
            restorePaneState(index, pane.webView, savedInstanceState)
            val open = savedInstanceState?.getBoolean(paneOpenKey(index))
                ?: getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(paneOpenKey(index), true)
            applyPaneOpenUi(index, open)
             setPaneActionState(index)
            pane.webView.contentDescription = getString(R.string.webview_description, index + 1)
        }

        savedInstanceState?.getInt(FULLSCREEN_PANE_KEY, -1)?.takeIf { it in panes.indices }?.let { fullscreenPaneIndex = it }
        applyPaneLayout()
    }

    private fun restorePaneState(index: Int, webView: WebView, savedInstanceState: Bundle?) {
        savedInstanceState?.getBundle(webViewStateKey(index))?.let { runCatching { webView.restoreState(it) } }
        val restoredUrl = savedInstanceState?.getString(webViewUrlKey(index))
            ?: getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(paneUrlKey(index), null)
        val pane = panes.getOrNull(index)
        pane?.lastUrl = webView.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: restoredUrl
        if (webView.url.isNullOrBlank() && !restoredUrl.isNullOrBlank()) {
            webView.loadUrl(restoredUrl)
        }
    }

    private fun showNavigationDialog(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        if (!pane.isOpen) return
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.address_hint)
            setText(pane.lastUrl ?: pane.webView.url.orEmpty())
            setSelection(text.length)
            setPadding(18, 0, 18, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.navigate_pane, index + 1))
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.go) { _, _ -> loadInput(pane.webView, input.text.toString()) }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun maybeChooseGoogleAccount(index: Int, rawUrl: String): Boolean {
        val pane = panes.getOrNull(index) ?: return false
        if (!isGoogleSignInUrl(rawUrl) || pane.selectedGoogleAccount != null) return false

        pendingGoogleAccountRequest = index to rawUrl
        val chooser = AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            true,
            getString(R.string.google_account_picker_description),
            null,
            null,
            null,
        )
        return runCatching {
            startActivityForResult(chooser, GOOGLE_ACCOUNT_PICKER_REQUEST)
            true
        }.getOrElse {
            pendingGoogleAccountRequest = null
            false
        }
    }

    private fun isGoogleSignInUrl(rawUrl: String): Boolean {
        val uri = Uri.parse(rawUrl)
        val host = uri.host?.lowercase() ?: return false
        if (host != "accounts.google.com" && !host.endsWith(".accounts.google.com")) return false
        return listOf("signin", "servicelogin", "accountchooser", "oauth", "identifier")
            .any { rawUrl.contains(it, ignoreCase = true) }
    }

    private fun addGoogleAccountHint(rawUrl: String, accountName: String): String =
        Uri.parse(rawUrl).buildUpon()
            .appendQueryParameter("Email", accountName)
            .appendQueryParameter("login_hint", accountName)
            .build()
            .toString()

    private fun prefillGoogleAccount(webView: WebView, accountName: String) {
        val escapedAccount = org.json.JSONObject.quote(accountName)
        webView.evaluateJavascript(
            """
            (() => {
              const input = document.querySelector('input[type="email"], input[name="identifier"]');
              if (!input) return;
              const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
              if (setter) setter.call(input, $escapedAccount); else input.value = $escapedAccount;
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
            })();
            """.trimIndent(),
            null,
        )
    }

    @Deprecated("Deprecated in Android API Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GOOGLE_ACCOUNT_PICKER_REQUEST) return

        val pending = pendingGoogleAccountRequest ?: return
        pendingGoogleAccountRequest = null
        val pane = panes.getOrNull(pending.first) ?: return
        val accountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (accountName.isNullOrBlank()) {
            Toast.makeText(this, R.string.google_account_not_selected, Toast.LENGTH_SHORT).show()
            pane.selectedGoogleAccount = "picker_cancelled"
            pane.webView.loadUrl(pending.second)
            return
        }
        pane.selectedGoogleAccount = accountName
        pane.webView.loadUrl(addGoogleAccountHint(pending.second, accountName))
    }

    private fun toggleFullscreen(index: Int) {
        if (index !in panes.indices || !panes[index].isOpen) return
        if (fullscreenPaneIndex != null && fullscreenPaneIndex != index) {
            fullscreenPaneIndex = null
            applyPaneLayout()
        }
        fullscreenPaneIndex = if (fullscreenPaneIndex == index) null else index
        applyPaneLayout()
    }

    private fun setPaneOpen(index: Int, open: Boolean) {
        val pane = panes.getOrNull(index) ?: return
        pane.isOpen = open
        if (!open && fullscreenPaneIndex == index) fullscreenPaneIndex = null
        if (open) {
            val urlToResume = pane.pendingUrl ?: pane.lastUrl
            if (!urlToResume.isNullOrBlank() && pane.webView.url.isNullOrBlank()) {
                pane.webView.loadUrl(urlToResume)
                pane.pendingUrl = null
            }
        }
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putBoolean(paneOpenKey(index), open).apply()
        applyPaneOpenUi(index, open)
        applyPaneLayout()
    }

    private fun applyPaneOpenUi(index: Int, open: Boolean) {
        val pane = panes.getOrNull(index) ?: return
        pane.isOpen = open
        pane.webView.visibility = if (open) View.VISIBLE else View.INVISIBLE
        pane.emptyState.visibility = if (open) View.GONE else View.VISIBLE
        pane.clickLayer.visibility = if (open && pane.isAutoClickEditing) View.VISIBLE else View.GONE
        pane.navigateButton.visibility = if (open) View.VISIBLE else View.GONE
        pane.reloadButton.visibility = if (open) View.VISIBLE else View.GONE
        pane.fullscreenButton.visibility = if (open) View.VISIBLE else View.GONE
        pane.closeButton.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            val title = pane.lastTitle?.takeIf { it.isNotBlank() } ?: if (pane.lastUrl.isNullOrBlank()) getString(R.string.ready_to_browse) else getString(R.string.page_ready)
            pane.titleView.text = title
            pane.subtitleView.text = getString(R.string.instance_active, index + 1)
        } else {
            pane.titleView.text = getString(R.string.instance_paused)
            pane.subtitleView.text = getString(R.string.instance_paused_detail)
        }
    }

    private fun recoverRenderer(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        val savedUrl = pane.lastUrl ?: pane.webView.url ?: getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(paneUrlKey(index), null)
        val oldWebView = pane.webView
        val parent = oldWebView.parent as? ViewGroup
        val childIndex = parent?.indexOfChild(oldWebView) ?: -1
        val oldLayoutParams = oldWebView.layoutParams
        parent?.removeView(oldWebView)
        oldWebView.stopLoading()
        oldWebView.destroy()

        val replacement = WebView(this).apply {
            id = pane.webViewId
            layoutParams = oldLayoutParams ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (parent != null) parent.addView(replacement, childIndex.coerceAtLeast(0))
        pane.webView = replacement
        pane.pendingUrl = savedUrl
        pane.lastTitle = null
        configureWebView(replacement, pane.profileName, index)
        replacement.contentDescription = getString(R.string.webview_description, index + 1)
        setPaneOpen(index, false)
        Toast.makeText(this, getString(R.string.renderer_recovered, index + 1), Toast.LENGTH_LONG).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post {
            applyPaneLayout()
            refreshAutoClickEditors()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        window.decorView.post {
            applyPaneLayout()
            refreshAutoClickEditors()
        }
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
        findViewById<View>(R.id.app_toolbar).visibility = View.GONE
        if (pane.container.parent !== fullscreenOverlay) {
            (pane.container.parent as? ViewGroup)?.removeView(pane.container)
            pane.container.scaleX = 1f
            pane.container.scaleY = 1f
            pane.container.translationX = 0f
            pane.container.translationY = 0f
            fullscreenOverlay.addView(pane.container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        grid.visibility = View.GONE
        fullscreenOverlay.visibility = View.VISIBLE
        pane.container.visibility = View.VISIBLE
        panes.forEachIndexed { paneIndex, browserPane ->
            setFullscreenButtonState(browserPane, paneIndex == index)
            setPaneActionState(paneIndex)
        }
        fullscreenOverlay.requestLayout()
        pane.clickLayer.post { renderAutoClickEditor(index) }
    }

    private fun exitFullscreenPane() {
        val grid = findViewById<GridLayout>(R.id.browser_grid)
        val selectedIndex = panes.indexOfFirst { it.container.parent === fullscreenOverlay }
        if (selectedIndex >= 0) {
            val pane = panes[selectedIndex]
            fullscreenOverlay.removeView(pane.container)
            pane.thumbnailHost.addView(pane.container, FrameLayout.LayoutParams(1, 1))
        }
        fullscreenOverlay.visibility = View.GONE
        findViewById<View>(R.id.app_toolbar).visibility = View.VISIBLE
grid.visibility = View.VISIBLE
        panes.forEachIndexed { index, pane ->
            pane.container.visibility = View.VISIBLE
            applyPaneOpenUi(index, pane.isOpen)
            setFullscreenButtonState(pane, false)
            setPaneActionState(index)
        }
        grid.requestLayout()
        grid.post {
            refreshGridPaneThumbnails()
            refreshAutoClickEditors()
        }
    }

    private fun refreshGridPaneThumbnails() {
        val browserContent = findViewById<View>(R.id.browser_content)
        val targetWidth = (fullscreenOverlay.width.takeIf { it > 0 } ?: browserContent.width).coerceAtLeast(1)
        val targetHeight = (fullscreenOverlay.height.takeIf { it > 0 } ?: browserContent.height).coerceAtLeast(1)

        panes.forEach { pane ->
            if (pane.container.parent !== pane.thumbnailHost) return@forEach
            val hostWidth = pane.thumbnailHost.width
            val hostHeight = pane.thumbnailHost.height
            if (hostWidth <= 0 || hostHeight <= 0) return@forEach

            val scale = minOf(
                hostWidth.toFloat() / targetWidth,
                hostHeight.toFloat() / targetHeight,
            )
            pane.container.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight)
            pane.container.pivotX = 0f
            pane.container.pivotY = 0f
            pane.container.scaleX = scale
            pane.container.scaleY = scale
            pane.container.translationX = (hostWidth - targetWidth * scale) / 2f
            pane.container.translationY = (hostHeight - targetHeight * scale) / 2f
        }
    }

    private fun refreshAutoClickEditors() {
        panes.forEachIndexed { index, pane ->
            if (pane.isAutoClickEditing) {
                pane.clickLayer.post { renderAutoClickEditor(index) }
            }
        }
    }

    private fun paneLayoutParams(index: Int): GridLayout.LayoutParams = GridLayout.LayoutParams(
        GridLayout.spec(index / 2, 1, 1f), GridLayout.spec(index % 2, 1, 1f),
    ).apply { width = 0; height = 0; setMargins(7, 7, 7, 7) }

    private fun setFullscreenButtonState(pane: BrowserPane, selected: Boolean) {
        pane.fullscreenButton.setImageResource(R.drawable.ic_expand)
        pane.fullscreenButton.setBackgroundResource(R.drawable.bg_icon_button)
        pane.fullscreenButton.setColorFilter(getColor(R.color.text_primary))
        pane.fullscreenButton.contentDescription = getString(if (selected) R.string.fullscreen_exit else R.string.fullscreen_enter)
    }


    private fun setPaneActionState(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        pane.autoClickButton.setImageResource(R.drawable.ic_auto_click)
        pane.autoClickButton.setBackgroundResource(if (pane.isAutoClicking) R.drawable.bg_danger_button else R.drawable.bg_icon_button)
        pane.autoClickButton.setColorFilter(getColor(if (pane.isAutoClicking) R.color.danger else R.color.text_primary))
        pane.autoClickButton.contentDescription = getString(if (pane.isAutoClicking) R.string.stop_auto_clicker else R.string.open_auto_clicker)
        pane.autoClickButton.visibility = if (pane.isOpen) View.VISIBLE else View.GONE
        pane.editorPlayPauseButton?.apply {
            text = if (pane.isAutoClicking) "Ⅱ" else "▶"
            contentDescription = getString(if (pane.isAutoClicking) R.string.auto_clicker_pause else R.string.auto_clicker_play)
        }
        pane.closeButton.setImageResource(R.drawable.ic_close)
        pane.closeButton.setBackgroundResource(R.drawable.bg_danger_button)
        pane.closeButton.setColorFilter(getColor(R.color.danger))
        pane.closeButton.contentDescription = getString(R.string.close_instance)
        pane.closeButton.visibility = if (pane.isOpen && fullscreenPaneIndex != index) View.VISIBLE else View.GONE
        pane.closeButton.setOnClickListener { setPaneOpen(index, false) }
    }


    private fun configureClickLayer(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          pane.clickLayer.setOnTouchListener { _, _ -> false }
      }

      private fun beginAutoClickerEditor(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          if (!pane.isAutoClicking) stopAutoClicker(index)
          pane.isAutoClickEditing = true
          pane.clickLayer.visibility = View.VISIBLE
          pane.clickLayer.isClickable = true
          pane.clickLayer.post { renderAutoClickEditor(index) }
      }

      private fun hideAutoClickerEditor(index: Int, stop: Boolean = true) {
        val pane = panes.getOrNull(index) ?: return
        if (stop) stopAutoClicker(index)
        pane.isAutoClickEditing = false
        pane.clickLayer.isClickable = false
        pane.clickLayer.visibility = View.GONE
        pane.editorPlayPauseButton = null
        pane.clickLayer.removeAllViews()
    }

    private fun addAutoClickPointAtCenter(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          if (!pane.isAutoClickEditing || pane.isAutoClicking) return
          pane.autoClickPoints.add(ClickPoint(0.5f, 0.5f))
          renderAutoClickEditor(index)
      }

      private fun removeAllAutoClickPoints(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          stopAutoClicker(index)
          pane.autoClickPoints.clear()
          pane.isAutoClickEditing = true
          pane.clickLayer.visibility = View.VISIBLE
          renderAutoClickEditor(index)
      }

      private fun showPointIntervalDialog(index: Int, pointIndex: Int) {
          val pane = panes.getOrNull(index) ?: return
          val point = pane.autoClickPoints.getOrNull(pointIndex) ?: return
          val unitLabels = arrayOf(getString(R.string.auto_clicker_unit_seconds), getString(R.string.auto_clicker_unit_minutes))
          val unitSpinner = Spinner(this).apply {
              adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, unitLabels).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
          }
          val useMinutes = point.intervalMs >= 60000L && point.intervalMs % 60000L == 0L
          unitSpinner.setSelection(if (useMinutes) 1 else 0)
          val amount = EditText(this).apply {
              inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
              setSingleLine(true)
              hint = getString(R.string.auto_clicker_point_interval_hint)
              setText(if (useMinutes) (point.intervalMs / 60000L).toString() else (point.intervalMs / 1000.0).toString().trimEnd('0').trimEnd('.'))
              setSelection(text.length)
          }
          val content = LinearLayout(this).apply {
               orientation = LinearLayout.VERTICAL
               setPadding(dp(20), dp(4), dp(20), 0)
               addView(TextView(this@MainActivity).apply {
                   text = getString(R.string.auto_clicker_interval_label)
                   setTextColor(getColor(R.color.text_secondary))
                   setTextSize(12f)
               }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
               addView(LinearLayout(this@MainActivity).apply {
                   orientation = LinearLayout.HORIZONTAL
                   gravity = Gravity.CENTER_VERTICAL
                   addView(amount, LinearLayout.LayoutParams(0, dp(52), 1.5f))
                   addView(unitSpinner, LinearLayout.LayoutParams(0, dp(52), 1f))
               }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
          }
          val dialog = AlertDialog.Builder(this)
              .setTitle(getString(R.string.auto_clicker_point_title, pointIndex + 1))
              .setMessage(R.string.auto_clicker_point_message)
              .setView(content)
              .setNegativeButton(R.string.cancel, null)
              .setPositiveButton(R.string.auto_clicker_save, null)
              .create()
          dialog.setOnShowListener {
              dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                  val value = amount.text.toString().trim().toDoubleOrNull()
                  val multiplier = if (unitSpinner.selectedItemPosition == 1) 60000.0 else 1000.0
                  val intervalMs = value?.times(multiplier)?.roundToLong()
                  if (intervalMs == null || intervalMs < 100L || intervalMs > 3600000L) {
                      Toast.makeText(this, R.string.auto_clicker_invalid_point_interval, Toast.LENGTH_SHORT).show()
                  } else {
                      point.intervalMs = intervalMs
                      renderAutoClickEditor(index)
                      dialog.dismiss()
                  }
              }
          }
          dialog.show()
      }

      private fun formatInterval(intervalMs: Long): String = when {
          intervalMs % 60000L == 0L -> (intervalMs / 60000L).toString() + " min"
          intervalMs % 1000L == 0L -> (intervalMs / 1000L).toString() + " s"
          else -> intervalMs.toString() + " ms"
      }

      private fun renderAutoClickEditor(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          if (!pane.isAutoClickEditing) return
          val layer = pane.clickLayer
          val layerWidth = layer.width.coerceAtLeast(1).toFloat()
          val layerHeight = layer.height.coerceAtLeast(1).toFloat()
          layer.visibility = View.VISIBLE
          layer.removeAllViews()
           val markerSize = dp(28)
          pane.autoClickPoints.forEachIndexed { pointIndex, point ->
              val marker = TextView(this).apply {
                  text = (pointIndex + 1).toString() + "\n" + formatInterval(point.intervalMs)
                  gravity = Gravity.CENTER
                  setTextColor(getColor(R.color.text_primary))
                   setTextSize(9f)
                   includeFontPadding = false
                   setBackgroundResource(R.drawable.bg_auto_click_marker)
                  elevation = dp(3).toFloat()
                  layoutParams = FrameLayout.LayoutParams(markerSize, markerSize)
                  x = (point.x.coerceIn(0f, 1f) * layerWidth - markerSize / 2f).coerceIn(0f, (layer.width - markerSize).coerceAtLeast(0).toFloat())
                  y = (point.y.coerceIn(0f, 1f) * layerHeight - markerSize / 2f).coerceIn(0f, (layer.height - markerSize).coerceAtLeast(0).toFloat())
              }
              var startRawX = 0f
              var startRawY = 0f
              var startViewX = 0f
              var startViewY = 0f
              var moved = false
              var longPressTriggered = false
              var longPressAction: Runnable? = null
              marker.setOnTouchListener { view, event ->
                  if (pane.isAutoClicking) return@setOnTouchListener false
                  when (event.actionMasked) {
                      MotionEvent.ACTION_DOWN -> {
                          startRawX = event.rawX
                          startRawY = event.rawY
                          startViewX = view.x
                          startViewY = view.y
                          moved = false
                          longPressTriggered = false
                          longPressAction = Runnable {
                              if (!moved && !pane.isAutoClicking) {
                                  longPressTriggered = true
                                  showPointIntervalDialog(index, pointIndex)
                              }
                          }.also { autoClickHandler.postDelayed(it, 450L) }
                          true
                      }
                      MotionEvent.ACTION_MOVE -> {
                          val dx = event.rawX - startRawX
                          val dy = event.rawY - startRawY
                          moved = moved || abs(dx) > dp(6) || abs(dy) > dp(6)
                          if (moved) longPressAction?.let(autoClickHandler::removeCallbacks)
                          view.x = (startViewX + dx).coerceIn(0f, (layer.width - markerSize).coerceAtLeast(0).toFloat())
                          view.y = (startViewY + dy).coerceIn(0f, (layer.height - markerSize).coerceAtLeast(0).toFloat())
                          true
                      }
                      MotionEvent.ACTION_UP -> {
                          longPressAction?.let(autoClickHandler::removeCallbacks)
                          if (moved) {
                              point.x = ((view.x + markerSize / 2f) / layerWidth).coerceIn(0f, 1f)
                              point.y = ((view.y + markerSize / 2f) / layerHeight).coerceIn(0f, 1f)
                          } else if (!longPressTriggered && pointIndex in pane.autoClickPoints.indices) {
                              pane.autoClickPoints.removeAt(pointIndex)
                              Toast.makeText(this, R.string.auto_clicker_point_removed, Toast.LENGTH_SHORT).show()
                              renderAutoClickEditor(index)
                          }
                          true
                      }
                      MotionEvent.ACTION_CANCEL -> {
                          longPressAction?.let(autoClickHandler::removeCallbacks)
                          true
                      }
                      else -> true
                  }
              }
layer.addView(marker)
          }
          val panel = LinearLayout(this).apply {
              orientation = LinearLayout.VERTICAL
              setPadding(dp(8), dp(5), dp(8), dp(5))
              setBackgroundResource(R.drawable.bg_pane_toolbar)
              elevation = dp(7).toFloat()
          }
          val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
          val hint = TextView(this).apply {
              text = getString(R.string.auto_clicker_edit_hint)
              setTextColor(getColor(R.color.text_primary))
              setTextSize(11f)
              maxLines = 2
              setPadding(dp(3), 0, dp(4), 0)
          }
          header.addView(hint, LinearLayout.LayoutParams(0, dp(34), 1f))
          panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
          val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
          fun compactAction(label: String, description: Int, click: () -> Unit): TextView = TextView(this).apply {
              text = label
              gravity = Gravity.CENTER
              setTextColor(getColor(R.color.text_primary))
              setTextSize(11f)
              contentDescription = getString(description)
              setBackgroundResource(R.drawable.bg_icon_button)
              setPadding(dp(5), 0, dp(5), 0)
              setOnClickListener { click() }
          }
          val playPause = compactAction(if (pane.isAutoClicking) "Ⅱ" else "▶", if (pane.isAutoClicking) R.string.auto_clicker_pause else R.string.auto_clicker_play) {
               if (pane.isAutoClicking) {
                   stopAutoClicker(index, true)
                   pane.isAutoClickEditing = true
                   renderAutoClickEditor(index)
               } else {
                   startAutoClicker(index)
               }
           }
           pane.editorPlayPauseButton = playPause
           row.addView(playPause, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(0, dp(3), dp(3), 0) })
           row.addView(compactAction("+", R.string.auto_clicker_add_point) { addAutoClickPointAtCenter(index) }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(0, dp(3), dp(3), 0) })
           row.addView(compactAction("⌫", R.string.auto_clicker_remove) { removeAllAutoClickPoints(index) }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(0, dp(3), dp(3), 0) })
panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
          layer.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })
      }

      private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

      private fun startAutoClicker(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          if (pane.autoClickPoints.isEmpty()) {
              Toast.makeText(this, R.string.auto_clicker_need_point, Toast.LENGTH_SHORT).show()
              return
          }
          stopAutoClicker(index)
          pane.autoClickIndex = 0
          pane.isAutoClicking = true
          pane.isAutoClickEditing = true
          pane.clickLayer.visibility = View.VISIBLE
           startAutoClickBackgroundService()
          val runnable = object : Runnable {
              override fun run() {
                  val currentPane = panes.getOrNull(index)
                  if (currentPane == null || currentPane.webView.width <= 0 || currentPane.webView.height <= 0) {
                      stopAutoClicker(index)
                      return
                  }
                  val point = currentPane.autoClickPoints.getOrNull(currentPane.autoClickIndex)
                  if (point == null) {
                      stopAutoClicker(index)
                      return
                  }
                  dispatchClick(currentPane.webView, point)
                  currentPane.autoClickIndex = (currentPane.autoClickIndex + 1) % currentPane.autoClickPoints.size
                  autoClickHandler.postDelayed(this, point.intervalMs.coerceAtLeast(100L))
              }
          }
          pane.autoClickRunnable = runnable
          setPaneActionState(index)
          autoClickHandler.post(runnable)
          Toast.makeText(this, R.string.auto_clicker_started, Toast.LENGTH_SHORT).show()
      }

      private fun stopAutoClicker(index: Int, notify: Boolean = false) {
          val pane = panes.getOrNull(index) ?: return
          pane.autoClickRunnable?.let(autoClickHandler::removeCallbacks)
          pane.autoClickRunnable = null
          pane.autoClickIndex = 0
          pane.isAutoClicking = false
          setPaneActionState(index)
           stopAutoClickBackgroundServiceIfIdle()
          if (notify) Toast.makeText(this, R.string.auto_clicker_stopped, Toast.LENGTH_SHORT).show()
      }

       private fun startAutoClickBackgroundService() {
           startBackgroundService(AutoClickForegroundService.ACTION_START)
       }

       private fun startBackgroundServiceForCurrentState() {
           val action = if (panes.any { it.isAutoClicking }) {
               AutoClickForegroundService.ACTION_START
           } else {
               AutoClickForegroundService.ACTION_START_BROWSER
           }
           startBackgroundService(action)
       }

       private fun startBackgroundService(action: String) {
           val serviceIntent = Intent(this, AutoClickForegroundService::class.java)
               .setAction(action)
           try {
               ContextCompat.startForegroundService(this, serviceIntent)
           } catch (error: IllegalStateException) {
               Log.w("QuadBrowser", "Unable to start background service", error)
           }
       }

       private fun stopAutoClickBackgroundServiceIfIdle() {
           if (panes.any { it.isAutoClicking } || !isActivityVisible) return
           stopService(Intent(this, AutoClickForegroundService::class.java))
       }

       private fun requestNotificationPermissionIfNeeded() {
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
               ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
           ) {
               requestPermissions(
                   arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                   NOTIFICATION_PERMISSION_REQUEST,
               )
           }
       }

      private fun dispatchClick(webView: WebView, point: ClickPoint) {
          val maxX = (webView.width - 1).coerceAtLeast(1).toFloat()
          val maxY = (webView.height - 1).coerceAtLeast(1).toFloat()
          val x = (point.x.coerceIn(0f, 1f) * maxX).coerceIn(0f, maxX)
           val y = (point.y.coerceIn(0f, 1f) * maxY).coerceIn(0f, maxY)
          val downTime = SystemClock.uptimeMillis()
          val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
          val up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, x, y, 0)
          webView.dispatchTouchEvent(down)
          webView.dispatchTouchEvent(up)
          down.recycle()
          up.recycle()
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

    private fun configureWebView(webView: WebView, profileName: String, paneIndex: Int) {
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
                updatePaneIdentity(paneIndex, url, null)
                persistPaneUrl(paneIndex, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                updatePaneIdentity(paneIndex, url, view.title)
                persistPaneUrl(paneIndex, url)
                panes.getOrNull(paneIndex)?.selectedGoogleAccount
                    ?.takeUnless { it == "picker_cancelled" }
                    ?.takeIf { isGoogleSignInUrl(url) }
                    ?.let { prefillGoogleAccount(view, it) }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                maybeChooseGoogleAccount(paneIndex, request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                maybeChooseGoogleAccount(paneIndex, url)

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

    private fun updatePaneIdentity(index: Int, url: String?, pageTitle: String?) {
        val pane = panes.getOrNull(index) ?: return
        if (!url.isNullOrBlank() && url != "about:blank") pane.lastUrl = url
        if (!pageTitle.isNullOrBlank()) pane.lastTitle = pageTitle
        if (!pane.isOpen) return
        pane.titleView.text = pane.lastTitle?.takeIf { it.isNotBlank() } ?: if (pane.lastUrl.isNullOrBlank()) getString(R.string.ready_to_browse) else getString(R.string.page_ready)
        pane.subtitleView.text = getString(R.string.instance_active, index + 1)
    }

    private fun persistPaneUrl(index: Int, url: String) {
        if (url.isBlank() || url == "about:blank") return
        panes.getOrNull(index)?.lastUrl = url
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putString(paneUrlKey(index), url).apply()
    }

    private fun persistAllPaneState() {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().also { editor ->
            panes.forEachIndexed { index, pane ->
                (pane.lastUrl ?: pane.webView.url)?.takeIf { it.isNotBlank() && it != "about:blank" }?.let { editor.putString(paneUrlKey(index), it) }
                editor.putBoolean(paneOpenKey(index), pane.isOpen)
            }
        }.apply()
    }

    private fun loadInput(webView: WebView, rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return
        webView.loadUrl(toDestinationUrl(input))
    }

    private fun toDestinationUrl(input: String): String {
        val parsed = Uri.parse(input)
        val hasHttpScheme = parsed.scheme.equals("http", ignoreCase = true) || parsed.scheme.equals("https", ignoreCase = true)
        if (hasHttpScheme && !parsed.host.isNullOrBlank()) return input
        if (Patterns.WEB_URL.matcher(input).matches() && !input.contains(" ")) return "https://" + input
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
            pane.lastUrl?.let { outState.putString(webViewUrlKey(index), it) }
            Bundle().also { state ->
                runCatching { pane.webView.saveState(state) }
                outState.putBundle(webViewStateKey(index), state)
            }
        }
        persistAllPaneState()
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        if (!panes.any { it.isAutoClicking }) {
            stopService(Intent(this, AutoClickForegroundService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        if (!panes.any { it.isAutoClicking }) {
            stopService(Intent(this, AutoClickForegroundService::class.java))
        }
    }

    override fun onPause() {
        persistAllPaneState()
        if (!isChangingConfigurations) startBackgroundServiceForCurrentState()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        if (!isChangingConfigurations) startBackgroundServiceForCurrentState()
        super.onUserLeaveHint()
    }

    override fun onStop() {
        isActivityVisible = false
        persistAllPaneState()
        if (!isChangingConfigurations && !isFinishing) startBackgroundServiceForCurrentState()
        super.onStop()
    }

    private fun webViewStateKey(index: Int): String = WEBVIEW_STATE_PREFIX + index
    private fun webViewUrlKey(index: Int): String = WEBVIEW_URL_PREFIX + index
    private fun paneUrlKey(index: Int): String = PANE_URL_PREFIX + index
    private fun paneOpenKey(index: Int): String = PANE_OPEN_PREFIX + index

    override fun onDestroy() {
        persistAllPaneState()
        if (!isChangingConfigurations) panes.forEach { it.webView.stopLoading(); it.webView.destroy() }
        panes.clear()
        super.onDestroy()
    }

    private data class PaneDefinition(
        val paneId: Int,
        val titleId: Int,
        val subtitleId: Int,
        val navigateButtonId: Int,
        val reloadButtonId: Int,
        val fullscreenButtonId: Int,
        val autoClickButtonId: Int,
        val closeButtonId: Int,
        val emptyStateId: Int,
        val reopenButtonId: Int,
        val webViewId: Int,
        val profileName: String,
    )
}
