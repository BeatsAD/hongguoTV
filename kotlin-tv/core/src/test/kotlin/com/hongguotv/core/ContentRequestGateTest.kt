package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ContentRequestGateTest {
    @Test fun manualRequestCannotBypassRetryAfterAndOtherClientsShareCooldown() {
        var now=0L; val gate=ContentRequestGate {now}; var calls=0
        fun client()=ContentRepository(OkHttpClient.Builder().addInterceptor {
            calls++
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(429).message("Slow down")
                .header("Retry-After","7200").body("".toResponseBody()).build()
        }.build(),requestGate=gate)
        assertThrows(ContentRequestDeferredException::class.java) { client().home(type=ContentType.SHORT) }
        assertThrows(ContentRequestDeferredException::class.java) { client().detail("123") }
        assertEquals(1,calls)
        now=7_200_001L
        assertThrows(ContentRequestDeferredException::class.java) { client().detail("123") }
        assertEquals(2,calls)
    }
    @Test fun searchDoesNotChangeSourceAfterAccessDenial() {
        var calls=0
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor {
            calls++
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("".toResponseBody()).build()
        }.build(),playbackSession=PlaybackDeviceSession(register={ _,_->DeviceRegister.Registered("123","456") }))
        assertThrows(ContentRequestDeferredException::class.java) { repo.search("测试") }
        assertEquals(1,calls)
    }
    @Test fun savedCooldownSurvivesRestartAndExpiredValuesDoNotBlock() {
        var saved=emptyMap<String,Long>(); val first=ContentRequestGate {0L}
        first.changed={saved=it}
        assertThrows(ContentRequestDeferredException::class.java) { first.observe("example.com",429,"3600") }
        val replacement=ContentRequestGate {100L}; replacement.restore(saved)
        assertThrows(ContentRequestDeferredException::class.java) { replacement.check("example.com") }
        replacement.check("other.example.com")
        val later=ContentRequestGate {3_600_001L}; later.restore(saved); later.check("example.com")
    }
    @Test fun httpDateAndMissingRetryAfterHaveConservativeDefaults() {
        val gate=ContentRequestGate {0L}
        val date=assertThrows(ContentRequestDeferredException::class.java) { gate.observe("date",503,"Thu, 01 Jan 1970 02:00:00 GMT") }
        assertEquals(7_200_000L,date.retryAfterMillis)
        val missing=assertThrows(ContentRequestDeferredException::class.java) { gate.observe("missing",429,null) }
        assertEquals(1_800_000L,missing.retryAfterMillis)
    }
    @Test fun repeatedFailuresBackOffAndSuccessResetsFailureCount() {
        var now=0L; val gate=ContentRequestGate {now}
        gate.failed("example.com")
        assertEquals(5_000L,assertThrows(ContentRequestDeferredException::class.java) { gate.check("example.com") }.retryAfterMillis)
        now=5_001; gate.failed("example.com")
        assertEquals(15_000L,assertThrows(ContentRequestDeferredException::class.java) { gate.check("example.com") }.retryAfterMillis)
        now+=15_001; gate.success("example.com"); gate.failed("example.com")
        assertEquals(5_000L,assertThrows(ContentRequestDeferredException::class.java) { gate.check("example.com") }.retryAfterMillis)
    }
}
