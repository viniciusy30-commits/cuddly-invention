package com.example.quadbrowser

    import android.content.Context
    import android.util.AttributeSet
    import android.view.View
    import android.view.ViewGroup

    /**
    * Fixed two-column/two-row host for the browser panes.
    *
    * GridLayout weights can distribute rounding leftovers differently between
    * rows. This class gives all four cells the same measured rectangle, leaving
    * the last unused pixel at the edge instead of changing a pane's viewport.
    */
    class QuadGridLayout @JvmOverloads constructor(
      context: Context,
      attrs: AttributeSet? = null,
    ) : ViewGroup(context, attrs) {
      private val gapPx = (8f * resources.displayMetrics.density).toInt().coerceAtLeast(1)

      override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
          val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
          val measuredHeight = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
          setMeasuredDimension(measuredWidth, measuredHeight)

          val contentWidth = (measuredWidth - paddingLeft - paddingRight - gapPx).coerceAtLeast(0)
          val contentHeight = (measuredHeight - paddingTop - paddingBottom - gapPx).coerceAtLeast(0)
          val cellWidth = contentWidth / 2
          val cellHeight = contentHeight / 2
          for (index in 0 until childCount) {
              getChildAt(index).measure(
                  MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                  MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY),
              )
          }
      }

      override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
          val contentWidth = (width - paddingLeft - paddingRight - gapPx).coerceAtLeast(0)
          val contentHeight = (height - paddingTop - paddingBottom - gapPx).coerceAtLeast(0)
          val cellWidth = contentWidth / 2
          val cellHeight = contentHeight / 2
          for (index in 0 until childCount) {
              val child = getChildAt(index)
              val row = index / 2
              val column = index % 2
              val childLeft = paddingLeft + column * (cellWidth + gapPx)
              val childTop = paddingTop + row * (cellHeight + gapPx)
              child.layout(childLeft, childTop, childLeft + cellWidth, childTop + cellHeight)
          }
      }
    }
    