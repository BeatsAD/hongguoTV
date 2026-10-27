// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

class RankingRepository(repository: ContentRepository) {
    private val api=ListApiProfile(repository)
    private val sessions=linkedMapOf<String,List<RankGroup>>()
    fun load(query: RankQuery,cursor: RankCursor?=null,shown: List<String> = emptyList()): RankPage {
        require(cursor==null || cursor.fingerprint==query.fingerprint)
        val params=mapOf("tab_type" to if(cursor==null) "26" else "18","client_req_type" to "2","client_template" to "2","background_selected_items" to "","category_id" to "0","limit" to "20","cell_sub_id" to "0","gender" to "2","cell_id" to "7470092475068071998","panel_selected_items" to query.panel.joinToString(","),"offset" to (cursor?.offset ?: 0).toString(),"session_id" to cursor?.session.orEmpty(),"filter_ids" to shown.joinToString(","),"unlimited_selector_change_type" to "0","selected_items" to query.top,"sub_selected_items" to query.sub)
        val raw=api.request("/reading/bookapi/bookmall/cell/change/v",params)
        val known=synchronized(sessions) { sessions[query.fingerprint].orEmpty() }
        val page=parse(raw,query,cursor,known)
        synchronized(sessions) { sessions[page.query.fingerprint]=page.groups;while(sessions.size>3) sessions.remove(sessions.keys.first()) }
        return page
    }
    companion object {
        fun parse(raw: String,requested: RankQuery,continuation: RankCursor?=null,knownGroups: List<RankGroup> = emptyList()): RankPage {
            val data=ListCards.data(raw); val cell=data.optJSONObject("cell_view") ?: throw ListProtocolException("榜单响应缺少列表")
            val selector=cell.optJSONObject("cell_selector")
            val source=if(data.has("next_offset")) data else cell
            if(continuation!=null && (continuation.fingerprint!=requested.fingerprint || source.str("session_id")!=continuation.session)) throw ListProtocolException("榜单续页会话不符，请重新加载")
            if(selector==null && (continuation==null || knownGroups.isEmpty())) throw ListProtocolException("榜单响应缺少选中路径")
            val groups=if(selector==null) knownGroups else selector.optJSONObject("outer_row")?.optJSONArray("items")?.objects().orEmpty().map { top ->
                val boards=top.optJSONObject("sub_cell_selector")?.optJSONObject("outer_row")?.optJSONArray("items")?.objects().orEmpty().map { sub ->
                    val panel=sub.optJSONObject("panel_selector")
                    RankBoard(sub.str("selector_item_id"),sub.str("show_name"),sub.optBoolean("is_selected"),sub.str("sub_title"),ListCards.dimensions(panel?.optJSONArray("inner_rows")),panel?.optBoolean("global_single",true) ?: true)
                }
                // Some current six-entry selectors expose an actor leaf without sub CellSelector.
                val leaf=if(boards.isEmpty() && top.optJSONObject("sub_cell_selector")==null) listOf(RankBoard("",top.str("show_name"),top.optBoolean("is_selected"),top.str("sub_title"),ListCards.dimensions(top.optJSONObject("panel_selector")?.optJSONArray("inner_rows")),true)) else boards
                RankGroup(top.str("selector_item_id"),top.str("show_name"),top.optBoolean("is_selected"),leaf)
            }
            val top=groups.singleOrNull { it.selected } ?: throw ListProtocolException("榜单选中入口异常")
            val sub=top.boards.singleOrNull { it.selected } ?: throw ListProtocolException("榜单选中子榜异常")
            val panel=sub.panel.flatMap { it.options }.filter { it.selected && it.id.isNotEmpty() }.map { it.id }
            if(top.id!=requested.top || (requested.sub.isNotBlank() && sub.id!=requested.sub) || panel.toSet()!=requested.panel.toSet()) throw ListProtocolException("平台返回了其他榜单，已拒绝替换当前结果")
            if(sub.globalSingle && requested.panel.size>1) throw ListProtocolException("该榜单仅支持全局单选")
            val actual=requested.copy(sub=sub.id)
            val rows=cell.optJSONArray("cell_data") ?: throw ListProtocolException("榜单条目缺失")
            val entries=rows.objects().flatMap { row ->
                val actors=row.optJSONArray("ugc_user_data")?.objects().orEmpty().mapNotNull { a ->
                    val base=a.optJSONObject("user_info")?.optJSONObject("base_info") ?: return@mapNotNull null
                    val id=base.str("actor_id_str").ifBlank { base.str("actor_id") }
                    if(!id.matches(Regex("[0-9]{1,30}")) || base.str("user_name").isBlank()) return@mapNotNull null
                    RankEntry.Actor(id,base.str("user_name"),cleanUrl(base.str("user_avatar")).replace("http://","https://"),a.str("intro"),a.optJSONObject("rec_text_item")?.str("RecommendText").orEmpty())
                }
                val videos=row.optJSONArray("video_data")?.objects().orEmpty().mapNotNull { v ->
                    val type=when(top.id) { "comic_series_rank"->ContentType.COMIC; "human"->ContentType.SHORT; "ai_playlet"->ContentType.AI; else->null }
                    val series=ListCards.series(v,type)
                    if(!ListCards.valid(series)) null else {
                        val rank=runCatching { JSONObject(v.str("recommend_info")).str("rank").toIntOrNull() }.getOrNull()?.takeIf { it>0 }
                        RankEntry.Drama(series,rank,v.optJSONObject("rec_text_item")?.str("RecommendText").orEmpty(),top.id=="series_album")
                    }
                }
                actors+videos
            }.distinctBy { it.javaClass.simpleName+it.id }
            if(rows.length()>0 && entries.isEmpty()) throw ListProtocolException("榜单条目格式已变化")
            if(!source.has("has_more") || !source.has("next_offset")) throw ListProtocolException("榜单分页缺失")
            val offset=source.optInt("next_offset",-1);val session=source.str("session_id");val more=source.optBoolean("has_more")
            if(offset<0 || (more && session.isBlank())) throw ListProtocolException("榜单游标无效")
            return RankPage(entries,groups,actual,RankCursor(offset,session,actual.fingerprint),more)
        }
    }
}
