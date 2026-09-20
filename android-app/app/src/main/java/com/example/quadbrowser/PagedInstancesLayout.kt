package com.example.quadbrowser

    import android.animation.Animator
    import android.animation.AnimatorListenerAdapter
    import android.animation.ValueAnimator
    import android.content.Context
    import android.util.AttributeSet
    import android.view.MotionEvent
    import android.view.ViewConfiguration
    import android.view.ViewGroup
    import kotlin.math.abs
    import kotlin.math.roundToInt

    /** A two-page horizontal pager that leaves vertical gestures to each WebView. */
    class PagedInstancesLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ViewGroup(context, attrs, defStyleAttr) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var pageOffset = 0f
        private var isDragging = false
        private var activeAnimator: ValueAnimator? = null
        private var pageChangedListener: ((Int) -> Unit)? = null

        var currentPage: Int = 0
            private set

        init {
            clipChildren = true
            clipToPadding = true
            isClickable = true
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

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeAnimator?.cancel()
                    downX = event.x
                    downY = event.y
                    isDragging = false
                    pageOffset = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!isDragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> isDragging = false
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) <= touchSlop || abs(dx) <= abs(dy)) return true
                        isDragging = true
                    }
                    pageOffset = (event.x - downX).coerceIn(-width.toFloat(), width.toFloat())
                    requestLayout()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val target = when {
                            pageOffset < -width * 0.2f -> currentPage + 1
                            pageOffset > width * 0.2f -> currentPage - 1
                            else -> currentPage
                        }.coerceIn(0, (childCount - 1).coerceAtLeast(0))
                        isDragging = false
                        animateToPage(target)
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    animateToPage(currentPage)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
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
    