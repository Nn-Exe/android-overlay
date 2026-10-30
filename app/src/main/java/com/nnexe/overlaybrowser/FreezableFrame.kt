package com.nnexe.overlaybrowser

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Holds the WebView. While frozen it keeps measuring at the size it had when frozen, even if the
 * window around it shrinks to 1x1. That way minimizing doesn't re-layout the page, and the page
 * (and its audio) keeps running in the background.
 */
class FreezableFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var frozenWidth = 0
    private var frozenHeight = 0

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
        if (frozenWidth > 0 && frozenHeight > 0) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(frozenWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(frozenHeight, MeasureSpec.EXACTLY),
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
