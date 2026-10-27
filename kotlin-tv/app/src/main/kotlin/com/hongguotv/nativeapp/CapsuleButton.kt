// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView

class CapsuleButton(context: Context,value: String,selected: Boolean=false,action: ()->Unit): TextView(context) {
    init {
        val d=resources.displayMetrics.density
        maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        id=View.generateViewId();text=value;textSize=13f;isFocusable=true;isFocusableInTouchMode=true;gravity=Gravity.CENTER
        setPadding((12*d).toInt(),(8*d).toInt(),(12*d).toInt(),(8*d).toInt());isSelected=selected
        fun paint() { setTextColor(TvStyle.text);background=TvStyle.shape(context,if(isSelected) TvStyle.accent else TvStyle.surface,if(hasFocus()) TvStyle.text else android.graphics.Color.TRANSPARENT,100) }
        paint();setOnFocusChangeListener { _,_->paint() };setOnClickListener { action() }
    }
}
