package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * A fixed cell viewport for a scaled browser pane.
 *
 * The child is always measured at the same reference surface size. The host
 * itself is measured by the 2x2 grid, so every row gets the same physical
 * viewport and the WebView never keeps a stale lower-row measurement.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceScale = 1f

    init {
        clipChildren = true
        clipToPadding = true
    }

    fun setSurfaceSize(width: Int, height: Int, scale: Float): Boolean {
        val nextWidth = width.coerceAtLeast(1)
        val nextHeight = height.coerceAtLeast(1)
        val nextScale = scale.coerceIn(0.1f, 1f)
        val changed = surfaceWidth != nextWidth ||
            surfaceHeight != nextHeight ||
            kotlin.math.abs(surfaceScale - nextScale) > 0.001f
        if (!changed) return false
        surfaceWidth = nextWidth
        surfaceHeight = nextHeight
        surfaceScale = nextScale
        requestLayout()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hostWidth = resolveHostSize(widthMeasureSpec, suggestedMinimumWidth)
        val hostHeight = resolveHostSize(heightMeasureSpec, suggestedMinimumHeight)
        val child = getChildAt(0)
        if (child != null && child.visibility != View.GONE) {
            val childWidth = if (surfaceWidth > 0) surfaceWidth else hostWidth
            val childHeight = if (surfaceHeight > 0) surfaceHeight else hostHeight
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
        val width = if (surfaceWidth > 0) surfaceWidth else measuredWidth
        val height = if (surfaceHeight > 0) surfaceHeight else measuredHeight
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = surfaceScale
        child.scaleY = surfaceScale
        child.translationX = (measuredWidth - width * surfaceScale).coerceAtLeast(0f) / 2f
        child.translationY = (measuredHeight - height * surfaceScale).coerceAtLeast(0f) / 2f
        child.layout(0, 0, width, height)
    }

    private fun resolveHostSize(measureSpec: Int, fallback: Int): Int = when (MeasureSpec.getMode(measureSpec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(measureSpec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(measureSpec)
        else -> fallback.coerceAtLeast(1)
    }
}
