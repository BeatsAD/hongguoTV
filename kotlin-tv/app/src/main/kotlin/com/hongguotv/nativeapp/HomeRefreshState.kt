// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** One explicit refresh batch, independent of automatic pagination and response order. */
internal class HomeRefreshState {
    private val pending=linkedSetOf<String>()
    private val failures=linkedSetOf<String>()
    private var total=0
    val running get()=pending.isNotEmpty()
    val failed get()=failures.isNotEmpty()
    val message: String get()=when {
        running -> "刷新中 · ${total-pending.size}/$total"
        total==0 -> ""
        failures.isNotEmpty() -> "${failures.joinToString("、")}更新失败 · 已保留原内容，请重试"
        else -> "首页已更新"
    }
    fun begin(labels: List<String>): Boolean {
        if(running || labels.isEmpty()) return false
        pending.addAll(labels);failures.clear();total=pending.size
        return true
    }
    fun complete(label: String,failed: Boolean) {
        if(pending.remove(label) && failed) failures+=label
    }
    fun cancel() { pending.clear();failures.clear();total=0 }
}
