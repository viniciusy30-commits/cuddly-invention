package com.example.quadbrowser

    import android.animation.Animator
    import android.animation.AnimatorListenerAdapter
    import android.animation.ValueAnimator
    import android.content.Context
    import android.util.AttributeSet
    import android.view.ViewGroup
    import kotlin.math.roundToInt

    /** A two-page horizontal pager. Page switching is driven entirely by
     * setCurrentPage() calls from outside (e.g. the top toolbar's drag/tap
     * area) — this view no longer intercepts touch gestures itself, so a
     * horizontal drag/scroll that starts inside the page content (a game
     * menu, a carousel, etc.) is never mistaken for a request to switch
     * instances. */
    class PagedInstancesLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ViewGroup(context, attrs, defStyleAttr) {
        private var pageOffset = 0f
        private var activeAnimator: ValueAnimator? = null
        private var pageChangedListener: ((Int) -> Unit)? = null

        var currentPage: Int = 0
            private set

        init {
            clipChildren = true
            clipToPadding = true
        }

        fun setPageChangedListener(listener: (Int) -> Unit) {
            pageChangedListener = listener
        }

        fun setCurrentPage(page: Int, animate: Boolean) {
            val target = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
            activeAnimator?.cancel()
            if (!animate || width <= 0) {
                val changed = currentPage != target
                currentPage = target
                pageOffset = 0f
                requestLayout()
                if (changed) pageChangedListener?.invoke(currentPage)
                return
            }
            animateToPage(target)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
            val measuredHeight = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
            for (index in 0 until childCount) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val pageWidth = measuredWidth
            val pageHeight = measuredHeight
            for (index in 0 until childCount) {
                val pageLeft = ((index - currentPage) * pageWidth + pageOffset).roundToInt()
                getChildAt(index).layout(pageLeft, 0, pageLeft + pageWidth, pageHeight)
            }
        }

        /**
         * Called from the toolbar's own drag handler (outside this view's
         * content area) to live-preview the page transition while dragging.
         * offset is in pixels, positive = dragging toward the previous page.
         */
        fun previewDragOffset(offsetPx: Float) {
            pageOffset = offsetPx.coerceIn(-width.toFloat(), width.toFloat())
            requestLayout()
        }

        /** Called when the toolbar drag ends, to settle on the nearest page. */
        fun finishDrag(offsetPx: Float) {
            val target = when {
                offsetPx < -width * 0.2f -> currentPage + 1
                offsetPx > width * 0.2f -> currentPage - 1
                else -> currentPage
            }.coerceIn(0, (childCount - 1).coerceAtLeast(0))
            animateToPage(target)
        }

        private fun animateToPage(target: Int) {
            val targetOffset = (currentPage - target) * width.toFloat()
            activeAnimator?.cancel()
            activeAnimator = ValueAnimator.ofFloat(pageOffset, targetOffset).apply {
                duration = 180L
                addUpdateListener {
                    pageOffset = it.animatedValue as Float
                    requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val changed = currentPage != target
                        currentPage = target
                        pageOffset = 0f
                        requestLayout()
                        if (changed) pageChangedListener?.invoke(currentPage)
                        activeAnimator = null
                    }
                })
                start()
            }
        }
    }
    