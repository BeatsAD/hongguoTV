// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.widget.*

internal class BrowseUi(val context: Context) {
    fun dp(value: Int)=(value*context.resources.displayMetrics.density).toInt()
    fun text(value: String,size: Float=14f,color: Int=TvStyle.text)=TextView(context).apply { text=value;textSize=size;setTextColor(color);includeFontPadding=false }
    fun column()=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;clipChildren=false }
    fun row()=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;clipChildren=false }
    fun title(value: String,size: Float=24f)=text(value,size).apply { typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);maxLines=1;setPadding(0,dp(3),0,dp(8)) }
    fun heading(value: String,size: Float=24f)=row().apply { minimumHeight=dp(44);addView(title(value,size),LinearLayout.LayoutParams(0,-2,1f)) }
    fun line(parent: LinearLayout,items: List<android.view.View>) {
        val row=row();items.forEach { row.addView(it,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) }) }
        parent.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false;clipToPadding=false;setPadding(dp(6),dp(6),dp(6),dp(6));addView(row) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(7) })
    }
}
