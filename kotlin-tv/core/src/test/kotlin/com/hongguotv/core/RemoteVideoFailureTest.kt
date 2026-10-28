package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RemoteVideoFailureTest {
    @Test fun accessDenialAndRateLimitDoNotSwitchCdn() {
        for(status in listOf(403,429)) {
            var calls=0
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                    .body(byteArrayOf().toResponseBody()).build()
            }.build()
            RemoteVideo(http,StreamInfo("https://example.com/main",null,"720P",backupUrls=listOf("https://other.example/backup"))).use { remote ->
                assertThrows(PlaybackSourceException::class.java) { remote.prepare() }
                assertEquals(1,calls)
            }
        }
    }
    private fun video(status: Int)=RemoteVideo(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .header("Content-Range","bytes 0-31/32").body(ByteArray(32).toResponseBody()).build()
    }.build(),StreamInfo("https://example.com/video",null,"720P"))

    @Test fun forbiddenVideoDoesNotEnterAnAutomaticNetworkRetryLoop() {
        video(403).use { remote ->
            val error=assertThrows(PlaybackSourceException::class.java) { remote.prepare() }
            assertEquals(PlaybackFailureKind.SOURCE,error.kind); assertFalse(PlaybackErrors.retryable(error))
        }
    }
    @Test fun temporaryServerFailureCanUseTheExistingRetryBudget() {
        video(503).use { remote -> assertTrue(PlaybackErrors.retryable(assertThrows(PlaybackSourceException::class.java) { remote.prepare() })) }
    }
    @Test fun invalidRangeAndContainerArePermanentFormatErrors() {
        for(status in listOf(200,206)) video(status).use { remote ->
            val error=assertThrows(PlaybackSourceException::class.java) { remote.prepare() }
            assertEquals(PlaybackFailureKind.FORMAT,error.kind); assertFalse(PlaybackErrors.retryable(error))
        }
    }
}
