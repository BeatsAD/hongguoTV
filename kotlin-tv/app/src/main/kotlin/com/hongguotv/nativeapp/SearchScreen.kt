// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.*

/** QR form is the sole text input. Results never replace the page-owned QR session. */
class SearchScreen(context: Context,private val state: SearchUiState,sidebar: View,load: (ImageView,String)->Unit,open: (Series)->Unit,
    actions: (Series,View)->Unit,private val scope: (ContentType?)->Unit,private val query: (String)->Unit,history: List<RecentSearch>,private val clear: ()->Unit,refresh: ()->Unit,append: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    private val scopeControls=ui.column()
    private val qr=ImageView(context)
    private val connection=ui.text("正在生成二维码…",12f,TvStyle.muted)
    private val remaining=ui.text("",11f,TvStyle.muted)
    private val resultTitle=ui.text("最近搜索",17f)
    private val waiting=ui.column()
    val grid=PosterGridView(context,if(resources.configuration.fontScale>1.2f) 2 else 3,sidebar,load,open,actions,{ if(state.error.isBlank()) append() })
    private val status=CapsuleButton(context,"等待手机输入") { append() }
    init {
        orientation=VERTICAL;addView(ui.title("找剧"))
        addView(scopeControls);renderScopes()
        val body=ui.row().apply { gravity=android.view.Gravity.TOP }
        val left=ui.column().apply { setPadding(ui.dp(8),ui.dp(8),ui.dp(16),0) }
        left.addView(ui.text("手机扫码输入",19f));left.addView(qr,LayoutParams(-1,ui.dp(160)).apply { topMargin=ui.dp(12);bottomMargin=ui.dp(12) })
        left.addView(connection);left.addView(remaining);left.addView(CapsuleButton(context,"刷新二维码",action=refresh),LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
        left.addView(ui.text("手机和电视需连接同一局域网\n遥控器选择范围与搜索结果",11f,TvStyle.muted).apply { setPadding(0,ui.dp(12),0,0) })
        body.addView(left,LayoutParams(ui.dp(200),-1))
        val right=ui.column();right.addView(resultTitle);right.addView(waiting)
        showHistory(history)
        right.addView(grid,LayoutParams(-1,0,1f));right.addView(status,LayoutParams(-1,-2));body.addView(right,LayoutParams(0,-1,1f));addView(body,LayoutParams(-1,0,1f))
        update(true)
    }
    fun renderScopes() {
        val focus=scopeControls.findFocus()?.tag;scopeControls.removeAllViews()
        ui.line(scopeControls,listOf(null,ContentType.SHORT,ContentType.COMIC,ContentType.AI).map { type -> CapsuleButton(context,type?.label ?: "全部剧集",state.scope==type) { scope(type) }.apply { tag="scope:${type?.storedValue ?: "all"}" } })
        grid.topTarget=scopeControls.findViewWithTag<View>("scope:${state.scope?.storedValue ?: "all"}")
        if(focus!=null) scopeControls.findViewWithTag<View>(focus)?.requestFocus()
    }
    fun showHistory(history: List<RecentSearch>) {
        waiting.removeAllViews()
        if(history.isEmpty()) waiting.addView(ui.text("在手机输入中文剧名，发送后结果会出现在这里。",14f,TvStyle.muted))
        else {
            history.take(8).forEach { item -> waiting.addView(CapsuleButton(context,item.query) { query(item.query) },LayoutParams(-1,-2).apply { bottomMargin=ui.dp(4) }) }
            waiting.addView(CapsuleButton(context,"清空搜索历史",action=clear))
        }
    }
    fun session(url: String?) {
        if(url==null) { qr.setImageDrawable(null);remaining.text="";return }
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,400,400)
        qr.setImageBitmap(Bitmap.createBitmap(IntArray(160000) { i -> if(bits[i%400,i/400]) Color.BLACK else Color.WHITE },400,400,Bitmap.Config.ARGB_8888))
        connection.text="手机扫码后用浏览器打开";qr.contentDescription="手机输入二维码"
    }
    fun connection(value: String) { connection.text=value }
    fun countdown(seconds: Long) { remaining.text="有效期 %02d:%02d".format(seconds/60,seconds%60) }
    fun update(initial: Boolean=false) {
        val searched=state.query.isNotEmpty()
        waiting.visibility=if(searched) GONE else VISIBLE;grid.visibility=if(searched) VISIBLE else GONE
        resultTitle.text=if(searched) "“${state.query}” · ${state.scope?.label ?: "综合结果"}" else "最近搜索"
        grid.submit(state.items.map { PosterGridView.Item(it) },if(initial) state.anchor else null)
        status.text=when { state.busy->"正在搜索…";state.scope==ContentType.AI->"AI剧搜索暂不可用";state.error.isNotBlank()->state.error+" · 重试";!searched->"等待手机输入";state.items.isEmpty()->"没有匹配的剧集";state.more.values.any { it }->"继续加载 ↓";else->"已显示全部结果" }
        if(searched && state.scope==null && state.error.isBlank() && !state.busy) status.text="真人剧与漫剧综合结果 · AI搜索暂不可用"+if(state.more.values.any { it }) " · 继续加载" else ""
    }
    fun capture() { state.anchor=grid.capture() }
}
