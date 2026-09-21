package com.example.quadbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Keeps the WebView laid out on a full reference surface and scales only the
 * final drawing into the visible grid cell. Android View.scaleX/scaleY is not
 * used, because it can leave a WebView compositor tile clipped after scroll.
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
        setWillNotDraw(false)
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
        invalidate()
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
        child.scaleX = 1f
        child.scaleY = 1f
        child.translationX = 0f
        child.translationY = 0f
        child.layout(0, 0, width, height)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (surfaceScale >= 0.999f) {
            super.dispatchDraw(canvas)
            return
        }
        val offsetX = (width - surfaceWidth * surfaceScale).coerceAtLeast(0f) / 2f
        val offsetY = (height - surfaceHeight * surfaceScale).coerceAtLeast(0f) / 2f
        val saveCount = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(offsetX, offsetY)
        canvas.scale(surfaceScale, surfaceScale)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveCount)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (surfaceScale >= 0.999f || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return super.dispatchTouchEvent(event)
        }
        val offsetX = (width - surfaceWidth * surfaceScale).coerceAtLeast(0f) / 2f
        val offsetY = (height - surfaceHeight * surfaceScale).coerceAtLeast(0f) / 2f
        val transformed = MotionEvent.obtain(event)
        val matrix = Matrix().apply {
            setScale(1f / surfaceScale, 1f / surfaceScale)
            postTranslate(-offsetX / surfaceScale, -offsetY / surfaceScale)
        }
        transformed.transform(matrix)
        val handled = super.dispatchTouchEvent(transformed)
        transformed.recycle()
        return handled
    }

    private fun resolveHostSize(measureSpec: Int, fallback: Int): Int = when (MeasureSpec.getMode(measureSpec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(measureSpec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(measureSpec)
        else -> fallback.coerceAtLeast(1)
    }
}
