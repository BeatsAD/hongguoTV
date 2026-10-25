// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

enum class Screen { CATALOG, DETAIL, PLAYER }

enum class LibrarySection(val tab: Int,val label: String) {
    FAVORITES(3,"收藏"), LATER(6,"稍后看"), HISTORY(4,"最近观看");
    companion object { fun fromTab(tab: Int)=entries.firstOrNull { it.tab==tab } }
}

/** Explicit row boundaries keep the remote out of gaps in the final, incomplete row. */
object TvGridFocus {
    fun left(index: Int,columns: Int)=if(index%columns==0) index else index-1
    fun right(index: Int,size: Int,columns: Int)=if(index%columns==columns-1 || index==size-1) index else index+1
    fun down(index: Int,size: Int,columns: Int): Int? = when {
        index+columns<size -> index+columns
        index/columns<(size-1)/columns -> size-1
        else -> null
    }
}
