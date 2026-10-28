// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Remote-first roles share states and geometry; episodes never borrow the primary action fill. */
open class BrowseButton(context: Context,value: String,private val role: Role=Role.ACTION,selected: Boolean=false,private val action: ()->Unit): TextView(context) {
    private var leading: TvGlyph?=null
    private var trailing: TvGlyph?=null
    fun leadingIcon(kind: TvGlyph.Kind,sizeDp: Int=20): BrowseButton { leading=glyph(kind,sizeDp);updateIcons();return this }
    fun trailingIcon(kind: TvGlyph.Kind,sizeDp: Int=16): BrowseButton { trailing=glyph(kind,sizeDp);updateIcons();return this }
    private fun glyph(kind: TvGlyph.Kind,sizeDp: Int)=TvGlyph(kind,currentTextColor).apply { val size=BrowseUi(context).dp(sizeDp);setBounds(0,0,size,size) }
    private fun updateIcons() { leading?.color(currentTextColor);trailing?.color(currentTextColor);setCompoundDrawables(leading,null,trailing,null);compoundDrawablePadding=BrowseUi(context).dp(if(text.isEmpty()) 0 else 8) }
    internal var focusSelection: FocusSelection?=null
        private set
    fun autoSelect(delay: Long=0): BrowseButton { focusSelection=FocusSelection(this,delay,action);return this }
    enum class Role { TEXT, ACTION, PRIMARY, TAB, FILTER, CAPSULE, SEARCH, EPISODE }
    init {
        val ui=BrowseUi(context);val choice=role in listOf(Role.TEXT,Role.TAB,Role.FILTER,Role.CAPSULE,Role.SEARCH)
        id=View.generateViewId();text=value;textSize=if(choice) 14f else 16f;includeFontPadding=false
        typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        maxLines=1;gravity=Gravity.CENTER;isFocusable=true;isFocusableInTouchMode=true;isSelected=selected
        minHeight=ui.dp(if(choice) 40 else 44)
        minWidth=ui.dp(when(role) { Role.PRIMARY->148;Role.ACTION->104;Role.EPISODE->56;Role.TEXT->80;else->72 })
        val horizontal=when(role) { Role.PRIMARY->20;Role.EPISODE->0;else->12 }
        setPadding(ui.dp(horizontal),ui.dp(6),ui.dp(horizontal),ui.dp(6))
        TvStyle.bind(this,primary=role==Role.PRIMARY,episode=role==Role.EPISODE,quiet=choice)
        if(role==Role.SEARCH) { leadingIcon(TvGlyph.Kind.SEARCH);gravity=Gravity.CENTER_VERTICAL }
        setOnClickListener { if(isEnabled) focusSelection?.confirm() ?: action() }
    }
}

internal class BrowseStatus(context: Context,action: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    private val label=ui.text("",14f,TvStyle.muted).apply { maxLines=3 }
    private val button=BrowseButton(context,"继续加载",BrowseButton.Role.TEXT,action=action)
    private val diagnostics=BrowseButton(context,"诊断详情",BrowseButton.Role.TEXT) {
        DiagnosticDialog.create(context).also { dialog ->
            dialog.setOnDismissListener { button.requestFocus() }
            dialog.show();TvDialogStyle.apply(dialog)
        }
    }
    val actionView: View get()=button
    init { gravity=Gravity.CENTER_VERTICAL;clipChildren=false;setPadding(ui.dp(6),ui.dp(6),ui.dp(6),ui.dp(6));addView(label,LayoutParams(0,-2,1f));addView(diagnostics);addView(button) }
    fun show(message: String,operation: String?=null) {
        label.text=message;button.text=operation;button.visibility=if(operation==null) GONE else VISIBLE
        diagnostics.visibility=if(operation=="重试") VISIBLE else GONE
        visibility=if(message.isBlank() && operation==null) GONE else VISIBLE
    }
}
