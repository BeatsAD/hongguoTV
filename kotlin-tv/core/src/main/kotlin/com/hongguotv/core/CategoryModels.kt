// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ListProtocolException(message: String): IOException(message)
data class FilterOption(val id: String,val label: String,val selected: Boolean=false)
data class FilterDimension(val id: String,val label: String,val selectionType: Int,val options: List<FilterOption>) {
    val multiple get()=selectionType==2
}
data class CategoryQuery(val type: ContentType,val selections: Map<String,List<String>> = emptyMap()) {
    val fingerprint get()=type.genre+selections.toSortedMap().entries.joinToString { (k,v) -> "$k=${v.sorted().joinToString(",")}" }
    fun choose(row: FilterDimension,id: String?): CategoryQuery {
        val values=selections.toMutableMap()
        if(id==null) values.remove(row.id) else {
            require(row.options.any { it.id==id })
            val prior=values[row.id].orEmpty()
            values[row.id]=if(row.multiple) { if(id in prior) prior-id else prior+id } else listOf(id)
        }
        return copy(selections=values.filterValues { it.isNotEmpty() })
    }
}
data class CatalogCursor(val offset: Int,val session: String,val fingerprint: String)
data class CategoryPage(val items: List<Series>,val dimensions: List<FilterDimension>,val cursor: CatalogCursor,val hasMore: Boolean)

/** Both cursor and unique IDs must progress before an automatic append is allowed. */
object ListProgress {
    fun <T> merge(old: List<T>,next: List<T>,key: (T)->String)= (old+next).distinctBy(key)
    fun stalled(priorOffset: Int,nextOffset: Int,added: Int,hasMore: Boolean)=hasMore && (nextOffset<=priorOffset || added==0)
    const val MAX_ITEMS=2400
}

internal object ListCards {
    fun series(o: JSONObject,type: ContentType?=null): Series {
        val categories=runCatching { JSONArray(o.str("category_schema")) }.getOrNull()?.objects()?.map { it.str("name") }.orEmpty()
        val subtitles=o.optJSONArray("sub_title_list")?.objects().orEmpty()
        val tags=(categories.ifEmpty { subtitles.filter { it.optInt("data_type")==3 }.map { it.str("content") } }).filter(String::isNotBlank).distinct()
        val explicitType=when(o.str("video_category_type")) { "ai_video" -> ContentType.AI; else -> when(subtitles.firstOrNull { it.optInt("data_type")==32 }?.str("content")) { "AI剧" -> ContentType.AI; "漫剧" -> ContentType.COMIC; "真人剧" -> ContentType.SHORT; else -> null } }
        val count=o.optInt("episode_cnt").takeIf { it>0 }
        return Series(o.str("series_id"),o.str("title"),cleanUrl(o.str("cover")),o.str("video_desc"),count?.let { "全${it}集" }.orEmpty(),tags.joinToString(" · "),explicitType ?: type,count,tags,!o.optBoolean("disable_play",false) && count!=null)
    }
    fun valid(series: Series)=series.id.matches(Regex("[0-9]{1,30}")) && series.title.isNotBlank()
    fun dimensions(rows: JSONArray?): List<FilterDimension> = rows?.objects().orEmpty().map { row ->
        FilterDimension(row.str("type"),row.str("row_name"),row.optInt("selection_type",1),row.optJSONArray("items")?.objects().orEmpty().map { FilterOption(it.str("selector_item_id"),it.str("show_name"),it.optBoolean("is_selected")) })
    }
    fun data(raw: String): JSONObject {
        if(raw.isBlank()) throw ListProtocolException("内容服务返回空响应")
        val root=try { JSONObject(raw) } catch(e: Exception) { throw ListProtocolException("内容响应结构异常") }
        Diagnostics.record("内容解析","businessCode=${root.optInt("code",-1)} hasData=${root.optJSONObject("data")!=null}")
        if(!root.has("code") || root.optInt("code",-1)!=0) throw ListProtocolException("内容服务拒绝了本次请求")
        return root.optJSONObject("data") ?: throw ListProtocolException("内容响应缺少数据")
    }
}
