// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "", val contentType: ContentType? = null, val episodeCount: Int? = null, val tagNames: List<String> = emptyList(), val playable: Boolean = true) {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags).put("type",contentType?.storedValue).put("episodeCount",episodeCount).put("tagNames",JSONArray(tagNames)).put("playable",playable)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags"),ContentType.known(o.str("type")),o.optInt("episodeCount").takeIf { it>0 },o.optJSONArray("tagNames")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank) }.orEmpty(),o.optBoolean("playable",true)) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String,
    val codec: String="", val width: Int?=null, val height: Int?=null, val frameRate: Double?=null, val backupUrls: List<String> = emptyList(), val availableQualities: Set<Int> = emptySet()) {
    val variantKey get()="${quality.substringBefore('（')}:$codec:${width}x$height"
}
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis, val requestGate: ContentRequestGate = ContentRequestGate(), val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared) {
    private val signer = Signer(clock)
    private val site = "https://hongguoduanju.com"
    private enum class SearchSource { APP, WEBSITE }
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long, val source: SearchSource = SearchSource.APP, val websitePage: Int = 0)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    @Volatile private var appSearchRetryAt = 0L
    internal fun text(url: String, signed: Signer.Request? = null, timeoutMillis: Long? = null, metadata: Boolean = true): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        val call=http.newCall(request.build())
        timeoutMillis?.let { call.timeout().timeout(it,TimeUnit.MILLISECONDS) }
        val host=call.request().url.host
        val stage=when {
            url.contains("/multi_video_model/") -> "播放取址"
            url.contains("/search/") -> "搜索请求"
            url.contains("/detail?") -> "剧集详情"
            metadata -> "内容请求"
            else -> "备用取址"
        }
        val started=System.nanoTime()
        var status=0
        var serverFailure=true
        try {
            requestGate.check(host)
            return RequestScope.execute(call) { response ->
            status=response.code
            serverFailure=response.code==408 || response.code>=500
            requestGate.observe(call.request().url.host,response.code,response.header("Retry-After"))
            if (!response.isSuccessful) throw PlaybackSourceException(if(PlaybackErrors.retryableHttp(response.code)) PlaybackFailureKind.NETWORK else PlaybackFailureKind.SOURCE,"内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw ContentProtocolException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw ContentProtocolException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw ContentProtocolException("内容响应过大")
            Diagnostics.record(stage,"host=$host HTTP=$status bytes=${bytes.size} elapsedMs=${(System.nanoTime()-started)/1_000_000}"+(if(bytes.isEmpty()) " 空响应" else ""))
            if(metadata) requestGate.success(host)
            String(bytes,Charsets.UTF_8)
        } } catch(failure: IOException) {
            Diagnostics.record(stage,"host=$host HTTP=$status elapsedMs=${(System.nanoTime()-started)/1_000_000}")
            Diagnostics.failure(stage,failure)
            if(metadata && serverFailure && failure !is ContentRequestDeferredException && !call.isCanceled() && !Thread.currentThread().isInterrupted) requestGate.failed(call.request().url.host)
            throw failure
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) },episodeCount=count.takeIf { it>0 },tagNames=(0 until tags.length()).map { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) }.filter(String::isNotBlank).distinct())
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.AI) throw ContentProtocolException("请使用AI剧分类目录")
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw ContentProtocolException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw ContentProtocolException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        if(type.searchTab==null) throw ContentProtocolException("AI剧搜索暂不可用；全部剧集搜索目前覆盖真人剧和漫剧")
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(page>1 && cursor==null) throw SearchSessionExpiredException()
        // Keep subsequent pages on the same source: App and website page sizes differ.
        if(cursor?.source==SearchSource.WEBSITE || (page==1 && clock()<appSearchRetryAt)) return websiteSearch(keyword,page,type,cursor)
        try {
            val result=appSearch(keyword,page,type,cursor)
            if(result.items.isNotEmpty() || page>1) return result
            Diagnostics.record("搜索兜底","App 首批结果为空，核对官网同类型结果")
        } catch (failure: Exception) {
            if(page>1 || failure is SearchSessionExpiredException || failure is ContentRequestDeferredException || Thread.currentThread().isInterrupted) throw failure
            appSearchRetryAt=clock()+15*60_000L
            Diagnostics.failure("搜索主源",failure)
            try { return websiteSearch(keyword,page,type) }
            catch (fallback: Exception) { if(fallback!==failure) fallback.addSuppressed(failure); throw fallback }
        }
        return websiteSearch(keyword,page,type)
    }
    private fun rememberSearchPage(keyword: String, page: Int, type: ContentType, cursor: SearchCursor) {
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=cursor
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
    }
    private fun websiteSearch(keyword: String, page: Int, type: ContentType, cursor: SearchCursor? = null): CatalogPage {
        // The current search page renders client-side. Use its JSON result endpoint and
        // require the explicit genre; never substitute mixed results for a typed search.
        var sourcePage=if(page==1) 1 else (cursor?.websitePage ?: throw SearchSessionExpiredException())+1
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12)
        // A typed page may be empty after filtering mixed website results. Scan at most
        // four source pages; keep the logical cursor at the last consumed source page.
        repeat(4) {
            val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
            if(remaining<=0) throw ContentProtocolException("搜索超时，请稍后重试")
            val payload=text("$site/api/search/result?query=${Signer.encode(keyword)}"+if(sourcePage>1) "&page=$sourcePage" else "",timeoutMillis=remaining)
            val data=try { JSONObject(payload) } catch (failure: org.json.JSONException) { throw ContentProtocolException("搜索返回格式异常，请稍后重试").apply { initCause(failure) } }
            val rows=data.optJSONArray("searchList") ?: throw ContentProtocolException("搜索数据暂不可用，请稍后重试")
            if(data.optInt("pageNum",-1)!=sourcePage) throw ContentProtocolException("搜索页码不匹配，请重新搜索")
            val totalPages=data.optInt("totalPages",-1)
            if(totalPages<0 || !data.has("hasMore")) throw ContentProtocolException("搜索分页数据异常，请稍后重试")
            val genre=if(type==ContentType.COMIC) "205" else "203"
            val items=valid(rows.objects().filter { it.optInt("doc_type")==23 && it.optJSONObject("video_data")?.str("genre")==genre }.map(::card))
            val more=data.optBoolean("hasMore",false) && sourcePage<minOf(totalPages,100)
            Diagnostics.record("官网搜索","type=${type.name} sourcePage=$sourcePage received=${rows.length()} matched=${items.size} more=$more")
            if(items.isNotEmpty() || !more || it==3) {
                rememberSearchPage(keyword,page,type,SearchCursor("","",null,clock(),SearchSource.WEBSITE,sourcePage))
                return CatalogPage(items,more && page<100)
            }
            sourcePage++
        }
        error("Unreachable search page")
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType, cursor: SearchCursor?): CatalogPage {
        requestGate.check("api5-normal-sinfonlinea.fqnovel.com")
        val identity=playbackSession.profile(http)
        val values=identity.values.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val payload=text(signed.url,signed.copy(headers=signed.headers+identity.cookie.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()),timeoutMillis=5_000)
        if(payload.isBlank()) throw ContentProtocolException("搜索接口未返回数据，请稍后重试")
        val data=try { JSONObject(payload) } catch (failure: org.json.JSONException) { throw ContentProtocolException("搜索返回格式异常，请稍后重试").apply { initCause(failure) } }
        if(data.optInt("code",-1)!=0) throw ContentProtocolException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw ContentProtocolException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw ContentProtocolException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"),contentType=type,episodeCount=count.takeIf { it>0 })
            }
        }
        if(list.isEmpty() && (tab.optJSONArray("data")?.length() ?: 0)>0) throw ContentProtocolException("搜索条目格式无法识别，请查看诊断详情")
        Diagnostics.record("App 搜索","type=${type.name} page=$page matched=${valid(list).size}")
        rememberSearchPage(keyword,page,type,SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock()))
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw ContentProtocolException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw ContentProtocolException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw ContentProtocolException("分集数据异常")
        return Detail(card(o).copy(id=id,episodeCount=episodes.size),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080, supported: (StreamInfo)->Boolean = { true }): StreamInfo {
        var compatibility=playbackSession.compatibilityPlayback
        repeat(2) { attempt ->
            try {
                return streamOnce(id,maxQuality,supported,compatibility).also {
                    if(compatibility) playbackSession.compatibilityPlayback=true
                }
            }
            catch(failure: EmptyContentException) {
                Diagnostics.failure("播放取址",failure)
                if(attempt==1 || Thread.currentThread().isInterrupted) throw failure
                Diagnostics.record("播放取址",if(compatibility) "兼容接口为空，仅重试一次" else "首选取址为空，尝试上游单集兼容接口；保留本机身份")
                compatibility=true
            }
        }
        error("Unreachable")
    }
    private fun streamOnce(id: String,maxQuality: Int,supported: (StreamInfo)->Boolean,compatibility: Boolean): StreamInfo {
        requestGate.check("api5-normal-sinfonlineb.fqnovel.com")
        require(id.matches(Regex("[0-9]{1,30}")))
        // Current signed GET and nested model layout: N3urda/hongguoTV a2256e8, GPL-3.0.
        // Keep each identity's query/signature/cookie together; never rotate registration on errors.
        val response=if(compatibility) {
            val device=playbackSession.playerProfile(http)
            val request=RegisteredPlayer.request(id,device)
            val body=text(request.url,request,timeoutMillis=10_000,metadata=false)
            RegisteredPlayer.normalize(body,id)
        } else {
            val identity=playbackSession.profile(http)
            val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",identity.values+mapOf(
                "video_id" to id,"video_id_type" to "0","device_level" to "1","need_all_video_definition" to "1",
                "need_mp4_align" to "0","use_os_player" to "0","video_platform" to "1024","source" to "0","from_video_id" to ""
            ))
            val request=if(identity.cookie.isBlank()) signed else signed.copy(headers=signed.headers+("Cookie" to identity.cookie))
            text(request.url,request,timeoutMillis=10_000,metadata=false)
        }
        if(response.isBlank()) throw EmptyContentException("播放接口返回空响应，请稍后重试或更新应用")
        val data=try { JSONObject(response) } catch(_: Exception) { throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"播放信息格式已变化，请更新应用") }
        val code=data.optInt("Code",data.optInt("code",0))
        if(code!=0) throw PlaybackSourceException(PlaybackFailureKind.SOURCE,"播放接口暂不可用（错误码 $code）")
        val map=data.optJSONObject("data") ?: throw EmptyContentException("播放信息为空，请稍后重试")
        if(map.length()==0) throw EmptyContentException("播放信息为空，请稍后重试")
        val models=map.optJSONObject("video_model_data") ?: map
        val entry=models.optJSONObject(id) ?: throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"播放信息没有对应剧集")
        val model=entry.optJSONObject("video_model") ?: try { JSONObject(entry.str("video_model")) } catch(_: Exception) { throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"播放信息格式已变化，请更新应用") }
        val inline=inlineStreams(model).filter(supported)
        val initial=inline.takeIf { it.isNotEmpty() }?.let { selectStream(it,maxQuality) }
        val raw=model.opt("fallback_api")
        // A playable inline list may omit higher tiers that the same video's fallback exposes.
        if(raw==null || (initial!=null && qualityNumber(initial.quality)>=maxQuality)) {
            return finishSelection(inline,maxQuality,false)
        }
        val supplemental=try {
            if(initial!=null && model.str("video_id").isBlank()) {
                throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"无法核对补充清晰度的视频身份")
            }
            fallbackStreams(raw,model.str("video_id"),if(initial==null) 10_000 else 3_000).filter(supported)
        } catch(error: Exception) {
            // A navigation cancellation must never turn into a successful stale playback result.
            if(RequestScope.isCancelled() || Thread.currentThread().isInterrupted || initial==null) throw error
            Diagnostics.failure("清晰度补查",error)
            return finishSelection(inline,maxQuality,false)
        }
        return finishSelection((inline+supplemental).distinctBy { it.url },maxQuality,true)
    }
    private fun inlineStreams(model: JSONObject): List<StreamInfo> {
        val inline=model.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { row ->
            try {
                val meta=row.optJSONObject("video_meta") ?: return@mapNotNull null
                val encryption=row.optJSONObject("encrypt_info") ?: return@mapNotNull null
                if(meta.str("codec_type").isBlank()) return@mapNotNull null
                val key=encryption.str("spade_a").takeIf { it.isNotBlank() }?.let(MediaCrypto::deriveKey)
                if(key==null && encryption.opt("encrypt") !in listOf(false,0,"0","false")) return@mapNotNull null
                candidate(cleanUrl(row.str("main_url")),key,meta,meta.str("definition").ifEmpty { meta.str("vheight") },cleanUrl(row.str("backup_url")))
            } catch(_: Exception) { null } // A malformed rendition must not discard other valid tiers.
        }
        return inline
    }
    private fun fallbackStreams(raw: Any,expectedVideoId: String,timeoutMillis: Long): List<StreamInfo> {
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"播放地址格式异常")
        val info=try {
            val response=JSONObject(text(fallback,timeoutMillis=timeoutMillis,metadata=false))
            val wrapper=response.optJSONObject("video_info")
            if(response.optInt("code",0)!=0 || (wrapper?.optInt("code",0) ?: 0)!=0) {
                throw PlaybackSourceException(PlaybackFailureKind.SOURCE,"补充清晰度接口暂不可用")
            }
            wrapper?.optJSONObject("data")
        } catch(e: IOException) { throw e } catch(_: Exception) { throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"播放信息格式已变化，请更新应用") }
            ?: throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"没有播放资源")
        if(expectedVideoId.isNotBlank() && info.str("video_id")!=expectedVideoId) {
            throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"补充清晰度不属于当前视频")
        }
        val videoList=info.optJSONObject("video_list") ?: throw PlaybackSourceException(PlaybackFailureKind.UNSUPPORTED,"没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k ->
            val item=videoList.optJSONObject(k) ?: return@mapNotNull null
            try {
                val seed=info.str("key_seed")
                val url=RegisteredPlayer.resolveUrl(item.str("main_url"),seed) ?: return@mapNotNull null
                val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                if(key==null && item.has("encrypt") && item.opt("encrypt") !in listOf(false,0,"0","false")) return@mapNotNull null
                val backup=runCatching {
                    RegisteredPlayer.resolveUrl(item.str("backup_url_1").ifEmpty { item.str("backup_url") },seed).orEmpty()
                }.getOrDefault("")
                val width=item.optInt("vwidth",item.optInt("width"));val height=item.optInt("vheight",item.optInt("height"))
                val label=if(width>0 && height>0) "${minOf(width,height)}p" else item.str("quality_desc").ifEmpty { item.str("definition").ifEmpty { item.str("height").ifEmpty { item.str("vheight").ifEmpty { item.str("quality").ifEmpty { k } } } } }
                candidate(url,key,item,label,backup)
            } catch(_: Exception) { null }
        }.toList()
        return rows
    }
    private fun finishSelection(rows: List<StreamInfo>,maxQuality: Int,supplemented: Boolean): StreamInfo {
        val selected=selectStream(rows,maxQuality)
        val tiers=rows.map { it.quality }.distinct().joinToString(",")
        Diagnostics.record("清晰度选择","requested=${maxQuality}P available=$tiers selected=${selected.quality} codec=${selected.codec} supplemental=$supplemented")
        return selected
    }
    private fun candidate(url: String,key: ByteArray?,meta: JSONObject,label: String,backup: String=""): StreamInfo? {
        val codec=meta.str("codec_type").lowercase()
        if(codec in setOf("bytevc2","bvc2") || (!url.startsWith("https://") && !url.startsWith("http://"))) return null
        val quality=qualityNumber(label)
        fun dimension(primary: String,secondary: String)=meta.optInt(primary,meta.optInt(secondary)).takeIf { it>0 }
        return StreamInfo(url,key,if(quality>0) "${quality}P" else "自动",codec,dimension("vwidth","width"),dimension("vheight","height"),
            meta.str("fps").ifEmpty { meta.str("frame_rate") }.toDoubleOrNull()?.takeIf { it.isFinite() && it>0 },
            listOf(backup).filter { it!=url && (it.startsWith("https://") || it.startsWith("http://")) })
    }
    private fun selectStream(rows: List<StreamInfo>,maxQuality: Int): StreamInfo {
        val valid=rows.sortedWith(compareByDescending<StreamInfo> { qualityNumber(it.quality) }.thenByDescending { it.codec in setOf("h264","avc","avc1") })
        val selected=valid.firstOrNull { qualityNumber(it.quality)<=maxQuality } ?: valid.lastOrNull()?.let { lowest -> valid.first { qualityNumber(it.quality)==qualityNumber(lowest.quality) } }
            ?: throw PlaybackSourceException(PlaybackFailureKind.UNSUPPORTED,"没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        return selected.copy(quality=selected.quality+(if(qualityNumber(selected.quality)>maxQuality) "（兼容资源）" else ""),
            availableQualities=valid.map { qualityNumber(it.quality) }.filter { it>0 }.toSet())
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw ContentProtocolException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw ContentProtocolException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw ContentProtocolException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
