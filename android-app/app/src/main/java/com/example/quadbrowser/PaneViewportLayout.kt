package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** A viewport host whose child is always laid out at the visible cell size. */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    // Compatibility API for the activity. The WebView is intentionally
    // never laid out at a larger off-screen reference size.
    private var requestedSurfaceWidth = 0
    private var requestedSurfaceHeight = 0

    init {
        clipChildren = true
        clipToPadding = true
    }

    fun setSurfaceSize(width: Int, height: Int, scale: Float): Boolean {
        val nextWidth = width.coerceAtLeast(1)
        val nextHeight = height.coerceAtLeast(1)
        val changed = requestedSurfaceWidth != nextWidth || requestedSurfaceHeight != nextHeight
        requestedSurfaceWidth = nextWidth
        requestedSurfaceHeight = nextHeight
        if (changed) requestLayout()
        return changed
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
        if (child.visibility != View.GONE) child.layout(0, 0, measuredWidth, measuredHeight)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }
}
