// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private val bg=TvStyle.background; private val surface=TvStyle.surface
    private val accent=TvStyle.accent; private val white=TvStyle.text; private val muted=TvStyle.muted
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository()
    private val decoderSupport=VideoDecoderSupport()
    private val playbackAttempt=PlaybackAttempt()
    private var saveStatusView: TextView?=null
    private var restoreAwaitingSave=false
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var detailFromStart=false
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private var playerHeading: LinearLayout?=null
    private var playerBack: View?=null
    private var playbackEpisode: TextView?=null
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private val pageSize=20
    private var catalogScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private val catalogCards=linkedMapOf<String,View>()
    private val cardImages=linkedMapOf<String,Pair<ImageView,String>>()
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var homeFromCache=false
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var homeUpdates: TextView?=null
    private val resumeCards=mutableListOf<View>()
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen==Screen.PLAYER && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen==Screen.PLAYER && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation=0
    private var screen=Screen.CATALOG
    private var tab=0
    private var librarySection=LibrarySection.FAVORITES
    private val libraryTabs=mutableListOf<View>()
    private var homeHero: CinemaHero?=null
    private var heroSelection: Series?=null
    private var homeFilters: LinearLayout?=null
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    private var ranking: ComicRanking?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var historyManage: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen==Screen.PLAYER) {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                maybePrefetch()
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen==Screen.PLAYER && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=if(selected && focused) TvStyle.focused(this,white,10) else rounded(if(selected) white else if(focused) TvStyle.raised else surface,if(focused) white else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setTextColor(if(selected) bg else white); isSelected=selected; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this,::onLibrarySaveState); artwork=ArtworkLoader(this); tvTools=TvTools(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        updater=AppUpdater(this) { foreground && library.isLoaded && screen==Screen.CATALOG && !tvTools.showing }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen==Screen.PLAYER) requestPlayback(true) },{ if(foreground && screen==Screen.PLAYER) requestPlayback(false) },{ position -> if(foreground && screen==Screen.PLAYER) seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen==Screen.PLAYER) returnToDetail() })
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && foreground) { showCatalog(load=true); favoriteMonitor.check() }
        }
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column().apply { clipChildren=false; clipToPadding=false }; val horizontal=(resources.displayMetrics.widthPixels*42f/1024).toInt(); val vertical=dp(14)
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        saveStatusView=button("本机改动尚未保存  重试保存") { library.flush() }.apply {
            visibility=if(library.saveFailed) View.VISIBLE else View.GONE
            container.addView(this,lp(-1,-2))
        }
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        return container
    }
    private fun onLibrarySaveState(state: Library.SaveState) {
        saveStatusView?.takeIf { it.isAttachedToWindow }?.let { view ->
            val next=if(state==Library.SaveState.SAVED && view.hasFocus()) view.focusSearch(View.FOCUS_DOWN) else null
            view.visibility=if(state==Library.SaveState.FAILED) View.VISIBLE else View.GONE
            next?.takeIf { it.isShown }?.requestFocus()
        }
        if(state==Library.SaveState.FAILED) Toast.makeText(this,"本机改动尚未保存，请检查可用空间后重试",Toast.LENGTH_LONG).show()
        else if(restoreAwaitingSave) { restoreAwaitingSave=false; Toast.makeText(this,"记录已合并并保存",Toast.LENGTH_LONG).show() }
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        generation++; cancelWork(); artwork.cancelPage()
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        catalogScroll?.let { scrollPositions[catalogPageKey]=it.scrollY }
        catalogBody=null
        homeScreen=null; homeHero=null; homeFilters=null; libraryTabs.clear(); catalogScroll=null
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        historyManage=null; rankRefresh=null; rankSubtitle=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; homeUpdates=null; resumeCards.clear()
        catalogCards.clear(); cardImages.clear()
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && generation==ticket) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun switchTab(next: Int) {
        leaveCollection()
        LibrarySection.fromTab(next)?.let { librarySection=it }
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        showCatalog(load=(next<=2 && catalog.isEmpty() && (next!=1 || query.isNotBlank())))
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type; heroSelection=null
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null
        showCatalog(load=(tab==0 || query.isNotBlank()),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun inlineHomeFilters()=tab==0 && collection==null && page==1
    private fun localCatalog()=LibrarySection.fromTab(tab)!=null || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> when(tab) { 3 -> library.favorites(); 6 -> library.later(); else -> library.history().map { it.series } }
    }
    private fun iconButton(kind: TvGlyph.Kind,label: String,selected: Boolean=false,action: ()->Unit): TextView {
        val glyph=TvGlyph(kind).apply { setBounds(0,0,dp(20),dp(20)) }
        return button("",selected,action).apply {
            contentDescription=label; setPadding(dp(11),dp(11),dp(11),dp(11)); setCompoundDrawables(glyph,null,null,null)
            fun paint(focus: Boolean) {
                glyph.color(if(selected || focus) bg else white)
                background=if(focus) TvStyle.focused(this@MainActivity,white,100) else TvStyle.shape(this@MainActivity,if(selected) white else surface,radius=100)
            }
            paint(false); setOnFocusChangeListener { _,focused -> paint(focused) }
        }
    }
    private fun navigation(container: LinearLayout) {
        val bar=FrameLayout(this).apply { elevation=dp(8).toFloat(); if(screen==Screen.CATALOG && !inlineHomeFilters()) setBackgroundColor(bg) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(R.drawable.app_icon) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果 TV",19f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        bar.addView(brand,FrameLayout.LayoutParams(-2,dp(48),Gravity.START or Gravity.CENTER_VERTICAL))
        val tabs=row().apply { background=TvStyle.shape(this@MainActivity,surface,radius=100); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        fun item(title: String,index: Int,selected: Boolean,action: ()->Unit)=button(title,selected,action).apply {
            tag="nav:$index"; textSize=14f; minHeight=dp(36); setPadding(dp(18),dp(8),dp(18),dp(8))
            fun paint(focus: Boolean) { setTextColor(if(selected || focus) bg else white); background=if(focus) TvStyle.focused(this@MainActivity,white,100) else TvStyle.shape(this@MainActivity,if(selected) white else Color.TRANSPARENT,radius=100) }
            paint(false); setOnFocusChangeListener { _,focus -> paint(focus) }
        }
        val home=item("首页",0,tab==0) { switchTab(0) }
        val ranks=item("漫剧榜",2,tab==2) { switchTab(2) }
        val saved=item("我的片单",3,LibrarySection.fromTab(tab)!=null) { switchTab(librarySection.tab) }
        listOf(home,ranks,saved).forEach { tabs.addView(it,lp(-2,dp(38))) }
        bar.addView(tabs,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        val tools=row()
        val search=iconButton(TvGlyph.Kind.SEARCH,"搜索",tab==1) { switchTab(1) }.apply { tag="nav:1" }
        val settings=iconButton(TvGlyph.Kind.SETTINGS,"设置",tab==5) { switchTab(5) }.apply { tag="nav:5" }
        tools.addView(search,lp(dp(42),dp(42)).apply { rightMargin=dp(10) }); tools.addView(settings,lp(dp(42),dp(42)))
        bar.addView(tools,FrameLayout.LayoutParams(-2,-2,Gravity.END or Gravity.CENTER_VERTICAL))
        nav.addAll(listOf(home,search,ranks,saved,saved,settings,saved))
        val order=listOf(home,ranks,saved,search,settings)
        order.forEachIndexed { i,view -> view.nextFocusLeftId=order[(i-1).coerceAtLeast(0)].id; view.nextFocusRightId=order[(i+1).coerceAtMost(order.lastIndex)].id; view.nextFocusUpId=view.id }
        container.addView(bar,lp(-1,dp(54)).apply { bottomMargin=dp(12) })
    }
    private fun segment(view: TextView,selected: Boolean) {
        view.isSelected=selected; view.textSize=14f
        fun paint(focused: Boolean) { view.setTextColor(if(selected || focused) bg else white); view.background=if(focused) TvStyle.focused(this,white,100) else TvStyle.shape(this,if(selected) white else Color.TRANSPARENT,radius=100) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun pageTitle(parent: LinearLayout,title: String) {
        parent.addView(text(title,36f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(32),0,dp(25)) })
    }
    private fun catalogHeader(container: LinearLayout,loading: Boolean) {
        if(tab==5) return
        if(tab==1) pageTitle(container,"找剧")
        if(tab<=1) {
            val typeRow=row().apply { setPadding(0,dp(3),0,dp(12)) }
            val types=row().apply { background=TvStyle.shape(this@MainActivity,surface,radius=100); setPadding(dp(4),dp(4),dp(4),dp(4)) }
            ContentType.entries.reversed().forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }.apply { tag="type:${type.name}"; segment(this,library.contentType==type); nextFocusUpId=nav[tab].id }
            }
            if(inlineHomeFilters()) homeFilters=types else { typeRow.addView(types); container.addView(typeRow) }
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        }
        if(tab==1) {
            val searchRow=row().apply { setPadding(0,dp(4),0,dp(20)) }
            val field=row().apply { background=TvStyle.shape(this@MainActivity,Color.rgb(36,37,41),radius=15); setPadding(dp(16),dp(6),dp(10),dp(6)) }
            searchRow.addView(field,lp(0,dp(60)).apply { weight=1f; rightMargin=dp(12) })
            val input=EditText(this).apply {
                id=View.generateViewId(); tag="search:input"; hint="搜索${library.contentType.label}名称或关键词"; setText(query); textSize=17f
                setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; setPadding(dp(16),dp(8),dp(16),dp(8))
                background=null; val glyph=TvGlyph(TvGlyph.Kind.SEARCH,muted).apply { setBounds(0,0,dp(18),dp(18)) }; setCompoundDrawables(glyph,null,null,null); compoundDrawablePadding=dp(14)
                setPadding(0,dp(8),dp(10),dp(8))
                setOnFocusChangeListener { _,focused -> field.background=TvStyle.shape(this@MainActivity,Color.rgb(36,37,41),if(focused) white else Color.TRANSPARENT,15) }
                filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                nextFocusUpId=typeButtons.getValue(library.contentType).id; nextFocusDownId=id
            }
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) input.requestFocus() else runSearch(next) }
            field.addView(input,lp(0,dp(48)).apply { weight=1f }); searchInput=input
            val clear=iconButton(TvGlyph.Kind.PLUS,"清空输入") {
                query=""; page=1; catalog=emptyList(); hasMore=false; catalogFocus=""; showCatalog()
            }.apply {
                rotation=45f; tag="search:clear"; minHeight=0; setPadding(dp(5),dp(5),dp(5),dp(5))
                compoundDrawables[0]?.setBounds(0,0,dp(14),dp(14)); visibility=if(input.text.isEmpty()) View.GONE else View.VISIBLE
            }
            field.addView(clear,lp(dp(26),dp(26)).apply { rightMargin=dp(12) })
            searchButton=iconButton(TvGlyph.Kind.ARROW,"搜索剧名",selected=true,action=::search).also { field.addView(it,lp(dp(40),dp(40))) }
            lateinit var history: TextView
            history=button("搜索历史") { showSearchHistory(history) }.apply {
                textSize=13f; setTextColor(muted); val clock=TvGlyph(TvGlyph.Kind.HISTORY,muted).apply { setBounds(0,0,dp(16),dp(16)) }; setCompoundDrawables(clock,null,null,null); compoundDrawablePadding=dp(7)
                background=rounded(Color.TRANSPARENT); setOnFocusChangeListener { _,f -> background=rounded(Color.TRANSPARENT,if(f) white else Color.TRANSPARENT) }; tag="search:history"; nextFocusUpId=nav[tab].id; nextFocusDownId=input.id; nextFocusLeftId=typeButtons.getValue(library.contentType).id; nextFocusRightId=id }
            lateinit var phone: TextView
            phone=button("手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) } }
            val phoneIcon=TvGlyph(TvGlyph.Kind.PHONE,white).apply { setBounds(0,0,dp(18),dp(18)) }; phone.setCompoundDrawables(phoneIcon,null,null,null); phone.compoundDrawablePadding=dp(8); phone.textSize=14f
            val controls=listOf<View>(input,clear,searchButton!!,phone)
            searchActions.addAll(listOf(history,clear,phone))
            val typeRow=(typeButtons.values.first().parent as View).parent as LinearLayout
            typeRow.addView(Space(this),lp(0,1).apply { weight=1f }); typeRow.addView(history,lp(-2,dp(40)))
            typeButtons.values.last().nextFocusRightId=history.id
            searchRow.addView(phone,lp(-2,dp(60)).apply { width=dp(136) })
            controls.forEachIndexed { i,view -> view.nextFocusUpId=input.nextFocusUpId; view.nextFocusDownId=view.id; view.nextFocusLeftId=controls[(i-1).coerceAtLeast(0)].id; view.nextFocusRightId=controls[(i+1).coerceAtMost(controls.lastIndex)].id }
            fun connectSearch() { controls.filter { it.visibility==View.VISIBLE }.let { visible -> visible.forEachIndexed { i,view -> view.nextFocusLeftId=visible[(i-1).coerceAtLeast(0)].id; view.nextFocusRightId=visible[(i+1).coerceAtMost(visible.lastIndex)].id } } }
            input.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { clear.visibility=if(s.isNullOrEmpty()) View.GONE else View.VISIBLE; connectSearch() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }); connectSearch()
            typeButtons.values.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }; container.addView(searchRow)
        } else if(tab==2) {
            pageTitle(container,"漫剧热播榜")
            val heading=row()
            rankSubtitle=text(if(loading) "正在读取最新榜单…" else ranking?.updatedText?.ifBlank { "来源：红果漫剧热播榜" } ?: "来源：红果漫剧热播榜",13f,muted)
            heading.addView(rankSubtitle,lp(0,-2).apply { weight=1f })
            rankRefresh=addButton(heading,"刷新榜单") { page=1; catalogFocus=""; showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
            container.addView(heading,lp(-1,-2).apply { bottomMargin=dp(18) }); nav.forEach { it.nextFocusDownId=rankRefresh!!.id }
        } else if(LibrarySection.fromTab(tab)!=null) {
            pageTitle(container,"我的片单")
            val heading=row()
            val segments=row().apply { background=TvStyle.shape(this@MainActivity,surface,radius=100); setPadding(dp(4),dp(4),dp(4),dp(4)) }; heading.addView(segments)
            LibrarySection.entries.forEach { section ->
                libraryTabs+=addButton(segments,section.label,section.tab==tab) { switchTab(section.tab) }.apply { tag="library:${section.name}"; segment(this,section.tab==tab); nextFocusUpId=nav[tab].id }
            }
            heading.addView(Space(this),lp(0,1).apply { weight=1f })
            if(tab==3) favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }.apply { nextFocusUpId=libraryTabs.first().id }
            if(tab==4 && library.history().isNotEmpty()) historyManage=addButton(heading,"管理记录") {
                val records=library.history()
                tvTools.choose("选择要管理的观看记录",records.map { it.series.title },historyManage,{ index -> manageHistory(records[index].series,historyManage) },"清空记录") {
                    tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                }
            }.apply { nextFocusUpId=libraryTabs.last().id }
            container.addView(heading,lp(-1,-2).apply { bottomMargin=dp(14) })
            libraryTabs.forEachIndexed { i,view -> view.nextFocusLeftId=libraryTabs[(i-1).coerceAtLeast(0)].id; view.nextFocusRightId=libraryTabs.getOrNull(i+1)?.id ?: favoriteCheck?.id ?: historyManage?.id ?: view.id }
            nav.forEach { it.nextFocusDownId=libraryTabs.first { it.tag=="library:${librarySection.name}" }.id }
            if(tab==3) { favoriteStatus=text(favoriteMonitor.status,12f,muted).apply { setPadding(0,0,0,dp(14)) }; container.addView(favoriteStatus) }
        }
    }
    private fun pageScroll()=TvScrollView(this)
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==0 && page==1) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen=Screen.CATALOG; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen==Screen.CATALOG) favoriteMonitor.check() }
        navigation(container)
        if(tab==5) {
            val scroll=pageScroll(); val body=column(); scroll.addView(body); container.addView(scroll,lp(-1,0).apply { weight=1f })
            settings(body); if(focusNav) nav[tab].requestFocus(); return
        }
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
        }
        catalogPageKey="$tab:${collection.orEmpty()}:$page:$query"
        val host=if(inlineHomeFilters()) container else column().also { content ->
            val scroll=pageScroll(); catalogScroll=scroll
            scroll.addView(content); container.addView(scroll,lp(-1,0).apply { weight=1f })
        }
        catalogHeader(host,load)
        val body=column(); catalogBody=body
        host.addView(body,if(inlineHomeFilters()) lp(-1,0).apply { weight=1f } else lp(-1,-2))
        if(load) {
            if(tab==0) catalogGrid(body,focusNav,focusType,loading=true)
            else { message(body,"正在加载…"); if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[tab].requestFocus() }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            work({
                val result=when(requestTab) { 0 -> repository.home(requestPage,requestType); 2 -> repository.comicRanking(requestPage); else -> repository.search(requestQuery,requestPage,requestType) }
                result
            }, { result ->
                if(requestTab==0 && requestPage==1) library.cacheHome(requestType,result.items,result.hasMore)
                if(requestTab==0 && requestPage==1 && (homeFromCache || tvTools.showing || updater.showing)) {
                    homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    rankSubtitle?.text=ranking?.updatedText?.ifBlank { "来源：红果漫剧热播榜" } ?: "来源：红果漫剧热播榜"
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                }
            }, { problem ->
                if(requestTab==0 && requestPage==1) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text="榜单加载失败"
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType)
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false) {
        if(tab==0 && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
        resumeCards.clear(); catalogCards.clear(); cardImages.clear(); collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        val scroll=catalogScroll ?: return
        val list=column(); body.addView(list,lp(-1,-2))
        fun topInPage(view: View): Int {
            var offset=0; var child: View?=view
            while(child!=null && child!==scroll) { offset+=child.top; child=child.parent as? View }
            return offset
        }
        var restoringScroll=true
        val displayed=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog.take(30)
        if(displayed.isEmpty()) {
            libraryTabs.forEach { it.nextFocusDownId=it.id }
            if(tab!=1 || query.isNotBlank()) message(list,if(loading) "正在加载推荐…" else when(tab) { 1 -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关${library.contentType.label}，换个关键词试试"; 3 -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 4 -> "播放过的剧集会自动保存在这里"; 6 -> "还没有稍后看的剧集，长按剧集可加入"; else -> "本页没有更多内容" })
            if((tab<=2 || localCatalog()) && page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) }
            if((tab<=2 || localCatalog()) && hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) }
            if(resumeCards.isNotEmpty()) {
                typeButtons.values.forEach { it.nextFocusDownId=resumeCards.first().id }
                resumeCards.forEach { it.nextFocusDownId=it.id }
            }
            if(focusType) typeButtons[library.contentType]?.requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else if(libraryTabs.isNotEmpty() && !focusNav) libraryTabs.first { it.tag=="library:${librarySection.name}" }.requestFocus() else if(tab==1 && query.isBlank() && !focusNav) searchInput?.requestFocus() else if(focusNav || resumeCards.isEmpty()) nav[tab].requestFocus() else resumeCards.first().requestFocus()
            return
        }
        if(LibrarySection.fromTab(tab)!=null) {
            val section=row().apply { setPadding(0,dp(18),0,dp(19)) }
            section.addView(text(when(tab) { 3 -> "我的收藏"; 6 -> "稍后看"; else -> "最近观看" },21f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            section.addView(text("${catalog.size} 部",12f,muted)); list.addView(section)
        } else if(tab==1 && query.isNotBlank()) {
            val section=row().apply { setPadding(0,dp(16),0,dp(20)) }
            section.addView(text("搜索结果",22f),lp(0,-2).apply { weight=1f }); section.addView(text("“$query”",14f,muted)); list.addView(section)
        }
        val cards=mutableListOf<View>(); val count=if(tab==2) 3 else 4; val gap=dp(if(tab==2) 22 else if(LibrarySection.fromTab(tab)!=null) 24 else 17)
        val width=(resources.displayMetrics.widthPixels-(resources.displayMetrics.widthPixels*84f/1024).toInt()-gap*(count-1))/count
        val aspect=if(tab==2) 3f/4.05f else if(LibrarySection.fromTab(tab)!=null) TvStyle.POSTER_ASPECT else 16f/9f
        displayed.chunked(count).forEach { items ->
            val line=row(); line.gravity=Gravity.TOP
            items.forEachIndexed { columnIndex,item ->
                val card=column(); card.setPadding(0,0,0,dp(9)); focusStyle(card); card.background=null; card.contentDescription=item.title; card.tag=item.id; catalogCards[item.id]=card
                val position=if(tab==2) ranking?.positions?.get(item.id) else null
                val artwork=FrameLayout(this).apply { background=TvStyle.shape(this@MainActivity,surface,radius=13); clipToOutline=true }; card.addView(artwork,lp(-1,(width/aspect).toInt()))
                val image=CinematicArtwork(this).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }; artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover); cardImages[item.id]=image to item.cover
                if(tab==2) {
                    val rankLabel=position?.rank?.let { "第 $it 名" } ?: "名次暂无"
                    artwork.addView(View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Color.argb(245,10,10,15),Color.TRANSPARENT)) },FrameLayout.LayoutParams(-1,-1))
                    artwork.addView(text(position?.rank?.toString() ?: "—",90f).apply { typeface=Typeface.create("sans-serif",Typeface.BOLD); setPadding(dp(18),0,dp(10),0); setTextColor(Color.argb(230,255,255,255)) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
                    val caption=column().apply { setPadding(dp(20),0,dp(20),dp(23)) }
                    caption.addView(text("红果漫剧",11f)); caption.addView(text(item.title,28f).apply { typeface=Typeface.create("serif",Typeface.NORMAL); maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(9),0,0) })
                    artwork.addView(caption,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
                    card.contentDescription="$rankLabel，${item.title}，${position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
                }
                if(tab!=2) card.addView(text(item.title,if(LibrarySection.fromTab(tab)!=null) 16f else 15f).apply { maxLines=2; minLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(10),0,0) },lp(-1,-2).apply { weight=1f })
                val progress=if(tab==4 || collection=="resume") library.progress(item.id) else null
                val badge=text(if(tab==3) library.favoriteLabel(item.id) else if(tab==6) "稍后看" else if(tab==2) listOf(item.badge,position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无").filter(String::isNotBlank).joinToString(" · ") else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge,12f,muted).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(if(tab==2) 12 else 3),0,0) }
                card.addView(badge,lp(-1,-2)); if(tab==3) favoriteBadges[item.id]=badge
                card.setOnClickListener { catalogFocus=item.id; openDetail(item,tab==4 || collection=="resume" || collection=="updates") }
                card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
                card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
                card.setOnFocusChangeListener { _,focused -> artwork.foreground=TvStyle.card(this,focused); if(focused) { catalogFocus=item.id; loadCover(image,item.cover); if(!restoringScroll) scroll.post { val top=topInPage(line); val bottom=top+line.height; scroll.smoothScrollTo(0,when { top<scroll.scrollY -> top; bottom>scroll.scrollY+scroll.height -> (bottom-scroll.height).coerceAtLeast(top); else -> scroll.scrollY }) } } }
                // The row measures its tallest card, then stretches siblings to keep badges aligned.
                line.addView(card,lp(width,-1).apply { if(columnIndex<count-1) rightMargin=gap }); cards+=card
            }
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
        }
        val paging=row(); paging.gravity=Gravity.CENTER
        var prev: View?=null; var next: View?=null
        if((tab<=2 || localCatalog()) && (page>1 || hasMore || (collection=="hot" && collectionReturn?.hasMore==true))) {
            prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
            paging.addView(text(if(localCatalog()) "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页" else if(tab==2 && ranking!=null) "第 $page / ${ranking!!.totalPages} 页" else "第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(if(tab==2) 135 else 95),dp(44)))
            next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
            if(collection=="hot" && !hasMore && collectionReturn?.hasMore==true) {
                next=addButton(paging,"下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) }
            }
            list.addView(paging,lp(-1,dp(50)))
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=cards[TvGridFocus.left(index,count)].id
            card.nextFocusRightId=cards[TvGridFocus.right(index,cards.size,count)].id
            card.nextFocusUpId=if(index<count) (searchInput?.id ?: collectionBack?.id ?: typeButtons[library.contentType]?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: libraryTabs.lastOrNull()?.id ?: resumeCards.getOrNull(minOf(index,2))?.id ?: nav[tab].id) else cards[index-count].id
            card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else if(TvGridFocus.down(index,cards.size,count)!=null) cards[TvGridFocus.down(index,cards.size,count)!!].id else next?.takeIf { it.isFocusable }?.id ?: prev?.takeIf { it.isFocusable }?.id ?: card.id
        }
        libraryTabs.forEach { it.nextFocusDownId=cards.first().id }
        nav.forEach { it.nextFocusDownId=libraryTabs.firstOrNull { item -> item.tag=="library:${librarySection.name}" }?.id ?: typeButtons[library.contentType]?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: cards.first().id }
        rankRefresh?.nextFocusDownId=cards.first().id
        historyManage?.nextFocusDownId=cards.first().id
        favoriteCheck?.nextFocusDownId=cards.first().id
        collectionBack?.nextFocusDownId=cards.first().id
        resumeCards.forEachIndexed { index,v -> v.nextFocusDownId=cards[minOf(index,cards.lastIndex)].id }
        if(resumeCards.isNotEmpty()) cards.take(count).forEachIndexed { index,v -> v.nextFocusUpId=resumeCards[minOf(index,resumeCards.lastIndex)].id }
        else homeUpdates?.let { updates -> updates.nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[0].id; updates.nextFocusDownId=cards.first().id; cards.take(count).forEach { it.nextFocusUpId=updates.id } }
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: resumeCards.firstOrNull()?.id ?: homeUpdates?.id ?: cards.first().id }
        searchInput?.nextFocusDownId=cards.first().id
        searchButton?.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id
        searchActions.forEach { it.nextFocusDownId=if(it.tag=="search:history") searchInput!!.id else cards[minOf(4,cards.lastIndex)].id }
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(resumeCards.isNotEmpty() && (catalogFocus.isEmpty() || catalogFocus.startsWith("resume:"))) (resumeCards.firstOrNull { it.tag==catalogFocus } ?: resumeCards.first()).requestFocus()
        else cards[displayed.indexOfFirst { it.id==catalogFocus }.coerceAtLeast(0)].requestFocus()
        val savedScroll=scrollPositions[catalogPageKey]
        scroll.post {
            if(catalogScroll!==scroll) return@post
            cards.forEach { view ->
                val card=view as LinearLayout
                val image=card.getChildAt(0)
                val textHeight=card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target=minOf((width/aspect).toInt(),(scroll.height-textHeight-dp(4)).coerceAtLeast(dp(48)))
                if(image.layoutParams.height!=target) image.layoutParams=image.layoutParams.apply { height=target }
            }
            scroll.post settle@{
                if(catalogScroll!==scroll) return@settle
                savedScroll?.let { scroll.scrollTo(0,it) }
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val top=topInPage(line); val bottom=top+line.height
                    val y=when {
                        top<scroll.scrollY -> top
                        bottom>scroll.scrollY+scroll.height -> if(line.height>=scroll.height) top else bottom-scroll.height
                        else -> scroll.scrollY
                    }
                    scroll.scrollTo(0,y.coerceAtLeast(0))
                }
                restoringScroll=false
            }
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        val hero=CinemaHero(this,::loadCover) { item,resume -> openDetail(item,resume,fromStart=resume && library.watched(item.id)) }; homeHero=hero
        fun preview(item: Series) {
            heroSelection=item
            val progress=library.progress(item.id)
            val label=if(library.watched(item.id)) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${progress.episodeIndex+1} 集" else "开始观看"
            hero.show(item,selectionStatus(item),label)
        }
        val shelves=homeShelves()
        (heroSelection ?: shelves.firstOrNull { it.key=="hot" }?.entries?.firstOrNull()?.series ?: if(!loading) shelves.firstOrNull()?.entries?.firstOrNull()?.series else null)?.let(::preview)
        val top=nav[0]
        val home=HomeScreen(this,top,::loadCover,{ item,_ -> preview(item) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it },hero,homeFilters)
        homeScreen=home; body.clipChildren=false
        body.addView(home,lp(resources.displayMetrics.widthPixels,0).apply { weight=1f; leftMargin=-(resources.displayMetrics.widthPixels*42f/1024).toInt() })
        home.render(shelves,catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        if(inlineHomeFilters()) nav.forEach { it.nextFocusDownId=home.firstId() }
        if(focusNav) nav[0].requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(catalog.any { it.id !in library.hidden() } && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val hidden=library.hidden(); val hot=catalog.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("hot","热门发现",hot.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(hot.size>10) { { showCollection("hot") } } else null))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!=Screen.CATALOG) return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        if(removed && (localCatalog() || tab==0)) {
            val oldIds=catalogCards.keys.toList(); val index=oldIds.indexOf(changedId).coerceAtLeast(0)
            if(localCatalog()) {
                catalog=localItems(); page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
            }
            val remaining=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else catalog.filterNot { it.id in library.hidden() }
            catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
            val y=catalogScroll?.scrollY ?: 0; scrollPositions[catalogPageKey]=y
            artwork.cancelPage(); favoriteBadges.clear()
            catalogBody?.let { body -> body.removeAllViews(); catalogGrid(body,false) }
        } else {
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4) labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (collection=="later" || tab==6)) }
                3 -> openDetail(series)
                4 -> if(tab==4) manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!=Screen.CATALOG || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
    }

    private fun settings(parent: LinearLayout) {
        pageTitle(parent,"设置")
        val identity=row().apply { setPadding(0,0,0,dp(24)) }
        identity.addView(ImageView(this).apply { setImageResource(R.drawable.app_icon) },lp(dp(44),dp(44)).apply { rightMargin=dp(14) })
        val identityCopy=column(); identityCopy.addView(text("红果 TV",20f)); identityCopy.addView(text("你的故事，按你的习惯播放。",12f,muted).apply { setPadding(0,dp(6),0,0) })
        identity.addView(identityCopy,lp(0,-2).apply { weight=1f })
        identity.addView(text(BuildConfig.VERSION_NAME,12f,muted)); parent.addView(identity)
        parent.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(30) })
        val stacked=widthDp()<800 || resources.configuration.fontScale>1.25f
        val groups=LinearLayout(this).apply { orientation=if(stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
        val playback=column(); val device=column()
        listOf(playback,device).forEachIndexed { i,group ->
            groups.addView(group,lp(if(stacked) -1 else 0,-2).apply { if(!stacked) { weight=1f; if(i==0) rightMargin=dp(32) } else bottomMargin=dp(20) })
        }
        parent.addView(groups)
        val panels=mutableMapOf<LinearLayout,LinearLayout>()
        fun heading(group: LinearLayout,title: String) {
            group.addView(text(title,14f,muted).apply { setPadding(dp(14),0,0,dp(12)) })
            val panel=column().apply { background=TvStyle.shape(this@MainActivity,surface,radius=15) }; group.addView(panel); panels[group]=panel
        }
        fun separator(panel: LinearLayout) { if(panel.childCount>0) panel.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { leftMargin=dp(56) }) }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val kind=when { label.startsWith("默认清晰度")->TvGlyph.Kind.QUALITY; label.startsWith("默认倍速")->TvGlyph.Kind.SPEED; label.startsWith("画面")->TvGlyph.Kind.FRAME; label.startsWith("手机")->TvGlyph.Kind.TRANSFER; label.startsWith("恢复")->TvGlyph.Kind.EYE; label.startsWith("开源")->TvGlyph.Kind.CODE; else->TvGlyph.Kind.INFO }
            val panel=panels.getValue(group); separator(panel)
            return PreferenceRow(this,label,kind).apply {
                focusStyle(this); background=rounded(Color.TRANSPARENT); setOnClickListener { action() }
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else Color.TRANSPARENT,if(focused) white else Color.TRANSPARENT) }
                panel.addView(this,lp(-1,-2))
            }
        }
        heading(playback,"播放偏好")
        heading(device,"片单与设备")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        separator(panels.getValue(playback))
        val autoNextButton=PreferenceSwitch(this).apply {
            text="自动连播"; isChecked=library.autoNext; focusStyle(this); background=rounded(Color.TRANSPARENT)
            setOnFocusChangeListener { _,f -> background=rounded(if(f) TvStyle.raised else Color.TRANSPARENT,if(f) white else Color.TRANSPARENT) }
            switchMinWidth=dp(44)
            thumbDrawable=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(white); setSize(dp(20),dp(20)) }
            trackDrawable=TvStyle.shape(this@MainActivity,surface,radius=100).apply { setSize(dp(44),dp(26)) }
            thumbTintList=android.content.res.ColorStateList.valueOf(white)
            trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(52,199,89),TvStyle.outline))
            setOnCheckedChangeListener { _,checked -> library.autoNext=checked }; panels.getValue(playback).addView(this,lp(-1,-2))
        }
        playback.addView(text("偏好自动保存，所有剧集共用。",12f,muted).apply { setPadding(dp(12),dp(12),0,0) })
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); restoreAwaitingSave=true; library.restore(data,restoreSettings); heroSelection=null
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并，正在保存…",Toast.LENGTH_SHORT).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        device.addView(text("收藏与观看进度保存在本机。",12f,muted).apply { setPadding(dp(14),dp(12),0,dp(25)) })
        heading(device,"关于")
        lateinit var updates: TextView
        updates=setting(device,"${if(BuildConfig.DEBUG) "应用版本" else "版本与更新"}  ${BuildConfig.VERSION_NAME}") { updater.show(updates) }
        lateinit var license: TextView
        license=setting(device,"开源许可") { tvTools.info("开源许可","本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：N3urda/hongguoTV-updates。",license) }
        val left=listOf(qualityButton,defaultSpeed,frame,autoNextButton); val right=listOf(backup,hidden,updates,license)
        listOf(left,right).forEach { items -> items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        } }
        left.forEachIndexed { i,v -> v.nextFocusLeftId=v.id; v.nextFocusRightId=right[i].id }
        right.forEachIndexed { i,v -> v.nextFocusLeftId=left[i].id; v.nextFocusRightId=v.id }
        if(stacked) {
            (left+right).forEach { it.nextFocusLeftId=it.id; it.nextFocusRightId=it.id }
            left.last().nextFocusDownId=right.first().id; right.first().nextFocusUpId=left.last().id
        }
        nav.forEach { it.nextFocusDownId=qualityButton.id }
        device.addView(text(if(BuildConfig.DEBUG) "开发测试版通过本地安装更新。" else "确认系统安装后更新，保留本机记录。",12f,muted).apply { setPadding(dp(14),dp(12),0,0) })
        nav[tab].requestFocus()
    }
    private fun runSearch(value: String,type: ContentType=library.contentType) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        if(library.contentType!=type) { library.contentType=type; heroSelection=null; for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) } }
        library.rememberSearch(clean,type); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true,fromStart: Boolean=false) {
        playbackAttempt.clear()
        pendingDetail=series; detailAutoplay=autoplay; detailFromStart=fromStart
        invalidatePage(); releaseCatalogViews(); screen=Screen.DETAIL; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val target=if(fromStart) ResumeTarget(0,0) else ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay,fromStart) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen=Screen.DETAIL; val data=detail ?: return showCatalog()
        val body=base(); navigation(body); body.clipChildren=false
        val side=(resources.displayMetrics.widthPixels*42f/1024).toInt()
        val scroll=TvScrollView(this,94).apply { clipChildren=false }
        val content=column().apply { clipChildren=false; clipToPadding=false; setPadding(side,0,side,0) }; scroll.addView(content)
        body.addView(scroll,lp(resources.displayMetrics.widthPixels,0).apply { weight=1f; leftMargin=-side })
        val progress=library.progress(data.series.id)
        val resumeTarget=ResumePlayback.target(data.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
        val watched=library.watched(data.series.id)
        val hero=CinemaHero(this,::loadCover) { _,_ -> }; hero.contextLabel("剧集详情")
        hero.show(data.series.copy(badge="全 ${data.episodes.size} 集"),"",if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeTarget.index+1} 集" else "开始观看")
        val play=hero.play
        play.setOnClickListener { playEpisode(if(watched) 0 else resumeTarget.index,if(watched) 0 else resumeTarget.position) }
        val favorite=hero.details.apply {
            text=if(library.favorite(data.series.id)) "已收藏" else "收藏"
            (compoundDrawables[0] as? TvGlyph)?.kind(if(library.favorite(data.series.id)) TvGlyph.Kind.CHECK else TvGlyph.Kind.PLUS)
            setOnClickListener {
                favoriteMonitor.stop(); val saved=library.toggle(data.series)
                if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
                text=if(saved) "已收藏" else "收藏"
                (compoundDrawables[0] as? TvGlyph)?.kind(if(saved) TvGlyph.Kind.CHECK else TvGlyph.Kind.PLUS)
            }
        }
        val synopsis=text(data.series.description.ifBlank { "选择剧集开始观看" },14f,muted).apply { maxLines=20; visibility=if(synopsisExpanded) View.VISIBLE else View.GONE; setPadding(0,dp(14),0,dp(18)) }
        lateinit var synopsisButton: TextView
        synopsisButton=button(if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded; synopsis.visibility=if(synopsisExpanded) View.VISIBLE else View.GONE
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
        }
        (favorite.parent as LinearLayout).addView(synopsisButton,lp(-2,-2).apply { leftMargin=dp(10) })
        val back=button("‹ 返回") { goBack() }.apply { textSize=12f; background=rounded(Color.TRANSPARENT); nextFocusUpId=nav[tab].id; nextFocusDownId=play.id }
        hero.addView(back,FrameLayout.LayoutParams(-2,dp(36),Gravity.START or Gravity.TOP).apply { leftMargin=side; topMargin=dp(94) })
        hero.onFocused={ scroll.post { scroll.smoothScrollTo(0,0) } }
        content.addView(hero,lp(resources.displayMetrics.widthPixels,dp(if(resources.configuration.fontScale>1.2f) 480 else 440)).apply { leftMargin=-side; topMargin=-dp(94) })
        content.addView(synopsis)
        val heroActions=listOf(play,favorite,synopsisButton)
        heroActions.forEachIndexed { index,view -> view.nextFocusUpId=back.id; view.nextFocusLeftId=heroActions[(index-1).coerceAtLeast(0)].id; view.nextFocusRightId=heroActions[(index+1).coerceAtMost(heroActions.lastIndex)].id }
        nav.forEach { it.nextFocusDownId=back.id }
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        val nextGroup=addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id; v.nextFocusUpId=if(i>=10) episodes[i-10].id else jump.id; v.nextFocusDownId=if(i+10<episodes.size) episodes[i+10].id else v.id }
        heroActions.forEach { it.nextFocusDownId=jump.id }
        listOf(previous,nextGroup,jump).forEach { it.nextFocusUpId=play.id; it.nextFocusDownId=episodes.first().id }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false,qualityOverride: Int?=null) {
        updater.interrupt()
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen==Screen.PLAYER && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val requestedId=data.episodes[selectedIndex]
        playbackAttempt.begin(requestedId,qualityOverride)
        val maxQuality=playbackAttempt.quality(library.maxQuality)
        val rejected=playbackAttempt.rejectedVariants()
        val prefetched=if(position==0L && !recovering && rejected.isEmpty()) preparedNext.take("$requestedId:$maxQuality") else null
        val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); player?.clearMediaItems()
            video?.close(); video=null; closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen=Screen.PLAYER; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,12,15))) }
            val heading=row().apply { setPadding(dp(42),dp(24),dp(42),dp(20)); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(160,11,12,15),Color.TRANSPARENT)) }
            val back=iconButton(TvGlyph.Kind.NEXT,"返回") { returnToDetail() }.apply { rotation=180f }; playerBack=back
            heading.addView(back,lp(dp(40),dp(40)).apply { rightMargin=dp(14) })
            val titles=column(); playbackTitle=text("",17f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
            titles.addView(playbackTitle); playbackEpisode=text("",12f,muted).also { titles.addView(it,lp(-1,-2).apply { topMargin=dp(4) }) }; heading.addView(titles,lp(0,-2).apply { weight=1f })
            playerHeading=heading; root.addView(heading,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
            playbackText=text("",12f).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(white); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))

            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playbackTitle.text=data.series.title; playbackEpisode?.text="第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val ticket=generation
        work({ prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality) { it.variantKey !in rejected && decoderSupport.supports(it) }).prepare() }, { remote ->
            video=remote; quality=remote.info.quality
            val p=player ?: run {
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showPanel() }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) syncMediaSession() }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
        }, { problem -> playerFailure(problem) })
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val http=okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(id,quality,decoderSupport::supports)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Next episode prepared near end")
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; playerHeading?.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val decoding=problem is PlaybackException && problem.errorCode in 4000..4999
        if(decoding && playbackAttempt.rejectDecoder(video?.info)) {
            playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            return
        }
        val source=PlaybackErrors.source(problem)
        val retryable=if(source!=null) PlaybackErrors.retryable(source) else if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else PlaybackErrors.retryable(problem)
        val upstreamMessage=source?.message
        playbackText.text=if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else upstreamMessage ?: if(problem is PlaybackException && problem.errorCode in 3000..3999) "视频格式无法解析，请换一部剧或更新应用。" else "播放失败，可重试或切换清晰度。"
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playbackAttempt.clear(); playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        addButton(actions,"本集尝试 720P") { playbackAttempt.clear(); playEpisode(episodeIndex,retryPosition,retryAutoplay,qualityOverride=720) }
        addButton(actions,"返回选集") { returnToDetail(true) }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else upstreamMessage?.let { " $it" }.orEmpty()))
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE; playerHeading?.visibility=View.GONE
    }
    private fun showHud() {
        if(screen!=Screen.PLAYER) return
        hud.visibility=View.VISIBLE; playerHeading?.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        transportPlay?.let { view ->
            if(view.tag=="player:transport") { view.text=""; view.contentDescription=if(player?.playWhenReady==true) "暂停" else "播放"; (view.compoundDrawables[0] as? TvGlyph)?.kind(if(player?.playWhenReady==true) TvGlyph.Kind.PAUSE else TvGlyph.Kind.PLAY) }
            else view.text=if(player?.playWhenReady==true) "暂停" else "播放"
        }
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!=Screen.PLAYER || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!=Screen.PLAYER) return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!=Screen.PLAYER) { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val transport=row(); controls.addView(transport)
        fun control(kind: TvGlyph.Kind,label: String,enabled: Boolean=true,click: ()->Unit)=iconButton(kind,label,action=click).apply {
            isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .3f; transport.addView(this,lp(dp(42),dp(42)).apply { rightMargin=dp(10) })
        }
        val previous=control(TvGlyph.Kind.PREVIOUS,"上一集",episodeIndex>0) { if(episodeIndex>0) playEpisode(episodeIndex-1) }
        val play=iconButton(if(player?.playWhenReady==true) TvGlyph.Kind.PAUSE else TvGlyph.Kind.PLAY,if(player?.playWhenReady==true) "暂停" else "播放",selected=true) { togglePlayback() }.apply { tag="player:transport" }
        transport.addView(play,lp(dp(48),dp(48)).apply { rightMargin=dp(10) }); transportPlay=play
        val next=control(TvGlyph.Kind.SKIP,"下一集",episodeIndex<(detail?.episodes?.lastIndex ?: 0)) { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }
        val rewind=control(TvGlyph.Kind.REWIND,"后退 10 秒") { seek(-1,0) }
        val forward=control(TvGlyph.Kind.FORWARD,"前进 10 秒") { seek(1,0) }
        transport.addView(Space(this),lp(0,1).apply { weight=1f })
        val options=transport
        lateinit var episodes: TextView
        episodes=addButton(options,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","从头播放"),more,{ choice ->
                main.post {
                    if(screen==Screen.PLAYER && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> { seekTo(0); hidePanel() }
                    }
                }
            })
        }.apply { tag="playback-settings" }
        val focusable=listOf(previous,play,next,rewind,forward,episodes,more).filter { it.isFocusable }
        focusable.forEachIndexed { index,view -> view.nextFocusLeftId=focusable[(index-1).coerceAtLeast(0)].id; view.nextFocusRightId=focusable[(index+1).coerceAtMost(focusable.lastIndex)].id; view.nextFocusUpId=playerBack?.id ?: view.id; view.nextFocusDownId=episodes.id }
        playerBack?.nextFocusDownId=play.id
        play.requestFocus()
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val dialog=AlertDialog.Builder(this).setTitle("全局默认倍速")
            .setSingleChoiceItems(tvTools.choiceAdapter(speeds.map(PlaybackSpeed::label),selected),selected) { popup,index ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                popup.dismiss()
            }.setNegativeButton("取消",null).create()
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); TvDialogStyle.apply(dialog); dialog.listView.setSelection(selected); dialog.listView.requestFocus()
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val dialog=AlertDialog.Builder(this).setTitle("全局默认清晰度")
            .setSingleChoiceItems(tvTools.choiceAdapter(options.map { "${it}P" },options.indexOf(library.maxQuality)),options.indexOf(library.maxQuality)) { popup,index ->
                val target=options[index]; val changed=library.maxQuality!=target || (inPlayer && playbackAttempt.quality(library.maxQuality)!=target)
                library.maxQuality=target; popup.dismiss()
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
                if(inPlayer && changed) { playbackAttempt.clear(); playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay) }
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); TvDialogStyle.apply(dialog)
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen==Screen.PLAYER) {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; playerHeading=null; playerBack=null; playbackEpisode=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        playbackAttempt.clear()
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            Screen.PLAYER -> if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            Screen.DETAIL -> showCatalog()
            else -> if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else finish() } else nav.getOrNull(tab)?.requestFocus()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen==Screen.PLAYER) {
            val key=event.keyCode
            if(panel && !playError && key==KeyEvent.KEYCODE_DPAD_DOWN) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) showEpisodePanel(currentFocus ?: transportPlay ?: hud)
                return true
            }
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            Screen.PLAYER -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            Screen.DETAIL -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        updater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    Screen.PLAYER -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    Screen.DETAIL -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false,fromStart=detailFromStart) } ?: showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || (tab==1 && query.isNotBlank())))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen==Screen.CATALOG) favoriteMonitor.check()
            if(screen==Screen.PLAYER) syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        updater.pause()
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen==Screen.PLAYER) { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
