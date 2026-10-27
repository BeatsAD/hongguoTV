// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** A role is deliberate: navigation tabs, filters and status text are not all capsules. */
open class BrowseButton(context: Context,value: String,private val role: Role=Role.ACTION,selected: Boolean=false,private val action: ()->Unit): TextView(context) {
    internal var focusSelection: FocusSelection?=null
        private set
    fun autoSelect(delay: Long=0): BrowseButton { focusSelection=FocusSelection(this,delay,action);return this }
    enum class Role { TEXT, ACTION, PRIMARY, TAB, FILTER, CAPSULE, SEARCH }
    init {
        val ui=BrowseUi(context)
        id=View.generateViewId();text=value;textSize=13f;includeFontPadding=false
        maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER
        isFocusable=true;isFocusableInTouchMode=true;isSelected=selected;minHeight=ui.dp(29)
        val horizontal=when(role) { Role.TEXT,Role.TAB->4;Role.FILTER->9;else->12 }
        setPadding(ui.dp(horizontal),ui.dp(if(role==Role.TAB) 8 else 6),ui.dp(horizontal),ui.dp(if(role==Role.TAB) 10 else 6))
        fun paint() {
            val flat=role==Role.TEXT || role==Role.TAB
            val primary=role==Role.PRIMARY || role==Role.CAPSULE && isSelected
            val color=when { primary->TvStyle.accent;role==Role.FILTER && isSelected->0xFF50322D.toInt();flat || role==Role.FILTER->Color.TRANSPARENT;role==Role.SEARCH->0xFF191A1E.toInt();else->TvStyle.surface }
            setTextColor(when { primary->TvStyle.background;role==Role.FILTER && isSelected->0xFFFFB4A7.toInt();role==Role.TAB && isSelected->TvStyle.text;role==Role.TAB || role==Role.SEARCH->TvStyle.muted;else->TvStyle.text })
            val radius=if(role==Role.CAPSULE || role==Role.SEARCH) 100 else 6
            val base=TvStyle.shape(context,color,if(hasFocus()) TvStyle.text else if(role==Role.SEARCH) TvStyle.outline else Color.TRANSPARENT,radius,if(hasFocus()) 2f else .5f)
            background=if(role==Role.TAB && isSelected) LayerDrawable(arrayOf(base,TvStyle.shape(context,TvStyle.accent,radius=1))).apply {
                setLayerGravity(1,Gravity.BOTTOM);setLayerSize(1,-1,ui.dp(2));setLayerInset(1,ui.dp(4),0,ui.dp(4),0)
            } else base
        }
        if(role==Role.SEARCH) {
            setCompoundDrawables(TvGlyph(TvGlyph.Kind.SEARCH,TvStyle.muted).apply { setBounds(0,0,ui.dp(15),ui.dp(15)) },null,null,null)
            compoundDrawablePadding=ui.dp(7);gravity=Gravity.CENTER_VERTICAL
        }
        paint();setOnFocusChangeListener { _,_->paint() };setOnClickListener { focusSelection?.confirm() ?: action() }
    }
}

/** A quiet status line with a separate, bounded operation when the user can act. */
internal class BrowseStatus(context: Context,action: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    private val label=ui.text("",11f,TvStyle.muted).apply { maxLines=2 }
    private val button=BrowseButton(context,"继续加载",BrowseButton.Role.TEXT,action=action)
    val actionView: View get()=button
    init { gravity=Gravity.CENTER_VERTICAL;minimumHeight=ui.dp(33);setPadding(0,ui.dp(4),0,0);addView(label,LayoutParams(0,-2,1f));addView(button) }
    fun show(message: String,operation: String?=null) {
        label.text=message;button.text=operation;button.visibility=if(operation==null) GONE else VISIBLE
    }
}
