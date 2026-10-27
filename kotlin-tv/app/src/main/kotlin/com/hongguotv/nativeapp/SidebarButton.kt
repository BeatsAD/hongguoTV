// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Current page and remote focus are independent visual states. */
class SidebarButton(context: Context,value: String,kind: TvGlyph.Kind,selected: Boolean,action: ()->Unit): TextView(context) {
    init {
        val density=resources.displayMetrics.density
        fun dp(n: Int)=(n*density).toInt()
        id=View.generateViewId();text=value;textSize=14f;includeFontPadding=false;isFocusable=true;isFocusableInTouchMode=true;isSelected=selected;gravity=Gravity.CENTER_VERTICAL
        minHeight=dp(33);maxLines=1
        setPadding(dp(8),dp(8),dp(4),dp(8));setTextColor(if(selected) TvStyle.text else 0xFFC2C3CC.toInt());compoundDrawablePadding=dp(11)
        setCompoundDrawables(TvGlyph(kind,filled=selected && kind==TvGlyph.Kind.HOME).apply { setBounds(0,0,dp(17),dp(17)) },null,null,null)
        fun paint() {
            val base=TvStyle.shape(context,if(selected) TvStyle.surface else Color.TRANSPARENT,if(hasFocus()) TvStyle.text else Color.TRANSPARENT,radius=6)
            background=if(selected) LayerDrawable(arrayOf(base,TvStyle.shape(context,TvStyle.accent,radius=2))).apply {
                setLayerGravity(1,Gravity.START or Gravity.CENTER_VERTICAL);setLayerSize(1,dp(3),dp(27))
            } else base
        }
        paint();setOnFocusChangeListener { _,_-> paint() };setOnClickListener { action() }
    }
}
