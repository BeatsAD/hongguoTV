// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout

/** Stable page actions stay outside the shelves, including while a refresh is in flight. */
internal class HomeHeader(context: Context,sidebar: View,refreshHome: ()->Unit,searchHome: ()->Unit,
    enterContent: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    val refresh=BrowseButton(context,"刷新",BrowseButton.Role.TEXT,action=refreshHome).apply {
        tag="home:refresh"
        setCompoundDrawables(context.getDrawable(R.drawable.ic_refresh)?.apply {
            setTint(TvStyle.muted);setBounds(0,0,ui.dp(15),ui.dp(15))
        },null,null,null)
        compoundDrawablePadding=ui.dp(5)
        contentDescription="刷新首页"
    }
    val search=BrowseButton(context,"搜索剧名",BrowseButton.Role.SEARCH,action=searchHome).apply { tag="home:search" }
    private val status=ui.text("",11f,TvStyle.muted).apply {
        maxLines=1;ellipsize=TextUtils.TruncateAt.END
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        minimumHeight=ui.dp(18)
    }
    init {
        orientation=VERTICAL
        val heading=ui.heading("首页",22f)
        heading.addView(refresh,LayoutParams(ui.dp(90),-2).apply { rightMargin=ui.dp(10) })
        heading.addView(search,LayoutParams(ui.dp(if(BrowseLayout.compact(TvStyle.windowDp(context))) 130 else 160),-2))
        addView(heading,LayoutParams(-1,-2));addView(status,LayoutParams(-1,-2))
        listOf(refresh,search).forEach { button ->
            button.setOnKeyListener { _,key,event ->
                if(event.action!=KeyEvent.ACTION_DOWN) false else when(key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { (if(button===search) refresh else sidebar).requestFocus();true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { search.requestFocus();true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { enterContent();true }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
        }
    }
    fun update(busy: Boolean,refreshing: Boolean,message: String) {
        // Keep the focused button enabled; the controller rejects duplicate refreshes.
        refresh.text=if(refreshing) "刷新中" else if(busy) "加载中" else "刷新"
        refresh.contentDescription=if(refreshing) "首页正在刷新" else if(busy) "首页正在加载" else "刷新首页"
        status.text=message.ifBlank { if(busy) "正在加载首页内容" else "" }
    }
}
