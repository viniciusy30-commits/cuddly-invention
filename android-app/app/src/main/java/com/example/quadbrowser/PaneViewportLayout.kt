package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Hosts a pane either at its measured size or on an explicit logical surface.
 * The latter is used only for the four-pane grid, where a stable thumbnail
 * coordinate space is useful. Pager, freeform and PiP layouts reset the
 * surface so the WebView fills the bounds Android actually provides.
 *
 * While a logical surface is larger than this host (the 2x2 grid), a
 * one-finger drag pans that surface so every part of the page can be reached.
 * A plain tap is still delivered to the page untouched.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceScaleX = 1f
    private var surfaceScaleY = 1f

    // Current pan offset, in pixels, of the surface's top-left corner.
    private var panX = 0f
    private var panY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false

    /** Return true to temporarily disable panning (e.g. while editing auto-click markers). */
    var isPanBlocked: (() -> Boolean)? = null

    /** True while an explicit logical surface (the grid cell) is active. */
    val hasSurface: Boolean
        get() = surfaceWidth > 0 && surfaceHeight > 0

    init {
        clipChildren = true
        clipToPadding = true
    }

    /**
     * Uses a fixed logical surface and independent horizontal/vertical
     * scales, so the child always fills this host's bounds exactly instead
     * of being letterboxed to a single shared ratio. Returning true means
     * the layout parameters changed and the caller may refresh markers.
     */
    fun setSurfaceSize(width: Int, height: Int, scaleX: Float, scaleY: Float = scaleX): Boolean {
        val nextWidth = width.coerceAtLeast(0)
        val nextHeight = height.coerceAtLeast(0)
        val nextScaleX = scaleX.coerceIn(0.01f, 1f)
        val nextScaleY = scaleY.coerceIn(0.01f, 1f)
        val changed = surfaceWidth != nextWidth || surfaceHeight != nextHeight ||
            surfaceScaleX != nextScaleX || surfaceScaleY != nextScaleY
        surfaceWidth = nextWidth
        surfaceHeight = nextHeight
        surfaceScaleX = nextScaleX
        surfaceScaleY = nextScaleY
        if (changed) requestLayout()
        return changed
    }

    fun resetSurfaceSize() {
        setSurfaceSize(0, 0, 1f, 1f)
    }

    /** Puts the viewport back on the top-left corner of the surface. */
    fun resetPan() {
        panX = 0f
        panY = 0f
        applyPanTranslation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hostWidth = resolveHostSize(widthMeasureSpec, suggestedMinimumWidth)
        val hostHeight = resolveHostSize(heightMeasureSpec, suggestedMinimumHeight)
        val child = getChildAt(0)
        val childWidth = if (surfaceWidth > 0) surfaceWidth else hostWidth
        val childHeight = if (surfaceHeight > 0) surfaceHeight else hostHeight
        if (child != null && child.visibility != View.GONE) {
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(hostWidth, hostHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        if (child.visibility == View.GONE) return
        val childWidth = if (surfaceWidth > 0) surfaceWidth else measuredWidth
        val childHeight = if (surfaceHeight > 0) surfaceHeight else measuredHeight
        child.layout(0, 0, childWidth, childHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = if (surfaceWidth > 0) surfaceScaleX else 1f
        child.scaleY = if (surfaceHeight > 0) surfaceScaleY else 1f
        val renderedWidth = childWidth * child.scaleX
        val renderedHeight = childHeight * child.scaleY
        val maxPanX = (renderedWidth - measuredWidth).coerceAtLeast(0f)
        val maxPanY = (renderedHeight - measuredHeight).coerceAtLeast(0f)
        panX = panX.coerceIn(0f, maxPanX)
        panY = panY.coerceIn(0f, maxPanY)
        // Smaller than the host: keep it centered. Larger: show the part
        // selected by the pan offset (top-left corner by default).
        child.translationX = if (maxPanX > 0f) -panX else (measuredWidth - renderedWidth) / 2f
        child.translationY = if (maxPanY > 0f) -panY else (measuredHeight - renderedHeight) / 2f
    }

    private fun maxPanX(): Float {
        val child = getChildAt(0) ?: return 0f
        return (child.width * child.scaleX - width).coerceAtLeast(0f)
    }

    private fun maxPanY(): Float {
        val child = getChildAt(0) ?: return 0f
        return (child.height * child.scaleY - height).coerceAtLeast(0f)
    }

    private fun canPan(): Boolean {
        if (!hasSurface) return false
        if (isPanBlocked?.invoke() == true) return false
        val child = getChildAt(0) ?: return false
        if (child.visibility == View.GONE) return false
        return maxPanX() > 0f || maxPanY() > 0f
    }

    private fun applyPanTranslation() {
        val child = getChildAt(0) ?: return
        if (maxPanX() > 0f) child.translationX = -panX
        if (maxPanY() > 0f) child.translationY = -panY
    }

    private fun panBy(dx: Float, dy: Float) {
        val nextX = (panX + dx).coerceIn(0f, maxPanX())
        val nextY = (panY + dy).coerceIn(0f, maxPanY())
        if (nextX == panX && nextY == panY) return
        panX = nextX
        panY = nextY
        applyPanTranslation()
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
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPanning = false
        }
        return isPanning
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!canPan()) return super.onTouchEvent(ev)
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
                if (isPanning) {
                    panBy(lastX - ev.x, lastY - ev.y)
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
        // thinks it can scroll. On a pannable surface that would make the
        // drag impossible, so the request is ignored here.
        if (hasSurface) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }
}
