// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw IOException("请求已取消")
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            synchronized(this) { if (cancelled) throw IOException("请求已取消") }
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<RequestScope>()
        private val deadline = ThreadLocal<Long>()
        fun isCancelled(): Boolean = current.get()?.let { scope -> synchronized(scope) { scope.cancelled } } ?: false
        internal fun ensureNotCancelled() {
            if(isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("请求已取消")
        }
        internal fun ensureActive() {
            ensureNotCancelled()
            if(deadline.get()?.let { it-System.nanoTime()<=0 }==true) throw SocketTimeoutException("播放取址超时")
        }
        internal fun remainingMillis(): Long = deadline.get()?.let {
            TimeUnit.NANOSECONDS.toMillis((it-System.nanoTime()).coerceAtLeast(0))
        } ?: Long.MAX_VALUE
        internal fun <T> withDeadline(until: Long,action: ()->T): T {
            val previous=deadline.get()
            deadline.set(previous?.let { minOf(it,until) } ?: until)
            try {
                ensureActive()
                // Network attempts share this deadline. A caller may recover a playable result
                // after optional I/O times out; cancellation must still discard that result.
                return action().also { ensureNotCancelled() }
            } finally {
                if(previous==null) deadline.remove() else deadline.set(previous)
            }
        }
        fun <T> execute(call: Call, consume: (Response) -> T): T {
            ensureActive()
            deadline.get()?.let {
                val remaining=it-System.nanoTime()
                if(remaining<=0) throw SocketTimeoutException("播放取址超时")
                val configured=call.timeout().timeoutNanos()
                call.timeout().timeout(if(configured>0) minOf(configured,remaining) else remaining,TimeUnit.NANOSECONDS)
            }
            val owner = current.get()
            owner?.attach(call)
            try { return call.execute().use(consume) }
            finally { owner?.detach(call) }
        }
    }
}
