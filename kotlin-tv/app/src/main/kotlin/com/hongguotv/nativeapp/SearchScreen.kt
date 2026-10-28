// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.*

/** QR form is the sole text input. Results never replace the page-owned QR session. */
class SearchScreen(context: Context,private val state: SearchUiState,sidebar: View,load: (ImageView,String)->Unit,open: (Series)->Unit,
    actions: (Series,View)->Unit,private val scope: (ContentType?)->Unit,private val query: (String)->Unit,history: List<RecentSearch>,private val clear: ()->Unit,reset: ()->Unit,refresh: ()->Unit,append: ()->Unit,private val restoreFocus: Boolean=true): LinearLayout(context) {
    private val ui=BrowseUi(context)
    private val qr=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(Color.WHITE);setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4)) }
    private val connection=ui.text("正在生成二维码…",14f,TvStyle.muted).apply { gravity=Gravity.CENTER;maxLines=2 }
    private val remaining=ui.text("",12f,TvStyle.muted).apply { gravity=Gravity.CENTER }
    private val resultTitle=ui.text("最近搜索",18f).apply { maxLines=2 }
    private var hasHistory=false
    private val waiting=ui.column()
    private val waitingFrame=FrameLayout(context)
    private val waitingScroll=TvScrollView(context).apply {
        addView(waiting);isFocusable=true;isFocusableInTouchMode=true;contentDescription="搜索历史与输入说明"
        setOnFocusChangeListener { _,focused -> waitingFrame.foreground=TvStyle.shape(context,Color.TRANSPARENT,if(focused) TvStyle.text else Color.TRANSPARENT,6) }
    }
    private val scopeButton=BrowseButton(context,"",action={ showScopes() }).apply { tag="search:scope" }
    private val clearResults=BrowseButton(context,"清除结果",BrowseButton.Role.TEXT) { reset();scopeButton.requestFocus() }
    private val qrScroll=TvScrollView(context)
    private val refreshControl=BrowseButton(context,"刷新二维码") {
        refresh();scopeButton.requestFocus();qrScroll.post { qrScroll.scrollTo(0,0) }
    }
    val grid=PosterGridView(context,TvStyle.searchColumns(context),sidebar,load,open,actions,{ if(state.error.isBlank()) append() })
    private val status=BrowseStatus(context) { append() }
    init {
        orientation=VERTICAL
        val heading=ui.heading("找剧");heading.addView(clearResults,LayoutParams(-2,-2).apply { rightMargin=ui.dp(8) });heading.addView(scopeButton);addView(heading,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(12) });renderScopes()
        val body=ui.row().apply { gravity=Gravity.TOP }
        val compact=BrowseLayout.compact(TvStyle.windowDp(context))
        val left=ui.column().apply {
            gravity=Gravity.CENTER_HORIZONTAL;setPadding(ui.dp(if(compact) 10 else 15),ui.dp(14),ui.dp(if(compact) 10 else 15),ui.dp(12))
            background=TvStyle.shape(context,TvStyle.subtle,TvStyle.divider,8,.5f)
        }
        left.addView(ui.text("手机扫码输入",16f).apply { gravity=Gravity.CENTER })
        left.addView(connection,LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
        val qrSize=if(compact) 132 else 164
        left.addView(qr,LayoutParams(ui.dp(qrSize),ui.dp(qrSize)).apply { topMargin=ui.dp(12);bottomMargin=ui.dp(12) })
        listOf("①  手机扫码打开输入页","②  输入剧名，发送到电视","③  用遥控器选择剧集").forEach { step ->
            left.addView(ui.text(step,12f,TvStyle.muted),LayoutParams(-1,-2).apply { bottomMargin=ui.dp(7) })
        }
        left.addView(ui.text("手机和电视需连接同一局域网",12f,TvStyle.muted).apply { gravity=Gravity.CENTER },LayoutParams(-1,-2).apply { topMargin=ui.dp(4) })
        left.addView(remaining,LayoutParams(-1,-2).apply { topMargin=ui.dp(5) })
        left.addView(refreshControl,LayoutParams(-2,-2).apply { topMargin=ui.dp(9) })
        // On compact displays the refresh action starts below the QR viewport. Explicit links
        // let the remote reach it and let ScrollView reveal it, rather than skipping the panel.
        scopeButton.nextFocusLeftId=refreshControl.id;waitingScroll.nextFocusLeftId=refreshControl.id
        refreshControl.nextFocusLeftId=sidebar.id;refreshControl.nextFocusRightId=scopeButton.id
        qrScroll.addView(left)
        body.addView(qrScroll,LayoutParams(ui.dp(BrowseLayout.qrPanel(TvStyle.windowDp(context)).toInt()),-1))
        val right=ui.column();right.addView(resultTitle,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(10) })
        waitingFrame.addView(waitingScroll,FrameLayout.LayoutParams(-1,-1))
        right.addView(waitingFrame,LayoutParams(-1,0,1f));right.addView(grid,LayoutParams(-1,0,1f));right.addView(status,LayoutParams(-1,-2))
        body.addView(right,LayoutParams(0,-1,1f).apply { leftMargin=ui.dp(if(compact) 14 else 20) })
        addView(body,LayoutParams(-1,0,1f));grid.bottomTarget=status.actionView;showHistory(history);update(true)
    }
    private fun showScopes() {
        val options=listOf(null,ContentType.SHORT,ContentType.COMIC,ContentType.AI)
        val labels=options.map { it?.label ?: "全部剧集" }
        val dialog=android.app.AlertDialog.Builder(context).setTitle("搜索范围").setSingleChoiceItems(labels.toTypedArray(),options.indexOf(state.scope)) { picker,index -> scope(options[index]);picker.dismiss() }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { scopeButton.requestFocus() };dialog.show();TvDialogStyle.apply(dialog)
        dialog.listView.setSelection(options.indexOf(state.scope));dialog.listView.requestFocus()
    }

    fun renderScopes() { scopeButton.text="范围：${state.scope?.label ?: "全部剧集"}";scopeButton.trailingIcon(TvGlyph.Kind.DOWN);grid.topTarget=scopeButton }
    fun showHistory(history: List<RecentSearch>) {
        waiting.removeAllViews()
        hasHistory=history.isNotEmpty()
        if(hasHistory) {
            val flow=BrowseFlow(context)
            history.take(8).forEach { item -> flow.addView(BrowseButton(context,item.query) { query(item.query) }.apply { maxWidth=ui.dp(160) }) }
            waiting.addView(flow,LayoutParams(-1,-2))
            waiting.addView(BrowseButton(context,"清空搜索历史",BrowseButton.Role.TEXT) { clear();waitingScroll.requestFocus() }.apply { textSize=14f },LayoutParams(-2,-2).apply { topMargin=ui.dp(7) })
            waiting.addView(View(context).apply { setBackgroundColor(TvStyle.outline) },LayoutParams(-1,ui.dp(1)).apply { topMargin=ui.dp(20);bottomMargin=ui.dp(20) })
        }
        waiting.addView(ui.text("发送剧名后，结果会显示在这里。\n可继续发送新剧名，无需重新扫码。",13f,TvStyle.muted).apply { setLineSpacing(ui.dp(4).toFloat(),1f) })
        updateResultTitle()
    }
    fun session(url: String?) {
        if(url==null) { qr.setImageDrawable(null);remaining.text="";return }
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,400,400)
        qr.setImageBitmap(Bitmap.createBitmap(IntArray(160000) { i -> if(bits[i%400,i/400]) Color.BLACK else Color.WHITE },400,400,Bitmap.Config.ARGB_8888))
        (qr.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap=false
        connection.text="已就绪 · 等待手机连接";qr.contentDescription="手机输入二维码"
    }
    fun connection(value: String) { connection.text=value }
    fun countdown(seconds: Long) { remaining.text="有效期 %02d:%02d".format(seconds/60,seconds%60) }
    private fun updateResultTitle() { resultTitle.text=if(state.query.isNotEmpty()) "“${state.query}”" else if(hasHistory) "最近搜索" else "等待手机输入" }
    fun update(initial: Boolean=false) {
        val searched=state.query.isNotEmpty()
        clearResults.visibility=if(searched) VISIBLE else GONE
        waitingFrame.visibility=if(searched) GONE else VISIBLE;grid.visibility=if(searched) VISIBLE else GONE
        updateResultTitle()
        grid.submit(state.items.map { PosterGridView.Item(it) },if(initial) state.anchor.copy(focus=state.anchor.focus && restoreFocus) else null)
        val message=when { state.busy->"正在搜索…";state.scope==ContentType.AI->"AI剧搜索暂不可用";state.error.isNotBlank()->state.error;!searched->"";state.items.isEmpty()->"没有匹配的剧集";state.more.values.any { it }->"已加载${state.items.size}部";else->"已显示全部结果" }
        val note=if(searched && state.scope==null && state.error.isBlank() && !state.busy) "$message · 仅含真人剧、漫剧" else message
        status.show(note,when { state.busy || !searched || state.scope==ContentType.AI->null;state.error.isNotBlank()->"重试";state.more.values.any { it }->"继续加载";else->null })
    }
    fun capture() { state.anchor=grid.capture() }
}
