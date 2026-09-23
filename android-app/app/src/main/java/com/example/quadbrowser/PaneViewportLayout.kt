package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Hosts a pane either at its measured size or on an explicit logical surface.
 * The latter is used only for the four-pane grid, where a stable thumbnail
 * coordinate space is useful. Pager, freeform and PiP layouts reset the
 * surface so the WebView fills the bounds Android actually provides.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceScale = 1f

    init {
        clipChildren = true
        clipToPadding = true
    }

    /**
     * Uses a fixed logical surface and a uniform visual scale. Returning true
     * means the layout parameters changed and the caller may refresh markers.
     */
    fun setSurfaceSize(width: Int, height: Int, scale: Float): Boolean {
        val nextWidth = width.coerceAtLeast(0)
        val nextHeight = height.coerceAtLeast(0)
        val nextScale = scale.coerceIn(0.01f, 1f)
        val changed = surfaceWidth != nextWidth || surfaceHeight != nextHeight || surfaceScale != nextScale
        surfaceWidth = nextWidth
        surfaceHeight = nextHeight
        surfaceScale = nextScale
        if (changed) requestLayout()
        return changed
    }

    fun resetSurfaceSize() {
        setSurfaceSize(0, 0, 1f)
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
        child.scaleX = if (surfaceWidth > 0) surfaceScale else 1f
        child.scaleY = if (surfaceHeight > 0) surfaceScale else 1f
        val renderedWidth = childWidth * child.scaleX
        val renderedHeight = childHeight * child.scaleY
        child.translationX = ((measuredWidth - renderedWidth) / 2f).coerceAtLeast(0f)
        child.translationY = ((measuredHeight - renderedHeight) / 2f).coerceAtLeast(0f)
    }

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }
}
