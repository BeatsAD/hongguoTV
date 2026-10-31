// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(
    http: OkHttpClient,val info: StreamInfo,
    private val closeExecutor: Executor=Executor { it.run() }
): Closeable {
    // Shares the caller's pool/dispatcher; no new executor. Do not wait 30–45 seconds on a dead CDN.
    private val http=http.newBuilder()
        .connectTimeout(minOf(http.connectTimeoutMillis.takeIf { it>0 } ?: 8000,8000).toLong(),java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(minOf(http.readTimeoutMillis.takeIf { it>0 } ?: 10000,10000).toLong(),java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(minOf(http.callTimeoutMillis.takeIf { it>0 } ?: 30000,30000).toLong(),java.util.concurrent.TimeUnit.MILLISECONDS).build()
    private val urls=(listOf(info.url)+info.backupUrls).distinct().take(2)
    private var sourceIndex=0
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    data class Range(val bytes: ByteArray,val total: Long)
    var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix=byteArrayOf()
    private var window: Response?=null
    private var windowCall: Call?=null
    private var windowPosition=0L
    private var windowStart=0L
    private var windowEnd=0L
    private var windowStarted=0L
    @Volatile private var downloaded=0L
    @Volatile private var decryptMillis=0L
    @Volatile private var windows=0
    fun stats()="requests=$windows downloadedBytes=$downloaded decryptMs=$decryptMillis"
    private fun request(start: Long,end: Long): Call {
        if(closed) throw IOException("播放请求已取消")
        val request=Request.Builder().url(urls[sourceIndex]).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=http.newCall(request)
        synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
        return call
    }
    private fun validate(response: Response,start: Long,end: Long): Long {
        if(response.code!=206) throw PlaybackSourceException(if(PlaybackErrors.retryableHttp(response.code)) PlaybackFailureKind.NETWORK else if(response.code==200) PlaybackFailureKind.FORMAT else PlaybackFailureKind.SOURCE,"视频服务器不支持分段读取（HTTP ${response.code}）",response.code)
        val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"视频分段响应异常")
        val (begin,finish,size)=match.destructured
        val full=size.toLongOrNull() ?: throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"视频长度异常")
        val last=minOf(end,full-1)
        if(begin.toLongOrNull()!=start || finish.toLongOrNull()!=last || full<=0) throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"视频分段范围不匹配")
        if(total!=0L && full!=total) throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"视频资源已变化，请重试")
        return full
    }
    private fun useBackup(error: IOException): Boolean {
        if(closed || Thread.currentThread().isInterrupted || RequestScope.isCancelled() || sourceIndex>=urls.lastIndex) return false
        val source=PlaybackErrors.source(error)
        if(source!=null && (source.kind!=PlaybackFailureKind.NETWORK || source.httpStatus in setOf(401,403,429))) return false
        sourceIndex++
        Diagnostics.record("视频备用线路","连接/传输失败，切换同清晰度备用 CDN；host=${java.net.URI(urls[sourceIndex]).host.orEmpty()}")
        return true
    }
    private fun range(start: Long,end: Long): Range {
        while(true) {
            try { return rangeOnce(start,end) }
            catch(error: IOException) { if(!useBackup(error)) throw error }
        }
    }
    private fun rangeOnce(start: Long,end: Long): Range {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val call=request(start,end)
        val began=System.nanoTime()
        var status=0
        try {
            return RequestScope.execute(call) { response ->
                status=response.code
                val full=validate(response,start,end)
                val n=(minOf(end,full-1)-start+1).toInt()
                val body=response.body ?: throw IOException("视频分段为空")
                val bytes=body.source().readByteArray(n.toLong())
                Diagnostics.record("视频索引","HTTP=${response.code} offset=$start bytes=$n elapsedMs=${(System.nanoTime()-began)/1_000_000}")
                Range(bytes,full)
            }
        } catch(failure: IOException) {
            Diagnostics.record("视频索引","host=${call.request().url.host} HTTP=$status offset=$start elapsedMs=${(System.nanoTime()-began)/1_000_000}")
            Diagnostics.failure("视频索引",failure); throw failure
        }
        finally { synchronized(calls) { calls-=call } }
    }
    @Synchronized fun prepare(): RemoteVideo {
        if(::prepared.isInitialized) return this
        val first=range(0,32*1024-1); total=first.total
        prefix=first.bytes
        val end=try { MediaCrypto.headerEnd(first.bytes,total) } catch(_: Exception) { throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"视频索引格式不支持，请换一部剧或更新应用") }
        val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes else first.bytes.copyOf(end)
        prepared=try { MediaCrypto.prepare(header,info.key) } catch(_: Exception) { throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"视频索引格式不支持，请换一部剧或更新应用") }
        if(prepared.samples.any { it.end>total }) throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"视频样本超出资源大小，请换一部剧或更新应用")
        return this
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(): RemoteVideo {
        prepare()
        val size=minOf(total,1024*1024L).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes
        return this
    }
    /** Close the read window on the media/loading thread; TLS close may write to the network. */
    @Synchronized fun closeRead() {
        windowCall?.let { call ->
            call.cancel()
            synchronized(calls) { calls-=call }
        }
        window?.close(); window=null; windowCall=null
    }
    /** Returns available bytes without waiting for an entire 512 KiB block. One 2 MiB HTTP
     * window is reused for sequential reads. Memory stays bounded by the index + a 64 KiB chunk. */
    @Synchronized fun readChunk(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        check(::prepared.isInitialized)
        if(start>=total) return byteArrayOf()
        if(start<prepared.header.size) return prepared.header.copyOfRange(start.toInt(),minOf(prepared.header.size,start.toInt()+minOf(length,64*1024)))
        val cached=prefix
        val count=minOf(length.toLong(),64*1024L,total-start).toInt()
        try {
            val raw=if(start<cached.size) cached.copyOfRange(start.toInt(),minOf(cached.size,start.toInt()+count))
            else {
                if(window==null || windowPosition!=start || start>=windowEnd) {
                    closeRead()
                    val end=minOf(total-1,start+2*1024*1024L-1)
                    val call=request(start,end)
                    // A response may remain open while the player is paused or its buffer is full.
                    // Keep connect/read deadlines, but do not count this intentional idle time.
                    call.timeout().clearTimeout()
                    windowCall=call; windowStarted=System.nanoTime()
                    val response=call.execute(); window=response
                    validate(response,start,end)
                    if(response.body==null) throw IOException("视频分段为空")
                    windowStart=start; windowPosition=start; windowEnd=end+1; windows++
                    Diagnostics.record("视频传输","host=${call.request().url.host} HTTP=${response.code} offset=$start windowBytes=${end-start+1} headersMs=${(System.nanoTime()-windowStarted)/1_000_000}")
                }
                val data=ByteArray(minOf(count.toLong(),windowEnd-start).toInt())
                val firstRead=windowPosition==windowStart
                val n=window!!.body!!.source().read(data)
                if(n<=0) throw java.io.EOFException("视频分段提前结束，位置 $start")
                if(firstRead) Diagnostics.record("视频首块","offset=$start bytes=$n elapsedMs=${(System.nanoTime()-windowStarted)/1_000_000}")
                downloaded+=n; windowPosition+=n
                if(windowPosition==windowEnd) {
                    Diagnostics.record("视频传输","windowComplete elapsedMs=${(System.nanoTime()-windowStarted)/1_000_000} $downloaded bytes downloaded")
                    closeRead()
                }
                if(n==data.size) data else data.copyOf(n)
            }
            val began=System.nanoTime()
            val result=MediaCrypto.decrypt(raw,start,info.key,prepared)
            val elapsed=(System.nanoTime()-began)/1_000_000; decryptMillis+=elapsed
            if(elapsed>=80) Diagnostics.record("视频解密","bytes=${raw.size} elapsedMs=$elapsed")
            return result
        } catch(failure: IOException) {
            closeRead(); Diagnostics.failure("视频传输 offset=$start",failure)
            if(useBackup(failure)) return readChunk(start,length)
            throw failure
        }
    }
    /** Exact-length helper for probes and existing core callers; Media3 uses readChunk. */
    fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        val out=java.io.ByteArrayOutputStream()
        val size=minOf(length.toLong(),(total-start).coerceAtLeast(0)).toInt()
        if(closed) throw IOException("播放请求已取消")
        while(out.size()<size) {
            val chunk=readChunk(start+out.size(),size-out.size())
            if(chunk.isEmpty()) throw java.io.EOFException("视频分段提前结束")
            out.write(chunk)
        }
        return out.toByteArray()
    }
    override fun close() {
        val pending=synchronized(calls) {
            if(closed) return
            closed=true
            calls.toList().also { calls.clear() }
        }
        // Playback owners provide a background executor: even cancelling/closing TLS may write.
        // Reject new reads immediately, but never make the UI wait for the read monitor.
        val cleanup=Runnable {
            pending.forEach { it.cancel() }
            synchronized(this) { closeRead() }
        }
        try { closeExecutor.execute(cleanup) }
        catch(_: RejectedExecutionException) {
            // A cancelled prepare may finish after its Activity has shut down the executor.
            Thread(cleanup,"hongguotv-video-cleanup").start()
        }
    }
}
