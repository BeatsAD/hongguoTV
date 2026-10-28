// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ContentRequestDeferredException(val retryAfterMillis: Long): IOException("内容服务暂时不可用，请稍后重试")

/** Shared by interactive and background metadata clients; manual refresh cannot bypass it. */
class ContentRequestGate(private val clock: () -> Long = System::currentTimeMillis) {
    private val blockedUntil=mutableMapOf<String,Long>()
    private val failures=mutableMapOf<String,Int>()
    var changed: ((Map<String,Long>)->Unit)?=null
    @Synchronized fun restore(values: Map<String,Long>) {
        values.filterValues { it>clock() }.forEach { (host,until) -> blockedUntil[host]=maxOf(blockedUntil[host] ?: 0,until) }
    }
    @Synchronized fun success(host: String) { failures.remove(host) }
    @Synchronized fun failed(host: String) {
        val count=((failures[host] ?: 0)+1).coerceAtMost(3); failures[host]=count
        block(host,listOf(5L,15L,60L)[count-1]*1_000L)
    }
    private fun block(host: String,delay: Long) {
        val now=clock()
        val until=now+delay.coerceAtMost(Long.MAX_VALUE-now)
        blockedUntil[host]=maxOf(blockedUntil[host] ?: 0,until)
        changed?.invoke(blockedUntil.filterValues { it>now })
    }
    @Synchronized fun check(host: String) {
        val remaining=(blockedUntil[host] ?: 0)-clock()
        if(remaining>0) throw ContentRequestDeferredException(remaining)
    }
    @Synchronized fun observe(host: String,status: Int,retryAfter: String?) {
        val now=clock()
        val requested=retryAfter?.trim()?.let { value ->
            value.toLongOrNull()?.takeIf { it>=0 }?.coerceAtMost(Long.MAX_VALUE/1000)?.times(1000)
                ?: runCatching { (ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceAtLeast(0) }.getOrNull()
        }
        val delay=when(status) {
            429 -> maxOf(requested ?: 0,30*60_000L)
            401,403 -> maxOf(requested ?: 0,60*60_000L)
            503 -> requested ?: return
            else -> return
        }
        block(host,delay)
        if(delay>0) throw ContentRequestDeferredException(delay)
    }
}
