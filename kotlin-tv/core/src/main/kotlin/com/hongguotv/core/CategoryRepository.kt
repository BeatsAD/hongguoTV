// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

class CategoryRepository(repository: ContentRepository) {
    private val api=ListApiProfile(repository)
    private val dictionaries=mutableMapOf<ContentType,List<FilterDimension>>()
    fun load(query: CategoryQuery,cursor: CatalogCursor?=null,shown: List<String> = emptyList()): CategoryPage {
        require(cursor==null || cursor.fingerprint==query.fingerprint) { "分类游标与条件不符" }
        val dictionary=synchronized(dictionaries) { dictionaries[query.type] }
        if(cursor==null && dictionary==null) {
            val initialQuery=CategoryQuery(query.type)
            val initial=parse(api.request("/reading/distribution/category/landpage/v/",body=requestBody(initialQuery,null,emptyList(),true).toString()),initialQuery)
            synchronized(dictionaries) { dictionaries[query.type]=initial.dimensions }
        }
        val body=requestBody(query,cursor,shown)
        val page=parse(api.request("/reading/distribution/category/landpage/v/",body=body.toString()),query)
        synchronized(dictionaries) {
            if(page.dimensions.isNotEmpty()) dictionaries[query.type]=page.dimensions
            return page.copy(dimensions=page.dimensions.ifEmpty { dictionaries[query.type].orEmpty() })
        }
    }
    companion object {
        fun requestBody(query: CategoryQuery,cursor: CatalogCursor?,shown: List<String>,selector: Boolean=false): JSONObject {
            require(cursor==null || cursor.fingerprint==query.fingerprint)
            val selected=JSONObject()
            listOf("category_dim_epoch","online_time","gender","category_dim_role","sort","category_dim_theme").forEach { selected.put(it,JSONArray()) }
            query.selections.forEach { (key,items) -> require(key!="genre"); selected.put(key,JSONArray(items)) }
            selected.put("genre",JSONArray(listOf(query.type.genre)))
            return JSONObject().put("filter_ids",shown.joinToString(",")).put("req_scene",if(query.type==ContentType.SHORT) "default" else query.type.genre)
                .put("offset",cursor?.offset ?: 0).put("limit",18).put("need_selector_panel",selector).put("select_items",selected)
                .put("session_id",cursor?.session.orEmpty()).put("req_type",if(selector) "default" else "only_content").put("client_req_type",3)
        }
        fun parse(raw: String,query: CategoryQuery): CategoryPage {
            val data=ListCards.data(raw)
            val rows=data.optJSONArray("video_data") ?: throw ListProtocolException("分类响应缺少剧集列表")
            if(!data.has("has_more") || !data.has("next_offset")) throw ListProtocolException("分类分页信息缺失")
            val offset=data.optInt("next_offset",-1); val session=data.str("session_id"); val more=data.optBoolean("has_more")
            if(offset<0 || (more && session.isBlank())) throw ListProtocolException("分类游标无效")
            val items=rows.objects().map { ListCards.series(it,query.type) }.filter(ListCards::valid).distinctBy { it.id }
            if(rows.length()>0 && items.isEmpty()) throw ListProtocolException("分类条目格式已变化")
            return CategoryPage(items,ListCards.dimensions(data.optJSONArray("selector_rows")).filter { it.id!="genre" },CatalogCursor(offset,session,query.fingerprint),more)
        }
    }
}
