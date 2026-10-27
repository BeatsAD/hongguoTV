// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.ContentType

/** The same shell is used by browsing and detail; the sidebar stays reachable at small heights. */
internal class BrowseScaffold(context: Context,route: String,type: ContentType,navigate: (String,ContentType)->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    val body=ui.column()
    val navigation=mutableListOf<View>()
    private val destinations=mutableMapOf<SidebarButton,Pair<String,ContentType?>>()
    lateinit var selectedNav: View
        private set
    var enterContent: ()->Unit={}
    init {
        gravity=Gravity.TOP;setBackgroundColor(TvStyle.background);clipChildren=false
        val width=TvStyle.windowDp(context);val compact=BrowseLayout.compact(width)
        val side=ui.column().apply { setBackgroundColor(0xFF101114.toInt());setPadding(ui.dp(if(compact) 10 else 19),ui.dp(if(compact) 16 else 27),ui.dp(if(compact) 10 else 19),ui.dp(16)) }
        addView(side,LayoutParams(ui.dp(BrowseLayout.sidebar(width,resources.configuration.fontScale).toInt()),-1))
        val brand=ui.row().apply { setPadding(ui.dp(7),0,0,0) }
        brand.addView(ImageView(context).apply { setImageResource(R.drawable.app_icon) },LayoutParams(ui.dp(28),ui.dp(28)))
        brand.addView(ui.text("红果 TV",18f).apply { maxLines=1;typeface=android.graphics.Typeface.DEFAULT_BOLD },LayoutParams(-2,-2).apply { leftMargin=ui.dp(9) })
        side.addView(brand,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(if(compact) 12 else 23) })
        val navBody=ui.column()
        val navScroll=TvScrollView(context).apply { addView(navBody) }
        side.addView(navScroll,LayoutParams(-1,0,1f))
        fun item(parent: LinearLayout,label: String,key: String,genre: ContentType?=null) {
            val chosen=route==key && (key!="catalog" || genre==type)
            val kind=when(key) { "home"->TvGlyph.Kind.HOME;"search"->TvGlyph.Kind.SEARCH;"ranks"->TvGlyph.Kind.RANK;"library"->TvGlyph.Kind.BOOKMARK;"settings"->TvGlyph.Kind.SETTINGS;else->when(genre) { ContentType.COMIC->TvGlyph.Kind.LAYERS;ContentType.AI->TvGlyph.Kind.AI;else->TvGlyph.Kind.FILM } }
            val button=SidebarButton(context,label,kind,chosen) { navigate(key,genre ?: type) }.apply { tag="browse:$key:${genre?.storedValue.orEmpty()}" }
            parent.addView(button,LayoutParams(-1,-2).apply { bottomMargin=ui.dp(3) });navigation+=button;destinations[button]=key to genre;if(chosen) selectedNav=button
        }
        item(navBody,"首页","home");item(navBody,"搜索","search");item(navBody,"榜单","ranks");item(navBody,"我的片单","library")
        navBody.addView(ui.text("分类",11f,TvStyle.muted).apply { setPadding(ui.dp(7),ui.dp(14),0,ui.dp(8)) })
        item(navBody,"真人剧","catalog",ContentType.SHORT);item(navBody,"漫剧","catalog",ContentType.COMIC);item(navBody,"AI剧","catalog",ContentType.AI)
        item(side,"设置","settings")
        if(!::selectedNav.isInitialized) selectedNav=navigation.first()
        val inset=ui.dp(BrowseLayout.inset(width).toInt())
        body.setPadding(inset,ui.dp(if(compact) 12 else 16),ui.dp(if(compact) 16 else 26),ui.dp(16))
        addView(body,LayoutParams(0,-1,1f))
        navigation.forEachIndexed { index,view ->
            view.nextFocusUpId=navigation.getOrNull(index-1)?.id ?: view.id;view.nextFocusDownId=navigation.getOrNull(index+1)?.id ?: view.id;view.nextFocusLeftId=view.id
            view.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_DOWN) { enterContent();true } else false }
        }
    }
    fun select(route: String,type: ContentType) {
        destinations.forEach { (button,destination) ->
            val selected=destination.first==route && (route!="catalog" || destination.second==type)
            button.select(selected);if(selected) selectedNav=button
        }
    }
    override fun focusSearch(focused: View?,direction: Int): View? {
        val next=super.focusSearch(focused,direction)
        // Spatial search from content must return to its current destination, rather than
        // accidentally activating whichever menu item happens to be closest vertically.
        return if(focused !in navigation && next in navigation) selectedNav else next
    }
}
