package com.example.quadbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * A viewport host that renders its child (the WebView) at a fixed reference
 * size — normally the real fullscreen size of the device — and scales that
 * whole rendered surface down visually to fit the host's actual size on
 * screen (e.g. one cell of the 2x2 grid). The web page never learns it's
 * being displayed small, so it never re-flows its responsive layout.
 *
 * Known issue this works around: a hardware-accelerated WebView composites
 * into its own GPU-managed surface. Combined with an ancestor's scaleX/scaleY
 * transform, that surface can desync from the clip/transform specifically
 * during scroll gestures, leaving stale background-colored pixels visible.
 * Neither adjusting clip bounds nor touch handling fixed this (both were
 * tried). The fix here: while a scroll gesture is active inside a scaled
 * pane, a frozen snapshot bitmap (taken just before the gesture) is shown
 * instead of the live transformed WebView, and the live view is restored the
 * instant the gesture ends. Touches always go to the real WebView underneath
 * (the snapshot never intercepts touch), so tapping/typing in the small
 * thumbnail keeps working exactly as before — only the visual glitch that
 * happens mid-scroll is covered up.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var referenceWidth = 0
    private var referenceHeight = 0
    private var scaledMode = false

    private val snapshotView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.MATRIX
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }
    private var snapshotBitmap: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isGestureActive = false
    private var restoreRunnable: Runnable? = null

    init {
        clipChildren = true
        clipToPadding = true
        addView(snapshotView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * width/height: the reference size to render the child at (normally the
     * device's real fullscreen size). Pass width/height <= 1 (or equal to
     * the host's own size) to render the child live at 1:1 with no scaling,
     * e.g. in fullscreen/paged mode — the scroll-glitch workaround only
     * applies when actually scaled down.
     */
    fun setSurfaceSize(width: Int, height: Int, scale: Float): Boolean {
        val nextWidth = width.coerceAtLeast(1)
        val nextHeight = height.coerceAtLeast(1)
        val changed = referenceWidth != nextWidth || referenceHeight != nextHeight
        referenceWidth = nextWidth
        referenceHeight = nextHeight
        if (changed) requestLayout()
        return changed
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hostWidth = resolveHostSize(widthMeasureSpec, suggestedMinimumWidth)
        val hostHeight = resolveHostSize(heightMeasureSpec, suggestedMinimumHeight)
        snapshotView.measure(
            MeasureSpec.makeMeasureSpec(hostWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(hostHeight, MeasureSpec.EXACTLY),
        )
        val child = getChildAt(1)
        if (child != null && child.visibility != View.GONE) {
            scaledMode = referenceWidth > hostWidth || referenceHeight > hostHeight
            val childWidth = if (scaledMode) referenceWidth else hostWidth
            val childHeight = if (scaledMode) referenceHeight else hostHeight
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
            if (scaledMode) {
                val scaleX = hostWidth.toFloat() / childWidth.toFloat()
                val scaleY = hostHeight.toFloat() / childHeight.toFloat()
                val uniformScale = minOf(scaleX, scaleY).coerceIn(0.05f, 1f)
                child.pivotX = 0f
                child.pivotY = 0f
                child.scaleX = uniformScale
                child.scaleY = uniformScale
                child.translationX = (hostWidth - childWidth * uniformScale) / 2f
                child.translationY = (hostHeight - childHeight * uniformScale) / 2f
            } else {
                child.pivotX = 0f
                child.pivotY = 0f
                child.scaleX = 1f
                child.scaleY = 1f
                child.translationX = 0f
                child.translationY = 0f
            }
        } else {
            scaledMode = false
        }
        setMeasuredDimension(hostWidth, hostHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        snapshotView.layout(0, 0, snapshotView.measuredWidth, snapshotView.measuredHeight)
        val child = getChildAt(1) ?: return
        if (child.visibility == View.GONE) return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    /**
     * Only active in scaledMode. Detects the start of a drag/scroll gesture
     * (a MOVE past touch slop after DOWN) and freezes a snapshot on top of
     * the live WebView for its duration, restoring the live view shortly
     * after the finger lifts. Touches themselves are never intercepted here
     * (dispatchTouchEvent is not overridden) — this only toggles which layer
     * is drawn on top, so tap/type/click behavior is completely unaffected.
     */
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val touchSlop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (scaledMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = ev.x
                    touchStartY = ev.y
                    cancelPendingRestore()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isGestureActive) {
                        val dx = Math.abs(ev.x - touchStartX)
                        val dy = Math.abs(ev.y - touchStartY)
                        if (dx > touchSlop || dy > touchSlop) {
                            beginFrozenGesture()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scheduleRestore()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun beginFrozenGesture() {
        val child = getChildAt(1) ?: return
        if (child.width <= 0 || child.height <= 0) return
        try {
            var bitmap = snapshotBitmap
            if (bitmap == null || bitmap.width != child.width || bitmap.height != child.height) {
                bitmap = Bitmap.createBitmap(child.width, child.height, Bitmap.Config.ARGB_8888)
                snapshotBitmap = bitmap
            }
            val canvas = Canvas(bitmap)
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            child.draw(canvas)
            applyBitmapToSnapshotView(bitmap)
            snapshotView.visibility = View.VISIBLE
            isGestureActive = true
        } catch (_: Exception) {
            // If capturing fails for any reason, just skip the freeze this
            // time rather than risk leaving the snapshot stuck on screen.
        }
    }

    private fun scheduleRestore() {
        cancelPendingRestore()
        val runnable = Runnable {
            snapshotView.visibility = View.GONE
            isGestureActive = false
        }
        restoreRunnable = runnable
        // Small delay after finger-up: WebView fling/scroll settling can
        // still repaint incorrectly for a moment after the gesture ends.
        handler.postDelayed(runnable, 180L)
    }

    private fun cancelPendingRestore() {
        restoreRunnable?.let { handler.removeCallbacks(it) }
        restoreRunnable = null
    }

    private fun applyBitmapToSnapshotView(bitmap: Bitmap) {
        val hostWidth = width
        val hostHeight = height
        if (hostWidth <= 0 || hostHeight <= 0) return
        val scaleX = hostWidth.toFloat() / bitmap.width.toFloat()
        val scaleY = hostHeight.toFloat() / bitmap.height.toFloat()
        val uniformScale = minOf(scaleX, scaleY).coerceIn(0.05f, 1f)
        val dx = (hostWidth - bitmap.width * uniformScale) / 2f
        val dy = (hostHeight - bitmap.height * uniformScale) / 2f
        val matrix = Matrix().apply {
            setScale(uniformScale, uniformScale)
            postTranslate(dx, dy)
        }
        snapshotView.imageMatrix = matrix
        snapshotView.setImageBitmap(bitmap)
    }

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingRestore()
    }
}
