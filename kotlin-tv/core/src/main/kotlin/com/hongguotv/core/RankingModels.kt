// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class RankQuery(val top: String="all",val sub: String="",val panel: List<String> = emptyList()) {
    val fingerprint get()="$top/$sub/${panel.sorted().joinToString(",")}"
    fun choose(row: FilterDimension,id: String?,globalSingle: Boolean): RankQuery {
        // The upstream empty-ID option (总榜) means no filter, not a selected ID.
        if(id.isNullOrEmpty()) return copy(panel=if(globalSingle) emptyList() else panel.filter { current -> row.options.none { it.id==current } })
        require(row.options.any { it.id==id })
        val selected=if(globalSingle) listOf(id) else if(row.multiple) {
            if(id in panel) panel-id else panel+id
        } else panel.filter { current -> row.options.none { it.id==current } }+id
        return copy(panel=selected)
    }
}
data class RankCursor(val offset: Int,val session: String,val fingerprint: String)
data class RankBoard(val id: String,val label: String,val selected: Boolean,val subtitle: String,val panel: List<FilterDimension>,val globalSingle: Boolean)
data class RankGroup(val id: String,val label: String,val selected: Boolean,val boards: List<RankBoard>)
sealed class RankEntry {
    abstract val id: String
    abstract val rank: Int?
    data class Drama(val series: Series,override val rank: Int?,val heat: String,val album: Boolean=false): RankEntry() { override val id get()=series.id }
    data class Actor(override val id: String,val name: String,val avatar: String,val description: String,val heat: String,override val rank: Int?=null): RankEntry()
}
data class RankPage(val entries: List<RankEntry>,val groups: List<RankGroup>,val query: RankQuery,val cursor: RankCursor,val hasMore: Boolean)
