package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteVideoStreamingTest {
    private fun videoBytes(): ByteArray {
        fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
        val header=box("ftyp","isom0000".toByteArray())+box("moov",byteArrayOf())
        return header+ByteArray(3*1024*1024-header.size) { (it%239).toByte() }
    }
    @Test fun failedPrimaryWindowContinuesAtExactOffsetOnBackup() {
        val data=videoBytes();val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            val range=exchange.requestHeaders.getFirst("Range")
            requests+=exchange.requestURI.path+":"+range
            val (start,last)=range.removePrefix("bytes=").split('-').map(String::toInt)
            val end=minOf(last,data.lastIndex)
            exchange.responseHeaders.add("Content-Range","bytes $start-$end/${data.size}")
            exchange.sendResponseHeaders(206,(end-start+1).toLong())
            try {
                exchange.responseBody.use {
                    it.write(data,start,if(exchange.requestURI.path=="/primary" && start>0) 8192 else end-start+1)
                }
            } catch(_: java.io.IOException) { exchange.close() }
        }
        server.start();val http=OkHttpClient();val base="http://127.0.0.1:${server.address.port}"
        try {
            RemoteVideo(http,StreamInfo("$base/primary",null,"720P",backupUrls=listOf("$base/backup"))).prepare().use { remote ->
                assertArrayEquals(data.copyOfRange(32768,232768),remote.read(32768,200000))
                assertTrue(requests.any { it.startsWith("/backup:bytes=40960-") })
            }
        } finally { server.stop(0);http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow() }
    }
    @Test fun firstMediaBytesAreDeliveredWhileRestOfWindowIsStillBlockedAndSeekIsCorrect() {
        val data=videoBytes();val release=CountDownLatch(1);val ranges=java.util.Collections.synchronizedList(mutableListOf<String>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val serverThreads=Executors.newCachedThreadPool();server.executor=serverThreads
        server.createContext("/video") { exchange ->
            try {
                val range=exchange.requestHeaders.getFirst("Range");ranges+=range
                val (start,last)=range.removePrefix("bytes=").split('-').map(String::toInt)
                val end=minOf(last,data.lastIndex)
                exchange.responseHeaders.add("Content-Range","bytes $start-$end/${data.size}")
                exchange.sendResponseHeaders(206,(end-start+1).toLong())
                exchange.responseBody.use { output ->
                    if(start==32768) {
                        output.write(data,start,8192);output.flush()
                        release.await(5,TimeUnit.SECONDS)
                        output.write(data,start+8192,end-start+1-8192)
                    } else output.write(data,start,end-start+1)
                }
            } catch(_: java.io.IOException) { exchange.close() }
        }
        server.start();val client=OkHttpClient.Builder().callTimeout(1,TimeUnit.SECONDS).build();val worker=Executors.newSingleThreadExecutor()
        try {
            RemoteVideo(client,StreamInfo("http://127.0.0.1:${server.address.port}/video",null,"720P")).prepare().use { remote ->
                val first=worker.submit<ByteArray> { remote.readChunk(32768,64*1024) }.get(2,TimeUnit.SECONDS)
                assertTrue(first.size in 1..8192)
                assertEquals(1L,release.count) // server has not sent the rest of the 2 MiB window
                assertArrayEquals(data.copyOfRange(32768,32768+first.size),first)
                // Simulate a pause/buffer-full interval longer than the caller's call timeout.
                assertFalse(release.await(1200,TimeUnit.MILLISECONDS))
                release.countDown()
                assertArrayEquals(data.copyOfRange(32768+first.size,32768+first.size+150000),remote.read(32768L+first.size,150000))
                assertEquals(2,ranges.size) // prefix + one sustained window, not a request per chunk
                remote.closeRead()
                assertArrayEquals(data.copyOfRange(2_800_000,2_850_000),remote.read(2_800_000,50_000))
                assertArrayEquals(data.copyOfRange(17,94),remote.read(17,77))
                assertEquals(3,ranges.size)
            }
        } finally {
            release.countDown();worker.shutdownNow();server.stop(0);serverThreads.shutdownNow()
            client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow()
        }
    }
    @Test fun closingRemoteInterruptsBlockedWindowRead() {
        val data=videoBytes();val bodyRequested=CountDownLatch(1);val release=CountDownLatch(1)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/video") { exchange ->
            try {
                val (start,last)=exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map(String::toInt)
                val end=minOf(last,data.lastIndex)
                exchange.responseHeaders.add("Content-Range","bytes $start-$end/${data.size}")
                exchange.sendResponseHeaders(206,(end-start+1).toLong())
                if(start>0) { bodyRequested.countDown();release.await(5,TimeUnit.SECONDS) }
                exchange.responseBody.use { it.write(data,start,end-start+1) }
            } catch(_: java.io.IOException) { exchange.close() }
        }
        server.start();val client=OkHttpClient();val workers=Executors.newFixedThreadPool(2)
        val remote=RemoteVideo(client,StreamInfo("http://127.0.0.1:${server.address.port}/video",null,"720P"))
        try {
            remote.prepare()
            val reading=workers.submit<Boolean> { try { remote.readChunk(32768,65536);false } catch(_: java.io.IOException) { true } }
            assertTrue(bodyRequested.await(2,TimeUnit.SECONDS))
            workers.submit { remote.close() }.get(2,TimeUnit.SECONDS)
            assertTrue(reading.get(2,TimeUnit.SECONDS))
        } finally {
            release.countDown();remote.close();workers.shutdownNow();server.stop(0)
            client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow()
        }
    }
}
