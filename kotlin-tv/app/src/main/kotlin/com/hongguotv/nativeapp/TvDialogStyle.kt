// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.TextView

/** Theme all app-owned alert controls, including stock adapter and numeric-keypad buttons. */
object TvDialogStyle {
    fun diagnosticBodyHeight(context: android.content.Context)=(context.resources.displayMetrics.heightPixels*.55f).toInt()
    private fun animation(context: android.content.Context)=if(!TvMotion.enabled()) 0 else if(TvMotion.spatial(context)) R.style.TvDialogMotion else R.style.TvFadeMotion
    fun show(dialog: AlertDialog) {
        dialog.window?.setWindowAnimations(animation(dialog.context))
        dialog.show();apply(dialog)
    }
    fun apply(dialog: AlertDialog) {
        val context=dialog.context;val metrics=context.resources.displayMetrics
        fun dp(n: Int)=(n*metrics.density).toInt()
        val width=minOf(metrics.widthPixels-dp(64),dp(460))
        dialog.window?.apply {
            setWindowAnimations(animation(context))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            setBackgroundDrawableResource(R.drawable.dialog_surface)
            setLayout(width,ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.post { if(decorView.height>metrics.heightPixels*.8f) setLayout(width,(metrics.heightPixels*.8f).toInt()) }
        }
        fun styleChoices(group: ViewGroup) {
            for(index in 0 until group.childCount) {
                val child=group.getChildAt(index)
                if(child is android.widget.CompoundButton) { child.minHeight=dp(52);child.textSize=16f;TvStyle.bind(child) }
                if(child is ViewGroup) styleChoices(child)
            }
        }
        (dialog.window?.decorView as? ViewGroup)?.let(::styleChoices)
        dialog.listView?.apply {
            selector=TvStyle.focused(context,TvStyle.raised,8)
            setPadding(dp(6),dp(6),dp(6),dp(6));clipToPadding=false
            divider=ColorDrawable(TvStyle.divider);dividerHeight=dp(1)
        }
        listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply { textSize=16f;minHeight=dp(44);isAllCaps=false;TvStyle.bind(this) }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize=14f;setTextColor(TvStyle.muted)
            post {
                if(lineCount>6) {
                    var ancestor=parent
                    while(ancestor is android.view.View && ancestor !is android.widget.ScrollView) ancestor=ancestor.parent
                    (ancestor as? android.widget.ScrollView)?.apply {
                        isFocusable=true;isFocusableInTouchMode=true;contentDescription="上下滚动正文";TvStyle.bind(this,quiet=true)
                        nextFocusDownId=dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.id ?: id
                        requestFocus()
                    }
                }
            }
        }
        @Suppress("DiscouragedApi")
        val title=context.resources.getIdentifier("alertTitle","id","android")
        dialog.findViewById<TextView>(title)?.apply { textSize=22f;setTextColor(TvStyle.text) }
    }
}
