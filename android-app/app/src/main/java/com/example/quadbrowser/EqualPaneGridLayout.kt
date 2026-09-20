package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout

/** A deterministic 2x2 grid: every pane receives the same measured cell size. */
class EqualPaneGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : GridLayout(context, attrs, defStyleAttr) {
    init {
        columnCount = 2
        rowCount = 2
        useDefaultMargins = false
        // Child hosts clip their own final visual cells; keep the grid from
        // clipping the untransformed bounds of a scaled fullscreen surface.
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams
            val row = index / 2
            val column = index % 2
            val slotLeft = contentWidth * column / 2
            val slotRight = contentWidth * (column + 1) / 2
            val slotTop = contentHeight * row / 2
            val slotBottom = contentHeight * (row + 1) / 2
            val childWidth = (slotRight - slotLeft - (params?.leftMargin ?: 0) - (params?.rightMargin ?: 0)).coerceAtLeast(0)
            val childHeight = (slotBottom - slotTop - (params?.topMargin ?: 0) - (params?.bottomMargin ?: 0)).coerceAtLeast(0)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (bottom - top - paddingTop - paddingBottom).coerceAtLeast(0)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams
            val row = index / 2
            val column = index % 2
            val slotLeft = paddingLeft + contentWidth * column / 2
            val slotRight = paddingLeft + contentWidth * (column + 1) / 2
            val slotTop = paddingTop + contentHeight * row / 2
            val slotBottom = paddingTop + contentHeight * (row + 1) / 2
            val childLeft = slotLeft + (params?.leftMargin ?: 0)
            val childTop = slotTop + (params?.topMargin ?: 0)
            val childRight = slotRight - (params?.rightMargin ?: 0)
            val childBottom = slotBottom - (params?.bottomMargin ?: 0)
            child.layout(childLeft, childTop, childRight, childBottom)
        }
    }
}
