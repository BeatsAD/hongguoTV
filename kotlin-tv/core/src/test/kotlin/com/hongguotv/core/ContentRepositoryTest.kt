package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun inlineModel(vararg variants: Triple<Int,String,String>): String {
        val rows=JSONArray()
        variants.forEach { (height,codec,url) -> rows.put(JSONObject().put("main_url",url)
            .put("video_meta",JSONObject().put("definition","${height}p").put("codec_type",codec))
            .put("encrypt_info",JSONObject().put("encrypt",false))) }
        return JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",JSONObject().put("video_list",rows)))).toString()
    }

    @Test fun playbackIdentityIsIndependentAndStableWithinClient() {
        val requests=mutableListOf<Request>()
        val respond: (Request)->String = { request -> requests+=request; inlineModel(Triple(720,"h264","https://example.com/video")) }
        val first=repository(respond=respond)
        first.stream("123"); first.stream("123"); repository(respond=respond).stream("123")
        for(field in listOf("iid","device_id","cdid")) {
            assertEquals(requests[0].url.queryParameter(field),requests[1].url.queryParameter(field))
            assertNotEquals(requests[0].url.queryParameter(field),requests[2].url.queryParameter(field))
        }
        assertTrue(requests[0].url.queryParameter("iid")!!.matches(Regex("3[0-9]{18}")))
        assertEquals("GET",requests[0].method)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/",requests[0].url.encodedPath)
        assertEquals("123",requests[0].url.queryParameter("video_id"))
        assertNull(requests[0].body)
        for(header in listOf("x-argus","x-gorgon","x-helios","x-medusa","x-khronos")) assertFalse(requests[0].header(header).isNullOrBlank())
    }

    @Test fun currentNestedModelsSupportObjectAndStringWithoutLosingQualityOrBackup() {
        for(asString in listOf(false,true)) {
            val entry=JSONObject(inlineModel(Triple(1080,"bytevc2","https://example.com/unsupported"),Triple(720,"h264","https://example.com/main")))
                .getJSONObject("data").getJSONObject("123")
            val model=entry.getJSONObject("video_model")
            model.getJSONArray("video_list").getJSONObject(1).put("backup_url","https://other.example/backup")
            if(asString) entry.put("video_model",model.toString())
            val body=JSONObject().put("code",0).put("data",JSONObject().put("video_model_data",JSONObject().put("123",entry)))
            var calls=0
            val result=repository { calls++;body.toString() }.stream("123",720)
            assertEquals("720P",result.quality);assertEquals("h264",result.codec)
            assertEquals("https://example.com/main",result.url);assertEquals(listOf("https://other.example/backup"),result.backupUrls)
            assertEquals(1,calls)
        }
    }

    @Test fun emptyGetUsesRegisteredSingleEpisodeWithoutReplacingCatalogIdentity() {
        var registrations=0
        val session=PlaybackDeviceSession(register={ _,_->
            registrations++;DeviceRegister.Registered("12345","67890",cookies=mapOf("ttreq" to "fixture-cookie"))
        },registerPlayer={ PlayerDevice("23456","78901","player-cdid","","") })
        val before=session.profile(OkHttpClient())
        val requests=mutableListOf<Request>()
        val reply: (Request)->String={ request ->
            requests+=request
            if(request.method=="GET") "" else inlineModel(Triple(720,"h264","https://example.com/video"))
        }
        val repo=repository(session=session,respond=reply)
        repo.stream("123");repo.stream("123");repository(session=session,respond=reply).stream("123")
        assertEquals(4,requests.size)
        assertEquals("12345",requests.first().url.queryParameter("device_id"))
        assertEquals("ttreq=fixture-cookie",requests.first().header("Cookie"))
        requests.drop(1).forEach {
            assertEquals("23456",it.url.queryParameter("device_id"))
            assertNull(it.header("Cookie"));assertEquals("POST",it.method)
            assertEquals("1967",it.url.queryParameter("aid"))
            assertEquals("/novel/player/video_model/v1/",it.url.encodedPath)
            assertFalse(it.header("x-ss-stub").isNullOrBlank())
        }
        assertEquals(1,registrations);assertEquals(before,session.profile(OkHttpClient()))
        assertTrue(session.compatibilityPlayback)
        assertFalse(Diagnostics.snapshot().contains("fixture-cookie"))
    }

    @Test fun emptyCompatibilityResultIsBoundedAndDoesNotLatchOrRotateTheDevice() {
        var registrations=0
        val session=PlaybackDeviceSession(register={ _,_->registrations++;DeviceRegister.Registered("12345","67890") },registerPlayer={ PlayerDevice("23456","78901","player-cdid","","") })
        val ids=mutableListOf<String?>()
        val repo=repository(session=session) { ids+=it.url.queryParameter("device_id");"" }
        assertThrows(EmptyContentException::class.java) { repo.stream("123") }
        val saved=session.snapshot()
        assertThrows(EmptyContentException::class.java) { repo.stream("123") }
        assertEquals(listOf("12345","23456","12345","23456"),ids)
        assertEquals(1,registrations);assertEquals(saved,session.snapshot());assertFalse(session.compatibilityPlayback)
    }

    @Test fun accessRejectionAndRateLimitingNeverFallBackToAnotherIdentity() {
        for(status in listOf(401,403,429)) {
            var calls=0
            val session=PlaybackDeviceSession(register={ _,_->DeviceRegister.Registered("12345","67890") })
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Rejected").body("".toResponseBody()).build()
            }.build()
            val repo=ContentRepository(client,playbackSession=session)
            assertThrows(IOException::class.java) { repo.stream("123") }
            assertEquals(1,calls);assertFalse(session.compatibilityPlayback)
            assertThrows(ContentRequestDeferredException::class.java) { repo.stream("123") }
            assertEquals(1,calls)
        }
    }

    @Test fun inlineModelSelectsCompatibleQualityWithoutLegacyRequest() {
        var calls=0
        val repo=repository {
            calls++
            inlineModel(Triple(1080,"bytevc2","https://example.com/unsupported"),Triple(1080,"h264","https://example.com/high"),Triple(480,"h264","https://example.com/low"))
        }
        assertEquals(StreamInfo("https://example.com/low",null,"480P",codec="h264",availableQualities=setOf(480,1080)),repo.stream("123",720))
        assertEquals(1,calls)
    }
    @Test fun inlineBackupIsKeptWithTheSameRendition() {
        val body=JSONObject(inlineModel(Triple(720,"h264","https://example.com/main")))
        body.getJSONObject("data").getJSONObject("123").getJSONObject("video_model").getJSONArray("video_list").getJSONObject(0)
            .put("backup_url","https://other.example/backup")
        val stream=repository { body.toString() }.stream("123")
        assertEquals("720P",stream.quality);assertEquals(listOf("https://other.example/backup"),stream.backupUrls)
    }

    @Test fun inlineModelSupportsStringModelAndHigherCompatibleQuality() {
        val body=JSONObject(inlineModel(Triple(1080,"h264","https://example.com/high")))
        val entry=body.getJSONObject("data").getJSONObject("123")
        entry.put("video_model",entry.getJSONObject("video_model").toString())
        assertEquals("1080P（兼容资源）",repository { body.toString() }.stream("123",720).quality)
    }

    @Test fun equalQualityPrefersAvcEvenWhenOnlyHigherResolutionIsAvailable() {
        val repo=repository { inlineModel(Triple(1080,"hevc","https://example.com/hevc"),Triple(1080,"h264","https://example.com/avc")) }
        assertEquals("https://example.com/avc",repo.stream("123",720).url)
    }

    @Test fun responseCannotSubstituteAnUnrequestedEpisode() {
        val response=inlineModel(Triple(720,"h264","https://example.com/other")).replace("\"123\"","\"456\"")
        assertEquals("播放信息没有对应剧集",assertThrows(IOException::class.java) { repository { response }.stream("123") }.message)
        val nested=JSONObject().put("data",JSONObject().put("video_model_data",JSONObject(response).getJSONObject("data")))
        assertEquals("播放信息没有对应剧集",assertThrows(IOException::class.java) { repository { nested.toString() }.stream("123") }.message)
    }

    @Test fun emptyAndRejectedModelsExplainUpstreamFailure() {
        assertEquals("兼容播放接口返回空响应，请稍后重试",assertThrows(IOException::class.java) { repository { " " }.stream("123") }.message)
        assertEquals("播放接口暂不可用（错误码 110001）",assertThrows(IOException::class.java) { repository { """{"code":110001}""" }.stream("123") }.message)
    }

    @Test fun emptyPlaybackTriesBoundedRoutesAndCanRecover() {
        var calls=0
        val repo=repository { calls++; if(calls==1) "" else inlineModel(Triple(720,"h264","https://example.com/good")) }
        assertEquals("720P",repo.stream("123").quality)
        assertEquals(2,calls)
        calls=0
        assertThrows(EmptyContentException::class.java) { repository { calls++; "" }.stream("123") }
        assertEquals(2,calls)
        calls=0
        assertThrows(PlaybackSourceException::class.java) { repository { calls++; """{"code":110001}""" }.stream("123") }
        assertEquals(1,calls)
    }
    @Test fun encryptedInlineResourceWithoutKeyIsNotTreatedAsClearVideo() {
        val body=inlineModel(Triple(720,"h264","https://example.com/encrypted")).replace("\"encrypt\":false","\"encrypt\":true")
        assertThrows(IOException::class.java) { repository { body }.stream("123") }
    }

    @Test fun malformedEncryptedTierDoesNotDiscardAHealthyTier() {
        val body=JSONObject(inlineModel(Triple(1080,"h264","https://example.com/bad"),Triple(720,"h264","https://example.com/good")))
        body.getJSONObject("data").getJSONObject("123").getJSONObject("video_model").getJSONArray("video_list")
            .getJSONObject(0).getJSONObject("encrypt_info").put("spade_a","invalid-base64!")
        assertEquals("https://example.com/good",repository { body.toString() }.stream("123").url)
    }

    @Test fun decoderPredicateReceivesDimensionsAndCanSelectLowerTier() {
        val body=JSONObject(inlineModel(Triple(1080,"h264","https://example.com/large"),Triple(720,"h264","https://example.com/small")))
        val rows=body.getJSONObject("data").getJSONObject("123").getJSONObject("video_model").getJSONArray("video_list")
        rows.getJSONObject(0).getJSONObject("video_meta").put("vwidth",1080).put("vheight",1920).put("fps",30)
        rows.getJSONObject(1).getJSONObject("video_meta").put("vwidth",720).put("vheight",1280).put("fps",25)
        val seen=mutableListOf<StreamInfo>()
        val selected=repository { body.toString() }.stream("123") { stream -> seen+=stream; stream.height!!<=1280 }
        assertEquals("https://example.com/small",selected.url)
        assertEquals(720,selected.width); assertEquals(1280,selected.height); assertEquals(25.0,selected.frameRate!!,0.0)
        assertEquals(2,seen.size)
        assertEquals(setOf(720),selected.availableQualities)
    }

    @Test fun allDeviceRejectedTiersStopWithoutNetworkRetries() {
        val error=assertThrows(PlaybackSourceException::class.java) {
            repository { inlineModel(Triple(1080,"h264","https://example.com/large")) }.stream("123") { false }
        }
        assertEquals(PlaybackFailureKind.UNSUPPORTED,error.kind)
        assertFalse(PlaybackErrors.retryable(error))
    }

    @Test fun rejectedApiAndInvalidJsonAreNotTemporaryNetworkFailures() {
        val rejected=assertThrows(PlaybackSourceException::class.java) { repository { """{"code":110001}""" }.stream("123") }
        val malformed=assertThrows(PlaybackSourceException::class.java) { repository { "<html>error</html>" }.stream("123") }
        assertFalse(PlaybackErrors.retryable(rejected)); assertFalse(PlaybackErrors.retryable(malformed))
        assertEquals(PlaybackFailureKind.FORMAT,malformed.kind)
    }

    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis,session: PlaybackDeviceSession?=null, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock,playbackSession=session ?: PlaybackDeviceSession(register={ _,_->
            DeviceRegister.Registered("3"+(1..18).joinToString("") { kotlin.random.Random.nextInt(10).toString() },"3"+(1..18).joinToString("") { kotlin.random.Random.nextInt(10).toString() })
        },registerPlayer={ PlayerDevice("23456","78901","player-cdid","","") })
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(2,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) {
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(1,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(3,requests)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { request -> requests++
            if(request.url.host=="hongguoduanju.com") """{"searchList":[{"doc_type":23,"video_data":{"series_id":"456","series_title":"真人剧","genre":"203"}}],"pageNum":1,"totalPages":1,"hasMore":false}"""
            else """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}"""
        }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(2,requests)
    }

    @Test fun missingComicTabDoesNotAcceptUnclassifiedWebsiteData() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}""" }
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
        assertEquals(2,requests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            """{"searchList":[{"doc_type":23,"video_data":{"series_id":"456","series_title":"测试短剧","genre":"203"}}],"pageNum":1,"totalPages":1,"hasMore":false}"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(listOf("/reading/bookapi/search/tab/v","/api/search/result"),paths)
    }
}
