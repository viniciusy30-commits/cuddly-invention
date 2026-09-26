package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Hosts exactly one child (normally a WebView) and lets the user drag inside
 * it with one finger to scroll the page, on top of whatever size Android
 * gave this host.
 *
 * Rewritten design (v2): this host NEVER measures its child larger than its
 * own bounds, and NEVER applies scaleX/scaleY/translation to it. The old
 * design gave the child a huge fixed "logical surface" (the fullscreen size)
 * and then shrank it with a View scale + translation to fit the small grid
 * cell; that made Android's renderer keep a display list/texture sized to
 * the big logical surface while only a scaled-down fraction of it was ever
 * on screen, and for panes in the bottom row of the grid that texture was
 * observed going stale (showing an old/offset frame) in a way the top row
 * never did. There was no way to reliably invalidate that cache from here.
 *
 * Instead, the child is always laid out at exactly this host's real size,
 * so there is never a mismatch between "logical" and "rendered" bounds for
 * Android to get wrong. The page itself is shown zoomed out to fit (a real
 * WebView engine zoom, set by the caller via setInitialScale — never a View
 * transform), and reaching the rest of the page is done by dragging with one
 * finger. That drag is implemented by intercepting every touch from DOWN
 * onward while pan is enabled and re-dispatching the whole stream straight
 * to the WebView with dispatchTouchEvent, so Chromium's own touch-scroll
 * handling drives the page — the same handling it already uses correctly in
 * fullscreen — instead of a hand-rolled scrollBy() that would have to guess
 * the relationship between screen pixels and the page's zoomed CSS pixels.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false

    /** Return true to temporarily disable panning (e.g. while editing auto-click markers). */
    var isPanBlocked: (() -> Boolean)? = null

    /**
     * Whether dragging with one finger should scroll the child WebView.
     * Callers turn this on only where "grab and drag to see more of the
     * page" makes sense (the compact grid cell); in fullscreen/pager mode
     * the WebView already handles its own touch scrolling natively and this
     * stays false so gestures aren't double-handled.
     */
    var panEnabled: Boolean = false

    init {
        clipChildren = true
        clipToPadding = true
    }

    /** Puts the page's own scroll position back at the top-left corner. */
    fun resetPan() {
        (getChildAt(0) as? WebView)?.scrollTo(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hostWidth = resolveHostSize(widthMeasureSpec, suggestedMinimumWidth)
        val hostHeight = resolveHostSize(heightMeasureSpec, suggestedMinimumHeight)
        val child = getChildAt(0)
        if (child != null && child.visibility != View.GONE) {
            child.measure(
                MeasureSpec.makeMeasureSpec(hostWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(hostHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(hostWidth, hostHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        if (child.visibility == View.GONE) return
        // Always exactly this host's real, on-screen size. No surface, no
        // scale, no translation: what Android measured is what is drawn.
        child.layout(0, 0, measuredWidth, measuredHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = 1f
        child.scaleY = 1f
        child.translationX = 0f
        child.translationY = 0f
    }

    private fun canPan(): Boolean {
        if (!panEnabled) return false
        if (isPanBlocked?.invoke() == true) return false
        val child = getChildAt(0)
        return child is WebView && child.visibility != View.GONE
    }

    /**
     * Intercepts starting from DOWN itself (not from a later MOVE) whenever
     * panning is possible, so this host — not the WebView — is always the
     * one deciding whether a given gesture becomes a drag. The WebView never
     * gets Android's normal touch dispatch in this mode; it only ever sees
     * the synthetic stream this class forwards to it below.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = canPan()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!canPan()) return super.onTouchEvent(ev)
        val webView = getChildAt(0) as? WebView ?: return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isDragging = false
                // Forward DOWN immediately and unconditionally: a plain tap
                // still needs the WebView to see a normal DOWN/UP pair to
                // register as a click. Whether this turns into a drag is
                // decided later, in MOVE; either way the WebView has already
                // seen a real DOWN to build the rest of the gesture on.
                forwardSynthetic(webView, ev, MotionEvent.ACTION_DOWN, ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && ev.pointerCount == 1 &&
                    (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)
                ) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                forwardSynthetic(webView, ev, MotionEvent.ACTION_MOVE, ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> {
                forwardSynthetic(webView, ev, MotionEvent.ACTION_UP, ev.x, ev.y)
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                forwardSynthetic(webView, ev, MotionEvent.ACTION_CANCEL, ev.x, ev.y)
                isDragging = false
            }
        }
        return true
    }

    private fun forwardSynthetic(webView: WebView, source: MotionEvent, action: Int, x: Float, y: Float) {
        val synthetic = MotionEvent.obtain(
            source.downTime, source.eventTime, action, x, y, source.metaState,
        )
        webView.dispatchTouchEvent(synthetic)
        synthetic.recycle()
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // The page asks its parents to leave a gesture alone as soon as it
        // thinks it can scroll. While this host is driving the gesture
        // itself (panEnabled), that request is ignored so the drag isn't
        // interrupted.
        if (panEnabled) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }
}
