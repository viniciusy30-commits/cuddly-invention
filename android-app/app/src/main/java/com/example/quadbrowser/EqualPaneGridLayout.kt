package com.example.quadbrowser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout

/**
 * A deterministic grid with a fixed 2-column layout: every pane receives the
 * same measured cell size. The number of rows adapts to how many children
 * are attached (2 rows for up to 4 panes, up to 4 rows for up to 8 panes),
 * so it keeps working whether 4 or 8 instances are enabled.
 */
class EqualPaneGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : GridLayout(context, attrs, defStyleAttr) {

    private val columns = 2

    init {
        columnCount = columns
        rowCount = 2
        useDefaultMargins = false
        // Each viewport host owns its exact cell; keep the grid itself bounded too.
        clipChildren = true
        clipToPadding = true
    }

    private fun visibleChildCount(): Int {
        var count = 0
        for (index in 0 until childCount) {
            if (getChildAt(index).visibility != View.GONE) count++
        }
        return count
    }

    private fun rowsForChildCount(count: Int): Int =
        if (count <= 0) 1 else ((count + columns - 1) / columns).coerceAtLeast(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val rows = rowsForChildCount(visibleChildCount())
        rowCount = rows

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams
            val row = index / columns
            val column = index % columns
            val slotLeft = contentWidth * column / columns
            val slotRight = contentWidth * (column + 1) / columns
            val slotTop = contentHeight * row / rows
            val slotBottom = contentHeight * (row + 1) / rows
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
        val rows = rowsForChildCount(visibleChildCount())
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams
            val row = index / columns
            val column = index % columns
            val slotLeft = paddingLeft + contentWidth * column / columns
            val slotRight = paddingLeft + contentWidth * (column + 1) / columns
            val slotTop = paddingTop + contentHeight * row / rows
            val slotBottom = paddingTop + contentHeight * (row + 1) / rows
            val childLeft = slotLeft + (params?.leftMargin ?: 0)
            val childTop = slotTop + (params?.topMargin ?: 0)
            val childRight = slotRight - (params?.rightMargin ?: 0)
            val childBottom = slotBottom - (params?.bottomMargin ?: 0)
            child.layout(childLeft, childTop, childRight, childBottom)
        }
    }
}
