// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Small bounded option lists wrap instead of becoming full-width buttons or hidden horizontal rows. */
internal class BrowseFlow(context: Context,private val gap: Int=8): ViewGroup(context) {
    private val spacing=(gap*resources.displayMetrics.density).toInt()
    init { clipChildren=false }
    override fun generateDefaultLayoutParams()=LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT)
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val limit=MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight
        var x=0;var y=0;var line=0
        for(i in 0 until childCount) {
            val child=getChildAt(i);if(child.visibility==GONE) continue
            measureChild(child,widthMeasureSpec,heightMeasureSpec)
            if(x>0 && x+child.measuredWidth>limit) { y+=line+spacing;x=0;line=0 }
            x+=child.measuredWidth+spacing;line=maxOf(line,child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(MeasureSpec.getSize(widthMeasureSpec),widthMeasureSpec),resolveSize(y+line+paddingTop+paddingBottom,heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        var x=paddingLeft;var y=paddingTop;var line=0
        for(i in 0 until childCount) {
            val child=getChildAt(i);if(child.visibility==GONE) continue
            if(x>paddingLeft && x+child.measuredWidth>width-paddingRight) { y+=line+spacing;x=paddingLeft;line=0 }
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
            x+=child.measuredWidth+spacing;line=maxOf(line,child.measuredHeight)
        }
    }
}
