package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DiagnosticsTest {
    @Test fun wrappedNetworkErrorsAreUsefulWithoutLeakingAddressOrSignature() {
        val error=IOException("https://private.example/video?signature=secret",SocketTimeoutException("secret"))
        val detail=Diagnostics.failure("视频传输",error)
        assertTrue(detail.contains("请求超时"));assertTrue(detail.contains("SocketTimeoutException"))
        assertFalse(detail.contains("private.example"));assertFalse(detail.contains("secret"))
        assertEquals("内容服务冷却中，约 31 秒后可重试",Diagnostics.summary(ContentRequestDeferredException(30_001)))
        val errno=Diagnostics.failure("网络",java.net.ConnectException("secret connect failed: ENETUNREACH"))
        assertTrue(errno.contains("ENETUNREACH"));assertFalse(errno.contains("secret"))
    }
    @Test fun diagnosticsRetainOnlyBoundedRecentEventsAndRedactCredentials() {
        repeat(100) { Diagnostics.record("test","sequence=$it token=secret https://example.com/?key=hidden") }
        val snapshot=Diagnostics.snapshot()
        assertEquals(80,snapshot.lines().size)
        assertFalse(snapshot.contains("secret"));assertFalse(snapshot.contains("hidden"))
        assertTrue(snapshot.contains("sequence=99"));assertFalse(snapshot.contains("sequence=0 "))
    }
}
