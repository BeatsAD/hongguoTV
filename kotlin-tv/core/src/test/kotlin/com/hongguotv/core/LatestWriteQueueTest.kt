package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LatestWriteQueueTest {
    @Test fun `failed write remains pending until a durable retry succeeds`() {
        val failed=CountDownLatch(1); val saved=CountDownLatch(1)
        val permit=java.util.concurrent.atomic.AtomicBoolean(false)
        val queue=LatestWriteQueue<String,Int>("test-save-state",60_000,write={ permit.get() },failed={ failed.countDown() },saved={ saved.countDown() })
        try {
            queue.submit(mapOf("favorites" to 3)); queue.flush()
            assertTrue(failed.await(2,TimeUnit.SECONDS)); assertTrue(queue.hasPendingWrites())
            assertEquals(1L,saved.count)
            permit.set(true); queue.flush()
            assertTrue(saved.await(2,TimeUnit.SECONDS)); assertFalse(queue.hasPendingWrites())
        } finally { queue.close(); assertTrue(queue.awaitClosed(2,TimeUnit.SECONDS)) }
    }

    @Test fun `in flight write is not reported as already saved`() {
        val writing=CountDownLatch(1); val finish=CountDownLatch(1); val saved=CountDownLatch(1)
        val queue=LatestWriteQueue<String,Int>("test-inflight-state",0,write={ writing.countDown(); finish.await(2,TimeUnit.SECONDS) },saved={ saved.countDown() })
        try {
            queue.submit(mapOf("progress" to 7))
            assertTrue(writing.await(2,TimeUnit.SECONDS)); assertTrue(queue.hasPendingWrites()); assertEquals(1L,saved.count)
            finish.countDown(); assertTrue(saved.await(2,TimeUnit.SECONDS)); assertFalse(queue.hasPendingWrites())
        } finally { finish.countDown(); queue.close(); assertTrue(queue.awaitClosed(2,TimeUnit.SECONDS)) }
    }
    @Test fun `close drains latest values for every partition without caller serialization`() {
        val committed = mutableListOf<Map<String, Int>>()
        val caller = Thread.currentThread()
        var writeThread: Thread? = null
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = {
            writeThread = Thread.currentThread(); committed += it; true
        })
        queue.submit(mapOf("progress" to 1, "favorites" to 5))
        queue.submit(mapOf("progress" to 2))
        queue.close()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
        assertEquals(listOf(mapOf("progress" to 2, "favorites" to 5)), committed)
        assertNotSame(caller, writeThread)
    }

    @Test fun `flush executes pending writes before coalescing delay`() {
        val committed = CountDownLatch(1)
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = { committed.countDown(); true })
        queue.submit(mapOf("progress" to 1))
        queue.flush()
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        queue.close()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
    }

    @Test fun `failed in flight old checkpoint never replaces newer value during close`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = mutableListOf<Map<String, Int>>()
        var attempts = 0
        val queue = LatestWriteQueue<String, Int>("test-library", 60_000, write = { values ->
            attempts++
            if (attempts == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                false
            } else { committed += values; true }
        })
        queue.submit(mapOf("progress" to 1, "favorites" to 8))
        queue.flush()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        queue.submit(mapOf("progress" to 2))
        queue.close()
        release.countDown()
        assertTrue(queue.awaitClosed(5, TimeUnit.SECONDS))
        assertEquals(listOf(mapOf("progress" to 2, "favorites" to 8)), committed)
    }
}
