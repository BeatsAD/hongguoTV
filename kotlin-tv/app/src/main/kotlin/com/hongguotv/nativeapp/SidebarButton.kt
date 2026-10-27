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
        id=View.generateViewId();text=value;textSize=15f;isFocusable=true;isFocusableInTouchMode=true;isSelected=selected;gravity=Gravity.CENTER_VERTICAL
        minHeight=dp(38);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(dp(14),dp(8),dp(10),dp(8));setTextColor(TvStyle.text);compoundDrawablePadding=dp(11)
        setCompoundDrawables(TvGlyph(kind).apply { setBounds(0,0,dp(18),dp(18)) },null,null,null)
        fun paint() {
            val base=TvStyle.shape(context,if(selected) TvStyle.surface else Color.TRANSPARENT,if(hasFocus()) TvStyle.text else Color.TRANSPARENT,radius=6)
            background=if(selected) LayerDrawable(arrayOf(base,TvStyle.shape(context,TvStyle.accent,radius=2))).apply {
                setLayerGravity(1,Gravity.START or Gravity.CENTER_VERTICAL);setLayerSize(1,dp(3),dp(27))
            } else base
        }
        paint();setOnFocusChangeListener { _,_-> paint() };setOnClickListener { action() }
    }
}
