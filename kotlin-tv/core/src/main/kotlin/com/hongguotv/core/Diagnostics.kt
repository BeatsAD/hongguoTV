// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Bounded, process-local diagnostics. Callers supply labels/metrics, never URLs or payloads. */
object Diagnostics {
    private val events=ArrayDeque<String>()
    private val sequence=AtomicLong()
    private val started=System.nanoTime()
    @Synchronized fun record(stage: String, detail: String): String {
        val line="#${sequence.incrementAndGet()} +${(System.nanoTime()-started)/1_000_000}ms $stage: ${redact(detail).take(360)}"
        if(events.size==80) events.removeFirst()
        events.addLast(line)
        return line
    }
    @Synchronized fun snapshot()=events.joinToString("\n")
    fun redact(value: String)=value.replace(Regex("https?://[^\\s]+"),"[地址已隐藏]")
        .replace(Regex("(?i)(token|signature|spade_a|key_seed|x-argus|x-gorgon|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+"),"$1=[已隐藏]")
    fun summary(error: Throwable): String {
        val causes=generateSequence(error) { it.cause }.take(8).toList()
        val deferred=causes.filterIsInstance<ContentRequestDeferredException>().firstOrNull()
        if(deferred!=null) return "内容服务冷却中，约 ${(deferred.retryAfterMillis+999)/1000} 秒后可重试"
        val source=PlaybackErrors.source(error)
        if(source!=null) return redact(source.message.orEmpty())
        causes.filterIsInstance<EmptyContentException>().firstOrNull()?.let { return redact(it.message.orEmpty()) }
        return when {
            causes.any { it is java.net.UnknownHostException } -> "域名解析失败，请检查 DNS 或网络"
            causes.any { it is javax.net.ssl.SSLException } -> "HTTPS 握手失败，请检查设备时间、网络或证书兼容性"
            causes.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } -> "请求超时，服务响应或网络传输过慢"
            causes.any { it is java.net.ConnectException } -> "无法建立连接，请检查网络或稍后重试"
            error.javaClass.name.startsWith("com.hongguotv.") -> redact(error.message.orEmpty()).take(180)
            else -> "加载失败（${error.javaClass.simpleName}），请查看诊断详情"
        }
    }
    fun failure(stage: String,error: Throwable): String {
        val types=generateSequence(error) { it.cause }.take(8).joinToString(" → ") { it.javaClass.simpleName }
        val errno=generateSequence(error) { it.cause }.take(8).mapNotNull {
            Regex("\\b(?:ENETUNREACH|EHOSTUNREACH|ECONNREFUSED|ECONNRESET|ETIMEDOUT|EAI_[A-Z_]+)\\b").find(it.message.orEmpty())?.value
        }.distinct().joinToString(",")
        return record(stage,"${summary(error)}；异常=$types"+if(errno.isNotEmpty()) "；系统错误=$errno" else "")
    }
}

/** Distinguishes transport success with an empty body from a genuine empty result list. */
class EmptyContentException(message: String): IOException(message)

class ContentProtocolException(message: String): IOException(message)
