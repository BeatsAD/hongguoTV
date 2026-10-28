// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.widget.*
import com.hongguotv.core.FilterDimension

/** Bounded expanded controls; every dimension retains its own upstream ID space. */
class FilterPanelView(context: Context,rows: List<FilterDimension>,selected: Map<String,List<String>>,forceSingle: Boolean=false,choose: (FilterDimension,String?)->Unit): ScrollView(context) {
    init {
        val ui=BrowseUi(context);val body=ui.column();setBackgroundColor(TvStyle.surface)
        clipChildren=true;clipToPadding=true;clipToOutline=true
        body.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8))
        isVerticalScrollBarEnabled=false
        rows.forEach { row ->
            val line=ui.row().apply { gravity=android.view.Gravity.TOP }
            line.addView(ui.text(row.label.removePrefix("全部"),12f,TvStyle.muted).apply { setPadding(0,ui.dp(7),0,0) },LinearLayout.LayoutParams(ui.dp(50),-2))
            val options=BrowseFlow(context,5)
            if(row.options.none { it.id.isEmpty() }) options.addView(BrowseButton(context,"全部",BrowseButton.Role.FILTER,selected[row.id].isNullOrEmpty()) { choose(row,null) }.apply { tag="filter:${row.id}:all";if(forceSingle || !row.multiple) autoSelect(RemoteFocus.NETWORK_DELAY) })
            row.options.forEach { option -> options.addView(BrowseButton(context,option.label,BrowseButton.Role.FILTER,if(option.id.isEmpty()) selected[row.id].isNullOrEmpty() else option.id in selected[row.id].orEmpty()) { choose(row,option.id.takeIf { it.isNotEmpty() }) }.apply { tag="filter:${row.id}:${option.id}";if(forceSingle || !row.multiple) autoSelect(RemoteFocus.NETWORK_DELAY) }) }
            line.addView(options,LinearLayout.LayoutParams(0,-2,1f))
            body.addView(line,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(8) })
        }
        addView(body)
    }
}
