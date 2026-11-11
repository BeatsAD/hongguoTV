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
    internal val focusSelection: FocusSelection
    private var redraw: ()->Unit={}
    fun select(value: Boolean) { isSelected=value;redraw() }
    init {
        val density=resources.displayMetrics.density
        fun dp(n: Int)=(n*density).toInt()
        id=View.generateViewId();text=value;textSize=16f;includeFontPadding=false;isFocusable=true;isFocusableInTouchMode=true;isSelected=selected;gravity=Gravity.CENTER_VERTICAL
        minHeight=dp(44);maxLines=1;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        setPadding(dp(8),dp(8),dp(4),dp(8));setTextColor(if(selected) TvStyle.text else TvStyle.muted);compoundDrawablePadding=dp(11)
        setCompoundDrawables(TvGlyph(kind,filled=selected && kind==TvGlyph.Kind.HOME).apply { setBounds(0,0,dp(22),dp(22)) },null,null,null)
        fun paint() {
            setTextColor(if(isSelected) TvStyle.text else TvStyle.muted)
            setCompoundDrawables(TvGlyph(kind,filled=isSelected && kind==TvGlyph.Kind.HOME).apply { setBounds(0,0,dp(22),dp(22)) },null,null,null)
            val base=TvControlDrawable(context,idleColor=Color.TRANSPARENT)
            background=if(isSelected) LayerDrawable(arrayOf(base,TvStyle.shape(context,TvStyle.accent,radius=2))).apply {
                setLayerGravity(1,Gravity.START or Gravity.CENTER_VERTICAL);setLayerSize(1,dp(3),dp(30))
            } else base
        }
        redraw={ paint() };paint()
        focusSelection=FocusSelection(this,RemoteFocus.NETWORK_DELAY,action,confirmSelected=true,exitKeys=setOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        setOnClickListener { focusSelection.confirm() }
    }
}
