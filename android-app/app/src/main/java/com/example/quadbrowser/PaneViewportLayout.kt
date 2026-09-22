package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * A viewport host that renders its child (the WebView) at a fixed reference
 * size — normally the real fullscreen size of the device — and then scales
 * that whole rendered surface down visually to fit the host's actual size on
 * screen (e.g. one cell of the 2x2 grid).
 *
 * This is what makes the grid thumbnail look like a true zoomed-out copy of
 * the fullscreen page: the web page never learns it's being displayed small,
 * so it never re-flows its responsive layout — Chromium always lays it out
 * at full width/height, and Android's View.scaleX/scaleY just shrinks the
 * finished pixels, the same way a photo thumbnail is a shrunk photo, not a
 * different photo taken with a smaller camera.
 */
class PaneViewportLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    // When these are > 0, the child is measured at exactly this size instead
    // of the host's own size, and then visually scaled down to fit the host.
    private var referenceWidth = 0
    private var referenceHeight = 0

    init {
        clipChildren = true
        clipToPadding = true
        // clipChildren alone is not always enough to constrain a hardware-
        // accelerated WebView's composited surface, especially mid-scroll.
        // Explicit clipBounds forces the canvas itself to be cut at these
        // exact pixel bounds regardless of how the child composites.
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipBounds = android.graphics.Rect(0, 0, w, h)
    }

    /**
     * width/height: the reference size to render the child at (normally the
     * device's real fullscreen size). scale: informational only — the actual
     * scale factor is always derived from host size / reference size, so the
     * child fits exactly regardless of small rounding differences upstream.
     * Pass width/height <= 1 (or equal reference == host) to render at 1:1
     * with no scaling, e.g. in fullscreen/paged mode.
     */
    fun setSurfaceSize(width: Int, height: Int, scale: Float): Boolean {
        val nextWidth = width.coerceAtLeast(1)
        val nextHeight = height.coerceAtLeast(1)
        val changed = referenceWidth != nextWidth || referenceHeight != nextHeight
        referenceWidth = nextWidth
        referenceHeight = nextHeight
        if (changed) requestLayout()
        if (this.width > 0 && this.height > 0) {
            clipBounds = android.graphics.Rect(0, 0, this.width, this.height)
        }
        return changed
    }

    private var appliedScale = 1f
    private var appliedTranslationX = 0f
    private var appliedTranslationY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hostWidth = resolveHostSize(widthMeasureSpec, suggestedMinimumWidth)
        val hostHeight = resolveHostSize(heightMeasureSpec, suggestedMinimumHeight)
        val child = getChildAt(0)
        if (child != null && child.visibility != View.GONE) {
            val useReference = referenceWidth > hostWidth || referenceHeight > hostHeight
            val childWidth = if (useReference) referenceWidth else hostWidth
            val childHeight = if (useReference) referenceHeight else hostHeight
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
            if (useReference) {
                val scaleX = hostWidth.toFloat() / childWidth.toFloat()
                val scaleY = hostHeight.toFloat() / childHeight.toFloat()
                // Uniform scale (letterbox/pillarbox) so the page keeps its
                // real aspect ratio instead of being stretched/squashed.
                val uniformScale = minOf(scaleX, scaleY).coerceIn(0.05f, 1f)
                child.pivotX = 0f
                child.pivotY = 0f
                child.scaleX = uniformScale
                child.scaleY = uniformScale
                // Center the scaled-down surface within the host cell.
                child.translationX = (hostWidth - childWidth * uniformScale) / 2f
                child.translationY = (hostHeight - childHeight * uniformScale) / 2f
                appliedScale = uniformScale
                appliedTranslationX = child.translationX
                appliedTranslationY = child.translationY
                // A hardware-accelerated WebView composites into its own GPU
                // texture sized to what it believes is on-screen. Combined
                // with a scaleX/scaleY transform on an ancestor, that texture
                // can be recomposited incompletely during scroll gestures —
                // the lower portion of the page then shows the WebView's
                // background color instead of content. Forcing a software
                // layer here makes this View (and everything under it,
                // including the WebView) render into a plain bitmap that is
                // scaled as a whole, which does not have this GPU recomposition
                // bug. This only applies in grid/thumbnail mode; fullscreen
                // mode (no scaling) keeps normal hardware acceleration.
                if (layerType != View.LAYER_TYPE_SOFTWARE) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
            } else {
                child.pivotX = 0f
                child.pivotY = 0f
                child.scaleX = 1f
                child.scaleY = 1f
                child.translationX = 0f
                child.translationY = 0f
                appliedScale = 1f
                appliedTranslationX = 0f
                appliedTranslationY = 0f
                if (layerType != View.LAYER_TYPE_NONE) {
                    setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }
        }
        setMeasuredDimension(hostWidth, hostHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        clipBounds = android.graphics.Rect(0, 0, right - left, bottom - top)
        if (child.visibility == View.GONE) return
        // Layout the child at its full measured (reference) size starting at
        // the origin; scaleX/scaleY + translation (set in onMeasure) handle
        // fitting it visually into the host's actual on-screen bounds.
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    /**
     * The child (WebView) is laid out at its full reference size (e.g.
     * fullscreen) but only ever *drawn* shrunk down via scaleX/scaleY —
     * Android does remap touch coordinates through that visual transform
     * automatically, BUT the child's real (unscaled) bounds still extend
     * far beyond this host's small visible area. If a sibling pane sits
     * right below/beside this one, a touch that lands outside this host's
     * own bounds must never reach this host's still-huge child. clipChildren
     * only affects drawing, not hit-testing, so we enforce it here: only
     * forward touches that start within this host's own on-screen bounds.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val withinHost = event.x >= 0f && event.x <= width.toFloat() &&
                event.y >= 0f && event.y <= height.toFloat()
            if (!withinHost) return false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resolveHostSize(spec: Int, fallback: Int): Int = when (MeasureSpec.getMode(spec)) {
        MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
        MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
        else -> fallback.coerceAtLeast(1)
    }
}
