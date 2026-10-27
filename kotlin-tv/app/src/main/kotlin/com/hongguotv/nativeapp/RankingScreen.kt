// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import android.widget.*
import com.hongguotv.core.*

class RankingScreen(context: Context,private val state: RankingUiState,sidebar: View,load: (ImageView,String)->Unit,
    open: (RankEntry)->Unit,actions: (Series,View)->Unit,private val change: (RankQuery)->Unit,append: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    private val controls=ui.column()
    val grid=PosterGridView(context,TvStyle.posterColumns(context),sidebar,load,{ card -> state.entries.firstOrNull { it.id==card.id }?.let(open) },{ series,view ->
        val entry=state.entries.firstOrNull { it.id==series.id };if(entry is RankEntry.Drama && entry.series.playable) actions(series,view) else entry?.let(open)
    },{ if(state.error.isBlank()) append() })
    private val status=BrowseStatus(context) { append() }
    init { orientation=VERTICAL;val heading=ui.heading("榜单");heading.addView(BrowseButton(context,"刷新",BrowseButton.Role.TEXT) { change(state.query) });addView(heading,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(6) });addView(controls);addView(grid,LayoutParams(-1,0,1f));addView(status,LayoutParams(-1,-2));grid.bottomTarget=status.actionView;renderControls();update(true) }
    fun renderControls() {
        val focus=controls.findFocus()?.tag;controls.removeAllViews()
        ui.line(controls,state.groups.sortedBy { listOf("all","comic_series_rank","human","ai_playlet","series_album","ranklist_celebrity").indexOf(it.id).let { index -> if(index<0) Int.MAX_VALUE else index } }.map { group -> BrowseButton(context,group.label,BrowseButton.Role.TAB,state.query.top==group.id) {
            change(RankQuery(group.id,group.boards.firstOrNull()?.id.orEmpty()))
        }.apply { tag="rank:${group.id}" } })
        val group=state.groups.firstOrNull { it.id==state.query.top }
        ui.line(controls,group?.boards.orEmpty().filter { it.id.isNotEmpty() }.map { board -> CapsuleButton(context,board.label,state.query.sub==board.id) { change(RankQuery(state.query.top,board.id)) }.apply { tag="board:${board.id}" } })
        val board=group?.boards?.firstOrNull { it.id==state.query.sub }
        val topButton=controls.findViewWithTag<View>("rank:${state.query.top}")
        val subButton=controls.findViewWithTag<View>("board:${state.query.sub}")
        grid.topTarget=subButton ?: topButton
        state.groups.forEach { item -> controls.findViewWithTag<View>("rank:${item.id}")?.apply { nextFocusUpId=id;nextFocusDownId=subButton?.id ?: grid.id } }
        group?.boards.orEmpty().forEach { item -> controls.findViewWithTag<View>("board:${item.id}")?.apply {
            nextFocusUpId=topButton?.id ?: id
            setOnKeyListener { _,key,event -> if(key==android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action==android.view.KeyEvent.ACTION_DOWN) { grid.focusFirst();true } else false }
        } }
        val row=ui.row();row.addView(ui.text(board?.subtitle.orEmpty(),11f,TvStyle.muted),LayoutParams(0,-2,1f))
        if(!board?.panel.isNullOrEmpty()) row.addView(BrowseButton(context,if(state.expanded) "收起分类" else "分类筛选",BrowseButton.Role.FILTER,state.query.panel.isNotEmpty()) { state.expanded=!state.expanded;renderControls();controls.findViewWithTag<View>("panel")?.requestFocus() }.apply { tag="panel" })
        if(row.childCount>1 || board?.subtitle.orEmpty().isNotBlank()) controls.addView(row,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(6) })
        if(state.expanded && board!=null) controls.addView(FilterPanelView(context,board.panel,board.panel.associate { dim -> dim.id to state.query.panel }) { dimension,id ->
            val selected=if(id==null) emptyList() else if(board.globalSingle) listOf(id) else {
                val other=state.query.panel.filter { current -> dimension.options.none { it.id==current } };other+id
            }
            change(state.query.copy(panel=selected))
        },LayoutParams(-1,ui.dp(if(BrowseLayout.compact(TvStyle.windowDp(context))) 90 else 150)))
        if(focus!=null) controls.findViewWithTag<View>(focus)?.requestFocus()
    }
    fun update(initial: Boolean=false) {
        grid.submit(state.entries.map { entry -> when(entry) {
            is RankEntry.Drama -> PosterGridView.Item(entry.series,listOf(entry.rank?.let { "第${it}名" }.orEmpty(),entry.heat,if(!entry.series.playable) "待上线 · 仅资料" else if(entry.album) "系列剧" else "").filter(String::isNotBlank).joinToString(" · "))
            is RankEntry.Actor -> PosterGridView.Item(Series(entry.id,entry.name,entry.avatar,entry.description,playable=false),"演员 · ${entry.heat}")
        } },if(initial) state.anchor else null)
        status.show(when { state.busy->"正在加载榜单…";state.error.isNotEmpty()->state.error;state.entries.isEmpty() && !state.hasMore->"当前榜单暂无条目";else->"已加载${state.entries.size}项"+if(state.hasMore) "" else " · 已到底" },when { state.busy->null;state.error.isNotBlank()->"重试";state.hasMore->"继续加载";else->null })
    }
    fun back(): Boolean { if(!state.expanded) return false;state.expanded=false;renderControls();controls.findViewWithTag<View>("panel")?.requestFocus();return true }
    fun capture() { state.anchor=grid.capture() }
}
