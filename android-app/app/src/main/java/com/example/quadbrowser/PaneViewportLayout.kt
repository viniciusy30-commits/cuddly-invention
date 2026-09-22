package com.example.quadbrowser

    import android.content.Context
    import android.util.AttributeSet
    import android.view.View
    import android.view.ViewGroup
    import android.widget.FrameLayout

    /**
    * Hosts one browser pane at the exact size of its cell in the 2x2 grid.
    * The WebView is not rendered as a scaled fullscreen surface: responsive
    * pages receive the real dimensions available to that instance.
    */
    class PaneViewportLayout @JvmOverloads constructor(
      context: Context,
      attrs: AttributeSet? = null,
      defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
      init {
          clipChildren = true
          clipToPadding = true
      }

      /** Kept for the existing activity contract; the host size is authoritative. */
      fun setSurfaceSize(width: Int, height: Int, scale: Float): Boolean = false

      override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
          val hostWidth = resolveHostSize(widthMeasureSpec, suggestedMinimumWidth)
          val hostHeight = resolveHostSize(heightMeasureSpec, suggestedMinimumHeight)
          val child = getChildAt(0)
          if (child != null && child.visibility != View.GONE) {
              child.measure(
                  MeasureSpec.makeMeasureSpec(hostWidth, MeasureSpec.EXACTLY),
                  MeasureSpec.makeMeasureSpec(hostHeight, MeasureSpec.EXACTLY),
              )
              child.pivotX = 0f
              child.pivotY = 0f
              child.scaleX = 1f
              child.scaleY = 1f
              child.translationX = 0f
              child.translationY = 0f
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
    