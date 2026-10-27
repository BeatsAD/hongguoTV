// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import com.hongguotv.core.*
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Owns browsing state, requests and phone lifetime; playback stays in MainActivity. */
class NativeBrowseController(private val activity: Activity,private val repository: ContentRepository,private val library: Library,
    private val load: (ImageView,String)->Unit,private val open: (Series,Boolean)->Unit,private val actions: (Series,View)->Unit,
    private val info: (String,String,View?)->Unit,private val settings: (LinearLayout,View)->Unit,private val routeChanged: (Int)->Unit,private val checkFavorites: ()->Unit,private val favoriteStatus: ()->String,private val clearHistory: (View)->Unit) {
    private val ui=BrowseUi(activity)
    private val main=Handler(Looper.getMainLooper())
    private val workers=Executors.newFixedThreadPool(2)
    private val categories=CategoryRepository(repository)
    private val ranks=RankingRepository(repository)
    private val states=ContentType.entries.associateWith { CatalogUiState(CategoryQuery(it,mapOf("sort" to listOf("hot_score")))) }
    private val homes=ContentType.entries.associateWith { CatalogUiState(CategoryQuery(it,mapOf("sort" to listOf("hot_score")))) }
    private val ranking=RankingUiState()
    private val search=SearchUiState()
    private val jobs=mutableMapOf<String,Pair<RequestScope,java.util.concurrent.Future<*>>>()
    private var epoch=0L
    private var route="home"
    private var type=ContentType.SHORT
    private var section=LibrarySection.FAVORITES
    private var root: FrameLayout?=null
    private var content: LinearLayout?=null
    private var sidebar: View?=null
    private var scaffold: BrowseScaffold?=null
    private var restoreContentFocus=true
    private var home: HomeScreen?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogView: CatalogScreen?=null
    private var rankView: RankingScreen?=null
    private var searchView: SearchScreen?=null
    private var localGrid: PosterGridView?=null
    private val localAnchors=mutableMapOf<LibrarySection,PosterGridView.Anchor>()
    @Volatile private var phoneScope: ContentType?=null
    @Volatile private var scopeRevision=0L
    private var saveStatus: View?=null
    private var phone: PhoneSearchServer?=null
    private var phoneEpoch=0L
    private var expiresAt=0L
    private var address=""
    private var active=false
    private var closed=false
    private val countdown=object: Runnable {
        override fun run() {
            if(!active || route!="search") return
            if(address.isNotEmpty() && address!=lanAddress()) { closePhone();searchView?.connection("网络已变化，请刷新二维码") }
            if(phone!=null) searchView?.countdown(((expiresAt-android.os.SystemClock.elapsedRealtime())/1000).coerceAtLeast(0))
            main.postDelayed(this,1000)
        }
    }
    private fun tab()=when(route) { "search"->1;"ranks"->2;"library"->section.tab;"settings"->5;else->0 }
    private fun capture() {
        home?.let { homePosition=it.capturePosition() };catalogView?.capture();rankView?.capture();searchView?.capture()
        localGrid?.let { localAnchors[section]=it.capture() }
    }
    private fun stopJobs() {
        epoch++;jobs.values.forEach { (scope,future) -> scope.cancel();future.cancel(true) };jobs.clear()
        (workers as java.util.concurrent.ThreadPoolExecutor).purge()
        (states.values+homes.values).forEach { it.busy=false };ranking.busy=false;search.busy=false
    }
    fun leave() { capture();active=false;stopJobs();closePhone();home=null;catalogView=null;rankView=null;searchView=null;localGrid=null }
    fun pause() { leave() }
    fun destroy() { leave();closed=true;workers.shutdownNow() }
    fun trimMemory() {
        states.filterKeys { route!="catalog" || it!=type }.values.forEach { it.items=emptyList();it.cursor=null;it.hasMore=true }
    }
    fun show(target: FrameLayout) { capture();stopJobs();closePhone();root=target;active=true;renderShell() }
    fun select(next: String,nextType: ContentType=type,keepNavigationFocus: Boolean=false) {
        val destinationType=if(next=="catalog") nextType else type
        if(active && route==next && type==destinationType) return
        capture();stopJobs();closePhone();route=next;type=destinationType
        if(next=="catalog") library.contentType=type
        active=true;renderShell(keepNavigationFocus)
    }
    private fun <T> request(key: String,action: ()->T,success: (T)->Unit,failure: (Throwable)->Unit) {
        if(!active || closed || key in jobs) return
        val ticket=epoch;val scope=RequestScope()
        if(BuildConfig.DEBUG) android.util.Log.d("HongguoBrowse","request $key revision=$ticket")
        val future=workers.submit {
            val result=runCatching {
                val connectivity=activity.getSystemService(ConnectivityManager::class.java)
                if(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true) throw java.io.IOException("网络未连接，请连接后重试")
                scope.run(action)
            }
            main.post {
                if(closed || !active || ticket!=epoch) return@post
                jobs.remove(key);result.fold(success,failure)
            }
        }
        jobs[key]=scope to future
    }
    private fun error(e: Throwable)=when(e) { is ContentRequestDeferredException->"服务冷却中，请稍后重试";is java.net.UnknownHostException->"无法连接内容服务，请检查网络";is java.net.ConnectException->"无法连接内容服务";is java.net.SocketTimeoutException->"连接超时";else->e.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(95).ifBlank { "加载失败" } }
    private fun renderShell(keepNavigationFocus: Boolean=false) {
        val target=root ?: return
        home=null;catalogView=null;rankView=null;searchView=null;localGrid=null
        restoreContentFocus=!keepNavigationFocus
        val shell=scaffold?.takeIf { it.parent===target } ?: BrowseScaffold(activity,route,type) { key,genre -> select(key,genre,keepNavigationFocus=true) }.also {
            target.removeAllViews();target.addView(it,FrameLayout.LayoutParams(-1,-1));scaffold=it
        }
        shell.body.removeAllViews();shell.select(route,type)
        sidebar=shell.selectedNav
        val body=shell.body;content=body
        shell.enterContent={ focusContent() }
        routeChanged(tab())
        saveStatus=CapsuleButton(activity,"本机改动尚未保存 · 重试保存") { library.flush() }.also { it.visibility=if(library.saveFailed) View.VISIBLE else View.GONE;body.addView(it) }
        when(route) {
            "catalog" -> renderCatalog(body)
            "ranks" -> renderRanking(body)
            "search" -> renderSearch(body)
            "library" -> renderLibrary(body)
            "settings" -> { val scroll=TvScrollView(activity);val panel=ui.column();scroll.addView(panel);body.addView(scroll,LinearLayout.LayoutParams(-1,-1));settings(panel,sidebar!!) }
            else -> renderHome(body)
        }
        if(!keepNavigationFocus || shell.navigation.none { it.hasFocus() }) FocusIntent.during(false) { sidebar?.requestFocus() }
        // Restore content focus only when returning from a page that owned it.
        main.post { if(active && !keepNavigationFocus) when(route) {
            "catalog" -> if(states.getValue(type).anchor.focus) catalogView?.grid?.submit(states.getValue(type).items.map { PosterGridView.Item(it) },states.getValue(type).anchor)
            "ranks" -> Unit
            "home" -> homePosition?.let { home?.restorePosition(it,it.focus.isNotBlank()) }
        } }
    }
    internal fun detailScaffold(target: FrameLayout,navigate: (String,ContentType)->Unit): BrowseScaffold {
        target.removeAllViews()
        return BrowseScaffold(activity,route,type,navigate).also { target.addView(it,FrameLayout.LayoutParams(-1,-1)) }
    }
    private fun focusContent() {
        when(route) {
            "home" -> home?.findViewById<View>(home!!.firstId())?.requestFocus()
            "catalog" -> catalogView?.grid?.takeIf { states.getValue(type).items.isNotEmpty() }?.focusFirst() ?: firstControl()
            "search" -> if(search.items.isNotEmpty()) searchView?.grid?.focusFirst() else firstControl()
            "ranks" -> if(ranking.entries.isNotEmpty()) rankView?.grid?.focusFirst() else firstControl()
            "library" -> if(localGrid?.focusFirst()!=true) content?.findViewWithTag<View>("library:${section.name}")?.requestFocus() ?: firstControl()
            else -> firstControl()
        }
    }
    private fun firstControl() { val views=java.util.ArrayList<View>();content?.addFocusables(views,View.FOCUS_DOWN);views.firstOrNull()?.requestFocus() }
    private fun renderHome(body: LinearLayout) {
        val heading=ui.heading("首页",22f)
        heading.addView(BrowseButton(activity,"搜索剧名",BrowseButton.Role.SEARCH) { search.scope=null;select("search") },LinearLayout.LayoutParams(ui.dp(if(BrowseLayout.compact(TvStyle.windowDp(activity))) 130 else 160),-2));body.addView(heading,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(10) })
        home=HomeScreen(activity,sidebar!!,load,{ _,_-> },{ series,resume -> openChecked(series,resume) },actions,{}).also { view ->
            body.addView(view,LinearLayout.LayoutParams(-1,0,1f));view.nearEnd={ key -> ContentType.known(key)?.let { loadHome(it) } }
        }
        homes.forEach { (genre,state) -> if(state.items.isEmpty()) library.cachedHome(genre)?.let { state.items=it.items } }
        updateHome()
        listOf(ContentType.SHORT,ContentType.COMIC,ContentType.AI).forEach { genre -> if(homes.getValue(genre).cursor==null) loadHome(genre) }
    }
    private fun updateHome() {
        val shelves=mutableListOf<HomeScreen.Shelf>()
        val history=library.history().filter { !it.completed && !library.watched(it.series.id) }.take(21)
        if(history.isNotEmpty()) shelves+=HomeScreen.Shelf("resume","接着看",history.map { p -> HomeScreen.Entry(p.series,"第${p.episodeIndex+1}集 · 已看${time(p.position)}",true,if(p.duration>0) (100L*p.position/p.duration).coerceIn(0,100).toInt() else null) }) { section=LibrarySection.HISTORY;select("library") }
        listOf(ContentType.SHORT,ContentType.COMIC,ContentType.AI).forEach { genre ->
            val state=homes.getValue(genre)
            val suffix=when { state.busy->" · 加载中";state.error.isNotBlank()->" · ${state.error}";state.cursor==null && state.items.isNotEmpty()->" · 上次缓存";else->"" }
            shelves+=HomeScreen.Shelf(genre.storedValue,genre.label,state.items.filterNot { it.id in library.hidden() }.map { HomeScreen.Entry(it,"") },suffix.removePrefix(" · ")) { select("catalog",genre) }
        }
        home?.updateShelves(shelves,requestFocus=home?.hasFocus()==true)
        home?.refresh?.apply { text=if(homes.values.any { it.error.isNotBlank() }) "首页部分内容未更新 · 确认重试" else "刷新首页";setOnClickListener { homes.forEach { (genre,state) -> state.cursor=null;state.error="";loadHome(genre) } } }
    }
    private fun loadHome(genre: ContentType) {
        val state=homes.getValue(genre)
        if(state.busy || (state.cursor!=null && state.items.size>=54) || (state.cursor!=null && (!state.hasMore || state.error.isNotBlank()))) return
        val cursor=state.cursor;state.busy=true;updateHome()
        request("home:${genre.storedValue}",{ categories.load(state.query,cursor,if(cursor==null) emptyList() else state.items.map { it.id }) },{ result ->
            state.busy=false;val prior=if(cursor==null) emptyList() else state.items;val merged=ListProgress.merge(prior,result.items) { it.id }
            state.items=merged.take(54);state.hasMore=result.hasMore
            state.error=if(ListProgress.stalled(cursor?.offset ?: -1,result.cursor.offset,merged.size-prior.size,result.hasMore)) "分页未前进" else ""
            state.cursor=result.cursor;library.cacheHome(genre,result.items,result.hasMore);updateHome()
        },{ e -> state.busy=false;state.error=error(e);updateHome() })
    }
    private fun renderCatalog(body: LinearLayout) {
        val state=states.getValue(type)
        catalogView=CatalogScreen(activity,state,sidebar!!,load,{ openChecked(it,false) },actions,{ next ->
            stopJobs();state.reset(next);catalogView?.renderControls();loadCategory(true)
        },{ loadCategory(true) },{ search.scope=type;select("search") },restoreFocus=restoreContentFocus).also { body.addView(it,LinearLayout.LayoutParams(-1,0,1f)) }
        if(state.cursor==null && state.error.isBlank()) loadCategory()
    }
    private fun loadCategory(manual: Boolean=false) {
        val state=states.getValue(type)
        if(state.busy || (state.cursor!=null && !state.hasMore) || (!manual && state.error.isNotBlank())) return
        if(state.items.size>=ListProgress.MAX_ITEMS && state.cursor!=null) { state.error="本次浏览已达内存预算，请重置条件重新加载";catalogView?.update();return }
        val cursor=state.cursor;val query=state.query;state.busy=true;state.error="";catalogView?.update()
        request("category",{ categories.load(query,cursor,if(cursor==null) emptyList() else state.items.map { it.id }) },{ result ->
            state.busy=false
            val previous=if(cursor==null) emptyList() else state.items;val merged=ListProgress.merge(previous,result.items) { it.id }
            if(ListProgress.stalled(cursor?.offset ?: -1,result.cursor.offset,merged.size-previous.size,result.hasMore)) state.error="分页没有新增内容，已停止自动加载"
            else { state.cursor=result.cursor;state.hasMore=result.hasMore }
            if(BuildConfig.DEBUG) android.util.Log.d("HongguoBrowse","category ${query.type.name}: offset=${cursor?.offset ?: 0}, next=${result.cursor.offset}, received=${result.items.size}, total=${merged.size}")
            state.items=merged.take(ListProgress.MAX_ITEMS);state.dimensions=result.dimensions;state.updatedAt=System.currentTimeMillis()
            catalogView?.renderControls();catalogView?.update()
        },{ e -> state.busy=false;state.error=error(e)+if(state.items.isNotEmpty()) " · 保留上次内容" else "";catalogView?.update() })
    }
    private fun renderRanking(body: LinearLayout) {
        rankView=RankingScreen(activity,ranking,sidebar!!,load,{ entry -> when(entry) {
            is RankEntry.Actor -> info(entry.name,listOf("演员资料",entry.description,entry.heat,"暂无已验证的关联作品入口").filter(String::isNotBlank).joinToString("\n"),rankView)
            is RankEntry.Drama -> openChecked(entry.series,false)
        } },actions,{ next -> stopJobs();if(ranking.query!=next) ranking.entries=emptyList();ranking.query=next;ranking.cursor=null;ranking.hasMore=true;ranking.error="";ranking.anchor=PosterGridView.Anchor();rankView?.renderControls();loadRank(true) },{ loadRank(true) },restoreFocus=restoreContentFocus).also { body.addView(it,LinearLayout.LayoutParams(-1,0,1f)) }
        if(ranking.cursor==null && ranking.error.isBlank()) loadRank()
    }
    private fun loadRank(manual: Boolean=false) {
        val state=ranking;if(state.busy || (state.cursor!=null && !state.hasMore) || (!manual && state.error.isNotBlank())) return
        if(state.entries.size>=ListProgress.MAX_ITEMS && state.cursor!=null) { state.error="已达本次浏览预算，请重新选择榜单";rankView?.update();return }
        val cursor=state.cursor;val query=state.query;state.busy=true;state.error="";rankView?.update()
        request("rank",{ ranks.load(query,cursor,if(cursor==null) emptyList() else state.entries.map { it.id }) },{ result ->
            state.busy=false;val prior=if(cursor==null) emptyList() else state.entries;val merged=ListProgress.merge(prior,result.entries) { it.javaClass.simpleName+it.id }
            if(ListProgress.stalled(cursor?.offset ?: -1,result.cursor.offset,merged.size-prior.size,result.hasMore)) state.error="榜单分页没有前进，已停止自动加载"
            else { state.cursor=result.cursor;state.hasMore=result.hasMore }
            state.query=result.query;state.entries=merged.take(ListProgress.MAX_ITEMS);state.groups=result.groups;rankView?.renderControls();rankView?.update()
        },{ e -> state.busy=false;state.error=error(e)+if(state.entries.isNotEmpty()) " · 保留上次内容" else "";rankView?.update() })
    }
    private fun renderSearch(body: LinearLayout) {
        phoneScope=search.scope
        searchView=SearchScreen(activity,search,sidebar!!,load,{ openChecked(it,false) },actions,{ scope ->
            search.scope=scope;phoneScope=scope;scopeRevision++;searchView?.renderScopes();if(search.query.isNotBlank()) beginSearch(search.query) else { search.error="";searchView?.update() }
        },::beginSearch,library.searches(),{ library.clearSearches();searchView?.showHistory(emptyList()) },{
            stopJobs();search.query="";search.items=emptyList();search.pages.clear();search.more.clear();search.error="";search.anchor=PosterGridView.Anchor()
            searchView?.showHistory(library.searches());searchView?.update()
        },::startPhone,{ loadSearch(true) },restoreFocus=restoreContentFocus).also { body.addView(it,LinearLayout.LayoutParams(-1,0,1f)) }
        startPhone();main.removeCallbacks(countdown);main.post(countdown)
        if(search.query.isNotBlank() && search.items.isEmpty() && search.error.isBlank()) loadSearch()
    }
    private fun beginSearch(value: String)=beginScopedSearch(value,search.scope)
    private fun beginScopedSearch(value: String,scope: ContentType?) {
        search.scope=scope
        val clean=value.trim();if(clean.isBlank() || clean.length>80 || clean.any(Char::isISOControl)) return
        stopJobs();search.query=clean;search.items=emptyList();search.pages.clear();search.more.clear();search.error="";search.anchor=PosterGridView.Anchor()
        library.rememberSearch(clean,search.scope ?: ContentType.SHORT);searchView?.update();loadSearch(true)
    }
    private fun loadSearch(manual: Boolean=false) {
        if(search.query.isBlank() || search.busy || (!manual && search.error.isNotBlank())) return
        if(search.scope==ContentType.AI) { search.error="AI剧搜索暂不可用";searchView?.update();return }
        if(search.items.size>=ListProgress.MAX_ITEMS) { search.error="搜索结果已达本次浏览预算，请重新输入";searchView?.update();return }
        val types=(search.scope?.let(::listOf) ?: listOf(ContentType.SHORT,ContentType.COMIC)).filter { search.more[it]!=false }
        if(types.isEmpty()) return
        val query=search.query;val pages=types.associateWith { (search.pages[it] ?: 0)+1 };search.busy=true;search.error="";searchView?.update()
        request("search",{ types.associateWith { genre -> runCatching { repository.search(query,pages.getValue(genre),genre) } } },{ results ->
            search.busy=false;val errors=mutableListOf<String>()
            results.forEach { (genre,result) -> result.onSuccess { page ->
                val previous=search.items.size;search.items=ListProgress.merge(search.items,page.items.map { it.copy(contentType=genre) }) { it.id }.take(ListProgress.MAX_ITEMS)
                search.pages[genre]=pages.getValue(genre);search.more[genre]=page.hasMore
                if(page.hasMore && previous==search.items.size) { search.more[genre]=false;errors+="${genre.label}本批没有新增结果" }
            }.onFailure { errors+="${genre.label}：${error(it)}" } }
            search.error=errors.joinToString("；");searchView?.update()
        },{ e -> search.busy=false;search.error=error(e);searchView?.update() })
    }
    private fun lanAddress(): String {
        val manager=activity.getSystemService(ConnectivityManager::class.java);val network=manager.activeNetwork;val caps=manager.getNetworkCapabilities(network) ?: return ""
        if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return ""
        return manager.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress.orEmpty()
    }
    private fun closePhone() { phoneEpoch++;phone?.close();phone=null;address="";main.removeCallbacks(countdown) }
    private fun startPhone() {
        closePhone();searchView?.session(null);address=lanAddress()
        if(address.isEmpty()) { searchView?.connection("请连接同一局域网后刷新二维码");return }
        val ticket=phoneEpoch;val selectedAddress=address
        // Socket bind and secure token creation happen off the main thread.
        workers.execute {
            val result=runCatching { PhoneSearchServer(selectedAddress,{ query ->
                val scope=phoneScope;val version=scopeRevision
                main.post { if(active && route=="search" && ticket==phoneEpoch && version==scopeRevision) { beginScopedSearch(query,scope);searchView?.renderScopes() } }
            },{ main.post { if(ticket==phoneEpoch) { closePhone();searchView?.session(null);searchView?.connection("二维码已过期，请刷新") } } },onConnected={ main.post { if(active && ticket==phoneEpoch) searchView?.connection("手机页面已打开 · 可连续发送") } }) }
            main.post {
                if(closed || !active || route!="search" || ticket!=phoneEpoch) { result.getOrNull()?.close();return@post }
                result.onSuccess { phone=it;expiresAt=android.os.SystemClock.elapsedRealtime()+900_000;searchView?.session(it.url);main.removeCallbacks(countdown);main.post(countdown) }
                    .onFailure { searchView?.connection("无法开启手机输入，请刷新重试") }
            }
        }
    }
    private fun renderLibrary(body: LinearLayout,focusTab: LibrarySection?=null) {
        saveStatus?.takeIf { it.parent==null }?.let { body.addView(it) }
        val heading=ui.heading("我的片单")
        if(section==LibrarySection.FAVORITES) heading.addView(BrowseButton(activity,"检查收藏更新",BrowseButton.Role.TEXT) { checkFavorites() })
        if(section==LibrarySection.HISTORY) heading.addView(BrowseButton(activity,"清空观看记录",BrowseButton.Role.TEXT) { sidebar?.let(clearHistory) })
        body.addView(heading)
        val counts=mapOf(LibrarySection.FAVORITES to library.favorites().size,LibrarySection.LATER to library.later().size,LibrarySection.HISTORY to library.history().size)
        val tabs=LibrarySection.entries.map { item -> BrowseButton(activity,"${item.label}  ${counts.getValue(item)}",BrowseButton.Role.TAB,section==item) {
            if(section!=item) {
                capture();section=item;body.removeAllViews();renderLibrary(body,focusTab=item);routeChanged(tab())
            }
        }.autoSelect().apply { tag="library:${item.name}" } }
        ui.line(body,tabs)
        tabs.forEachIndexed { index,view ->
            view.nextFocusLeftId=tabs.getOrNull(index-1)?.id ?: sidebar!!.id
            view.nextFocusRightId=tabs.getOrNull(index+1)?.id ?: view.id
            view.setOnKeyListener { _,key,event ->
                if(key==android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action==android.view.KeyEvent.ACTION_DOWN) { localGrid?.focusFirst();true } else false
            }
        }
        val items=when(section) { LibrarySection.FAVORITES->library.favorites();LibrarySection.LATER->library.later();LibrarySection.HISTORY->library.history().map { it.series } }
        localGrid=PosterGridView(activity,TvStyle.catalogColumns(activity),sidebar!!,load,{ openChecked(it,section==LibrarySection.HISTORY) },actions,{}).also { view ->
            view.topTarget=tabs[LibrarySection.entries.indexOf(section)];body.addView(view,LinearLayout.LayoutParams(-1,0,1f));view.submit(items.map { item ->
                val progress=library.progress(item.id);PosterGridView.Item(item,if(section==LibrarySection.HISTORY && progress!=null) "第${progress.episodeIndex+1}集 · 已看${time(progress.position)}" else if(section==LibrarySection.FAVORITES) (if(library.watched(item.id)) "已看 · " else "")+library.favoriteLabel(item.id) else "",progress?.takeIf { section==LibrarySection.HISTORY && it.duration>0 }?.let { it.position.toFloat()/it.duration })
            },localAnchors[section]?.let { it.copy(focus=it.focus && restoreContentFocus && focusTab==null) })
        }
        body.addView(ui.text(if(items.isEmpty()) "暂无本机记录" else "本机记录 · ${items.size}部 · 长按或菜单键管理"+if(section==LibrarySection.FAVORITES) " · ${favoriteStatus()}" else "",12f,TvStyle.muted))
        if(focusTab!=null) body.findViewWithTag<View>("library:${focusTab.name}")?.let { tabView ->
            FocusIntent.during(false) { tabView.requestFocus() }
        }
    }
    fun saveStatusChanged() { saveStatus?.visibility=if(library.saveFailed) View.VISIBLE else View.GONE }
    fun refreshLibrary() {
        if(!active) return
        capture()
        if(route=="home") updateHome() else if(route=="library") {
            val body=content ?: return;val focus=RemoteFocus.capture(body)
            body.removeAllViews();renderLibrary(body);routeChanged(tab());RemoteFocus.restore(body,focus)
        }
    }
    private fun openChecked(series: Series,resume: Boolean) {
        if(!series.playable) { info(series.title,series.description.ifBlank { "待上线或暂无可播放分集" },content);return };open(series,resume)
    }
    private fun time(value: Long)="%02d:%02d".format(value.coerceAtLeast(0)/60_000,value.coerceAtLeast(0)/1000%60)
    fun back(): Boolean {
        if(catalogView?.back()==true || rankView?.back()==true) return true
        if(sidebar?.hasFocus()!=true) { sidebar?.requestFocus();return true }
        if(route!="home") { select("home");return true };return false
    }
    fun save(bundle: Bundle) {
        capture();bundle.putString("browse.route",route);bundle.putString("browse.type",type.storedValue);bundle.putString("browse.section",section.name)
        states.forEach { (genre,state) -> bundle.putString("browse.filters.${genre.storedValue}",JSONObject(state.query.selections).toString());bundle.putString("browse.anchor.${genre.storedValue}",state.anchor.id) }
        bundle.putString("browse.query",search.query);bundle.putString("browse.scope",search.scope?.storedValue)
    }
    fun restore(bundle: Bundle?) {
        if(bundle==null) return
        route=bundle.getString("browse.route")?.takeIf { it in setOf("home","catalog","ranks","search","library","settings") } ?: "home"
        type=ContentType.known(bundle.getString("browse.type")) ?: ContentType.SHORT
        section=LibrarySection.entries.firstOrNull { it.name==bundle.getString("browse.section") } ?: LibrarySection.FAVORITES
        states.forEach { (genre,state) -> runCatching {
            val json=JSONObject(bundle.getString("browse.filters.${genre.storedValue}") ?: "{}")
            state.query=CategoryQuery(genre,json.keys().asSequence().filter { it!="genre" }.associateWith { key -> val a=json.getJSONArray(key);(0 until a.length()).map { a.getString(it) } })
            state.anchor=PosterGridView.Anchor(bundle.getString("browse.anchor.${genre.storedValue}").orEmpty(),focus=true)
        } }
        search.query=bundle.getString("browse.query").orEmpty().take(80);search.scope=ContentType.known(bundle.getString("browse.scope"))
    }
}
