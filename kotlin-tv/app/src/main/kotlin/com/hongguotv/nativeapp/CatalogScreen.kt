// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import android.widget.*
import com.hongguotv.core.*

class CatalogScreen(context: Context,private val state: CatalogUiState,sidebar: View,load: (ImageView,String)->Unit,
    open: (Series)->Unit,actions: (Series,View)->Unit,private val change: (CategoryQuery)->Unit,append: ()->Unit,search: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    private val controls=ui.column()
    val grid=PosterGridView(context,TvStyle.catalogColumns(context),sidebar,load,open,actions,{ if(state.error.isBlank()) append() })
    private val status=BrowseStatus(context) { append() }
    init {
        orientation=VERTICAL;clipChildren=false
        val heading=ui.heading(state.query.type.label);heading.addView(BrowseButton(context,"搜索剧名",BrowseButton.Role.SEARCH,action=search));addView(heading,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(8) })
        addView(controls);addView(grid,LayoutParams(-1,0,1f));addView(status,LayoutParams(-1,-2))
        grid.bottomTarget=status.actionView;renderControls();update(true)
        sidebar.nextFocusRightId=grid.id
    }
    fun renderControls() {
        val oldFocus=controls.findFocus()?.tag
        controls.removeAllViews()
        val theme=state.dimensions.firstOrNull { it.id=="category_dim_theme" }
        val options=mutableListOf<View>()
        if(theme!=null) {
            options+=BrowseButton(context,"全部",BrowseButton.Role.FILTER,state.query.selections[theme.id].isNullOrEmpty()) { change(state.query.choose(theme,null)) }.apply { tag="theme:all" }
            val chosen=state.query.selections[theme.id].orEmpty()
            theme.options.filterIndexed { index,item -> index<6 || item.id in chosen }.forEach { option -> options+=BrowseButton(context,option.label,BrowseButton.Role.FILTER,option.id in chosen) { change(state.query.choose(theme,option.id)) }.apply { tag="theme:${option.id}" } }
        }
        val more=BrowseButton(context,if(state.expanded) "收起 ▴" else "更多 ▾",BrowseButton.Role.FILTER,state.expanded) { state.expanded=!state.expanded;renderControls();controls.findViewWithTag<View>("more")?.requestFocus() }.apply { tag="more" }
        options+=more; options+=BrowseButton(context,"重置",BrowseButton.Role.TEXT) { change(CategoryQuery(state.query.type,mapOf("sort" to listOf("hot_score")))) }.apply { tag="reset" }
        ui.line(controls,options)
        grid.topTarget=more
        options.forEach { view -> view.setOnKeyListener { _,key,event -> if(key==android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action==android.view.KeyEvent.ACTION_DOWN && !state.expanded) { grid.focusFirst();true } else false } }
        val summary=state.dimensions.flatMap { row -> row.options.filter { it.id in state.query.selections[row.id].orEmpty() }.map { it.label } }.joinToString(" · ")
        if(summary.isNotBlank()) controls.addView(ui.text("平台筛选结果 · $summary",11f,TvStyle.muted).apply { maxLines=2;setPadding(0,0,0,ui.dp(6)) })
        if(state.expanded) controls.addView(FilterPanelView(context,state.dimensions,state.query.selections) { row,id -> change(state.query.choose(row,id)) },LayoutParams(-1,ui.dp(if(BrowseLayout.compact(TvStyle.windowDp(context))) 100 else 170)))
        if(oldFocus!=null) controls.findViewWithTag<View>(oldFocus)?.requestFocus()
    }
    fun update(initial: Boolean=false) {
        grid.submit(state.items.map { PosterGridView.Item(it) },if(initial) state.anchor else null)
        status.show(when { state.busy -> "正在加载…";state.error.isNotEmpty()->"已加载${state.items.size}部 · "+state.error;state.items.isEmpty() && !state.hasMore -> "没有符合条件的剧集";else -> "已加载${state.items.size}部"+if(state.hasMore) "" else " · 已到底" },when { state.busy->null;state.error.isNotBlank()->"重试";state.hasMore->"继续加载";else->null })
    }
    fun back(): Boolean { if(!state.expanded) return false;state.expanded=false;renderControls();controls.findViewWithTag<View>("more")?.requestFocus();return true }
    fun capture() { state.anchor=grid.capture() }
}
