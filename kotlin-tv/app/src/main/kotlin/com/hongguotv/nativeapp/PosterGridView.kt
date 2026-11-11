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
    internal var compactSearch=false
    var leftTarget: View=sidebar
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
    private var moveRevision=0L
    private fun cancelMove() { moveRevision++;desired=null;stopScroll() }
    private fun focusDesired() {
        if(getTag(R.id.tv_motion_retired)==true) { desired=null;return }
        val id=desired ?: return
        val index=rows.indexOfFirst { it.series.id==id }
        val target=manager.findViewByPosition(index) ?: return
        if(manager.getDecoratedTop(target)>=paddingTop && manager.getDecoratedBottom(target)<=height-paddingBottom) {
            desired=null;target.requestFocus()
        }
    }
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
                if(focused) { focusId=item.series.id; if(position>=rows.size-columns*2) append() }
            }
            card.setOnKeyListener { _,key,event ->
                if(event.action!=KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val index=desired?.let { id -> rows.indexOfFirst { it.series.id==id }.takeIf { it>=0 } } ?: holder.bindingAdapterPosition
                if(index !in rows.indices) return@setOnKeyListener false
                when(key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if(index%columns==0) { cancelMove();leftTarget.requestFocus() } else move(index-1);true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { move(TvGridFocus.right(index,rows.size,columns));true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { val next=TvGridFocus.down(index,rows.size,columns);if(next!=null) move(next) else if(bottomTarget?.isShown==true && bottomTarget?.isFocusable==true) { cancelMove();bottomTarget?.requestFocus() } else { pendingDown=index;append() };true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if(index>=columns) { move(index-columns);true } else topTarget?.let { cancelMove();it.requestFocus();true } ?: false }
                    KeyEvent.KEYCODE_MENU -> { actions(item.series,card);true }
                    else -> false
                }
            }
            load(card.image,item.series.cover)
            if(desired==item.series.id) { val ticket=moveRevision;card.post { if(ticket==moveRevision && card.isAttachedToWindow) focusDesired() } }
        }
    }
    init {
        layoutManager=manager;adapter=cards;itemAnimator=null;setItemViewCacheSize(columns);clipChildren=true;clipToPadding=true
        val gap=(5*resources.displayMetrics.density).toInt()
        setPadding(gap,gap,gap,gap)
        addItemDecoration(object: ItemDecoration() { override fun getItemOffsets(out: Rect,view: View,parent: RecyclerView,state: State) { out.set(gap,gap,gap,gap) } })
        addOnScrollListener(object: OnScrollListener() {
            override fun onScrolled(view: RecyclerView,dx: Int,dy: Int) {
                focusDesired()
                if(dy>0 && manager.findLastVisibleItemPosition()>=rows.size-columns*2) append()
            }
            override fun onScrollStateChanged(view: RecyclerView,state: Int) { if(state==SCROLL_STATE_IDLE) focusDesired() }
        })
    }
    private fun posterLimit(height: Int): Int {
        if(compactSearch) return (BrowseLayout.searchPosterHeight(height/resources.displayMetrics.density,resources.configuration.fontScale)*resources.displayMetrics.density).toInt()
        return (height-(52*resources.displayMetrics.density*resources.configuration.fontScale).toInt()).coerceAtLeast((80*resources.displayMetrics.density).toInt())
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        for(i in 0 until childCount) (getChildAt(i) as? PosterCardView)?.maxPosterHeight=posterLimit(h)
    }
    private fun move(index: Int,smooth: Boolean=true) {
        val item=rows.getOrNull(index) ?: return
        cancelMove();desired=item.series.id
        val target=manager.findViewByPosition(index)
        val top=target?.let { manager.getDecoratedTop(it)-paddingTop } ?: 0
        val bottom=target?.let { manager.getDecoratedBottom(it)-(height-paddingBottom) } ?: 0
        if(target!=null && top>=0 && bottom<=0) { desired=null;target.requestFocus();return }
        if(smooth && TvMotion.enabled()) {
            if(target!=null) smoothScrollBy(0,if(top<0) top else bottom)
            else smoothScrollToPosition(index)
        } else {
            scrollToPosition(index)
            val ticket=moveRevision
            post { if(ticket==moveRevision) manager.findViewByPosition(index)?.let { desired=null;it.requestFocus() } }
        }
    }
    override fun onDetachedFromWindow() { cancelMove();super.onDetachedFromWindow() }
    // LayoutManager offsets are relative to the padded start, using decorated card bounds.
    fun capture()=Anchor(focusId,manager.findFirstVisibleItemPosition().coerceAtLeast(0),manager.findViewByPosition(manager.findFirstVisibleItemPosition())?.let { manager.getDecoratedTop(it)-paddingTop } ?: 0,hasFocus())
    fun submit(next: List<Item>,anchor: Anchor?=null) {
        val saved=anchor ?: capture();val oldCount=rows.size
        rows=next
        focusId=saved.id.takeIf { id -> rows.any { it.series.id==id } }.orEmpty()
        val isAppend=oldCount>0 && oldCount<=next.size && (0 until oldCount).all { i -> cardsSnapshot.getOrNull(i)==next[i] }
        if(!isAppend || anchor!=null) cancelMove()
        if(isAppend) { if(next.size>oldCount) cards.notifyItemRangeInserted(oldCount,next.size-oldCount) } else cards.notifyDataSetChanged()
        cardsSnapshot=next
        if(!isAppend || anchor!=null) manager.scrollToPositionWithOffset(saved.position.coerceAtMost((rows.size-1).coerceAtLeast(0)),saved.offset)
        if(saved.focus && rows.isNotEmpty() && (!isAppend || anchor!=null)) {
            val target=rows.indexOfFirst { it.series.id==saved.id }.takeIf { it>=0 } ?: saved.position.coerceIn(0,rows.lastIndex)
            desired=rows[target].series.id;val ticket=moveRevision;post {
                if(ticket!=moveRevision || !isAttachedToWindow) return@post
                desired=null;FocusIntent.during(false) { manager.findViewByPosition(target)?.requestFocus() }
                if(!isAppend || anchor!=null) { stopScroll();manager.scrollToPositionWithOffset(saved.position.coerceAtMost(rows.lastIndex),saved.offset) }
            }
        }
        if(!isAppend) pendingDown=null
        pendingDown?.let { index -> if(next.size>oldCount) { if(hasFocus()) move((index+columns).coerceAtMost(next.lastIndex));pendingDown=null } }
    }
    private var cardsSnapshot=emptyList<Item>()
    fun focusFirst(): Boolean { if(rows.isEmpty()) return false;move(rows.indexOfFirst { it.series.id==focusId }.coerceAtLeast(0),smooth=false);return true }
}
