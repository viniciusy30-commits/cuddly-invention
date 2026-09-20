package com.example.quadbrowser

import android.accounts.AccountManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebStorage
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
import org.json.JSONArray
import org.json.JSONObject
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {
    private companion object {
        const val FULLSCREEN_PANE_KEY = "fullscreen_pane_index"
        const val WEBVIEW_STATE_PREFIX = "webview_state_"
        const val WEBVIEW_URL_PREFIX = "webview_url_"
        const val PANE_URL_PREFIX = "pane_url_"
        const val PANE_OPEN_PREFIX = "pane_open_"
        const val PANE_PAUSED_PREFIX = "pane_paused_"
        const val PANE_ORDER_KEY = "pane_order"
        const val PANE_NAME_PREFIX = "pane_name_"
        const val PANE_COLOR_PREFIX = "pane_color_"
        const val PANE_AVATAR_PREFIX = "pane_avatar_"
        const val AUTO_PRESET_PREFIX = "auto_preset_"
        const val AUTO_DELETED_PRESET_PREFIX = "auto_deleted_preset_"
        const val SETTINGS_PREFS = "quad_browser_settings"
        const val DARK_THEME_KEY = "dark_theme"
        const val GOOGLE_ACCOUNT_PICKER_REQUEST = 2301
        const val GOOGLE_ACCOUNT_PERMISSION_REQUEST = 2302
        const val NOTIFICATION_PERMISSION_REQUEST = 4101
    }

    private data class ClickPoint(var x: Float, var y: Float, var intervalMs: Long = 1000L)

    private data class AutoClickPreset(val name: String, val points: List<ClickPoint>)

    private data class BrowserPane(
        val container: View,
        val thumbnailHost: FrameLayout,
        var webView: WebView,
        val avatarView: TextView,
        val dragHandle: View,
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
        var isPaused: Boolean = false,
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
        var gridScalePercent: Int? = null,
        var gridTransformApplied: Boolean = false,
        var gridReferenceWidth: Int = 0,
        var gridReferenceHeight: Int = 0,
        var gridHostWidth: Int = 0,
        var gridHostHeight: Int = 0,
        var gridScale: Float = 0f,
    )

    private val panes = mutableListOf<BrowserPane>()
    private lateinit var fullscreenOverlay: FrameLayout
    private var fullscreenPaneIndex: Int? = null
    private var paneOrder = mutableListOf(0, 1, 2, 3)
    private val defaultPaneNames = listOf("Conta principal", "Conta secundária", "Conta de trocas", "Conta de farm")
    private val paneColorOptions = listOf("#5869DD", "#D94F66", "#00A896", "#F0A202", "#7B61FF")
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
        findViewById<View>(R.id.browser_content).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            refreshGridPaneThumbnails()
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
            PaneDefinition(R.id.pane_1, R.id.avatar_1, R.id.pane_title_1, R.id.pane_drag_handle_1, R.id.pane_subtitle_1, R.id.navigate_1, R.id.reload_1, R.id.fullscreen_1, R.id.auto_click_1, R.id.close_1, R.id.empty_state_1, R.id.reopen_1, R.id.webview_1, "webview1"),
            PaneDefinition(R.id.pane_2, R.id.avatar_2, R.id.pane_title_2, R.id.pane_drag_handle_2, R.id.pane_subtitle_2, R.id.navigate_2, R.id.reload_2, R.id.fullscreen_2, R.id.auto_click_2, R.id.close_2, R.id.empty_state_2, R.id.reopen_2, R.id.webview_2, "webview2"),
            PaneDefinition(R.id.pane_3, R.id.avatar_3, R.id.pane_title_3, R.id.pane_drag_handle_3, R.id.pane_subtitle_3, R.id.navigate_3, R.id.reload_3, R.id.fullscreen_3, R.id.auto_click_3, R.id.close_3, R.id.empty_state_3, R.id.reopen_3, R.id.webview_3, "webview3"),
            PaneDefinition(R.id.pane_4, R.id.avatar_4, R.id.pane_title_4, R.id.pane_drag_handle_4, R.id.pane_subtitle_4, R.id.navigate_4, R.id.reload_4, R.id.fullscreen_4, R.id.auto_click_4, R.id.close_4, R.id.empty_state_4, R.id.reopen_4, R.id.webview_4, "webview4"),
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
              thumbnailHost.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
                  avatarView = findViewById(definition.avatarId),
                  clickLayer = clickLayer,
                  titleView = findViewById(definition.titleId),
                dragHandle = findViewById(definition.dragHandleId),
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
            thumbnailHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                refreshGridPaneThumbnails()
            }
            applyPaneIdentityUi(index)
            configureClickLayer(index)
            configureWebView(pane.webView, pane.profileName, index)
            pane.navigateButton.setOnClickListener { showNavigationDialog(index) }
            pane.titleView.setOnClickListener { showPaneIdentityDialog(index) }
            pane.avatarView.setOnClickListener { showPaneIdentityDialog(index) }
            configurePaneReordering(index)
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
            val preferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            val open = savedInstanceState?.getBoolean(paneOpenKey(index))
                ?: preferences.getBoolean(paneOpenKey(index), true)
            pane.isPaused = savedInstanceState?.getBoolean(panePausedKey(index))
                ?: preferences.getBoolean(panePausedKey(index), false)
            applyPaneOpenUi(index, open)
             setPaneActionState(index)
            pane.webView.contentDescription = getString(R.string.webview_description, index + 1)
        }

        paneOrder = loadPaneOrder(savedInstanceState)
        savedInstanceState?.getInt(FULLSCREEN_PANE_KEY, -1)?.takeIf { it in panes.indices }?.let { fullscreenPaneIndex = it }
        applyPaneLayout()
    }

    private fun restorePaneState(index: Int, webView: WebView, savedInstanceState: Bundle?) {
        savedInstanceState?.getBundle(webViewStateKey(index))?.let { runCatching { webView.restoreState(it) } }
        val restoredUrl = savedInstanceState?.getString(webViewUrlKey(index))
            ?: getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(paneUrlKey(index), null)
        val pane = panes.getOrNull(index)
        if (pane != null) applyPaneIdentityUi(index)
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


    private fun paneName(index: Int): String = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        .getString(PANE_NAME_PREFIX + index, null)
        ?.takeIf { it.isNotBlank() }
        ?: defaultPaneNames.getOrElse(index) { "Conta " + (index + 1) }

    private fun paneColor(index: Int): String = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        .getString(PANE_COLOR_PREFIX + index, null)
        ?.takeIf { it.isNotBlank() && runCatching { Color.parseColor(it) }.isSuccess }
        ?: paneColorOptions.getOrElse(index) { paneColorOptions.first() }

    private fun paneAvatar(index: Int): String = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        .getString(PANE_AVATAR_PREFIX + index, null)
        ?.takeIf { it.isNotBlank() }
        ?: (index + 1).toString()

    private fun applyPaneIdentityUi(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        val color = Color.parseColor(paneColor(index))
        pane.avatarView.text = paneAvatar(index).take(3)
        pane.avatarView.setTextColor(Color.WHITE)
        pane.avatarView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        refreshPaneHeader(index)
    }

    private fun refreshPaneHeader(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        pane.titleView.text = paneName(index)
        val position = (paneOrder.indexOf(index) + 1).coerceAtLeast(1)
        pane.subtitleView.text = when {
            !pane.isOpen -> getString(R.string.instance_paused_detail)
            pane.isPaused -> getString(R.string.instance_temporarily_paused, position)
            else -> getString(
                R.string.instance_page_detail,
                pane.lastTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.page_ready),
                position,
            )
        }
    }

    private fun showPaneIdentityDialog(index: Int) {
        val nameInput = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.account_name_hint)
            setText(paneName(index))
            setSelection(text.length)
        }
        val avatarInput = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.account_avatar_hint)
            setText(paneAvatar(index))
            setSelection(text.length)
        }
        val colorLabels = arrayOf(
            getString(R.string.account_color_blue),
            getString(R.string.account_color_red),
            getString(R.string.account_color_green),
            getString(R.string.account_color_gold),
            getString(R.string.account_color_purple),
        )
        val colorSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, colorLabels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(paneColorOptions.indexOf(paneColor(index)).coerceAtLeast(0))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(avatarInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(colorSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_instance_identity)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_identity) { _, _ ->
                val preferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                val safeName = nameInput.text.toString().trim().ifBlank { defaultPaneNames[index] }
                val safeAvatar = avatarInput.text.toString().trim().ifBlank { (index + 1).toString() }
                preferences.edit()
                    .putString(PANE_NAME_PREFIX + index, safeName)
                    .putString(PANE_AVATAR_PREFIX + index, safeAvatar.take(3))
                    .putString(PANE_COLOR_PREFIX + index, paneColorOptions[colorSpinner.selectedItemPosition.coerceIn(paneColorOptions.indices)])
                    .apply()
                applyPaneIdentityUi(index)
            }
            .show()
    }

    private fun configurePaneReordering(index: Int) {
        val pane = panes.getOrNull(index) ?: return

        fun attachReorderTouch(targetView: View) {
            var startX = 0f
            var startY = 0f
            var dragging = false
            targetView.setOnTouchListener { touchedView, event ->
                if (fullscreenPaneIndex != null) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging && (abs(event.rawX - startX) > dp(12) || abs(event.rawY - startY) > dp(12))) dragging = true
                        if (dragging) {
                            val from = paneOrder.indexOf(index)
                            val target = nearestPanePosition(event.rawX, event.rawY)
                            if (from >= 0 && target >= 0 && from != target) swapPanePositions(from, target)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) touchedView.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }

        listOf(pane.dragHandle, pane.titleView, pane.avatarView).forEach(::attachReorderTouch)
    }
    private fun nearestPanePosition(rawX: Float, rawY: Float): Int {
        val location = IntArray(2)
        return paneOrder.indices.minByOrNull { position ->
            val host = panes[paneOrder[position]].thumbnailHost
            host.getLocationOnScreen(location)
            val centerX = location[0] + host.width / 2f
            val centerY = location[1] + host.height / 2f
            (centerX - rawX) * (centerX - rawX) + (centerY - rawY) * (centerY - rawY)
        } ?: -1
    }

    private fun swapPanePositions(from: Int, to: Int) {
        if (from !in paneOrder.indices || to !in paneOrder.indices || from == to) return
        val moved = paneOrder[from]
        paneOrder[from] = paneOrder[to]
        paneOrder[to] = moved
        persistPaneOrder()
        applyPaneLayout()
    }

    private fun maybeChooseGoogleAccount(index: Int, rawUrl: String): Boolean {
        val pane = panes.getOrNull(index) ?: return false
        if (!isGoogleSignInUrl(rawUrl) || pane.selectedGoogleAccount != null) return false
        if (pendingGoogleAccountRequest?.first == index) return true

        pendingGoogleAccountRequest = index to rawUrl
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.GET_ACCOUNTS),
                GOOGLE_ACCOUNT_PERMISSION_REQUEST,
            )
            return true
        }
        return showGoogleAccountPicker()
    }

    private fun showGoogleAccountPicker(): Boolean {
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
        val path = uri.path.orEmpty()
        return path.contains("signin", ignoreCase = true) ||
            path.contains("servicelogin", ignoreCase = true) ||
            path.contains("accountchooser", ignoreCase = true) ||
            rawUrl.contains("oauth", ignoreCase = true) ||
            rawUrl.contains("identifier", ignoreCase = true)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != GOOGLE_ACCOUNT_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            showGoogleAccountPicker()
        } else {
            val pending = pendingGoogleAccountRequest
            pendingGoogleAccountRequest = null
            pending?.let { panes.getOrNull(it.first)?.webView?.loadUrl(it.second) }
            Toast.makeText(this, R.string.google_account_permission_denied, Toast.LENGTH_SHORT).show()
        }
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
        refreshPaneHeader(index)
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
        pane.gridScalePercent = null
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
        resetWebViewViewportScale(pane)
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
            pane.thumbnailHost.addView(pane.container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        fullscreenOverlay.visibility = View.GONE
        findViewById<View>(R.id.app_toolbar).visibility = View.VISIBLE
grid.visibility = View.VISIBLE
        applyGridPaneOrder()
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

    private fun applyGridPaneOrder() {
        val grid = findViewById<GridLayout>(R.id.browser_grid)
        grid.removeAllViews()
        paneOrder.forEachIndexed { position, identity ->
            val pane = panes.getOrNull(identity) ?: return@forEachIndexed
            grid.addView(pane.thumbnailHost, paneLayoutParams(position))
            refreshPaneHeader(identity)
        }
        grid.requestLayout()
        grid.post { refreshGridPaneThumbnails() }
    }

    private fun resetWebViewViewportScale(pane: BrowserPane) {
        pane.gridScalePercent = null
        pane.gridTransformApplied = false
        pane.gridReferenceWidth = 0
        pane.gridReferenceHeight = 0
        pane.gridHostWidth = 0
        pane.gridHostHeight = 0
        pane.gridScale = 0f
        val webView = pane.webView
        val host = webView.parent as? FrameLayout ?: return

        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.pivotX = 0f
        webView.pivotY = 0f
        webView.scaleX = 1f
        webView.scaleY = 1f
        webView.translationX = 0f
        webView.translationY = 0f

        pane.clickLayer.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        pane.clickLayer.pivotX = 0f
        pane.clickLayer.pivotY = 0f
        pane.clickLayer.scaleX = 1f
        pane.clickLayer.scaleY = 1f
        pane.clickLayer.translationX = 0f
        pane.clickLayer.translationY = 0f
        host.clipChildren = true
        host.clipToPadding = true

        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.setInitialScale(0)
        webView.requestLayout()
    }

    private fun applyGridWebViewTransform(
          pane: BrowserPane,
          referenceWidth: Int,
          referenceHeight: Int,
      ): Boolean {
          val webView = pane.webView
          val host = webView.parent as? FrameLayout ?: return false
          val hostWidth = host.width
          val hostHeight = host.height
          if (hostWidth <= 0 || hostHeight <= 0 || referenceWidth <= 0 || referenceHeight <= 0) return false

          val scale = minOf(
              hostWidth.toFloat() / referenceWidth.toFloat(),
              hostHeight.toFloat() / referenceHeight.toFloat(),
          )
          val scaledWidth = (referenceWidth * scale).toInt()
          val scaledHeight = (referenceHeight * scale).toInt()
          val currentLayoutParams = webView.layoutParams
          val needsLayoutParams = currentLayoutParams.width != referenceWidth || currentLayoutParams.height != referenceHeight
          val changed = !pane.gridTransformApplied ||
              pane.gridReferenceWidth != referenceWidth ||
              pane.gridReferenceHeight != referenceHeight ||
              pane.gridHostWidth != hostWidth ||
              pane.gridHostHeight != hostHeight ||
              kotlin.math.abs(pane.gridScale - scale) > 0.0005f ||
              needsLayoutParams
          if (!changed) return false

          pane.gridScalePercent = (scale * 100f).toInt().coerceIn(10, 100)
          pane.gridReferenceWidth = referenceWidth
          pane.gridReferenceHeight = referenceHeight
          pane.gridHostWidth = hostWidth
          pane.gridHostHeight = hostHeight
          pane.gridScale = scale
          pane.gridTransformApplied = true

          host.clipChildren = true
          host.clipToPadding = true
          if (needsLayoutParams) {
              webView.layoutParams = FrameLayout.LayoutParams(referenceWidth, referenceHeight)
              pane.clickLayer.layoutParams = FrameLayout.LayoutParams(referenceWidth, referenceHeight)
          }

          val translationX = ((hostWidth - scaledWidth).coerceAtLeast(0) / 2f)
          val translationY = ((hostHeight - scaledHeight).coerceAtLeast(0) / 2f)
          webView.pivotX = 0f
          webView.pivotY = 0f
          webView.scaleX = scale
          webView.scaleY = scale
          webView.translationX = translationX
          webView.translationY = translationY

          pane.clickLayer.pivotX = 0f
          pane.clickLayer.pivotY = 0f
          pane.clickLayer.scaleX = scale
          pane.clickLayer.scaleY = scale
          pane.clickLayer.translationX = translationX
          pane.clickLayer.translationY = translationY
          return true
      }

          private fun equalizeGridCells(grid: GridLayout): Boolean {
        val availableWidth = grid.width - grid.paddingLeft - grid.paddingRight
        val availableHeight = grid.height - grid.paddingTop - grid.paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return false

        // The grid is measured after the WebViews have been attached. Give every
        // cell an explicit bound so a large WebView viewport cannot make the
        // first row consume the space intended for the second row.
        val cellMargin = 7
        val cellWidth = ((availableWidth - cellMargin * 4) / 2).coerceAtLeast(1)
        val cellHeight = ((availableHeight - cellMargin * 4) / 2).coerceAtLeast(1)
        var changed = false

        paneOrder.forEachIndexed { position, identity ->
            val pane = panes.getOrNull(identity) ?: return@forEachIndexed
            if (pane.thumbnailHost.parent !== grid) return@forEachIndexed
            val current = pane.thumbnailHost.layoutParams as? GridLayout.LayoutParams
            if (current?.width == cellWidth && current.height == cellHeight) return@forEachIndexed

            pane.thumbnailHost.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(position / 2),
                GridLayout.spec(position % 2),
            ).apply {
                width = cellWidth
                height = cellHeight
                setGravity(Gravity.FILL)
                setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
            }
            changed = true
        }
        if (changed) grid.requestLayout()
        return changed
    }

private fun refreshGridPaneThumbnails() {
          val grid = findViewById<GridLayout>(R.id.browser_grid)
          if (equalizeGridCells(grid)) {
              grid.post { refreshGridPaneThumbnails() }
              return
          }
          val browserContent = findViewById<View>(R.id.browser_content)
          val fullViewportWidth = browserContent.width - browserContent.paddingLeft - browserContent.paddingRight
          val fullViewportHeight = browserContent.height - browserContent.paddingTop - browserContent.paddingBottom
          if (fullViewportWidth <= 0 || fullViewportHeight <= 0) return

          panes.forEach { pane ->
              if (pane.container.parent !== pane.thumbnailHost) return@forEach
              val webViewHost = pane.webView.parent as? FrameLayout ?: return@forEach
              if (webViewHost.width <= 0 || webViewHost.height <= 0) return@forEach

              pane.container.pivotX = 0f
              pane.container.pivotY = 0f
              pane.container.scaleX = 1f
              pane.container.scaleY = 1f
              pane.container.translationX = 0f
              pane.container.translationY = 0f

              // Only update the native layout when a real size changes. This
              // avoids the layout-listener redraw loop that caused flashing.
              val changed = applyGridWebViewTransform(pane, fullViewportWidth, fullViewportHeight)
              if (changed) {
                  pane.webView.post {
                      pane.webView.evaluateJavascript("window.dispatchEvent(new Event(\"resize\"));", null)
                  }
              }
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
    ).apply {
          width = 0
          height = 0
          setGravity(Gravity.FILL)
          setMargins(7, 7, 7, 7)
      }

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

    private fun builtInAutoClickPresets(): List<AutoClickPreset> = listOf(
        AutoClickPreset("Farm rápido", listOf(ClickPoint(0.5f, 0.5f, 250L), ClickPoint(0.7f, 0.5f, 250L))),
        AutoClickPreset("Farm lento", listOf(ClickPoint(0.5f, 0.5f, 1800L), ClickPoint(0.7f, 0.5f, 1800L))),
        AutoClickPreset("Evento", listOf(ClickPoint(0.5f, 0.5f, 1000L))),
        AutoClickPreset("Batalha", listOf(ClickPoint(0.35f, 0.5f, 400L), ClickPoint(0.65f, 0.5f, 400L))),
        AutoClickPreset("Coleta", listOf(ClickPoint(0.5f, 0.7f, 750L), ClickPoint(0.5f, 0.35f, 750L))),
    )

    private fun customAutoClickPresets(index: Int): List<AutoClickPreset> {
        val raw = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(AUTO_PRESET_PREFIX + index, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (presetIndex in 0 until array.length()) {
                    val presetObject = array.getJSONObject(presetIndex)
                    val pointsArray = presetObject.optJSONArray("points") ?: JSONArray()
                    val points = buildList {
                        for (pointIndex in 0 until pointsArray.length()) {
                            val point = pointsArray.getJSONObject(pointIndex)
                            add(ClickPoint(point.optDouble("x", 0.5).toFloat(), point.optDouble("y", 0.5).toFloat(), point.optLong("intervalMs", 1000L)))
                        }
                    }
                    add(AutoClickPreset(presetObject.optString("name"), points))
                }
            }
        }.getOrDefault(emptyList()).filter { it.name.isNotBlank() }
    }

    private fun deletedBuiltInAutoClickPresetNames(index: Int): Set<String> {
        val raw = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(AUTO_DELETED_PRESET_PREFIX + index, null) ?: return emptySet()
        return raw.split("\u001F").filter { it.isNotBlank() }.toSet()
    }

    private fun availableAutoClickPresets(index: Int): List<AutoClickPreset> {
        val deletedNames = deletedBuiltInAutoClickPresetNames(index)
        return builtInAutoClickPresets().filterNot { it.name in deletedNames } + customAutoClickPresets(index)
    }

    private fun saveAutoClickPreset(index: Int, name: String) {
        val pane = panes.getOrNull(index) ?: return
        if (pane.autoClickPoints.isEmpty()) {
            Toast.makeText(this, R.string.auto_clicker_need_point, Toast.LENGTH_SHORT).show()
            return
        }
        val array = JSONArray()
        customAutoClickPresets(index).filterNot { it.name.equals(name, ignoreCase = true) }.forEach { preset ->
            val presetObject = JSONObject().put("name", preset.name)
            val points = JSONArray()
            preset.points.forEach { point ->
                points.put(JSONObject().put("x", point.x).put("y", point.y).put("intervalMs", point.intervalMs))
            }
            presetObject.put("points", points)
            array.put(presetObject)
        }
        val current = JSONObject().put("name", name)
        val currentPoints = JSONArray()
        pane.autoClickPoints.forEach { point ->
            currentPoints.put(JSONObject().put("x", point.x).put("y", point.y).put("intervalMs", point.intervalMs))
        }
        current.put("points", currentPoints)
        array.put(current)
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putString(AUTO_PRESET_PREFIX + index, array.toString()).apply()
        Toast.makeText(this, R.string.auto_clicker_preset_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showSavePresetDialog(index: Int) {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.auto_clicker_preset_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_clicker_save_preset)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.auto_clicker_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) Toast.makeText(this, R.string.auto_clicker_preset_name_required, Toast.LENGTH_SHORT).show()
                else saveAutoClickPreset(index, name)
            }
            .show()
    }

    private fun applyAutoClickPreset(index: Int, preset: AutoClickPreset) {
        val pane = panes.getOrNull(index) ?: return
        stopAutoClicker(index)
        pane.autoClickPoints = preset.points.map { ClickPoint(it.x, it.y, it.intervalMs) }.toMutableList()
        pane.isAutoClickEditing = true
        pane.clickLayer.visibility = View.VISIBLE
        renderAutoClickEditor(index)
        Toast.makeText(this, getString(R.string.auto_clicker_preset_applied, preset.name), Toast.LENGTH_SHORT).show()
    }

    private fun deleteAutoClickPreset(index: Int, name: String) {
        val preferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        val builtIn = builtInAutoClickPresets().any { it.name.equals(name, ignoreCase = true) }
        if (builtIn) {
            val deletedNames = deletedBuiltInAutoClickPresetNames(index).toMutableSet().apply { add(name) }
            preferences.edit().putString(AUTO_DELETED_PRESET_PREFIX + index, deletedNames.joinToString("\u001F")).apply()
        } else {
            val remaining = customAutoClickPresets(index).filterNot { it.name.equals(name, ignoreCase = true) }
            if (remaining.isEmpty()) {
                preferences.edit().remove(AUTO_PRESET_PREFIX + index).apply()
            } else {
                val array = JSONArray()
                remaining.forEach { preset ->
                    val presetObject = JSONObject().put("name", preset.name)
                    val points = JSONArray()
                    preset.points.forEach { point ->
                        points.put(JSONObject().put("x", point.x).put("y", point.y).put("intervalMs", point.intervalMs))
                    }
                    presetObject.put("points", points)
                    array.put(presetObject)
                }
                preferences.edit().putString(AUTO_PRESET_PREFIX + index, array.toString()).apply()
            }
        }
        Toast.makeText(this, getString(R.string.auto_clicker_preset_deleted, name), Toast.LENGTH_SHORT).show()
    }
    private fun showDeletePresetDialog(index: Int) {
        val presets = availableAutoClickPresets(index)
        if (presets.isEmpty()) {
            Toast.makeText(this, R.string.auto_clicker_no_presets, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_clicker_delete_preset_title)
            .setItems(presets.map { it.name }.toTypedArray()) { _, which ->
                val preset = presets[which]
                AlertDialog.Builder(this)
                    .setTitle(R.string.auto_clicker_delete_preset_title)
                    .setMessage(getString(R.string.auto_clicker_delete_preset_message, preset.name))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.auto_clicker_delete_preset) { _, _ ->
                        deleteAutoClickPreset(index, preset.name)
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    private fun showPresetDialog(index: Int) {
        val presets = availableAutoClickPresets(index)
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_clicker_presets)
            .setItems(presets.map { it.name }.toTypedArray()) { _, which -> applyAutoClickPreset(index, presets[which]) }
            .setPositiveButton(R.string.auto_clicker_delete_preset) { _, _ -> showDeletePresetDialog(index) }
            .setNeutralButton(R.string.auto_clicker_save_preset) { _, _ -> showSavePresetDialog(index) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
row.addView(compactAction("P", R.string.auto_clicker_presets) { showPresetDialog(index) }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(0, dp(3), 0, 0) })
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
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        with(webView.settings) {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.setBackgroundColor(getColor(R.color.pane_background))
        webView.webChromeClient = WebChromeClient()
        webView.settings.userAgentString = webView.settings.userAgentString
            ?.replace("; wv", "")
            ?.replace(" Version/4.0", "")
        CookieManager.getInstance().flush()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings, if (isDarkTheme) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
        webView.setOnLongClickListener { false }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                updatePaneIdentity(paneIndex, url, null)
                persistPaneUrl(paneIndex, url)
                maybeChooseGoogleAccount(paneIndex, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                updatePaneIdentity(paneIndex, url, view.title)
                persistPaneUrl(paneIndex, url)
                panes.getOrNull(paneIndex)?.selectedGoogleAccount
                    ?.takeUnless { it == "picker_cancelled" }
                    ?.takeIf { isGoogleSignInUrl(url) }
                    ?.let { prefillGoogleAccount(view, it) }
                view.post {
                    view.requestLayout()
                    view.evaluateJavascript("window.dispatchEvent(new Event(\"resize\"));", null)
                }
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
            editor.putString(PANE_ORDER_KEY, paneOrder.joinToString(","))
            panes.forEachIndexed { index, pane ->
                (pane.lastUrl ?: pane.webView.url)?.takeIf { it.isNotBlank() && it != "about:blank" }?.let { editor.putString(paneUrlKey(index), it) }
                editor.putBoolean(paneOpenKey(index), pane.isOpen)
                editor.putBoolean(panePausedKey(index), pane.isPaused)
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
        outState.putIntArray(PANE_ORDER_KEY, paneOrder.toIntArray())
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

    private fun loadPaneOrder(savedInstanceState: Bundle?): MutableList<Int> {
        val saved = savedInstanceState?.getIntArray(PANE_ORDER_KEY)?.toList()
        val stored = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(PANE_ORDER_KEY, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() }
        val candidate = saved ?: stored ?: listOf(0, 1, 2, 3)
        return if (candidate.size == 4 && candidate.toSet() == setOf(0, 1, 2, 3)) candidate.toMutableList()
        else mutableListOf(0, 1, 2, 3)
    }

    private fun persistPaneOrder() {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit()
            .putString(PANE_ORDER_KEY, paneOrder.joinToString(","))
            .apply()
    }

    private fun webViewStateKey(index: Int): String = WEBVIEW_STATE_PREFIX + index
    private fun webViewUrlKey(index: Int): String = WEBVIEW_URL_PREFIX + index
    private fun paneUrlKey(index: Int): String = PANE_URL_PREFIX + index
    private fun paneOpenKey(index: Int): String = PANE_OPEN_PREFIX + index
    private fun panePausedKey(index: Int): String = PANE_PAUSED_PREFIX + index

    override fun onDestroy() {
        persistAllPaneState()
        if (!isChangingConfigurations) panes.forEach { it.webView.stopLoading(); it.webView.destroy() }
        panes.clear()
        super.onDestroy()
    }

    private data class PaneDefinition(
        val paneId: Int,
        val avatarId: Int,
        val titleId: Int,
        val dragHandleId: Int,
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
