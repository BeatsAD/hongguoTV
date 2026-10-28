package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RankingQueryTest {
    private val other=FilterDimension("other","综合",1,listOf(FilterOption("","总榜"),FilterOption("gender_female","女频"),FilterOption("gender_male","男频")))
    private val theme=FilterDimension("theme","主题",2,listOf(FilterOption("cate_11","乡村"),FilterOption("cate_127","职场")))

    @Test fun `total empty ID clears the global selection and returns to the same query`() {
        val total=RankQuery("all","ranklist_hot_play_sc")
        val female=total.choose(other,"gender_female",true)
        assertEquals(listOf("gender_female"),female.panel)
        assertEquals(total,female.choose(other,"",true))
        assertEquals(total,female.choose(other,null,true))
        assertEquals(total,total.choose(theme,"cate_11",true).choose(other,"",true))
    }

    @Test fun `row reset preserves other dimensions when the board allows combinations`() {
        val selected=RankQuery(panel=listOf("gender_female","cate_11"))
        assertEquals(listOf("cate_11"),selected.choose(other,"",false).panel)
        assertEquals(listOf("gender_female"),selected.choose(theme,null,false).panel)
        assertEquals(listOf("cate_11","gender_male"),selected.choose(other,"gender_male",false).panel)
        assertEquals(listOf("gender_female","cate_11","cate_127"),selected.choose(theme,"cate_127",false).panel)
        assertEquals(listOf("gender_female"),selected.choose(theme,"cate_11",false).panel)
        assertThrows(IllegalArgumentException::class.java) { selected.choose(other,"unknown",true) }
    }

    @Test fun `total request accepts the default response but a genuinely mismatched filter is rejected`() {
        val raw=checkNotNull(javaClass.getResource("/lists/rank-all-ranklist_hot_play_sc.json")).readText()
        val root=JSONObject(raw)
        val groups=root.getJSONObject("data").getJSONObject("cell_view").getJSONObject("cell_selector").getJSONObject("outer_row").getJSONArray("items")
        for(i in 0 until groups.length()) {
            val group=groups.getJSONObject(i)
            if(!group.optBoolean("is_selected")) continue
            val boards=group.getJSONObject("sub_cell_selector").getJSONObject("outer_row").getJSONArray("items")
            for(j in 0 until boards.length()) {
                val board=boards.getJSONObject(j)
                if(board.optBoolean("is_selected")) board.put("panel_selector",JSONObject("""{"global_single":true,"inner_rows":[{"type":"other","row_name":"综合","selection_type":1,"items":[{"selector_item_id":"","show_name":"总榜","is_selected":true}]}]}"""))
            }
        }
        val query=RankQuery("all","ranklist_hot_play_sc",listOf("gender_female")).choose(other,"",true)
        var sentPanel: String?=null
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            sentPanel=chain.request().url.queryParameter("panel_selected_items")
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null,root.toString())).build()
        }.build()
        val page=RankingRepository(ContentRepository(http)).load(query)
        assertEquals("",sentPanel)
        assertEquals(query,page.query)
        assertTrue(page.entries.isNotEmpty())
        assertTrue(page.groups.single { it.selected }.boards.single { it.selected }.panel.single().options.single().selected)
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(root.toString(),query.copy(panel=listOf("gender_female"))) }
        assertThrows(ListProtocolException::class.java) { RankingRepository.parse(root.toString(),query.copy(panel=listOf(""))) }
    }
}
