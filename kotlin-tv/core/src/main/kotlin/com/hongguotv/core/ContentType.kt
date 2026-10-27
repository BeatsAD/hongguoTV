// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

enum class ContentType(val storedValue: String, val label: String, val searchTab: Int?, val genre: String) {
    COMIC("comic","漫剧",19,"comic_series"),
    SHORT("short","真人剧",11,"short_play"),
    AI("ai","AI剧",null,"ai_series");

    companion object {
        fun known(value: String?) = entries.firstOrNull { it.storedValue==value }
        fun fromStored(value: String?) = known(value) ?: COMIC
    }
}
