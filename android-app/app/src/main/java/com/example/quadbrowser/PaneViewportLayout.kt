package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * A viewport host whose child (the WebView) is always laid out at exactly
 * this host's own on-screen size — no reference/fullscreen sizing, no
 * visual scaling, no snapshotting. The web page renders responsively at
 * whatever size the cell actually is, the same way it would on a small
 * phone screen.
 *
 * This is intentionally the simplest possible implementation. Earlier
 * versions tried to render the page at a fixed "fullscreen" reference size
 * and shrink it visually (via View.scaleX/scaleY or a frozen snapshot) so
 * grid thumbnails would look like a zoomed-out copy of the fullscreen page.
 * That approach caused a cascade of hard-to-fix issues: a GPU-compositing
 * desync during scroll (stale background-colored pixels), duplicated/
 * misplaced content when a new page loaded, and inconsistent cell sizing.
 * None of the incremental fixes attempted (clip bounds, touch remapping,
 * software layers, frozen snapshots) fully resolved it. Falling back to
 * "the WebView is simply the size of its cell" removes the entire class of
 * bug at the cost of the page re-flowing its own responsive layout for a
 * smaller viewport instead of being a pixel-perfect shrunk copy.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        clipChildren = true
        clipToPadding = true
    }

    /**
     * Kept for source compatibility with existing call sites in
     * MainActivity — this implementation no longer uses a reference size or
     * scale factor, so width/height/scale are accepted but ignored. Always
     * returns false (no layout change to react to).
     */
    fun setSurfaceSize(width: Int, height: Int, scale: Float): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hostWidth = resolveHostSize(widthMeasureSpec, suggestedMinimumWidth)
        val hostHeight = resolveHostSize(heightMeasureSpec, suggestedMinimumHeight)
        val child = getChildAt(0)
        if (child != null && child.visibility != View.GONE) {
            child.scaleX = 1f
            child.scaleY = 1f
            child.translationX = 0f
            child.translationY = 0f
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
        child.layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }
}
