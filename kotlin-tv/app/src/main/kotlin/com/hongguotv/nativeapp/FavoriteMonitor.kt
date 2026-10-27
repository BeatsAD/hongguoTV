// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RequestScope
import com.hongguotv.core.ContentRequestDeferredException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Serial foreground checks; cancellation owns only calls made by this monitor. */
class FavoriteMonitor(private val library: Library, source: ContentRepository, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var epoch = 0
    private var job: Future<*>? = null
    private var scope: RequestScope? = null
    private var destroyed = false
    private val http = source.http.newBuilder().callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val repository = ContentRepository(http,requestGate=source.requestGate)
    private var nextCheckAt=0L
    var running = false; private set
    var status = "进入收藏可手动检查更新"; private set
    var lastChangedId: String? = null; private set

    fun check(force: Boolean = false) {
        if (running || destroyed || !library.isLoaded) return
        val now = System.currentTimeMillis()
        if(now<nextCheckAt) {
            status="已检查过，稍后可再检查"; changed(); return
        }
        val favorites = library.favorites()
        val eligible=favorites.filter { force || library.favoriteUpdate(it.id)?.let { now - it.checkedAt !in 0 until 6*3_600_000L } != false }
            .sortedBy { library.favoriteUpdate(it.id)?.checkedAt ?: 0 }
        val items=eligible.take(10)
        lastChangedId = null
        if (items.isEmpty()) {
            status = if (favorites.isEmpty()) "收藏后可检查新集" else "最近已检查，可手动刷新"
            changed(); return
        }
        val ticket = ++epoch
        val owned = RequestScope()
        scope = owned
        running = true
        nextCheckAt=now+60_000L
        var completed = 0; var failures = 0; var consecutiveFailures = 0
        fun next() {
            if (ticket != epoch) return
            if (completed == items.size || consecutiveFailures >= 3) {
                running = false
                status = "本批已检查 $completed/${items.size} 部" + (if (failures > 0) " · $failures 部失败，稍后可重试" else if(eligible.size>items.size) " · 还有收藏待检查" else " · 检查完成")
                changed(); return
            }
            val item = items[completed]
            val started = System.currentTimeMillis()
            status = "检查更新 ${completed + 1}/${items.size}…"
            changed()
            lastChangedId = null
            job = worker.submit {
                if (ticket != epoch) return@submit
                val result = runCatching { owned.run { repository.detail(item.id) } }
                main.post {
                    if (ticket != epoch) return@post
                    job = null
                    result.onSuccess { detail ->
                        library.observeDetail(detail, started)
                        lastChangedId = item.id
                        consecutiveFailures = 0
                    }.onFailure {
                        failures++; consecutiveFailures++
                        nextCheckAt=maxOf(nextCheckAt,System.currentTimeMillis()+5*60_000L)
                        if(it is ContentRequestDeferredException) {
                            nextCheckAt=maxOf(nextCheckAt,System.currentTimeMillis()+it.retryAfterMillis)
                            consecutiveFailures=3
                        }
                    }
                    completed++
                    if(completed==items.size || consecutiveFailures>=3) next()
                    else main.postDelayed({ if(ticket==epoch) next() },2_000L)
                }
            }
        }
        next()
    }
    fun stop() {
        epoch++
        if (running) status = "更新检查已暂停，返回目录后继续"
        running = false
        scope?.cancel(); scope = null
        job?.cancel(true); job = null
    }
    fun destroy() { destroyed = true; stop(); worker.shutdownNow() }
}
