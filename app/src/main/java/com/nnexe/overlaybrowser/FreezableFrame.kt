package com.nnexe.overlaybrowser

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Holds the WebView.
 *
 * 1. While frozen (minimized), it keeps measuring at the size it had when frozen, even if the
 *    window around it shrinks to 1x1, so minimizing doesn't re-layout the page and audio continues.
 * 2. When the floating window is resized smaller than a standard mobile viewport (e.g. < 480x270 dp),
 *    it keeps the child WebView laid out at >= 480x270 dp with the same aspect ratio and hardware-scales
 *    it down to fit. This prevents YouTube and Chromium's media player from hitting their small-player
 *    minimum size threshold (~356x200 CSS px), which otherwise stutters playback every 1 second.
 */
class FreezableFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var frozenWidth = 0
    private var frozenHeight = 0
    private var currentScaleX = 1f
    private var currentScaleY = 1f

    fun freeze() {
        frozenWidth = width
        frozenHeight = height
    }

    fun unfreeze() {
        frozenWidth = 0
        frozenHeight = 0
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (frozenWidth > 0 && frozenHeight > 0) frozenWidth else MeasureSpec.getSize(widthMeasureSpec)
        val h = if (frozenWidth > 0 && frozenHeight > 0) frozenHeight else MeasureSpec.getSize(heightMeasureSpec)

        if (w <= 0 || h <= 0) {
            currentScaleX = 1f
            currentScaleY = 1f
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val targetMinW = if (w >= h) dp(MIN_LONG_DP) else dp(MIN_SHORT_DP)
        val targetMinH = if (w >= h) dp(MIN_SHORT_DP) else dp(MIN_LONG_DP)
        val upScale = max(1f, max(targetMinW.toFloat() / w, targetMinH.toFloat() / h))

        val childW = (w * upScale).roundToInt().coerceAtLeast(1)
        val childH = (h * upScale).roundToInt().coerceAtLeast(1)
        currentScaleX = w.toFloat() / childW
        currentScaleY = h.toFloat() / childH

        val childWidthSpec = MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) {
                child.measure(childWidthSpec, childHeightSpec)
            }
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = currentScaleX
            child.scaleY = currentScaleY
            child.layout(0, 0, cw, ch)
        }
    }

    override fun getChildVisibleRect(child: View, r: Rect, offset: Point?): Boolean {
        val visible = super.getChildVisibleRect(child, r, offset)
        if (visible && (currentScaleX < 1f || currentScaleY < 1f)) {
            r.right = r.left + child.width
            r.bottom = r.top + child.height
        }
        return visible
    }

    private companion object {
        const val MIN_LONG_DP = 480
        const val MIN_SHORT_DP = 270
    }
}
