// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class RequestScopeTest {
    private fun call()=OkHttpClient().newCall(Request.Builder().url("https://example.invalid/").build())

    @Test fun anExpiredPlaybackBudgetNeverStartsAnotherRequest() {
        val request=call()
        assertThrows(SocketTimeoutException::class.java) {
            RequestScope.withDeadline(System.nanoTime()-1) { RequestScope.execute(request) { error("must not execute") } }
        }
        assertFalse(request.isExecuted())
        RequestScope.ensureActive() // No expired deadline leaks into the next intent on this worker.
    }

    @Test fun anExpiredNestedBudgetIsRestoredAfterFailure() {
        RequestScope.withDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)) {
            assertThrows(SocketTimeoutException::class.java) {
                RequestScope.withDeadline(System.nanoTime()-1) { RequestScope.ensureActive() }
            }
            RequestScope.ensureActive()
        }
    }

    @Test fun remainingBudgetCapsBothHeadersAndBodyWithoutExtendingAShorterCallTimeout() {
        val client=OkHttpClient.Builder().callTimeout(1,TimeUnit.MILLISECONDS).addInterceptor {
            throw java.io.IOException("fixture")
        }.build()
        RequestScope.withDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)) {
            val request=client.newCall(Request.Builder().url("https://example.invalid/").build())
            assertThrows(java.io.IOException::class.java) { RequestScope.execute(request) { error("no response") } }
            assertTrue(request.timeout().timeoutNanos()<=TimeUnit.MILLISECONDS.toNanos(1))
        }
        val longer=client.newBuilder().callTimeout(1,TimeUnit.MINUTES).build()
        RequestScope.withDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)) {
            val request=longer.newCall(Request.Builder().url("https://example.invalid/").build())
            assertThrows(java.io.IOException::class.java) { RequestScope.execute(request) { error("no response") } }
            assertTrue(request.timeout().timeoutNanos() in 1..TimeUnit.SECONDS.toNanos(5))
        }
        assertEquals(0L,call().timeout().timeoutNanos())
    }
}
