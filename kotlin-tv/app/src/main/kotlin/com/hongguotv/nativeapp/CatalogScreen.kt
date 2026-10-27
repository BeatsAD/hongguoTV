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
    private val status=CapsuleButton(context,"加载中") { append() }
    init {
        orientation=VERTICAL;clipChildren=false
        val heading=ui.row();heading.addView(ui.title(state.query.type.label),LayoutParams(0,-2,1f));heading.addView(CapsuleButton(context,"找剧",action=search));addView(heading)
        addView(controls);addView(grid,LayoutParams(-1,0,1f));addView(status,LayoutParams(-1,-2))
        renderControls();update(true)
        sidebar.nextFocusRightId=grid.id
    }
    fun renderControls() {
        val oldFocus=controls.findFocus()?.tag
        controls.removeAllViews()
        val theme=state.dimensions.firstOrNull { it.id=="category_dim_theme" }
        val options=mutableListOf<View>()
        if(theme!=null) {
            options+=CapsuleButton(context,"全部",state.query.selections[theme.id].isNullOrEmpty()) { change(state.query.choose(theme,null)) }.apply { tag="theme:all" }
            val chosen=state.query.selections[theme.id].orEmpty()
            theme.options.filterIndexed { index,item -> index<6 || item.id in chosen }.forEach { option -> options+=CapsuleButton(context,option.label,option.id in chosen) { change(state.query.choose(theme,option.id)) }.apply { tag="theme:${option.id}" } }
        }
        val more=CapsuleButton(context,if(state.expanded) "收起" else "更多",state.expanded) { state.expanded=!state.expanded;renderControls();controls.findViewWithTag<View>("more")?.requestFocus() }.apply { tag="more" }
        options+=more; options+=CapsuleButton(context,"重置") { change(CategoryQuery(state.query.type)) }.apply { tag="reset" }
        ui.line(controls,options)
        grid.topTarget=more
        options.forEach { view -> view.setOnKeyListener { _,key,event -> if(key==android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action==android.view.KeyEvent.ACTION_DOWN && !state.expanded) { grid.focusFirst();true } else false } }
        val summary=state.dimensions.flatMap { row -> row.options.filter { it.id in state.query.selections[row.id].orEmpty() }.map { it.label } }.joinToString(" · ")
        if(summary.isNotBlank()) controls.addView(ui.text("平台筛选结果 · $summary",11f,TvStyle.muted))
        if(state.expanded) controls.addView(FilterPanelView(context,state.dimensions,state.query.selections) { row,id -> change(state.query.choose(row,id)) },LayoutParams(-1,ui.dp(140)))
        if(oldFocus!=null) controls.findViewWithTag<View>(oldFocus)?.requestFocus()
    }
    fun update(initial: Boolean=false) {
        grid.submit(state.items.map { PosterGridView.Item(it) },if(initial) state.anchor else null)
        status.text=when { state.busy -> "正在加载…";state.error.isNotEmpty()->"已加载${state.items.size}部 · "+state.error+" · 确认重试";state.items.isEmpty() && !state.hasMore -> "没有符合条件的剧集";state.hasMore -> "已加载${state.items.size}部 · 继续加载 ↓";else -> "已到底 · ${state.items.size} 部" }
    }
    fun back(): Boolean { if(!state.expanded) return false;state.expanded=false;renderControls();controls.findViewWithTag<View>("more")?.requestFocus();return true }
    fun capture() { state.anchor=grid.capture() }
}
