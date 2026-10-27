// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class RankQuery(val top: String="all",val sub: String="",val panel: List<String> = emptyList()) {
    val fingerprint get()="$top/$sub/${panel.sorted().joinToString(",")}"
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
