package com.hongguotv.core

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ListProtocolTest {
    private fun fixture(name: String)=checkNotNull(javaClass.getResource("/lists/$name")).readText()
    @Test fun `three dictionaries retain separate ids and sparse AI cursor`() {
        val pages=ContentType.entries.associateWith { type -> CategoryRepository.parse(fixture("category-${type.genre}.json"),CategoryQuery(type)) }
        pages.forEach { (type,page) ->
            assertTrue(page.items.isNotEmpty());assertTrue(page.dimensions.isNotEmpty());assertTrue(page.items.all { it.contentType==type })
            assertTrue(page.items.all { it.id.length>16 });assertTrue(page.dimensions.none { it.id=="genre" })
        }
        val ai=pages.getValue(ContentType.AI)
        assertEquals(14,ai.items.size);assertEquals(18,ai.cursor.offset)
        val body=CategoryRepository.requestBody(CategoryQuery(ContentType.AI),ai.cursor,ai.items.map { it.id })
        assertEquals(18,body.getInt("offset"));assertEquals(ai.cursor.session,body.getString("session_id"))
        assertEquals(ai.items.map { it.id }.joinToString(","),body.getString("filter_ids"))
    }
    @Test fun `single multi all and query-bound cursor`() {
        val row=FilterDimension("theme","主题",2,listOf(FilterOption("a","A"),FilterOption("b","B")))
        var query=CategoryQuery(ContentType.SHORT).choose(row,"a").choose(row,"b")
        assertEquals(listOf("a","b"),query.selections["theme"])
        query=query.choose(row,"a");assertEquals(listOf("b"),query.selections["theme"])
        assertTrue(query.choose(row,null).selections.isEmpty())
        assertEquals(listOf("b"),CategoryQuery(ContentType.SHORT).choose(row.copy(selectionType=1),"a").choose(row.copy(selectionType=1),"b").selections["theme"])
        assertThrows(IllegalArgumentException::class.java) { CategoryRepository.requestBody(query,CatalogCursor(18,"s","wrong"),emptyList()) }
    }
    @Test fun `dictionary responses never establish a content pagination session`() {
        val bodies=mutableListOf<JSONObject>()
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer);bodies+=JSONObject(buffer.readUtf8())
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null,fixture("category-short_play.json"))).build()
        }.build()
        val repo=CategoryRepository(ContentRepository(http))
        val query=CategoryQuery(ContentType.SHORT,mapOf("sort" to listOf("hot_score")))
        repo.load(CategoryQuery(ContentType.SHORT));repo.load(query);repo.load(query)
        assertEquals(listOf("default","only_content","only_content","only_content"),bodies.map { it.getString("req_type") })
        assertTrue(bodies[0].getBoolean("need_selector_panel"));assertFalse(bodies[1].getBoolean("need_selector_panel"))
        assertEquals("hot_score",bodies[2].getJSONObject("select_items").getJSONArray("sort").getString(0))
    }
    @Test fun `valid empty is different from blank body refusal or missing list`() {
        val query=CategoryQuery(ContentType.AI)
        assertTrue(CategoryRepository.parse("""{"code":0,"data":{"video_data":[],"has_more":false,"next_offset":0}}""",query).items.isEmpty())
        for(value in listOf("","{}","{","""{"code":1,"data":{}}""","""{"code":0,"data":{"has_more":false,"next_offset":0}}""")) {
            assertThrows(ListProtocolException::class.java) { CategoryRepository.parse(value,query) }
        }
    }
    @Test fun `all 28 recorded boards preserve selection and special entry types`() {
        val manifest=JSONArray(fixture("rank-manifest.json"));assertEquals(28,manifest.length())
        val seen=mutableSetOf<String>();var actors=false;var album=false;var upcoming=false
        for(i in 0 until manifest.length()) {
            val row=manifest.getJSONObject(i);val query=RankQuery(row.getString("selected_items"),row.getString("sub_selected_items"))
            val page=RankingRepository.parse(fixture(row.getString("file")),query)
            assertEquals(query,page.query);assertEquals(query.fingerprint,page.cursor.fingerprint);assertEquals(6,page.groups.size)
            assertTrue(page.entries.isNotEmpty());seen+=query.top
            actors=actors || page.entries.any { it is RankEntry.Actor }
            album=album || page.entries.any { it is RankEntry.Drama && it.album }
            upcoming=upcoming || page.entries.any { it is RankEntry.Drama && !it.series.playable }
        }
        assertEquals(6,seen.size);assertTrue(actors);assertTrue(album);assertTrue(upcoming)
    }
    @Test fun `recommendation telemetry rank is not advertised as an official position`() {
        val raw=fixture("rank-all-ranklist_hot_play_sc.json")
        val query=RankQuery("all","ranklist_hot_play_sc")
        assertNull((RankingRepository.parse(raw,query).entries.first() as RankEntry.Drama).rank)
        val root=JSONObject(raw);root.getJSONObject("data").getJSONObject("cell_view").getJSONArray("cell_data").getJSONObject(0).getJSONArray("video_data").getJSONObject(0).put("rank",7)
        assertEquals(7,(RankingRepository.parse(root.toString(),query).entries.first() as RankEntry.Drama).rank)
    }
    @Test fun `HTTP success with another selected path is rejected`() {
        val raw=fixture("rank-all-ranklist_hot_play_sc.json")
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(raw,RankQuery("human","human_hot_play")) }
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(raw,RankQuery("all","ranklist_hot_play_sc",listOf("cate_11"))) }
        val root=JSONObject(raw);root.getJSONObject("data").getJSONObject("cell_view").remove("cell_selector")
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(root.toString(),RankQuery()) }
    }
    @Test fun `continuation without selectors requires the verified session and query`() {
        val first=RankingRepository.parse(fixture("rank-all-ranklist_hot_play_sc.json"),RankQuery("all","ranklist_hot_play_sc"))
        val page=RankingRepository.parse(fixture("rank-page2.json"),first.query,first.cursor,first.groups)
        assertTrue(page.entries.isNotEmpty());assertEquals(first.groups,page.groups)
        assertTrue(page.cursor.offset>first.cursor.offset)
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(fixture("rank-page2.json"),first.query) }
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(fixture("rank-page2.json"),first.query,first.cursor.copy(session="other"),first.groups) }
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(fixture("rank-page2.json"),first.query.copy(top="human"),first.cursor,first.groups) }
    }
    @Test fun `dynamic actor leaf is usable but does not silently accept a requested sub board`() {
        val page=RankingRepository.parse(fixture("rank-actor-leaf.json"),RankQuery("ranklist_celebrity"))
        assertTrue(page.entries.all { it is RankEntry.Actor });assertEquals("",page.query.sub)
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(fixture("rank-actor-leaf.json"),RankQuery("ranklist_celebrity","ranklist_actor_fans")) }
    }
    @Test fun `dedup and non progressing batches stop automatic append`() {
        assertEquals(listOf("a","b","c"),ListProgress.merge(listOf("a","b"),listOf("b","c")) { it })
        assertTrue(ListProgress.stalled(18,18,2,true));assertTrue(ListProgress.stalled(18,36,0,true))
        assertFalse(ListProgress.stalled(18,36,1,true));assertFalse(ListProgress.stalled(18,18,0,false))
    }
    @Test fun `unknown metadata stays unknown and AI cannot use a made up search tab`() {
        val old=Series.fromJson(JSONObject("""{"id":"123","title":"旧记录"}"""))
        assertNull(old.contentType);assertNull(old.episodeCount);assertTrue(old.playable)
        val ai=old.copy(contentType=ContentType.AI,episodeCount=14,tagNames=listOf("科幻"))
        assertEquals(ai,Series.fromJson(ai.toJson()));assertNull(ContentType.AI.searchTab)
        assertThrows(java.io.IOException::class.java) { ContentRepository().search("测试",type=ContentType.AI) }
    }
}
