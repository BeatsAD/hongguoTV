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
    private lateinit var browser: NativeBrowseController
    private val detailRepository=ContentRepository(repository.http.newBuilder().callTimeout(12,java.util.concurrent.TimeUnit.SECONDS).build(),requestGate=repository.requestGate)
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
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var lastFreshnessPoll=0L
    private var detailUpdatedAt: Long?=null
    private var detailAttemptedAt: Long?=null
    private var detailRefreshScope: RequestScope?=null
    private var detailRefreshJob: java.util.concurrent.Future<*>?=null
    private var pendingDetailUpdate: Detail?=null
    private var detailRefreshStatus: TextView?=null
    private var detailScroll: ScrollView?=null
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private lateinit var favoriteMonitor: FavoriteMonitor
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
    private var hasMore=false
    private val nav=mutableListOf<View>()
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
            val refreshedAt=android.os.SystemClock.elapsedRealtime()
            if(refreshedAt-lastFreshnessPoll>=30_000L) { lastFreshnessPoll=refreshedAt; refreshCurrentContent(allowNetwork=false) }
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
        val cooldowns=getSharedPreferences("content-cooldowns-v1",Context.MODE_PRIVATE)
        repository.requestGate.restore(cooldowns.all.mapNotNull { (host,value) -> (value as? Long)?.let { host to it } }.toMap())
        repository.requestGate.changed={ values -> cooldowns.edit().apply { values.forEach { (host,until) -> putLong(host,until) } }.apply() }
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this,::onLibrarySaveState); artwork=ArtworkLoader(this); tvTools=TvTools(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        updater=AppUpdater(this) { foreground && library.isLoaded && screen==Screen.CATALOG && !tvTools.showing }
        browser=NativeBrowseController(this,repository,library,::loadCover,{ series,resume -> openDetail(series,resume) },::quickActions,
            { title,message,anchor -> tvTools.info(title,message,anchor) },{ body,anchor -> nav.clear();repeat(7) { nav.add(anchor) };settings(body) },{ selected -> tab=selected },{ favoriteMonitor.check(force=true) },{ favoriteMonitor.status },{ anchor -> tvTools.confirm("清空观看记录","清除本机所有观看进度？收藏会保留。",anchor) { library.clearHistory();browser.refreshLibrary() } })
        browser.restore(state)
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
            if(!isDestroyed && foreground) { showCatalog(load=true) }
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
        if(::browser.isInitialized) browser.saveStatusChanged()
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
        nav.clear()
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
    private fun pageTitle(parent: LinearLayout,title: String) {
        parent.addView(text(title,36f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(32),0,dp(25)) })
    }

    private fun pageScroll()=TvScrollView(this)
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false) {
        initialCatalogPending=false;restoreStoppedPage=false;pendingDetail=null
        invalidatePage();releaseCatalogViews();screen=Screen.CATALOG
        browser.show(root)
    }

    private fun refreshCurrentContent(allowNetwork: Boolean=true) {
        if(!foreground || !library.isLoaded || isDestroyed || tvTools.showing || updater.showing || speedDialog?.isShowing==true || episodePanel!=null) return
        if(screen==Screen.DETAIL || screen==Screen.PLAYER) { applyPendingDetail();if(allowNetwork) refreshDetail() }
    }

    private fun refreshLibraryCards() { browser.refreshLibrary() }

    private fun quickActions(series: Series,anchor: View) {
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4) labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(); if(added && tab==3) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards() }
                3 -> openDetail(series)
                4 -> if(tab==4) manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards() }
            }
        })
    }
    private fun refreshFavoriteLabels() { if(library.isLoaded && screen==Screen.CATALOG && !favoriteMonitor.running) browser.refreshLibrary() }

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
                    favoriteMonitor.stop(); restoreAwaitingSave=true; library.restore(data,restoreSettings)
                    browser.trimMemory()
                    showCatalog()
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
        left.forEachIndexed { i,v -> v.nextFocusLeftId=nav[tab].id; v.nextFocusRightId=right[i].id }
        right.forEachIndexed { i,v -> v.nextFocusLeftId=left[i].id; v.nextFocusRightId=v.id }
        if(stacked) {
            (left+right).forEach { it.nextFocusLeftId=nav[tab].id; it.nextFocusRightId=it.id }
            left.last().nextFocusDownId=right.first().id; right.first().nextFocusUpId=left.last().id
        }
        nav.forEach { it.nextFocusDownId=qualityButton.id }
        device.addView(text(if(BuildConfig.DEBUG) "开发测试版通过本地安装更新。" else "确认系统安装后更新，保留本机记录。",12f,muted).apply { setPadding(dp(14),dp(12),0,0) })
        nav[tab].requestFocus()
    }

    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); browser.refreshLibrary() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true,fromStart: Boolean=false) {
        playbackAttempt.clear(); favoriteMonitor.stop(); cancelDetailRefresh(); pendingDetailUpdate=null
        pendingDetail=series; detailAutoplay=autoplay; detailFromStart=fromStart
        browser.leave();invalidatePage(); releaseCatalogViews(); screen=Screen.DETAIL; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            detail=result.copy(series=result.series.copy(contentType=series.contentType ?: result.series.contentType,
                episodeCount=result.episodes.size,tagNames=series.tagNames.ifEmpty { result.series.tagNames })); detailUpdatedAt=android.os.SystemClock.elapsedRealtime(); detailAttemptedAt=null
            library.observeDetail(detail!!,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val target=if(fromStart) ResumeTarget(0,0) else ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            if(resume && progress!=null && progress.episodeId !in result.episodes) {
                directPlayback=false;Toast.makeText(this,"上次分集已不可用，请重新选集",Toast.LENGTH_LONG).show();showDetail(true)
            } else if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay,fromStart) } })
    }
    private fun cancelDetailRefresh() {
        detailRefreshScope?.cancel(); detailRefreshScope=null
        detailRefreshJob?.cancel(true); detailRefreshJob=null
    }
    private fun refreshDetail(force: Boolean=false) {
        val current=detail ?: return
        if(!foreground || screen !in listOf(Screen.DETAIL,Screen.PLAYER) || detailRefreshJob!=null || pendingDetailUpdate!=null || (screen==Screen.PLAYER && !playbackReady)) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(detailAttemptedAt?.let { now-it in 0 until ContentFreshness.MANUAL_DELAY }==true) return
        if(!force && !ContentFreshness.due(now,detailUpdatedAt,detailAttemptedAt)) return
        detailAttemptedAt=now
        detailRefreshStatus?.text="正在后台检查分集…"
        val scope=RequestScope(); detailRefreshScope=scope
        val started=System.currentTimeMillis()
        detailRefreshJob=io.submit {
            val result=runCatching { scope.run { detailRepository.detail(current.series.id) } }
            main.post {
                if(detailRefreshScope!==scope) return@post
                detailRefreshScope=null; detailRefreshJob=null
                if(!foreground || detail!==current || screen !in listOf(Screen.DETAIL,Screen.PLAYER)) return@post
                result.onSuccess { fresh ->
                    detailUpdatedAt=android.os.SystemClock.elapsedRealtime()
                    library.observeDetail(fresh,started)
                    if(fresh!=current) { pendingDetailUpdate=fresh; if(!tvTools.showing && speedDialog?.isShowing!=true && episodePanel==null) applyPendingDetail() }
                    detailRefreshStatus?.text="分集已更新 · 共 ${fresh.episodes.size} 集 · 刷新"
                }.onFailure { detailRefreshStatus?.text="更新暂不可用 · 保留当前分集 · 重试" }
            }
        }
    }
    /** Streaming keeps its current episode identity; a new list is applied at a selection boundary. */
    private fun applyPendingDetail(allowPlayer: Boolean=false,redraw: Boolean=true) {
        val fresh=pendingDetailUpdate ?: return
        val old=detail ?: return
        if(screen!=Screen.DETAIL && !(screen==Screen.PLAYER && allowPlayer)) return
        val index=ResumePlayback.refreshedIndex(fresh.episodes,old.episodes.getOrNull(episodeIndex),episodeIndex,screen==Screen.PLAYER) ?: return
        if(screen==Screen.PLAYER) { saveProgress(); cancelPrefetch() }
        detail=fresh; pendingDetailUpdate=null
        episodeIndex=index
        group=episodeIndex/20
        if(screen==Screen.DETAIL && redraw) showDetail(false,preservePosition=true)
        else if(screen==Screen.PLAYER) {
            playbackTitle.text=fresh.series.title; playbackEpisode?.text="第 ${episodeIndex+1} 集"
            syncMediaSession()
        }
    }
    private fun showDetail(focusEpisode: Boolean,preservePosition: Boolean=false) {
        val focusedTag=if(preservePosition) root.findFocus()?.tag else null
        val savedY=if(preservePosition) detailScroll?.scrollY else null
        invalidatePage(); releaseCatalogViews(); screen=Screen.DETAIL; val data=detail ?: return showCatalog()
        val body=base(); nav.clear(); val detailNav=button("‹ 返回列表") { showCatalog() }; body.addView(detailNav);repeat(7) { nav.add(detailNav) }
        val scroll=pageScroll(); detailScroll=scroll; val content=column(); scroll.addView(content)
        body.addView(scroll,lp(-1,0).apply { weight=1f })
        val progress=library.progress(data.series.id)
        val resumeTarget=ResumePlayback.target(data.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
        val watched=library.watched(data.series.id)
        val hero=CinemaHero(this,::loadCover) { _,_ -> }; hero.contextLabel("剧集详情")
        hero.show(data.series.copy(badge="全 ${data.episodes.size} 集"),"",if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeTarget.index+1} 集" else "开始观看")
        val play=hero.play.apply { tag="detail:play" }
        play.setOnClickListener { playEpisode(if(watched) 0 else resumeTarget.index,if(watched) 0 else resumeTarget.position) }
        val favorite=hero.details.apply {
            tag="detail:favorite"
            text=if(library.favorite(data.series.id)) "已收藏" else "收藏"
            (compoundDrawables[0] as? TvGlyph)?.kind(if(library.favorite(data.series.id)) TvGlyph.Kind.CHECK else TvGlyph.Kind.PLUS)
            setOnClickListener {
                favoriteMonitor.stop(); val saved=library.toggle(data.series)
                if(saved) library.observeDetail(data,System.currentTimeMillis(),acknowledge=true)
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
        synopsisButton.tag="detail:synopsis"
        (favorite.parent as LinearLayout).addView(synopsisButton,lp(-2,-2).apply { leftMargin=dp(10) })
        val back=button("‹ 返回") { goBack() }.apply { tag="detail:back"; textSize=12f; background=rounded(Color.TRANSPARENT); nextFocusUpId=nav[tab].id; nextFocusDownId=play.id }
        content.addView(back,lp(-2,dp(36)).apply { bottomMargin=dp(12) })
        hero.onFocused={ scroll.post { scroll.smoothScrollTo(0,0) } }
        content.addView(hero,lp(-1,hero.preferredHeight).apply { bottomMargin=dp(20) })
        content.addView(synopsis)
        detailRefreshStatus=button("共 ${data.episodes.size} 集 · 刷新分集") { refreshDetail(force=true) }.apply {
            tag="detail:refresh"; textSize=12f; nextFocusUpId=play.id
            content.addView(this,lp(-2,-2).apply { bottomMargin=dp(12) })
        }
        val later=button(if(library.queued(data.series.id)) "已在稍后看" else "稍后看") { library.toggleLater(data.series);showDetail(false,true) }.apply { tag="detail:later" }
        (favorite.parent as LinearLayout).addView(later,lp(-2,-2).apply { leftMargin=dp(8) })
        val heroActions=listOf(play,favorite,synopsisButton,later)
        heroActions.forEachIndexed { index,view -> view.nextFocusUpId=back.id; view.nextFocusLeftId=heroActions[(index-1).coerceAtLeast(0)].id; view.nextFocusRightId=heroActions[(index+1).coerceAtMost(heroActions.lastIndex)].id }
        nav.forEach { it.nextFocusDownId=back.id }
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        previous.tag="detail:previous"
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        val nextGroup=addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        nextGroup.tag="detail:next"
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        jump.tag="detail:jump"
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.tag="detail:episode:${data.episodes[index]}"; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id; v.nextFocusUpId=if(i>=10) episodes[i-10].id else jump.id; v.nextFocusDownId=if(i+10<episodes.size) episodes[i+10].id else v.id }
        heroActions.forEach { it.nextFocusDownId=detailRefreshStatus!!.id }
        detailRefreshStatus!!.nextFocusDownId=jump.id
        listOf(previous,nextGroup,jump).forEach { it.nextFocusUpId=detailRefreshStatus!!.id; it.nextFocusDownId=episodes.first().id }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
        if(focusedTag!=null) root.findViewWithTag<View>(focusedTag)?.takeIf { it.isFocusable && it.isShown }?.requestFocus()
        if(savedY!=null) { val ticket=generation; scroll.post { if(generation==ticket) scroll.scrollTo(0,savedY) } }
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
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<(detail?.episodes?.lastIndex ?: -1)) playEpisode(episodeIndex+1)
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
            if(anchor.tag!="playback-settings") { anchor.text="定时 ${sleepTimer.label()}" }; updatePlaybackText(); showHud()
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
                if(anchor.tag!="playback-settings") { anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P" }
                if(inPlayer && changed) { playbackAttempt.clear(); playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay) }
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); TvDialogStyle.apply(dialog)
    }
    private fun showEpisodePanel(anchor: View) {
        if(episodePanel!=null) return
        applyPendingDetail(allowPlayer=true)
        refreshDetail()
        val data=detail ?: return
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
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; screen=Screen.DETAIL; applyPendingDetail(redraw=false); showDetail(true); refreshDetail() }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            Screen.PLAYER -> if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            Screen.DETAIL -> showCatalog()
            else -> if(!browser.back()) finish()
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
    override fun onSaveInstanceState(outState: Bundle) { if(::browser.isInitialized) browser.save(outState);super.onSaveInstanceState(outState) }
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
            if(screen==Screen.CATALOG) showCatalog()
            else if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    Screen.PLAYER -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    Screen.DETAIL -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false,fromStart=detailFromStart) } ?: showCatalog()
                    else -> showCatalog()
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen==Screen.CATALOG && tab==3) favoriteMonitor.check()
            if(screen==Screen.PLAYER) syncMediaSession()
            refreshCurrentContent()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        browser.pause();updater.pause(); cancelDetailRefresh()
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
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { cancelPrefetch();browser.trimMemory() }
    }
    override fun onDestroy() {
        generation++; cancelWork(); cancelDetailRefresh()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        browser.destroy();favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
