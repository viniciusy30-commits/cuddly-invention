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
 * Android to get wrong. Making the page look "zoomed out" (so more of the
 * site is visible at once) and letting the user reach the rest of the page
 * are both delegated to the WebView itself:
 *  - zoomed-out framing -> WebView.setInitialScale() / the page's own CSS
 *    viewport, applied by the caller (MainActivity), not by this class.
 *  - reaching the rest of the page -> a real WebView.scrollBy() on drag,
 *    which is the browser engine's own scroll, not a View transform.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false

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
        return child != null && child.visibility != View.GONE
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!canPan()) {
            isPanning = false
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                isPanning = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPanning && ev.pointerCount == 1 &&
                    (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)
                ) {
                    isPanning = true
                    lastX = ev.x
                    lastY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // The WebView already received DOWN and this first MOVE
                    // before touchSlop was exceeded, so its own page may have
                    // already started scrolling internally on its own. Cancel
                    // that gesture on the child explicitly so this host's
                    // drag-to-scroll is the only thing driving the page's
                    // scroll position from here on.
                    cancelChildTouch(ev)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPanning = false
        }
        return isPanning
    }

    private fun cancelChildTouch(ev: MotionEvent) {
        val child = getChildAt(0) ?: return
        val cancelEvent = MotionEvent.obtain(ev)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        child.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!canPan()) return super.onTouchEvent(ev)
        val webView = getChildAt(0) as? WebView
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                isPanning = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPanning && ev.pointerCount == 1 &&
                    (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)
                ) {
                    isPanning = true
                    lastX = ev.x
                    lastY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isPanning && webView != null) {
                    // Real engine scroll: the browser owns clamping to the
                    // document's actual scrollable range, so there is no
                    // separate pan/max-pan bookkeeping here to fall out of
                    // sync with what is actually drawn.
                    val dx = (lastX - ev.x).toInt()
                    val dy = (lastY - ev.y).toInt()
                    webView.scrollBy(dx, dy)
                    lastX = ev.x
                    lastY = ev.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPanning = false
        }
        return true
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // The page asks its parents to leave a gesture alone as soon as it
        // thinks it can scroll. While this host is driving the scroll itself
        // (panEnabled), that request is ignored so the drag isn't interrupted.
        if (panEnabled) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }
}
