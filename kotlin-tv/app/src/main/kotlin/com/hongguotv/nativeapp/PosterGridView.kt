// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import com.hongguotv.core.Series

/** Recycled cards with ID-based restoration and explicit remote boundaries. */
class PosterGridView(context: Context,private val columns: Int,private val sidebar: View,private val load: (android.widget.ImageView,String)->Unit,
    private val open: (Series)->Unit,private val actions: (Series,View)->Unit,private val append: ()->Unit): RecyclerView(context) {
    data class Item(val series: Series,val caption: String="",val fraction: Float?=null)
    data class Anchor(val id: String="",val position: Int=0,val offset: Int=0,val focus: Boolean=false)
    var topTarget: View?=null
    var bottomTarget: View?=null
        set(value) {
            field=value
            value?.setOnKeyListener { _,key,event ->
                if(key==KeyEvent.KEYCODE_DPAD_UP && event.action==KeyEvent.ACTION_DOWN && rows.isNotEmpty()) { move(rows.lastIndex);true } else false
            }
        }
    private var rows=emptyList<Item>()
    private var focusId=""
    private var desired: String?=null
    private var pendingDown: Int?=null
    private val manager=GridLayoutManager(context,columns)
    private inner class Holder(val card: PosterCardView): ViewHolder(card)
    private val cards=object: Adapter<Holder>() {
        override fun getItemCount()=rows.size
        override fun onCreateViewHolder(parent: ViewGroup,viewType: Int)=Holder(PosterCardView(context).apply { layoutParams=LayoutParams(-1,-2) })
        override fun onBindViewHolder(holder: Holder,position: Int) {
            val item=rows[position];val card=holder.card
            if(height>0) card.maxPosterHeight=posterLimit(height)
            card.bind(item.series,item.caption,item.fraction);card.tag=item.series.id
            card.setOnClickListener { open(rows[holder.bindingAdapterPosition.takeIf { it in rows.indices } ?: return@setOnClickListener].series) }
            card.setOnLongClickListener { actions(item.series,card);true }
            card.setOnFocusChangeListener { _,focused ->
                // Keep the shared card's ring while adding list-level focus tracking.
                (card.getChildAt(0) as View).foreground=TvStyle.card(context,focused)
                if(focused) { focusId=item.series.id; if(position>=rows.size-columns*2) append() }
            }
            card.setOnKeyListener { _,key,event ->
                if(event.action!=KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val index=holder.bindingAdapterPosition;if(index !in rows.indices) return@setOnKeyListener false
                when(key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if(index%columns==0) sidebar.requestFocus() else move(index-1);true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { move(TvGridFocus.right(index,rows.size,columns));true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { val next=TvGridFocus.down(index,rows.size,columns);if(next!=null) move(next) else if(bottomTarget?.isShown==true && bottomTarget?.isFocusable==true) bottomTarget?.requestFocus() else { pendingDown=index;append() };true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if(index>=columns) { move(index-columns);true } else topTarget?.let { it.requestFocus();true } ?: false }
                    KeyEvent.KEYCODE_MENU -> { actions(item.series,card);true }
                    else -> false
                }
            }
            load(card.image,item.series.cover)
            if(desired==item.series.id) card.post { if(desired==item.series.id && card.isAttachedToWindow) { desired=null;card.requestFocus() } }
        }
    }
    init {
        layoutManager=manager;adapter=cards;itemAnimator=null;setItemViewCacheSize(columns);clipChildren=true;clipToPadding=true
        val gap=(5*resources.displayMetrics.density).toInt()
        setPadding(gap,gap,gap,gap)
        addItemDecoration(object: ItemDecoration() { override fun getItemOffsets(out: Rect,view: View,parent: RecyclerView,state: State) { out.set(gap,gap,gap,gap) } })
        addOnScrollListener(object: OnScrollListener() { override fun onScrolled(view: RecyclerView,dx: Int,dy: Int) { if(dy>0 && manager.findLastVisibleItemPosition()>=rows.size-columns*2) append() } })
    }
    private fun posterLimit(height: Int)=(height-(52*resources.displayMetrics.density*resources.configuration.fontScale).toInt()).coerceAtLeast((80*resources.displayMetrics.density).toInt())
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        for(i in 0 until childCount) (getChildAt(i) as? PosterCardView)?.maxPosterHeight=posterLimit(h)
    }
    private fun move(index: Int) { rows.getOrNull(index)?.let { desired=it.series.id;scrollToPosition(index);post { manager.findViewByPosition(index)?.requestFocus()?.also { desired=null } } } }
    // LayoutManager offsets are relative to the padded start, using decorated card bounds.
    fun capture()=Anchor(focusId,manager.findFirstVisibleItemPosition().coerceAtLeast(0),manager.findViewByPosition(manager.findFirstVisibleItemPosition())?.let { manager.getDecoratedTop(it)-paddingTop } ?: 0,hasFocus())
    fun submit(next: List<Item>,anchor: Anchor?=null) {
        val saved=anchor ?: capture();val oldCount=rows.size
        rows=next
        focusId=saved.id.takeIf { id -> rows.any { it.series.id==id } }.orEmpty()
        val isAppend=oldCount>0 && oldCount<=next.size && (0 until oldCount).all { i -> cardsSnapshot.getOrNull(i)==next[i] }
        if(isAppend) { if(next.size>oldCount) cards.notifyItemRangeInserted(oldCount,next.size-oldCount) } else cards.notifyDataSetChanged()
        cardsSnapshot=next
        if(!isAppend || anchor!=null) manager.scrollToPositionWithOffset(saved.position.coerceAtMost((rows.size-1).coerceAtLeast(0)),saved.offset)
        if(saved.focus && rows.isNotEmpty()) {
            val target=rows.indexOfFirst { it.series.id==saved.id }.takeIf { it>=0 } ?: saved.position.coerceIn(0,rows.lastIndex)
            desired=rows[target].series.id;post {
                manager.findViewByPosition(target)?.requestFocus()
                if(!isAppend || anchor!=null) { stopScroll();manager.scrollToPositionWithOffset(saved.position.coerceAtMost(rows.lastIndex),saved.offset) }
            }
        }
        if(!isAppend) pendingDown=null
        pendingDown?.let { index -> if(next.size>oldCount) { if(hasFocus()) move((index+columns).coerceAtMost(next.lastIndex));pendingDown=null } }
    }
    private var cardsSnapshot=emptyList<Item>()
    fun focusFirst(): Boolean { if(rows.isEmpty()) return false;move(rows.indexOfFirst { it.series.id==focusId }.coerceAtLeast(0));return true }
}
