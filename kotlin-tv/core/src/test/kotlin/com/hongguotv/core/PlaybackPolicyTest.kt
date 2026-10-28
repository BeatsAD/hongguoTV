package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class PlaybackPolicyTest {
    @Test fun wrappingARejectedSourceDoesNotMakeItRetryable() {
        val source=PlaybackSourceException(PlaybackFailureKind.SOURCE,"播放接口暂不可用（错误码 110001）")
        val wrapper=IOException("wrapped",source)
        assertSame(source,PlaybackErrors.source(wrapper))
        assertFalse(PlaybackErrors.retryable(wrapper))
        assertTrue(PlaybackErrors.retryable(SocketTimeoutException()))
        assertTrue(PlaybackErrors.retryableHttp(503)); assertTrue(PlaybackErrors.retryableHttp(429))
        assertFalse(PlaybackErrors.retryableHttp(403)); assertFalse(PlaybackErrors.retryableHttp(200))
    }
    @Test fun temporaryQualitySurvivesRecoveryButNotNextEpisode() {
        val attempt=PlaybackAttempt()
        attempt.begin("one",720); attempt.begin("one")
        assertEquals(720,attempt.quality(1080))
        attempt.begin("two")
        assertEquals(1080,attempt.quality(1080))
    }
    @Test fun decoderRecoveryIsBoundedAndExcludesOnlyFailedVariant() {
        val attempt=PlaybackAttempt(); attempt.begin("one")
        val high=StreamInfo("https://example.com/one",null,"1080P",codec="hevc",width=1080,height=1920)
        val renewed=high.copy(url="https://example.com/renewed",quality="1080P（兼容资源）")
        val lower=high.copy(quality="720P",width=720,height=1280)
        assertTrue(attempt.rejectDecoder(high))
        assertTrue(renewed.variantKey in attempt.rejectedVariants())
        assertFalse(lower.variantKey in attempt.rejectedVariants())
        assertFalse(attempt.rejectDecoder(lower))
        attempt.begin("two")
        assertTrue(attempt.rejectDecoder(high))
    }
    @Test fun explicitRetryClearsTemporaryLimitsAndDecoderRejections() {
        val attempt=PlaybackAttempt(); attempt.begin("one",720)
        attempt.rejectDecoder(StreamInfo("https://example.com/one",null,"720P"))
        attempt.clear(); attempt.begin("one")
        assertEquals(1080,attempt.quality(1080)); assertTrue(attempt.rejectedVariants().isEmpty())
    }
    @Test fun sustainedBufferingIgnoresPausesAndResetsOnReady() {
        val watch=BufferingWatchdog()
        assertFalse(watch.expired(0,true,true,false))
        assertFalse(watch.expired(17_999,true,true,false))
        assertTrue(watch.expired(18_000,true,true,false))
        assertFalse(watch.expired(20_000,true,false,false))
        assertFalse(watch.expired(90_000,true,true,true))
        assertTrue(watch.expired(102_000,true,true,true))
        assertFalse(watch.expired(103_000,false,true,true))
        assertFalse(watch.expired(104_000,true,true,true))
    }
    @Test fun networkDowngradeIsEpisodeScopedAndCannotSelectHigherCompatibilityFallback() {
        val attempt=PlaybackAttempt();attempt.begin("one")
        assertEquals(720,attempt.lowerForNetwork("1080P",1080))
        assertFalse(attempt.withinNetworkLimit(StreamInfo("url",null,"1080P")))
        assertTrue(attempt.withinNetworkLimit(StreamInfo("url",null,"720P")))
        attempt.begin("one");assertEquals(720,attempt.quality(1080))
        assertEquals(480,attempt.lowerForNetwork("720P",1080))
        assertNull(attempt.lowerForNetwork("480P",1080))
        attempt.begin("two");assertEquals(1080,attempt.quality(1080))
        assertTrue(attempt.withinNetworkLimit(StreamInfo("url",null,"1080P")))
    }
    @Test fun reorderedEpisodeUsesItsIdAndOriginalPosition() {
        assertEquals(ResumeTarget(2,45000),ResumePlayback.target(listOf("new","first","second"),"second",45000,false))
        assertEquals(ResumeTarget(2,0),ResumePlayback.target(listOf("new","first","second"),"first",45000,true))
    }
}
