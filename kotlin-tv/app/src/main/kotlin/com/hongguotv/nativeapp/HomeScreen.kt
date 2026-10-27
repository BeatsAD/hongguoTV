// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** Bounded, seven-column shelves. The full category remains a recycled vertical grid. */
class HomeScreen(context: Context,private val top: View,private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,private val open: (Series,Boolean)->Unit,private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit,private val hero: CinemaHero?=null,private val filters: View?=null): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val status: String="",val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: List<PosterCardView>,val entries: List<Entry>,val all: View,val indicator: TextView,val previous: View,val next: View)
    private val ui=BrowseUi(context)
    private val scroll=TvScrollView(context)
    private val contents=ui.column()
    private val rows=mutableListOf<Row>()
    private var shelves=emptyList<Shelf>()
    private var lastFocus=""
    private var more: (() -> Unit)?=null
    var nearEnd: (String)->Unit={}
    val refresh=BrowseButton(context,"刷新首页",BrowseButton.Role.TEXT) { more?.invoke() }
    init { orientation=VERTICAL;clipChildren=false;scroll.addView(contents);addView(scroll,LayoutParams(-1,0,1f)) }
    fun capturePosition(): Position {
        val key=findFocus()?.tag?.toString() ?: lastFocus
        val r=rows.indexOfFirst { it.cards.any { card -> card.tag==key } }.coerceAtLeast(0)
        val row=rows.getOrNull(r)
        return Position(key,row?.key,row?.cards?.indexOfFirst { it.tag==key }?.coerceAtLeast(0) ?: 0,r,scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }
    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: Position?=null) {
        this.shelves=shelves;this.more=more
        contents.removeAllViews();rows.clear()
        shelves.filter { it.entries.isNotEmpty() || it.more!=null }.forEach { shelf ->
            val block=ui.column(); val heading=ui.row()
            heading.addView(ui.text(shelf.title,16f).apply { typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) },LayoutParams(0,-2,1f))
            val horizontal=HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false;clipToPadding=false }
            val line=ui.row().apply { gravity=android.view.Gravity.TOP };horizontal.addView(line)
            val cards=shelf.entries.map { entry -> PosterCardView(context).apply {
                tag="${shelf.key}:${entry.series.id}";bind(entry.series,entry.label,entry.progress?.div(100f))
                setOnClickListener { remember(tag.toString());open(entry.series,entry.resume) }
                setOnLongClickListener { actions(entry.series,this);true }
            } }
            val indicator=ui.text("",10f,TvStyle.muted)
            fun page(direction: Int) {
                val count=TvStyle.homeColumns(context)
                val current=cards.indexOfFirst { it.hasFocus() }.takeIf { it>=0 } ?: (horizontal.scrollX/(cards.firstOrNull()?.width?.plus(ui.dp(12)) ?: 1).coerceAtLeast(1))
                cards.getOrNull((current+direction*count).coerceIn(0,(cards.size-1).coerceAtLeast(0)))?.requestFocus()
            }
            val previous=BrowseButton(context,"‹",BrowseButton.Role.TEXT) { page(-1) }.apply { contentDescription="上一屏${shelf.title}" }
            val next=BrowseButton(context,"›",BrowseButton.Role.TEXT) { page(1) }.apply { contentDescription="下一屏${shelf.title}" }
            heading.addView(indicator);heading.addView(previous);heading.addView(next)
            val all=BrowseButton(context,if(shelf.key=="resume") "全部记录 ›" else "全部${shelf.title} ›",BrowseButton.Role.TEXT) { shelf.more?.invoke() }.apply { tag="${shelf.key}:all";textSize=12f }
            heading.addView(all,LayoutParams(-2,-2).apply { leftMargin=ui.dp(16) })
            block.addView(heading,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(6) })
            if(shelf.status.isNotBlank()) block.addView(ui.text(shelf.status,11f,TvStyle.muted))
            block.addView(horizontal)
            cards.forEachIndexed { index,card -> line.addView(card,LayoutParams(ui.dp(85),-2).apply { rightMargin=if(index==cards.lastIndex) 0 else ui.dp(12) }) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(15) });rows+=Row(shelf.key,block,horizontal,cards,shelf.entries,all,indicator,previous,next)
        }
        contents.addView(refresh)
        rows.forEachIndexed { i,row ->
            row.all.nextFocusLeftId=top.id;row.all.nextFocusDownId=row.cards.firstOrNull()?.id ?: refresh.id
            row.cards.forEachIndexed { j,card ->
                card.setOnFocusChangeListener { _,focused ->
                    card.getChildAt(0).foreground=TvStyle.card(context,focused)
                    if(focused) { updateTools(row,j);lastFocus=card.tag.toString();remember(lastFocus);selected(row.entries[j].series,row.entries[j].label);loadNearby(i,j);if(j>=row.cards.size-3) nearEnd(row.key) }
                }
                card.setOnKeyListener { _,code,event ->
                    if(event.action!=KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val target=when(code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> row.cards.getOrNull(j-1) ?: top
                        KeyEvent.KEYCODE_DPAD_RIGHT -> row.cards.getOrNull(j+1) ?: card
                        KeyEvent.KEYCODE_DPAD_UP -> if(i==0) row.all else rows[i-1].cards.getOrNull(j.coerceAtMost(rows[i-1].cards.lastIndex)) ?: rows[i-1].all
                        KeyEvent.KEYCODE_DPAD_DOWN -> rows.getOrNull(i+1)?.let { it.cards.getOrNull(j.coerceAtMost(it.cards.lastIndex)) ?: it.all } ?: refresh
                        KeyEvent.KEYCODE_MENU -> { actions(row.entries[j].series,card);return@setOnKeyListener true }
                        else -> return@setOnKeyListener false
                    };target.requestFocus();true
                }
            }
        }
        refresh.nextFocusLeftId=top.id;refresh.nextFocusUpId=rows.lastOrNull()?.cards?.firstOrNull()?.id ?: top.id
        post {
            fit()
            val saved=position
            if(saved!=null) restorePosition(saved,requestFocus)
            else if(requestFocus) target(focus)?.requestFocus()
            loadNearby(0,0)
        }
    }
    private fun target(key: String)=rows.flatMap { it.cards+it.all }.firstOrNull { it.tag==key } ?: rows.firstOrNull()?.cards?.firstOrNull() ?: refresh
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus()) { val saved=capturePosition();render(next,saved.focus,requestFocus,more,saved) }
    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        rows.forEach { it.horizontal.scrollTo(position.horizontal[it.key] ?: 0,0) };scroll.scrollTo(0,position.vertical)
        if(requestFocus) target(position.focus)?.requestFocus()
    }
    private fun fit() {
        if(width<=0) return
        val count=TvStyle.homeColumns(context);val w=(width-ui.dp(12)*(count-1))/count
        rows.forEach { row -> row.cards.forEach { it.layoutParams=it.layoutParams.apply { width=w } };updateTools(row,row.cards.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)) }
    }
    private fun updateTools(row: Row,index: Int) {
        val count=TvStyle.homeColumns(context)
        val page=index/count+1;val pages=((row.cards.size+count-1)/count).coerceAtLeast(1)
        row.indicator.text="$page / $pages";row.indicator.contentDescription="已加载内容，第${page}屏，共${pages}屏"
        listOf(row.previous to (page>1),row.next to (page<pages)).forEach { (view,enabled) -> view.isEnabled=enabled;view.isFocusable=enabled;view.alpha=if(enabled) 1f else .3f }
    }
    private fun loadNearby(row: Int,column: Int) {
        for(i in row..minOf(row+1,rows.lastIndex)) { val current=rows[i];val start=if(i==row) (column-1).coerceAtLeast(0) else 0
            current.cards.drop(start).take(TvStyle.homeColumns(context)+2).forEach { card -> val index=current.cards.indexOf(card);loadCover(card.image,current.entries[index].series.cover) }
        }
    }
    fun reloadArtwork() { val p=capturePosition();loadNearby(p.row,p.column) }
    fun refreshSelection() {}
    fun updateEntry(shelfKey: String,entry: Entry): Boolean { val next=shelves.map { if(it.key==shelfKey) it.copy(entries=it.entries.map { old -> if(old.series.id==entry.series.id) entry else old }) else it };updateShelves(next);return true }
    fun firstId()=rows.firstOrNull()?.cards?.firstOrNull()?.id ?: refresh.id
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw) post { fit() } }
}
