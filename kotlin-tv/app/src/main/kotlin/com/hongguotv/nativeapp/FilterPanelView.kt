// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.widget.*
import com.hongguotv.core.FilterDimension

/** Bounded expanded controls; every dimension retains its own upstream ID space. */
class FilterPanelView(context: Context,rows: List<FilterDimension>,selected: Map<String,List<String>>,choose: (FilterDimension,String?)->Unit): ScrollView(context) {
    init {
        val ui=BrowseUi(context);val body=ui.column();setBackgroundColor(TvStyle.surface)
        body.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8))
        rows.forEach { row ->
            body.addView(ui.text(row.label,12f,TvStyle.muted))
            val options=listOf(CapsuleButton(context,"全部",selected[row.id].isNullOrEmpty()) { choose(row,null) }.apply { tag="filter:${row.id}:all" })+row.options.map { option -> CapsuleButton(context,option.label,option.id in selected[row.id].orEmpty()) { choose(row,option.id) }.apply { tag="filter:${row.id}:${option.id}" } }
            // A horizontal selector is scrollable even for unusually large upstream dictionaries.
            ui.line(body,options)
        }
        addView(body)
    }
}
