package com.hongguotv.core

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RemoteVideoCloseTest {
    /** Mirrors the TV's retained HTTPS response; closing its body may perform network I/O. */
    private fun video(executor: Executor,onClose: ()->Unit): RemoteVideo {
        fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
        val header=box("ftyp","isom0000".toByteArray())+box("moov",byteArrayOf())
        val data=header+ByteArray(3*1024*1024-header.size)
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val (start,last)=request.header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
            val end=minOf(last,data.lastIndex)
            val source=object: ForwardingSource(Buffer().write(data,start,end-start+1)) {
                override fun close() { try { if(start>0) onClose() } finally { super.close() } }
            }.buffer()
            val body=object: ResponseBody() {
                override fun contentType(): MediaType?=null
                override fun contentLength()=(end-start+1).toLong()
                override fun source(): BufferedSource=source
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .header("Content-Range","bytes $start-$end/${data.size}").body(body).build()
        }.build()
        return RemoteVideo(http,StreamInfo("https://example.test/video",null,"720P"),executor).prepare().also {
            it.readChunk(32768,1) // Keep a partially consumed media response open, as when paused.
        }
    }

    @Test fun retainedResponseClosesOffCallerWithoutBlockingAndOnlyOnce() {
        val caller=Thread.currentThread()
        val closeThread=AtomicReference<Thread>()
        val closeFailure=AtomicReference<Throwable>()
        val closes=AtomicInteger()
        val started=CountDownLatch(1);val finish=CountDownLatch(1);val closed=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        val remote=video(executor) {
            try {
                check(Thread.currentThread()!==caller) { "Response close attempted network I/O on the caller thread" }
                closeThread.set(Thread.currentThread());closes.incrementAndGet();started.countDown()
                check(finish.await(5,TimeUnit.SECONDS))
            } catch(failure: Throwable) { closeFailure.set(failure);throw failure }
            finally { closed.countDown() }
        }
        try {
            remote.close()
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertEquals(1L,closed.count) // close returned while transport cleanup is still blocked.
            remote.close()
            finish.countDown();assertTrue(closed.await(2,TimeUnit.SECONDS))
            executor.shutdown();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS))
            assertNull(closeFailure.get())
            assertThrows(IOException::class.java) { remote.readChunk(32768,1) }
            assertNotSame(caller,closeThread.get());assertEquals(1,closes.get())
        } finally {
            finish.countDown();remote.close();executor.shutdown()
            executor.awaitTermination(2,TimeUnit.SECONDS)
        }
    }

    @Test fun delayedCleanupImmediatelyRejectsReadsAndNeverRunsInline() {
        val pending=mutableListOf<Runnable>()
        val closed=AtomicInteger()
        val remote=video(Executor { pending+=it }) { closed.incrementAndGet() }
        remote.close();remote.close()
        assertThrows(IOException::class.java) { remote.readChunk(32768,1) }
        assertEquals(0,closed.get());assertEquals(1,pending.size)
        pending.single().run()
        assertEquals(1,closed.get())
    }

    @Test fun lateResultAfterExecutorShutdownStillClosesOffCaller() {
        val caller=Thread.currentThread()
        val closeThread=AtomicReference<Thread>()
        val closed=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor().apply { shutdown() }
        val remote=video(executor) { closeThread.set(Thread.currentThread());closed.countDown() }
        remote.close();remote.close()
        assertTrue(closed.await(2,TimeUnit.SECONDS))
        assertNotSame(caller,closeThread.get())
        assertThrows(IOException::class.java) { remote.readChunk(32768,1) }
    }
}
