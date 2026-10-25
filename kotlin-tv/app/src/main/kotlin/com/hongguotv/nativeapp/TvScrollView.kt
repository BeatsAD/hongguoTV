// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Rect
import android.widget.ScrollView

/** Permit the hero behind navigation at the top, but keep scrolled cards below the fixed bar. */
class TvScrollView(context: Context,overdrawTopDp: Int=0): ScrollView(context) {
    private val overdraw=(overdrawTopDp*resources.displayMetrics.density).toInt()
    init { isVerticalScrollBarEnabled=false; clipToPadding=false; isFocusable=false }
    private fun clipViewport() { clipBounds=Rect(0,-(overdraw-scrollY).coerceAtLeast(0),width,height) }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); clipViewport() }
    override fun onScrollChanged(x: Int,y: Int,oldX: Int,oldY: Int) { super.onScrollChanged(x,y,oldX,oldY); clipViewport() }
}
