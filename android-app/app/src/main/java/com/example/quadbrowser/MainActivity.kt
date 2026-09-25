package com.example.quadbrowser

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.PictureInPictureParams
import android.view.Gravity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Rational
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
import com.google.firebase.firestore.FirebaseFirestore
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
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    companion object {
        // Weak reference so the Service never keeps this Activity from being
        // garbage-collected; used only to periodically nudge WebViews to
        // keep running (see AutoClickForegroundService's keep-alive ticker).
        private var activityRef: java.lang.ref.WeakReference<MainActivity>? = null

        fun keepBackgroundWebViewsAlive() {
            val activity = activityRef?.get() ?: return
            // While the activity is visible, Android already keeps these WebViews
            // active. Avoid waking the main thread and touching all four renderers
            // every keep-alive tick during normal foreground use.
            if (activity.isActivityVisible) return
            activity.panes
                .asSequence()
                .filter { it.isOpen }
                .forEach { it.webView.resumeTimers() }
        }

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
        const val SETTINGS_PREFS = "quad_browser_settings"
        const val DARK_THEME_KEY = "dark_theme"
        const val VIEW_MODE_KEY = "view_mode_paged"
        const val ENERGY_SAVER_STATE_KEY = "energy_saver_enabled"
        const val GOOGLE_ACCOUNT_PICKER_REQUEST = 2301
        const val GOOGLE_ACCOUNT_PERMISSION_REQUEST = 2302
        const val NOTIFICATION_PERMISSION_REQUEST = 4101
        const val MOBILE_VIEWPORT_WIDTH = 390
    }

    private data class ClickPoint(var x: Float, var y: Float, var intervalMs: Long = 1000L)

    private data class AutoClickPreset(val name: String, val points: List<ClickPoint>)

    private data class BrowserPane(
        val container: View,
        val thumbnailHost: PaneViewportLayout,
        val bodyViewportHost: PaneViewportLayout,
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
        var webViewZoomPercent: Int = 100,
        // Auto-click points are normalized to this stable fullscreen content
        // surface, then projected into the current grid/PiP WebView bounds.
        var autoClickReferenceWidth: Float = 0f,
        var autoClickReferenceHeight: Float = 0f,
    )

    private val panes = mutableListOf<BrowserPane>()
    private var diagnosticsOverlay: TextView? = null
    private var diagnosticsEnabled = false
    private lateinit var fullscreenOverlay: FrameLayout
    private lateinit var instancePager: PagedInstancesLayout
    private lateinit var pagerSwitchStrip: View
    private lateinit var pagerPreviousButton: ImageButton
    private lateinit var pagerNextButton: ImageButton
    private lateinit var pagerDots: List<TextView>
    private var fullscreenPaneIndex: Int? = null
    private lateinit var energySaverOverlay: FrameLayout
    private var isEnergySaverEnabled = false
    private var paneOrder = mutableListOf(0, 1, 2, 3)
    private val defaultPaneNames = (1..4).map { "Instância $it" }
    private val paneColorOptions = listOf(
        "#5869DD", "#D94F66", "#00A896", "#F0A202", "#7B61FF",
        "#2E9E5B", "#DD7A2E", "#3F8FDD", "#C94FAE", "#8C8C3B",
    )
    private var pendingGoogleAccountRequest: Pair<Int, String>? = null
    private var isDarkTheme = false
    private var isActivityVisible = false
    // True from the moment the POST_NOTIFICATIONS system dialog is requested
    // until its result comes back. While true, the app must NEVER call
    // startForegroundService() — doing so while the system permission dialog
    // has focus can leave the process without enough priority to call
    // Service.startForeground() in time, which crashes the whole app with a
    // fatal, uncatchable ForegroundServiceDidNotStartInTimeException. This
    // was the root cause of the app silently closing right after granting
    // the notification permission.
    private var notificationPermissionDialogPending = false
    private val autoClickHandler = Handler(Looper.getMainLooper())
    private var isRefreshingGridPaneThumbnails = false
    private var isPagerMode = false
    private var accessGateOverlay: View? = null
    private var accessListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var accessExpiresAtMillis: Long? = null
    private var isAccessCountdownBlinking: Boolean = false
    // TEMPORARY diagnostic log — a permanent, never-cleared, scrollable panel
    // that accumulates every debug message from the registration flow. Fixes
    // the earlier problem where the debug text would flash and disappear
    // (overwritten or removed the instant the next Firestore callback
    // fired). Shown as a small floating box in the corner so it never blocks
    // the rest of the UI. Safe to remove once the registration issue is
    // found.
    private var debugLogMessages = mutableListOf<String>()
    private var debugLogPanel: android.widget.ScrollView? = null
    private var debugLogTextView: TextView? = null
    private data class ContactInfo(val label: String, val url: String)
    private var contactOptions: List<ContactInfo> = emptyList()
    private var contactListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    private lateinit var accessTimeRemainingLabel: TextView
    private val accessCountdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val accessCountdownRunnable = object : Runnable {
        override fun run() {
            updateAccessTimeRemainingLabel()
            // Tick every second so an expiry is caught (and the app is
            // blocked) right when the plan time runs out, not up to 30s late.
            accessCountdownHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        isDarkTheme = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(DARK_THEME_KEY, false)
        AppCompatDelegate.setDefaultNightMode(if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        activityRef = java.lang.ref.WeakReference(this)
        setContentView(R.layout.activity_main)
        accessTimeRemainingLabel = findViewById(R.id.access_time_remaining)
        beginAccessGate()
        requestNotificationPermissionIfNeeded()

        fullscreenOverlay = findViewById(R.id.fullscreen_overlay)
        energySaverOverlay = findViewById(R.id.energy_saver_overlay)
        findViewById<ImageButton>(R.id.energy_saver_toggle).apply {
            updateEnergySaverToggle(this)
            setOnClickListener { setEnergySaverEnabled(!isEnergySaverEnabled) }
        }
        findViewById<Button>(R.id.energy_saver_exit).setOnClickListener { setEnergySaverEnabled(false) }
        instancePager = findViewById(R.id.browser_pager)
        pagerSwitchStrip = findViewById(R.id.pager_switch_strip)
        pagerPreviousButton = findViewById(R.id.pager_previous)
        pagerNextButton = findViewById(R.id.pager_next)
        pagerDots = listOf(
            findViewById(R.id.pager_dot_1),
            findViewById(R.id.pager_dot_2),
            findViewById(R.id.pager_dot_3),
            findViewById(R.id.pager_dot_4),
        )
        pagerDots.forEachIndexed { index, dot ->
            dot.setOnClickListener { instancePager.setCurrentPage(index, true) }
        }
        pagerPreviousButton.setOnClickListener { movePagerBy(-1) }
        pagerNextButton.setOnClickListener { movePagerBy(1) }
        setupPagerSwitchStripDrag()
        val preferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        isPagerMode = preferences.getBoolean(VIEW_MODE_KEY, false)
        instancePager.setPageChangedListener {
            refreshPagerPaneThumbnails()
            updatePagerDotsAppearance()
        }
        findViewById<View>(R.id.browser_content).addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Only react to real size changes, and always defer to the next
            // frame. Reacting synchronously mid-layout (e.g. while
            // applyGridPaneOrder() is still re-adding the four panes one by
            // one) can catch the grid in a half-built state, where only the
            // first pane is attached — that pane then gets measured against
            // the wrong cell size and gets stuck that way until something
            // else forces a refresh (like a manual reload).
            val widthChanged = (right - left) != (oldRight - oldLeft)
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (widthChanged || heightChanged) {
                view.post { refreshGridPaneThumbnails() }
            }
        }
        findViewById<ImageButton>(R.id.view_mode_toggle).apply {
            updateViewModeToggle(this)
            setOnClickListener { toggleViewMode() }
            setOnLongClickListener { toggleDiagnosticsOverlay(); true }
        }
        findViewById<ImageButton>(R.id.theme_toggle).apply {
            updateThemeToggle(this)
            setOnClickListener { toggleTheme() }
        }
        findViewById<ImageButton>(R.id.background_button).setOnClickListener {
            minimizeToPictureInPicture()
        }
        findViewById<ImageButton>(R.id.contact_button).setOnClickListener {
            showContactDialog()
        }
        startContactRealtimeListener()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showProfileSupportError()
            return
        }

        val definitions = listOf(
            PaneDefinition(R.id.pane_1, R.id.avatar_1, R.id.pane_title_1, R.id.pane_drag_handle_1, R.id.pane_subtitle_1, R.id.navigate_1, R.id.reload_1, R.id.fullscreen_1, R.id.auto_click_1, R.id.close_1, R.id.empty_state_1, R.id.reopen_1, R.id.pane_body_1, R.id.webview_1, "webview1"),
            PaneDefinition(R.id.pane_2, R.id.avatar_2, R.id.pane_title_2, R.id.pane_drag_handle_2, R.id.pane_subtitle_2, R.id.navigate_2, R.id.reload_2, R.id.fullscreen_2, R.id.auto_click_2, R.id.close_2, R.id.empty_state_2, R.id.reopen_2, R.id.pane_body_2, R.id.webview_2, "webview2"),
            PaneDefinition(R.id.pane_3, R.id.avatar_3, R.id.pane_title_3, R.id.pane_drag_handle_3, R.id.pane_subtitle_3, R.id.navigate_3, R.id.reload_3, R.id.fullscreen_3, R.id.auto_click_3, R.id.close_3, R.id.empty_state_3, R.id.reopen_3, R.id.pane_body_3, R.id.webview_3, "webview3"),
            PaneDefinition(R.id.pane_4, R.id.avatar_4, R.id.pane_title_4, R.id.pane_drag_handle_4, R.id.pane_subtitle_4, R.id.navigate_4, R.id.reload_4, R.id.fullscreen_4, R.id.auto_click_4, R.id.close_4, R.id.empty_state_4, R.id.reopen_4, R.id.pane_body_4, R.id.webview_4, "webview4"),
        )

        definitions.forEachIndexed { index, definition ->
              val grid = findViewById<EqualPaneGridLayout>(R.id.browser_grid)
              val container = findViewById<View>(definition.paneId)
              val body = findViewById<View>(definition.bodyId)
              val bodyParent = body.parent as ViewGroup
              val bodyIndex = bodyParent.indexOfChild(body)
              val bodyLayoutParams = body.layoutParams
              bodyParent.removeView(body)
              val bodyViewportHost = PaneViewportLayout(this)
              bodyParent.addView(bodyViewportHost, bodyIndex, bodyLayoutParams)
              bodyViewportHost.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
              val thumbnailHost = PaneViewportLayout(this)
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
                   bodyViewportHost = bodyViewportHost,
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
            bodyViewportHost.isPanBlocked = { pane.isAutoClickEditing }
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
            pane.closeButton.setOnClickListener { confirmDeletePaneInstance(index) }
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
         if (savedInstanceState?.getBoolean(ENERGY_SAVER_STATE_KEY, false) == true) {
             setEnergySaverEnabled(true)
         }
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
        ?: defaultPaneNames.getOrElse(index) { "Instância " + (index + 1) }

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
        if (::pagerDots.isInitialized) updatePagerDotsAppearance()
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
            getString(R.string.account_color_forest),
            getString(R.string.account_color_orange),
            getString(R.string.account_color_sky),
            getString(R.string.account_color_pink),
            getString(R.string.account_color_olive),
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
                val safeName = nameInput.text.toString().trim().ifBlank { "Instância " + (index + 1) }
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
            // While dragging we only track which slot the finger is currently
            // over (pendingTarget) and lightly highlight it — we never touch
            // the view tree mid-drag. Rebuilding the grid (which detaches and
            // re-adds every pane's WebView) on every pixel of movement was
            // what crashed the app: it tore down the very view still
            // receiving this touch sequence. The actual swap + layout
            // rebuild now happens once, only on release.
            var pendingTarget = -1
            targetView.setOnTouchListener { touchedView, event ->
                if (fullscreenPaneIndex != null) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        dragging = false
                        pendingTarget = -1
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging && (abs(event.rawX - startX) > dp(12) || abs(event.rawY - startY) > dp(12))) dragging = true
                        if (dragging) {
                            val target = nearestPanePosition(event.rawX, event.rawY)
                            if (target >= 0 && target != pendingTarget) {
                                setPaneDragHighlight(pendingTarget, false)
                                pendingTarget = target
                                setPaneDragHighlight(pendingTarget, true)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            touchedView.performClick()
                        } else {
                            setPaneDragHighlight(pendingTarget, false)
                            val from = paneOrder.indexOf(index)
                            val target = pendingTarget
                            if (from >= 0 && target >= 0 && from != target) {
                                // Posted to the next frame: swapping here
                                // detaches thumbnailHost (and its WebView)
                                // from the view tree, and doing that while
                                // Android is still mid-dispatch of this very
                                // touch sequence on a descendant of that
                                // view is what was crashing the app, even
                                // though it now only runs once per drag.
                                touchedView.post {
                                    swapPanePositions(from, target)
                                }
                            }
                        }
                        dragging = false
                        pendingTarget = -1
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        setPaneDragHighlight(pendingTarget, false)
                        dragging = false
                        pendingTarget = -1
                        true
                    }
                    else -> false
                }
            }
        }

        listOf(pane.dragHandle, pane.titleView, pane.avatarView).forEach(::attachReorderTouch)
    }

    /** Lightweight visual-only feedback for the slot currently under the
     *  finger while reordering — a simple alpha dip, no layout changes. */
    private fun setPaneDragHighlight(position: Int, highlighted: Boolean) {
        if (position !in paneOrder.indices) return
        val pane = panes.getOrNull(paneOrder[position]) ?: return
        pane.thumbnailHost.alpha = if (highlighted) 0.55f else 1f
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
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            // The system dialog is gone now, so it's safe again to start the
            // foreground service. If the app lost visibility while the
            // dialog was up, catch up now instead of waiting for the next
            // pause/resume.
            notificationPermissionDialogPending = false
            if (!isActivityVisible) startBackgroundServiceForCurrentState()
            return
        }
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

    /** Asks for confirmation before permanently wiping an instance's data. */
    private fun confirmDeletePaneInstance(index: Int) {
        if (panes.getOrNull(index) == null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_instance_title)
            .setMessage(getString(R.string.delete_instance_message, paneName(index)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_instance_confirm) { _, _ -> deletePaneInstance(index) }
            .show()
    }

    /**
     * Permanently clears this instance: wipes its WebView's cache, history,
     * form data and SSL state, stops any auto-clicker, resets its saved
     * name/avatar/color/url back to defaults, and leaves the slot empty
     * (same empty-state UI as before, so the user can start a fresh session
     * in that slot by tapping "Abrir instância" again).
     */
    private fun deletePaneInstance(index: Int) {
        val pane = panes.getOrNull(index) ?: return

        stopAutoClicker(index)
        if (fullscreenPaneIndex == index) {
            fullscreenPaneIndex = null
        }

        // Wipe this WebView's own browsing data. clearCache/clearHistory/
        // clearFormData/clearSslPreferences are scoped to this WebView
        // instance, not global — so they do NOT touch the other instances'
        // sessions. Cookies and site storage (localStorage/IndexedDB) are
        // deliberately left alone here: this app's cookie/storage APIs are
        // process-wide, not scoped to a single instance's profile, so
        // calling them would risk logging every other open instance out too.
        pane.webView.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            clearFormData()
            clearSslPreferences()
            loadUrl("about:blank")
        }

        pane.pendingUrl = null
        pane.lastUrl = null
        pane.lastTitle = null
        pane.selectedGoogleAccount = null
        pane.autoClickPoints = mutableListOf()
        pane.autoClickIndex = 0
        pane.isPaused = false
        pane.gridScalePercent = null
        pane.webViewZoomPercent = 100

        // Reset the slot's saved identity (name/avatar/color) and any
        // remembered address/state, so it comes back as a brand new,
        // unnamed instance the next time it's opened.
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit()
            .remove(PANE_NAME_PREFIX + index)
            .remove(PANE_AVATAR_PREFIX + index)
            .remove(PANE_COLOR_PREFIX + index)
            .remove(paneUrlKey(index))
            .remove(webViewUrlKey(index))
            .remove(webViewStateKey(index))
            .remove(panePausedKey(index))
            .apply()

        applyPaneIdentityUi(index)
        setPaneOpen(index, false)
        Toast.makeText(this, getString(R.string.instance_deleted, paneName(index)), Toast.LENGTH_SHORT).show()
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
         pane.gridTransformApplied = false
        pane.pendingUrl = savedUrl
        pane.lastTitle = null
        configureWebView(replacement, pane.profileName, index)
        replacement.contentDescription = getString(R.string.webview_description, index + 1)
        setPaneOpen(index, false)
        Toast.makeText(this, getString(R.string.renderer_recovered, index + 1), Toast.LENGTH_LONG).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Do not reparent WebView containers here. Android can report the
        // software keyboard as a configuration change; removing and adding
        // the focused WebView closes the IME and makes text entry impossible.
        // The normal measure/layout pass handles orientation and IME resizing.
        window.decorView.post { refreshAutoClickEditors() }
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
        val grid = findViewById<EqualPaneGridLayout>(R.id.browser_grid)
        val pane = panes[index]
        applyFullscreenWebViewViewport(pane)
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
        setPagerVisible(false)
        fullscreenOverlay.visibility = View.VISIBLE
        pane.container.visibility = View.VISIBLE
        panes.forEachIndexed { paneIndex, browserPane ->
            setFullscreenButtonState(browserPane, paneIndex == index)
            setPaneActionState(paneIndex)
        }
        fullscreenOverlay.requestLayout()
        pane.clickLayer.post { renderAutoClickEditorWhenReady(index) }
        logGridDiagnostics("enterFullscreen-p${index + 1}")
    }

    private fun exitFullscreenPane() {
          val grid = findViewById<EqualPaneGridLayout>(R.id.browser_grid)
          val selectedIndex = panes.indexOfFirst { it.container.parent === fullscreenOverlay }
          if (selectedIndex >= 0) {
              val pane = panes[selectedIndex]
              fullscreenOverlay.removeView(pane.container)
              pane.thumbnailHost.addView(pane.container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
          }
          fullscreenOverlay.visibility = View.GONE
          findViewById<View>(R.id.app_toolbar).visibility = View.VISIBLE
          if (isPagerMode) {
              grid.visibility = View.GONE
              applyPagerPaneOrder()
          } else {
              setPagerVisible(false)
              grid.visibility = View.VISIBLE
              applyGridPaneOrder()
          }
          panes.forEachIndexed { index, pane ->
              pane.container.visibility = View.VISIBLE
              applyPaneOpenUi(index, pane.isOpen)
              setFullscreenButtonState(pane, false)
              setPaneActionState(index)
          }
          if (isPagerMode) {
              instancePager.requestLayout()
              instancePager.post { refreshPagerPaneThumbnails(); refreshAutoClickEditors(); logGridDiagnostics("exitFullscreen-pager") }
          } else {
              grid.requestLayout()
              grid.post { refreshGridPaneThumbnails(); refreshAutoClickEditors(); logGridDiagnostics("exitFullscreen-grid") }
          }
      }

      private fun applyGridPaneOrder() {
          val grid = findViewById<EqualPaneGridLayout>(R.id.browser_grid)
          instancePager.removeAllViews()
          grid.removeAllViews()
          paneOrder.forEachIndexed { position, identity ->
              val pane = panes.getOrNull(identity) ?: return@forEachIndexed
              (pane.thumbnailHost.parent as? ViewGroup)?.removeView(pane.thumbnailHost)
              grid.addView(pane.thumbnailHost, paneLayoutParams(position))
              refreshPaneHeader(identity)
          }
          grid.visibility = View.VISIBLE
          setPagerVisible(false)
          grid.requestLayout()
          grid.post {
              refreshGridPaneThumbnails()
              // One more frame after the first: on some devices the grid's
              // children report their final measured size a frame later than
              // the grid container itself, which is what let the very first
              // pane get an outdated cell size baked in until something else
              // (like a manual reload) forced a second refresh.
              grid.post { refreshGridPaneThumbnails() }
          }
      }

    private fun applyPagerPaneOrder() {
        val grid = findViewById<EqualPaneGridLayout>(R.id.browser_grid)
        grid.removeAllViews()
        instancePager.removeAllViews()
        paneOrder.forEach { identity ->
            val pane = panes.getOrNull(identity) ?: return@forEach
            (pane.thumbnailHost.parent as? ViewGroup)?.removeView(pane.thumbnailHost)
            val page = FrameLayout(this).apply {
                setBackgroundColor(getColor(R.color.grid_background))
                clipChildren = true
                clipToPadding = true
            }
            page.addView(
                pane.thumbnailHost,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            refreshPaneHeader(identity)
            instancePager.addView(
                page,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        instancePager.setCurrentPage(instancePager.currentPage.coerceIn(0, 3), false)
        setPagerVisible(true)
        grid.visibility = View.GONE
        instancePager.requestLayout()
        instancePager.post { refreshPagerPaneThumbnails() }
    }

        private fun restoreDefaultPageViewport(view: WebView, viewportHeightCss: Int = 0) {
        val script = """
            (function() {
                var mobileWidth = $MOBILE_VIEWPORT_WIDTH;
                var forcedHeight = $viewportHeightCss;
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                var content = 'width=' + mobileWidth + ', initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                if (forcedHeight > 0) content += ', height=' + forcedHeight;
                meta.setAttribute('content', content);
                document.documentElement.style.minWidth = '';
                if (document.body) document.body.style.minWidth = '';
                window.dispatchEvent(new Event('resize'));
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
        view.postDelayed({ view.evaluateJavascript(script, null) }, 250L)
    }

    private fun gridViewportHeightCss(surfaceHeightPx: Int): Int =
        (surfaceHeightPx / resources.displayMetrics.density).roundToInt().coerceAtLeast(0)

    private fun applyActualSizeWebViewViewport(pane: BrowserPane) {
        val hostWidth = pane.bodyViewportHost.width
        val hostHeight = pane.bodyViewportHost.height
        // Layout callbacks can arrive several times without changing the cell
        // size. Reapplying the surface and dispatching two JS resize events for
        // every pane in that case makes sites such as pokeidle.io do avoidable
        // work on both the Android and renderer threads.
        if (pane.gridTransformApplied &&
            pane.gridHostWidth == hostWidth &&
            pane.gridHostHeight == hostHeight
        ) return

        pane.thumbnailHost.resetSurfaceSize()
        pane.bodyViewportHost.resetSurfaceSize()
        pane.gridScalePercent = null
        pane.gridTransformApplied = true
        pane.gridReferenceWidth = hostWidth
        pane.gridReferenceHeight = hostHeight
        pane.gridHostWidth = hostWidth
        pane.gridHostHeight = hostHeight
        pane.gridScale = 1f
        pane.webView.settings.useWideViewPort = true
        pane.webView.settings.loadWithOverviewMode = false
        pane.webView.setInitialScale(0)
        restoreDefaultPageViewport(pane.webView)
        applyWebViewZoom(pane, 100)
    }

    private fun applyFullscreenWebViewViewport(pane: BrowserPane) {
        pane.thumbnailHost.resetSurfaceSize()
        pane.bodyViewportHost.resetSurfaceSize()
        pane.gridScalePercent = null
        pane.gridTransformApplied = true
        pane.webView.settings.useWideViewPort = true
        pane.webView.settings.loadWithOverviewMode = false
        pane.webView.setInitialScale(0)
        restoreDefaultPageViewport(pane.webView)
        applyWebViewZoom(pane, 100)
    }

    private fun applyGridWebViewViewport(pane: BrowserPane) {
        // Keep the WebView on the same logical surface used by fullscreen.
        // PaneViewportLayout clips that surface to the 2x2 cell instead of
        // resizing/reflowing the page. This is intentional: the auto-click
        // coordinates must point at the same page pixels in both views.
        val (referenceWidth, referenceHeight) = thumbnailReferenceSize()
        val hostWidth = pane.bodyViewportHost.width
        val hostHeight = pane.bodyViewportHost.height
        if (hostWidth <= 1 || hostHeight <= 1) return
        if (pane.gridTransformApplied &&
            pane.gridReferenceWidth == referenceWidth &&
            pane.gridReferenceHeight == referenceHeight &&
            pane.gridHostWidth == hostWidth &&
            pane.gridHostHeight == hostHeight &&
            pane.gridScale == 1f
        ) return

        pane.thumbnailHost.resetSurfaceSize()
        pane.bodyViewportHost.setSurfaceSize(referenceWidth, referenceHeight, 1f, 1f)
        pane.gridScalePercent = null
        pane.gridTransformApplied = true
        pane.gridReferenceWidth = referenceWidth
        pane.gridReferenceHeight = referenceHeight
        pane.gridHostWidth = hostWidth
        pane.gridHostHeight = hostHeight
        pane.gridScale = 1f
        pane.webView.settings.useWideViewPort = true
        pane.webView.settings.loadWithOverviewMode = false
        pane.webView.setInitialScale(0)
        restoreDefaultPageViewport(pane.webView, gridViewportHeightCss(referenceHeight))
        applyWebViewZoom(pane, 100)
    }

    /** Keeps the pager's visibility and its toolbar switch strip in sync. */
    private fun setPagerVisible(visible: Boolean) {
        instancePager.visibility = if (visible) View.VISIBLE else View.GONE
        pagerSwitchStrip.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) updatePagerDotsAppearance()
        // The contact button only makes sense in the grid ("all instances")
        // view, not the single/paged instance view.
        updateContactButtonVisibility(showingGrid = !visible)
    }

    /** Updates labels, colors and selection state for the instance switcher. */
    private fun updatePagerDotsAppearance() {
        pagerDots.forEachIndexed { position, dot ->
            val identity = paneOrder.getOrNull(position) ?: return@forEachIndexed
            val isSelected = position == instancePager.currentPage
            val color = runCatching { Color.parseColor(paneColor(identity)) }
                .getOrDefault(getColor(R.color.accent))
            val selectedStroke = if (isDarkTheme) Color.WHITE else Color.BLACK
            val inactiveStroke = if (isDarkTheme) Color.argb(100, 255, 255, 255) else Color.argb(100, 0, 0, 0)
            dot.text = paneAvatar(identity).ifBlank { (identity + 1).toString() }.take(3)
            dot.setTextColor(Color.WHITE)
            dot.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            dot.includeFontPadding = false
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(if (isSelected) 2 else 1), if (isSelected) selectedStroke else inactiveStroke)
            }
            dot.alpha = 1f
            dot.contentDescription = getString(R.string.pager_switch_to_instance_named, paneName(identity))
        }
        val canSwitch = instancePager.childCount > 1
        pagerPreviousButton.isEnabled = canSwitch
        pagerNextButton.isEnabled = canSwitch
        pagerPreviousButton.alpha = if (canSwitch) 1f else 0.45f
        pagerNextButton.alpha = if (canSwitch) 1f else 0.45f
    }

    private fun movePagerBy(delta: Int) {
        val pageCount = instancePager.childCount
        if (pageCount <= 1) return
        val target = (instancePager.currentPage + delta + pageCount) % pageCount
        instancePager.setCurrentPage(target, true)
    }

    /**
     * A horizontal drag started on the top toolbar's dedicated strip (never
     * on the page content itself) switches between the 4 instances in
     * paged/single mode. This keeps horizontal gestures inside the actual
     * web page (scrolling a menu, dragging an item) from ever being
     * mistaken for a request to change instances.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPagerSwitchStripDrag() {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var isDragging = false
        pagerSwitchStrip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    if (!isDragging && Math.abs(dx) > touchSlop) isDragging = true
                    if (isDragging) instancePager.previewDragOffset(dx)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        instancePager.finishDrag(event.x - downX)
                    } else {
                        view.performClick()
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) instancePager.finishDrag(0f)
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun thumbnailReferenceSize(): Pair<Int, Int> {
        val content = findViewById<View>(R.id.browser_content)
        val referenceWidth = fullscreenOverlay.width.takeIf { it > 1 }
            ?: (content.width - content.paddingLeft - content.paddingRight).takeIf { it > 1 }
            ?: MOBILE_VIEWPORT_WIDTH
        val fullHeight = fullscreenOverlay.height.takeIf { it > 1 }
            ?: (content.height - content.paddingTop - content.paddingBottom).takeIf { it > 1 }
            ?: (referenceWidth * 2.05f).roundToInt()
        // The fixed surface belongs to pane_body, not the card toolbar. Keep
        // the toolbar visible in every 2x2 cell and use only the fullscreen
        // body height as the WebView reference.
        val bodyTop = panes.firstOrNull()?.bodyViewportHost?.top?.takeIf { it > 1 } ?: dp(68)
        val referenceHeight = (fullHeight - bodyTop).coerceAtLeast(1)
        return referenceWidth.coerceAtLeast(1) to referenceHeight
    }

    private fun refreshGridPaneThumbnails() {
        if (isPagerMode) {
            refreshPagerPaneThumbnails()
            return
        }
        if (isRefreshingGridPaneThumbnails || panes.isEmpty()) return
        val grid = findViewById<EqualPaneGridLayout>(R.id.browser_grid)
        if (grid.width <= 1 || grid.height <= 1) return
        val openPaneCount = paneOrder.count { identity -> panes.getOrNull(identity) != null }
        // The grid is rebuilt by removing every child and re-adding them one
        // at a time (see applyGridPaneOrder). Mid-rebuild it can briefly have
        // fewer children than expected, and GridLayout will stretch that lone
        // child to fill the whole grid — a wrong cell size that would then
        // get baked into that pane's WebView until something forces another
        // refresh. Skip until every expected pane is actually attached.
        if (grid.childCount < openPaneCount) return
        val readyPanes = panes.filter { it.container.parent === it.thumbnailHost && it.thumbnailHost.width > 0 && it.thumbnailHost.height > 0 }
        if (readyPanes.isEmpty()) return

        // The page intentionally keeps its fullscreen logical surface here;
        // PaneViewportLayout clips the overflow to each compact cell.
        isRefreshingGridPaneThumbnails = true
        try {
            grid.clipChildren = true
            grid.clipToPadding = true
            readyPanes.forEach { pane ->
                applyGridWebViewViewport(pane)
            }
        } finally {
            isRefreshingGridPaneThumbnails = false
        }
        logGridDiagnostics("refreshGridPaneThumbnails")
    }

    private fun refreshPagerPaneThumbnails() {
        if (!isPagerMode || isRefreshingGridPaneThumbnails || panes.isEmpty()) return
        val readyPanes = panes.filter { it.container.parent === it.thumbnailHost && it.thumbnailHost.parent != null && it.thumbnailHost.width > 0 && it.thumbnailHost.height > 0 }
        if (readyPanes.isEmpty()) return
        isRefreshingGridPaneThumbnails = true
        try {
            readyPanes.forEach { pane ->
                // Pager pages are the primary view, not thumbnails. Measure
                // the WebView to the page that is actually available so one
                // instance fills the screen in normal, freeform and PiP modes.
                applyActualSizeWebViewViewport(pane)
            }
        } finally {
            isRefreshingGridPaneThumbnails = false
        }
    }
            private fun refreshAutoClickEditors() {
        panes.forEachIndexed { index, pane ->
            if (pane.isAutoClickEditing) {
                pane.clickLayer.post { renderAutoClickEditorWhenReady(index) }
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
        pane.closeButton.setOnClickListener { confirmDeletePaneInstance(index) }
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
          pane.clickLayer.isClickable = false
          captureAutoClickReference(index)
        pane.clickLayer.post { renderAutoClickEditorWhenReady(index) }
      }

      private fun captureAutoClickReference(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          val layerWidth = pane.clickLayer.width
          val layerHeight = pane.clickLayer.height
          if (layerWidth <= 1 || layerHeight <= 1) return
          // Once a fullscreen reference exists, never replace it while the
          // pane is shown in the smaller 2x2/PiP layout.
          if (pane.autoClickReferenceWidth > 1f && pane.autoClickReferenceHeight > 1f &&
              pane.container.parent !== fullscreenOverlay
          ) return
          pane.autoClickReferenceWidth = layerWidth.toFloat()
          pane.autoClickReferenceHeight = layerHeight.toFloat()
      }

      private fun renderAutoClickEditorWhenReady(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        pane.clickLayer.post {
            if (!pane.isAutoClickEditing) return@post
            if (pane.clickLayer.width <= 0 || pane.clickLayer.height <= 0) {
                pane.clickLayer.post { renderAutoClickEditorWhenReady(index) }
            } else {
                renderAutoClickEditor(index)
            }
        }
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
          renderAutoClickEditorWhenReady(index)
      }

      private fun removeAllAutoClickPoints(index: Int) {
          val pane = panes.getOrNull(index) ?: return
          stopAutoClicker(index)
          pane.autoClickPoints.clear()
          pane.isAutoClickEditing = true
          pane.clickLayer.visibility = View.VISIBLE
          renderAutoClickEditorWhenReady(index)
      }

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

    private fun availableAutoClickPresets(index: Int): List<AutoClickPreset> = customAutoClickPresets(index)

    /** Saves the given points as a named preset for one pane index. */
    private fun saveAutoClickPresetForIndex(index: Int, name: String, points: List<ClickPoint>) {
        val array = JSONArray()
        customAutoClickPresets(index).filterNot { it.name.equals(name, ignoreCase = true) }.forEach { preset ->
            val presetObject = JSONObject().put("name", preset.name)
            val presetPoints = JSONArray()
            preset.points.forEach { point ->
                presetPoints.put(JSONObject().put("x", point.x).put("y", point.y).put("intervalMs", point.intervalMs))
            }
            presetObject.put("points", presetPoints)
            array.put(presetObject)
        }
        val current = JSONObject().put("name", name)
        val currentPoints = JSONArray()
        points.forEach { point ->
            currentPoints.put(JSONObject().put("x", point.x).put("y", point.y).put("intervalMs", point.intervalMs))
        }
        current.put("points", currentPoints)
        array.put(current)
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putString(AUTO_PRESET_PREFIX + index, array.toString()).apply()
    }

    private fun saveAutoClickPreset(index: Int, name: String, applyToAll: Boolean) {
        val pane = panes.getOrNull(index) ?: return
        if (pane.autoClickPoints.isEmpty()) {
            Toast.makeText(this, R.string.auto_clicker_need_point, Toast.LENGTH_SHORT).show()
            return
        }
        if (applyToAll) {
            panes.indices.forEach { paneIndex -> saveAutoClickPresetForIndex(paneIndex, name, pane.autoClickPoints) }
        } else {
            saveAutoClickPresetForIndex(index, name, pane.autoClickPoints)
        }
        Toast.makeText(this, R.string.auto_clicker_preset_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showSavePresetDialog(index: Int) {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.auto_clicker_preset_name_hint)
        }
        val applyToAllCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.auto_clicker_apply_to_all_instances)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(8), pad, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(applyToAllCheckbox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_clicker_save_preset)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.auto_clicker_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) Toast.makeText(this, R.string.auto_clicker_preset_name_required, Toast.LENGTH_SHORT).show()
                else saveAutoClickPreset(index, name, applyToAllCheckbox.isChecked)
            }
            .show()
    }

    private fun applyAutoClickPreset(index: Int, preset: AutoClickPreset) {
        val pane = panes.getOrNull(index) ?: return
        stopAutoClicker(index)
        pane.autoClickPoints = preset.points.map { ClickPoint(it.x, it.y, it.intervalMs) }.toMutableList()
        pane.isAutoClickEditing = true
        pane.clickLayer.visibility = View.VISIBLE
        renderAutoClickEditorWhenReady(index)
        Toast.makeText(this, getString(R.string.auto_clicker_preset_applied, preset.name), Toast.LENGTH_SHORT).show()
    }

    private fun deleteAutoClickPreset(index: Int, name: String) {
        val preferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.auto_clicker_presets)
            .setPositiveButton(R.string.auto_clicker_delete_preset) { _, _ -> showDeletePresetDialog(index) }
            .setNeutralButton(R.string.auto_clicker_save_preset) { _, _ -> showSavePresetDialog(index) }
            .setNegativeButton(R.string.cancel, null)
        if (presets.isEmpty()) {
            dialog.setMessage(R.string.auto_clicker_no_presets)
        } else {
            dialog.setItems(presets.map { it.name }.toTypedArray()) { _, which -> applyAutoClickPreset(index, presets[which]) }
        }
        dialog.show()
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
                      renderAutoClickEditorWhenReady(index)
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
          layer.visibility = View.VISIBLE
          captureAutoClickReference(index)
          val layerWidth = layer.width.coerceAtLeast(1).toFloat()
          val layerHeight = layer.height.coerceAtLeast(1).toFloat()
          val referenceWidth = pane.autoClickReferenceWidth.takeIf { it > 1f } ?: layerWidth
          val referenceHeight = pane.autoClickReferenceHeight.takeIf { it > 1f } ?: layerHeight
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
                  val referenceX = point.x.coerceIn(0f, 1f) * referenceWidth
                  val referenceY = point.y.coerceIn(0f, 1f) * referenceHeight
                  x = ((referenceX / referenceWidth) * layerWidth - markerSize / 2f).coerceIn(0f, (layer.width - markerSize).coerceAtLeast(0).toFloat())
                  y = ((referenceY / referenceHeight) * layerHeight - markerSize / 2f).coerceIn(0f, (layer.height - markerSize).coerceAtLeast(0).toFloat())
              }
              var startRawX = 0f
              var startRawY = 0f
              var startViewX = 0f
              var startViewY = 0f
              var gestureScale = 1f
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
                          gestureScale = pane.gridScale.takeIf { it > 0f } ?: 1f
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
                          val dx = (event.rawX - startRawX) / gestureScale
                          val dy = (event.rawY - startRawY) / gestureScale
                          moved = moved || abs(dx) > dp(6) || abs(dy) > dp(6)
                          if (moved) longPressAction?.let(autoClickHandler::removeCallbacks)
                          view.x = (startViewX + dx).coerceIn(0f, (layer.width - markerSize).coerceAtLeast(0).toFloat())
                          view.y = (startViewY + dy).coerceIn(0f, (layer.height - markerSize).coerceAtLeast(0).toFloat())
                          true
                      }
                      MotionEvent.ACTION_UP -> {
                          longPressAction?.let(autoClickHandler::removeCallbacks)
                          if (moved) {
                              val currentX = ((view.x + markerSize / 2f) / layerWidth).coerceIn(0f, 1f)
                              val currentY = ((view.y + markerSize / 2f) / layerHeight).coerceIn(0f, 1f)
                              point.x = ((currentX * referenceWidth) / referenceWidth).coerceIn(0f, 1f)
                              point.y = ((currentY * referenceHeight) / referenceHeight).coerceIn(0f, 1f)
                          } else if (!longPressTriggered && pointIndex in pane.autoClickPoints.indices) {
                              pane.autoClickPoints.removeAt(pointIndex)
                              Toast.makeText(this, R.string.auto_clicker_point_removed, Toast.LENGTH_SHORT).show()
                              renderAutoClickEditorWhenReady(index)
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
                   renderAutoClickEditorWhenReady(index)
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
                  dispatchClick(currentPane, point)
                  currentPane.autoClickIndex = (currentPane.autoClickIndex + 1) % currentPane.autoClickPoints.size
                  autoClickHandler.postDelayed(this, point.intervalMs.coerceAtLeast(100L))
              }
          }
          pane.autoClickRunnable = runnable
          setPaneActionState(index)
          pane.clickLayer.post {
              if (!pane.isAutoClicking) return@post
              renderAutoClickEditor(index)
              autoClickHandler.post(runnable)
          }
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
          private fun startBackgroundService(action: String, showFloatingBubble: Boolean = false) {
              // Never start the foreground service while the POST_NOTIFICATIONS
              // system dialog is open — see notificationPermissionDialogPending's
              // comment. onRequestPermissionsResult() catches up and calls this
              // again right after the dialog closes if needed.
              if (notificationPermissionDialogPending) return
              val serviceIntent = Intent(this, AutoClickForegroundService::class.java)
                  .setAction(action)
                  .putExtra(AutoClickForegroundService.EXTRA_SHOW_BUBBLE, showFloatingBubble)
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

    /**
     * Listens to the single "config/contacts" document in Firestore, which the
     * admin panel edits directly. Each field is optional and freely editable
     * from the panel — currently "discord" and "whatsapp", plus any extra
     * entries under "extra" (a list of {label, url} maps) for any other
     * contact the admin wants to add later without an app update.
     *
     * Document shape (collection "config", doc id "contacts"):
     *   {
     *     discordProfile: <URL or username, optional>,
     *     discordServer: <invite URL, optional>,
     *     whatsapp: <phone number or wa.me link, optional>,
     *     extra: [ { label: <string>, url: <string> }, ... ]  (optional)
     *   }
     */
    private fun startContactRealtimeListener() {
        contactListenerRegistration?.remove()
        val db = FirebaseFirestore.getInstance()
        contactListenerRegistration = db.collection("config").document("contacts")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    contactOptions = emptyList()
                    runOnUiThread { updateContactButtonVisibility() }
                    return@addSnapshotListener
                }
                val options = mutableListOf<ContactInfo>()
                val discordProfile = snapshot.getString("discordProfile")?.trim().orEmpty()
                if (discordProfile.isNotEmpty()) {
                    options.add(ContactInfo(getString(R.string.contact_discord_label) + " (perfil)", normalizeContactUrl(discordProfile)))
                }
                val discordServer = snapshot.getString("discordServer")?.trim().orEmpty()
                if (discordServer.isNotEmpty()) {
                    options.add(ContactInfo(getString(R.string.contact_discord_label) + " (servidor)", normalizeContactUrl(discordServer)))
                }
                val whatsapp = snapshot.getString("whatsapp")?.trim().orEmpty()
                if (whatsapp.isNotEmpty()) {
                    options.add(ContactInfo(getString(R.string.contact_whatsapp_label), normalizeWhatsappUrl(whatsapp)))
                }
                val extraList = snapshot.get("extra") as? List<*>
                extraList?.forEach { entry ->
                    val map = entry as? Map<*, *> ?: return@forEach
                    val label = (map["label"] as? String)?.trim().orEmpty()
                    val url = (map["url"] as? String)?.trim().orEmpty()
                    if (label.isNotEmpty() && url.isNotEmpty()) {
                        options.add(ContactInfo(label, normalizeContactUrl(url)))
                    }
                }
                contactOptions = options
                runOnUiThread { updateContactButtonVisibility() }
            }
    }

    private fun normalizeContactUrl(raw: String): String {
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun normalizeWhatsappUrl(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val digits = raw.filter { it.isDigit() }
        return "https://wa.me/$digits"
    }

    /** Shows the contact button only in the grid ("all instances") view, and only when at least one contact is configured. */
    private fun updateContactButtonVisibility(showingGrid: Boolean = !isPagerMode && fullscreenPaneIndex == null) {
        val button = findViewById<ImageButton>(R.id.contact_button)
        button.visibility = if (showingGrid && contactOptions.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showContactDialog() {
        if (contactOptions.isEmpty()) {
            Toast.makeText(this, R.string.contact_none_available, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.contact_dialog_title)
            .setItems(contactOptions.map { it.label }.toTypedArray()) { _, which ->
                val option = contactOptions[which]
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(option.url)))
                } catch (_: Exception) {
                    Toast.makeText(this, option.url, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun beginAccessGate() {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(14, 18, 28))
            isClickable = true
            isFocusable = true
        }
        val progress = ProgressBar(this)
        val title = TextView(this).apply {
            text = "Verificando acesso"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "Aguarde enquanto validamos este dispositivo."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        }
        overlay.addView(progress, LinearLayout.LayoutParams(64, 64))
        overlay.addView(title, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(message, LinearLayout.LayoutParams(-1, -2))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        accessGateOverlay = overlay

        // Start the realtime listener immediately: it fires right away with
        // the current status, and keeps firing afterward (e.g. the admin
        // approves the device while this screen is showing), so the app
        // unlocks the instant access is granted — no restart needed.
        startAccessRealtimeListener()
    }

    /**
     * Keeps listening to this device's Firestore document for the entire
     * lifetime of the app. If the admin blocks the device or its access
     * expires while the app is open, this fires immediately and the app is
     * locked right away — the user never needs to close and reopen it for a
     * block/expiry to take effect. Likewise, if the admin extends access or
     * approves the device, the app unlocks immediately without a restart.
     */
    private fun startAccessRealtimeListener() {
        accessListenerRegistration?.remove()
        accessListenerRegistration = AccessControlGate.listen(applicationContext) { decision ->
            runOnUiThread {
                if (!decision.debugError.isNullOrBlank()) appendDebugLog(decision.debugError)
                if (decision.allowed) {
                    if (accessGateOverlay != null) {
                        findViewById<FrameLayout>(android.R.id.content).removeView(accessGateOverlay)
                        accessGateOverlay = null
                    }
                    onAccessGranted(decision.expiresAtMillis)
                } else {
                    onAccessRevoked(decision.reason, decision.debugError)
                }
            }
        }
    }

    /**
     * TEMPORARY diagnostic panel — appends a line to a permanent, always
     * visible, scrollable log box floating in the corner of the screen. Never
     * cleared, never removed by other UI changes (unlike the old debug text
     * that lived inside the access overlay and got wiped the moment access
     * was granted or the message changed). Tap the box to copy everything.
     * Safe to remove entirely once the registration issue is found.
     */
    private fun appendDebugLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        debugLogMessages.add("[$timestamp] $message")

        if (debugLogPanel == null) {
            val root = findViewById<FrameLayout>(android.R.id.content)
            val panel = android.widget.ScrollView(this).apply {
                setBackgroundColor(Color.argb(230, 20, 20, 20))
                setPadding(16, 12, 16, 12)
            }
            val textView = TextView(this).apply {
                textSize = 10f
                setTextColor(Color.rgb(255, 180, 180))
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            panel.addView(textView, LinearLayout.LayoutParams(-1, -2))
            panel.setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("debug_log", debugLogMessages.joinToString("\n")))
                Toast.makeText(this, "Log copiado!", Toast.LENGTH_SHORT).show()
            }
            val params = FrameLayout.LayoutParams(-1, 260).apply {
                gravity = Gravity.BOTTOM
            }
            root.addView(panel, params)
            debugLogPanel = panel
            debugLogTextView = textView
        }

        debugLogTextView?.text = debugLogMessages.joinToString("\n")
        debugLogPanel?.post { debugLogPanel?.fullScroll(View.FOCUS_DOWN) }
    }

    /** Builds and shows a fresh blocking overlay (used by onAccessRevoked when none is showing yet). */
    private fun buildAccessOverlay(reason: String, debugError: String? = null): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(14, 18, 28))
            isClickable = true
            isFocusable = true
        }
        val title = TextView(this).apply {
            text = "Acesso indisponível"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = reason
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        }
        overlay.addView(title, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(message, LinearLayout.LayoutParams(-1, -2))
        // TEMPORARY diagnostic line — remove once the registration issue is
        // found. Shows the raw error, if any, so it can be read straight off
        // the screen without needing logcat or a computer.
        if (!debugError.isNullOrBlank()) {
            val debugLabel = TextView(this).apply {
                text = "[debug] $debugError"
                textSize = 11f
                setTextColor(Color.rgb(255, 140, 140))
                gravity = Gravity.CENTER
                setPadding(24, 28, 24, 0)
            }
            overlay.addView(debugLabel, LinearLayout.LayoutParams(-1, -2))
        }
        return overlay
    }

    /** Called when access becomes valid (initial check, listener update, or extension). */
    private fun onAccessGranted(expiresAtMillis: Long?) {
        accessExpiresAtMillis = expiresAtMillis
        updateAccessTimeRemainingLabel()
        accessCountdownHandler.removeCallbacks(accessCountdownRunnable)
        accessCountdownHandler.post(accessCountdownRunnable)
    }

    /**
     * Called the instant access is revoked (blocked or expired) while the
     * app is already open, or while the initial "waiting for approval"
     * screen is still showing. Immediately shows the blocking screen over
     * everything, regardless of what the user was doing. There is no close
     * button: as soon as the admin approves/unblocks/extends this device in
     * the panel, the realtime listener fires again and the app unlocks by
     * itself, with no need to close and reopen it.
     */
    private fun onAccessRevoked(reason: String, debugError: String? = null) {
        accessCountdownHandler.removeCallbacks(accessCountdownRunnable)
        stopAccessCountdownBlink()
        accessTimeRemainingLabel.visibility = View.GONE
        val existing = accessGateOverlay
        if (existing is LinearLayout) {
            // Already showing an overlay (e.g. the initial progress screen, or
            // a previous denial) — just refresh its message in place.
            existing.removeAllViews()
            val title = TextView(this).apply {
                text = "Acesso indisponível"
                textSize = 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            val message = TextView(this).apply {
                text = reason
                textSize = 15f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 18, 0, 0)
            }
            existing.addView(title, LinearLayout.LayoutParams(-1, -2))
            existing.addView(message, LinearLayout.LayoutParams(-1, -2))
            // TEMPORARY diagnostic line — remove once the registration issue
            // is found.
            if (!debugError.isNullOrBlank()) {
                val debugLabel = TextView(this).apply {
                    text = "[debug] $debugError"
                    textSize = 11f
                    setTextColor(Color.rgb(255, 140, 140))
                    gravity = Gravity.CENTER
                    setPadding(24, 28, 24, 0)
                }
                existing.addView(debugLabel, LinearLayout.LayoutParams(-1, -2))
            }
            return
        }
        val root = findViewById<FrameLayout>(android.R.id.content)
        val overlay = buildAccessOverlay(reason, debugError)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        accessGateOverlay = overlay
    }

    /** Formats and shows (or hides) the "time remaining" strip under the toolbar. */
    private fun updateAccessTimeRemainingLabel() {
        val expiresAt = accessExpiresAtMillis
        if (expiresAt == null) {
            accessTimeRemainingLabel.visibility = View.GONE
            stopAccessCountdownBlink()
            return
        }
        val remainingMs = expiresAt - System.currentTimeMillis()
        if (remainingMs <= 0) {
            // Don't wait for a Firestore write to notice expiry: the
            // realtime listener only fires when the document changes, so if
            // nobody touches it after the plan runs out the app would never
            // lock on its own. Block immediately using the local clock.
            accessTimeRemainingLabel.visibility = View.GONE
            stopAccessCountdownBlink()
            onAccessRevoked("O seu tempo de acesso acabou. Fale com o administrador para renovar.")
            return
        }
        accessTimeRemainingLabel.visibility = View.VISIBLE
        if (remainingMs <= 60_000L) {
            val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0)
            accessTimeRemainingLabel.text = "Acesso expira em ${totalSeconds}s"
            startAccessCountdownBlink()
        } else {
            stopAccessCountdownBlink()
            accessTimeRemainingLabel.setTextColor(getColor(R.color.text_secondary))
            accessTimeRemainingLabel.text = "Acesso válido por mais " + formatTimeRemaining(remainingMs)
        }
    }

    /** Starts (if not already running) a blink loop that flashes the countdown label red. */
    private fun startAccessCountdownBlink() {
        if (isAccessCountdownBlinking) return
        isAccessCountdownBlinking = true
        accessTimeRemainingLabel.setTextColor(Color.RED)
        runAccessCountdownBlinkStep()
    }

    private fun runAccessCountdownBlinkStep() {
        if (!isAccessCountdownBlinking) return
        accessTimeRemainingLabel.animate()
            .alpha(0.2f)
            .setDuration(400L)
            .withEndAction {
                if (!isAccessCountdownBlinking) return@withEndAction
                accessTimeRemainingLabel.animate()
                    .alpha(1f)
                    .setDuration(400L)
                    .withEndAction { runAccessCountdownBlinkStep() }
                    .start()
            }
            .start()
    }

    private fun stopAccessCountdownBlink() {
        if (!isAccessCountdownBlinking) return
        isAccessCountdownBlinking = false
        accessTimeRemainingLabel.animate().cancel()
        accessTimeRemainingLabel.alpha = 1f
        accessTimeRemainingLabel.setTextColor(getColor(R.color.text_secondary))
    }

    private fun formatTimeRemaining(remainingMs: Long): String {
        val totalMinutes = remainingMs / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "$days dia" + (if (days != 1L) "s" else "") + if (hours > 0) " e ${hours}h" else ""
            hours > 0 -> "${hours}h" + if (minutes > 0) " ${minutes}min" else ""
            else -> "${minutes.coerceAtLeast(1)} min"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
               ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
           ) {
               notificationPermissionDialogPending = true
               requestPermissions(
                   arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                   NOTIFICATION_PERMISSION_REQUEST,
               )
           }
       }

      private fun dispatchClick(pane: BrowserPane, point: ClickPoint) {
          val webView = pane.webView
          val maxX = (webView.width - 1).coerceAtLeast(1).toFloat()
          val maxY = (webView.height - 1).coerceAtLeast(1).toFloat()
          val referenceWidth = pane.autoClickReferenceWidth.takeIf { it > 1f } ?: maxX
          val referenceHeight = pane.autoClickReferenceHeight.takeIf { it > 1f } ?: maxY
          val referenceX = point.x.coerceIn(0f, 1f) * referenceWidth
          val referenceY = point.y.coerceIn(0f, 1f) * referenceHeight
          val x = ((referenceX / referenceWidth) * maxX).coerceIn(0f, maxX)
          val y = ((referenceY / referenceHeight) * maxY).coerceIn(0f, maxY)
          val downTime = SystemClock.uptimeMillis()
          val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
          val up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, x, y, 0)
          webView.dispatchTouchEvent(down)
          webView.dispatchTouchEvent(up)
          down.recycle()
          up.recycle()
      }

    private fun toggleDiagnosticsOverlay() {
        diagnosticsEnabled = !diagnosticsEnabled
        if (diagnosticsEnabled) {
            val overlay = diagnosticsOverlay ?: TextView(this).apply {
                setBackgroundColor(Color.argb(210, 0, 0, 0))
                setTextColor(Color.GREEN)
                textSize = 10f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                typeface = Typeface.MONOSPACE
                setLineSpacing(0f, 1.05f)
            }.also { diagnosticsOverlay = it }
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            (window.decorView as ViewGroup).addView(
                overlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                    topMargin = dp(60)
                },
            )
            overlay.bringToFront()
            Toast.makeText(this, "Diagnóstico ligado (toque longo de novo p/ desligar)", Toast.LENGTH_SHORT).show()
            logGridDiagnostics("toggle-on")
        } else {
            diagnosticsOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            Toast.makeText(this, "Diagnóstico desligado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logGridDiagnostics(trigger: String) {
        if (!diagnosticsEnabled) return
        val (refW, refH) = thumbnailReferenceSize()
        val grid = findViewById<EqualPaneGridLayout>(R.id.browser_grid)
        val lines = StringBuilder()
        lines.append("DIAG [$trigger] pager=").append(isPagerMode)
        lines.append(" grid=").append(grid.width).append("x").append(grid.height)
        lines.append(" ref=").append(refW).append("x").append(refH).append("\n")
        panes.forEachIndexed { index, pane ->
            val hostW = pane.bodyViewportHost.width
            val hostH = pane.bodyViewportHost.height
            val cachedMatch = pane.gridTransformApplied &&
                pane.gridReferenceWidth == refW &&
                pane.gridReferenceHeight == refH &&
                pane.gridHostWidth == hostW &&
                pane.gridHostHeight == hostH &&
                pane.gridScale == 1f
            lines.append("P").append(index + 1)
                .append(" host=").append(hostW).append("x").append(hostH)
                .append(" cache=").append(pane.gridReferenceWidth).append("x").append(pane.gridReferenceHeight)
                .append("/").append(pane.gridHostWidth).append("x").append(pane.gridHostHeight)
                .append(" applied=").append(pane.gridTransformApplied)
                .append(if (cachedMatch) " SKIP" else " APPLY")
                .append(" wv=").append(pane.webView.width).append("x").append(pane.webView.height)
                .append("\n")
        }
        Log.d("QuadBrowserDiag", lines.toString())
        diagnosticsOverlay?.text = lines.toString()
    }

    private fun toggleViewMode() {
        if (panes.isEmpty()) return
        isPagerMode = !isPagerMode
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(VIEW_MODE_KEY, isPagerMode)
            .apply()
        updateViewModeToggle(findViewById(R.id.view_mode_toggle))
        applyPaneLayout()
    }

    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putBoolean(DARK_THEME_KEY, isDarkTheme).apply()
        AppCompatDelegate.setDefaultNightMode(if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        recreate()
    }

    private fun updateEnergySaverToggle(button: ImageButton) {
        button.setImageResource(R.drawable.ic_battery_saver)
        button.setBackgroundResource(if (isEnergySaverEnabled) R.drawable.bg_danger_button else R.drawable.bg_icon_button)
        button.setColorFilter(getColor(if (isEnergySaverEnabled) R.color.accent else R.color.text_primary))
        button.contentDescription = getString(if (isEnergySaverEnabled) R.string.energy_saver_disable else R.string.energy_saver_enable)
    }

    private fun setEnergySaverEnabled(enabled: Boolean) {
        if (!::energySaverOverlay.isInitialized) return
        isEnergySaverEnabled = enabled
        panes.forEach { pane -> pane.webView.alpha = if (enabled) 0f else 1f }
        energySaverOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            val activeInstances = panes.count { it.isOpen }
            energySaverOverlay.findViewById<TextView>(R.id.energy_saver_status).text =
                getString(R.string.energy_saver_status, activeInstances)
            energySaverOverlay.bringToFront()
        }
        updateEnergySaverToggle(findViewById(R.id.energy_saver_toggle))
    }

    private fun updateThemeToggle(button: ImageButton) {
            button.setImageResource(if (isDarkTheme) R.drawable.ic_moon else R.drawable.ic_sun)
            button.contentDescription = getString(if (isDarkTheme) R.string.theme_switch_to_light else R.string.theme_switch_to_dark)
        }

    private fun updateViewModeToggle(button: ImageButton) {
        button.setImageResource(
            if (isPagerMode) R.drawable.ic_single_view else R.drawable.ic_grid_view,
        )
        button.contentDescription = getString(
            if (isPagerMode) R.string.view_mode_switch_to_grid else R.string.view_mode_switch_to_paged,
        )
    }
    private fun minimizeToPictureInPicture() {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
              Toast.makeText(this, R.string.picture_in_picture_unavailable, Toast.LENGTH_SHORT).show()
              return
          }

          // The floating (PiP) window used to always show one instance at
          // real size. Since grid/pager view was added, minimizing while in
          // grid or pager mode left every pane fullscreen-less, so all 4
          // instances got squeezed into the tiny PiP window together — that
          // is the "compressed" floating window and the auto-click markers
          // landing in the wrong place. Force whichever instance the user is
          // currently looking at into fullscreen first, so PiP always shows
          // a single WebView at its real measured size, exactly like before.
          if (fullscreenPaneIndex == null) {
              val candidate = if (isPagerMode) {
                  paneOrder.getOrNull(instancePager.currentPage)
              } else {
                  paneOrder.firstOrNull { panes.getOrNull(it)?.isOpen == true }
              }
              candidate?.takeIf { it in panes.indices && panes[it].isOpen }?.let { toggleFullscreen(it) }
          }

          val content = findViewById<View>(R.id.browser_content)
          val width = content.width.coerceAtLeast(1)
          val height = content.height.coerceAtLeast(1)
          val ratio = (width.toFloat() / height.toFloat()).coerceIn(0.418f, 2.39f)
          val denominator = 1000
          val numerator = (ratio * denominator).roundToInt().coerceIn(418, 2390)
          val paramsBuilder = PictureInPictureParams.Builder()
              .setAspectRatio(Rational(numerator, denominator))
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              paramsBuilder.setSeamlessResizeEnabled(true)
          }

          try {
              if (!enterPictureInPictureMode(paramsBuilder.build())) {
                  Toast.makeText(this, R.string.picture_in_picture_unavailable, Toast.LENGTH_SHORT).show()
              }
          } catch (error: IllegalStateException) {
              Log.w("QuadBrowser", "Unable to enter picture-in-picture mode", error)
              Toast.makeText(this, R.string.picture_in_picture_unavailable, Toast.LENGTH_SHORT).show()
          }
      }

    
    private fun applyWebViewZoom(pane: BrowserPane, targetPercent: Int) {
        val target = targetPercent.coerceIn(10, 100)
        val previous = pane.webViewZoomPercent.coerceIn(10, 100)
        if (previous == target) return
        pane.webViewZoomPercent = target
        pane.webView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pane.webView.zoomBy(target.toFloat() / previous.toFloat())
            } else {
                pane.webView.setInitialScale(target)
            }
            pane.webView.evaluateJavascript("window.dispatchEvent(new Event(\"resize\"));", null)
        }
    }

    private fun configureWebView(webView: WebView, profileName: String, paneIndex: Int) {
        webView.alpha = if (isEnergySaverEnabled) 0f else 1f
        // Some WebView provider versions only allow a limited number of
        // simultaneous named profiles (or reject certain names outright).
        // Never let a failure here take down the whole app — if the named
        // profile can't be set, this pane just falls back to the default
        // WebView profile instead of crashing on launch.
        runCatching { WebViewCompat.setProfile(webView, profileName) }
            .onFailure { Log.e("QuadBrowser", "setProfile failed for $profileName (pane ${paneIndex + 1})", it) }
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
        CookieManager.getInstance().flush()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings, if (isDarkTheme) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
        webView.setOnLongClickListener { false }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                panes.getOrNull(paneIndex)?.webViewZoomPercent = 100
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
                panes.getOrNull(paneIndex)?.let { current ->
                    if (current.bodyViewportHost.hasSurface && current.gridReferenceHeight > 1) {
                        restoreDefaultPageViewport(view, gridViewportHeightCss(current.gridReferenceHeight))
                    }
                }
                view.post { view.requestLayout() }
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
        outState.putBoolean(ENERGY_SAVER_STATE_KEY, isEnergySaverEnabled)
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

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        window.decorView.post {
            // Re-apply the active layout so every WebView is re-measured
            // against whatever bounds it actually has now (PiP window,
            // restored full window, grid cell, etc).
            applyPaneLayout()
            refreshAutoClickEditors()
        }
    }

    override fun onPause() {
        persistAllPaneState()
        if (!isChangingConfigurations) startBackgroundServiceForCurrentState()
        // Explicitly keep every WebView's JS timers/rendering active while
        // backgrounded. Android's default WebView behavior is to throttle
        // background WebViews to save battery; since the whole point of the
        // foreground service + notification is to keep these instances
        // genuinely running (not just alive-but-frozen), this is disabled
        // per-WebView rather than relying on the global default.
        panes.forEach { it.webView.resumeTimers() }
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
        panes.forEach { it.webView.resumeTimers() }
        super.onStop()
    }

    private fun loadPaneOrder(savedInstanceState: Bundle?): MutableList<Int> {
        // panes.size is always 4 — the leftover ".coerceAtLeast(8)" here was
        // from an earlier 8-instance experiment that was reverted elsewhere,
        // but this line still built an 8-item default order. Any device that
        // fell back to it ended up with a paneOrder containing indices 4-7,
        // which don't correspond to any real pane — the drag-to-reorder
        // code would then crash (index out of bounds) the moment it tried
        // to look up one of those phantom positions.
        val defaultOrder = (0 until panes.size).toList()
        val expected = (0 until panes.size).toSet()
        val saved = savedInstanceState?.getIntArray(PANE_ORDER_KEY)?.toList()
        val stored = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(PANE_ORDER_KEY, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() }
        val candidate = saved ?: stored ?: defaultOrder
        return if (candidate.size == panes.size && candidate.toSet() == expected) candidate.toMutableList()
        else defaultOrder.toMutableList()
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
        if (activityRef?.get() === this) activityRef = null
        accessListenerRegistration?.remove()
        contactListenerRegistration?.remove()
        accessCountdownHandler.removeCallbacks(accessCountdownRunnable)
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
        val bodyId: Int,
        val webViewId: Int,
        val profileName: String,
    )
}
