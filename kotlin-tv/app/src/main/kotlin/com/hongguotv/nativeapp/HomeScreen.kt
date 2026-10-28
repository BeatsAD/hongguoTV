// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import com.hongguotv.core.Series

/** Bounded, seven-column shelves. The full category remains a recycled vertical grid. */
class HomeScreen(context: Context,private val top: View,private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,private val open: (Series,Boolean)->Unit,private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit,private val hero: CinemaHero?=null,private val filters: View?=null): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val status: String="",val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: List<PosterCardView>,val entries: List<Entry>,val all: View,val indicator: TextView,val previous: View,val next: View,val status: TextView)
    private val ui=BrowseUi(context)
    private val scroll=TvScrollView(context)
    private val contents=ui.column()
    private val rows=mutableListOf<Row>()
    private var shelves=emptyList<Shelf>()
    private var lastFocus=""
    private val requestedArtwork=mutableSetOf<ImageView>()
    private var artworkPosted=false
    private var revision=0L
    private var positionRevision=0L
    private var restoringFocus=false
    private val layoutListener=ViewTreeObserver.OnGlobalLayoutListener { scheduleArtwork() }
    internal var header: HomeHeader?=null
    var nearEnd: (String)->Unit={}
    init {
        orientation=VERTICAL;clipChildren=false;scroll.addView(contents);addView(scroll,LayoutParams(-1,0,1f))
        scroll.setOnScrollChangeListener { _,_,_,_,_->scheduleArtwork() }
        contents.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->scheduleArtwork() }
    }
    fun capturePosition(): Position {
        val key=header?.findFocus()?.tag?.toString() ?: findFocus()?.tag?.toString() ?: lastFocus
        val r=rows.indexOfFirst { it.key==key.substringBefore(':') }.coerceAtLeast(0)
        val row=rows.getOrNull(r)
        return Position(key,row?.key,row?.cards?.indexOfFirst { it.tag==key }?.coerceAtLeast(0) ?: 0,r,scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }
    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,position: Position?=null) {
        this.shelves=shelves
        val ticket=++revision
        val anchorTicket=positionRevision
        contents.removeAllViews();rows.clear();requestedArtwork.clear()
        shelves.filter { it.entries.isNotEmpty() || it.more!=null }.forEach { shelf ->
            val block=ui.column(); val heading=ui.row()
            heading.addView(ui.text(shelf.title,16f).apply { typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) },LayoutParams(0,-2,1f))
            val horizontal=HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled=false;clipToPadding=false
                setOnScrollChangeListener { _,_,_,_,_->scheduleArtwork() }
            }
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
            val previous=BrowseButton(context,"‹",BrowseButton.Role.TEXT) { page(-1) }.apply { tag="${shelf.key}:previous";contentDescription="上一屏${shelf.title}" }
            val next=BrowseButton(context,"›",BrowseButton.Role.TEXT) { page(1) }.apply { tag="${shelf.key}:next";contentDescription="下一屏${shelf.title}" }
            heading.addView(indicator);heading.addView(previous);heading.addView(next)
            val all=BrowseButton(context,if(shelf.key=="resume") "全部记录 ›" else "全部${shelf.title} ›",BrowseButton.Role.TEXT) { shelf.more?.invoke() }.apply { tag="${shelf.key}:all";textSize=12f }
            heading.addView(all,LayoutParams(-2,-2).apply { leftMargin=ui.dp(16) })
            block.addView(heading,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(6) })
            val status=ui.text(shelf.status,11f,TvStyle.muted).apply { visibility=if(shelf.status.isBlank()) GONE else VISIBLE }
            block.addView(status)
            block.addView(horizontal)
            cards.forEachIndexed { index,card -> line.addView(card,LayoutParams(ui.dp(85),-2).apply { rightMargin=if(index==cards.lastIndex) 0 else ui.dp(12) }) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(15) });rows+=Row(shelf.key,block,horizontal,cards,shelf.entries,all,indicator,previous,next,status)
        }
        rows.forEachIndexed { i,row ->
            row.all.nextFocusLeftId=top.id;row.all.nextFocusDownId=row.cards.firstOrNull()?.id ?: row.all.id
            listOf(row.previous,row.next,row.all).forEach { it.nextFocusUpId=header?.refresh?.id ?: top.id }
            row.cards.forEachIndexed { j,card ->
                card.setOnFocusChangeListener { _,focused ->
                    card.getChildAt(0).foreground=TvStyle.card(context,focused)
                    if(focused) { updateTools(row,j);lastFocus=card.tag.toString();remember(lastFocus);selected(row.entries[j].series,row.entries[j].label);loadCover(card.image,row.entries[j].series.cover);requestedArtwork+=card.image;scheduleArtwork();if(j>=row.cards.size-3 && !restoringFocus) nearEnd(row.key) }
                }
                card.setOnKeyListener { _,code,event ->
                    if(event.action!=KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val target=when(code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> row.cards.getOrNull(j-1) ?: top
                        KeyEvent.KEYCODE_DPAD_RIGHT -> row.cards.getOrNull(j+1) ?: card
                        KeyEvent.KEYCODE_DPAD_UP -> if(i==0) row.all else rows[i-1].cards.getOrNull(j.coerceAtMost(rows[i-1].cards.lastIndex)) ?: rows[i-1].all
                        KeyEvent.KEYCODE_DPAD_DOWN -> rows.getOrNull(i+1)?.let { it.cards.getOrNull(j.coerceAtMost(it.cards.lastIndex)) ?: it.all } ?: card
                        KeyEvent.KEYCODE_MENU -> { actions(row.entries[j].series,card);return@setOnKeyListener true }
                        else -> return@setOnKeyListener false
                    };target.requestFocus();true
                }
            }
        }
        // Restore focus synchronously so a queued render cannot later steal header/navigation focus.
        if(requestFocus) restoreFocus(target(focus,position))
        post {
            if(ticket!=revision) return@post
            fit()
            post restore@{
                if(ticket!=revision) return@restore
                if(anchorTicket==positionRevision && position!=null && (!requestFocus || findFocus()?.tag?.toString()==focus)) restorePosition(position,false)
                scheduleArtwork()
            }
        }
    }
    private fun target(key: String,position: Position?=null): View? =
        header?.findViewWithTag<View>(key) ?: rows.flatMap { it.cards+listOf(it.all,it.previous,it.next) }.firstOrNull { it.tag==key && it.isFocusable }
        ?: rows.firstOrNull { it.key==position?.shelf }?.let { it.cards.getOrNull((position?.column ?: 0).coerceAtMost(it.cards.lastIndex)) ?: it.all }
        ?: rows.firstOrNull()?.cards?.firstOrNull() ?: header?.refresh
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus()) {
        if(shelves.map { Triple(it.key,it.title,it.entries) }==next.map { Triple(it.key,it.title,it.entries) }) {
            shelves=next
            rows.forEach { row ->
                val status=next.firstOrNull { it.key==row.key }?.status.orEmpty()
                row.status.text=status;row.status.visibility=if(status.isBlank()) GONE else VISIBLE
            }
            scheduleArtwork();return
        }
        val saved=capturePosition();render(next,saved.focus,requestFocus,saved)
    }
    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        positionRevision++
        rows.forEach { it.horizontal.scrollTo(position.horizontal[it.key] ?: 0,0) };scroll.scrollTo(0,position.vertical)
        if(requestFocus) restoreFocus(target(position.focus,position))
        scheduleArtwork()
    }
    private fun restoreFocus(view: View?) {
        restoringFocus=true
        try { FocusIntent.during(false) { view?.requestFocus() } } finally { restoringFocus=false }
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
    private fun scheduleArtwork() {
        if(artworkPosted) return
        artworkPosted=true
        postOnAnimation { artworkPosted=false;loadVisibleArtwork() }
    }
    private fun loadVisibleArtwork() {
        if(!isAttachedToWindow) return
        val visible=Rect()
        rows.forEach { row -> row.cards.forEachIndexed { index,card ->
            if(card.image.getGlobalVisibleRect(visible) && requestedArtwork.add(card.image)) loadCover(card.image,row.entries[index].series.cover)
        } }
    }
    fun reloadArtwork() { requestedArtwork.clear();scheduleArtwork() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();viewTreeObserver.addOnGlobalLayoutListener(layoutListener);scheduleArtwork()
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        requestedArtwork.clear()
        super.onDetachedFromWindow()
    }
    fun refreshSelection() {}
    fun updateEntry(shelfKey: String,entry: Entry): Boolean { val next=shelves.map { if(it.key==shelfKey) it.copy(entries=it.entries.map { old -> if(old.series.id==entry.series.id) entry else old }) else it };updateShelves(next);return true }
    fun focusContent() {
        val cards=rows.flatMap { it.cards }
        val visible=Rect()
        (cards.firstOrNull { it.tag==lastFocus } ?: cards.firstOrNull { it.image.getGlobalVisibleRect(visible) }
            ?: cards.firstOrNull() ?: header?.refresh)?.requestFocus()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw) post { fit();scheduleArtwork() } }
}
