// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.*

class CatalogUiState(var query: CategoryQuery) {
    var items=emptyList<Series>()
    var dimensions=emptyList<FilterDimension>()
    var cursor: CatalogCursor?=null
    var hasMore=true
    var busy=false
    var error=""
    var expanded=false
    var anchor=PosterGridView.Anchor()
    var updatedAt=0L
    fun reset(next: CategoryQuery) { if(query!=next) items=emptyList();query=next;cursor=null;hasMore=true;error="";anchor=PosterGridView.Anchor() }
}
class RankingUiState {
    var query=RankQuery()
    var entries=emptyList<RankEntry>()
    var groups=emptyList<RankGroup>()
    var cursor: RankCursor?=null
    var hasMore=true
    var busy=false
    var error=""
    var expanded=false
    var anchor=PosterGridView.Anchor()
}
class SearchUiState {
    // null means a bounded aggregate of the two verified search sources.
    var scope: ContentType?=null
    var query=""
    var items=emptyList<Series>()
    val pages=mutableMapOf<ContentType,Int>()
    val more=mutableMapOf<ContentType,Boolean>()
    var busy=false
    var error=""
    var anchor=PosterGridView.Anchor()
}
