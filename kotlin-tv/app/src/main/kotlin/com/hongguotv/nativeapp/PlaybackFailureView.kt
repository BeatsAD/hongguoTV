// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Transparent, centered failure state; the action row never wraps or scrolls horizontally. */
internal class PlaybackFailureView(context: Context,private val retry: ()->Unit,private val lower: ()->Unit,
    private val back: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    private val status=ui.text("",14f,TvStyle.muted).apply { gravity=Gravity.CENTER;maxLines=3 }
    private val actions=ui.row().apply { gravity=Gravity.CENTER }
    private var mode: Pair<Boolean,Boolean>?=null
    init {
        orientation=VERTICAL;gravity=Gravity.CENTER
        setPadding(ui.dp(24),ui.dp(80),ui.dp(24),ui.dp(80));clipChildren=false
        addView(ui.text("播放失败",24f).apply { gravity=Gravity.CENTER },LayoutParams(-1,-2))
        addView(status,LayoutParams(-1,-2).apply { topMargin=ui.dp(10) })
        addView(actions,LayoutParams(-2,-2).apply { topMargin=ui.dp(BrowseLayout.FAILURE_CONTENT_GAP) })
    }
    fun show(message: String,automatic: Boolean,canLower: Boolean) {
        if(status.text.toString()!=message) status.text=message
        val next=automatic to canLower
        if(mode==next) return
        val focused=if(mode?.first==automatic) actions.focusedChild?.tag else null
        mode=next;actions.removeAllViews()
        fun action(label: String,key: String,click: ()->Unit) {
            val button=BrowseButton(context,label,action=click).apply { tag=key }
            actions.addView(button,LayoutParams(-2,-2).apply {
                if(actions.childCount>0) leftMargin=ui.dp(BrowseLayout.FAILURE_ACTION_GAP)
            })
        }
        if(!automatic) {
            action("立即重试","retry",retry)
            if(canLower) action("尝试720P","lower",lower)
        }
        action("返回选集","back",back)
        for(i in 0 until actions.childCount) {
            val button=actions.getChildAt(i)
            button.nextFocusLeftId=actions.getChildAt((i-1).coerceAtLeast(0)).id
            button.nextFocusRightId=actions.getChildAt((i+1).coerceAtMost(actions.childCount-1)).id
            button.nextFocusUpId=button.id;button.nextFocusDownId=button.id
        }
        (focused?.let { actions.findViewWithTag<View>(it) } ?: actions.getChildAt(0)).requestFocus()
    }
}
