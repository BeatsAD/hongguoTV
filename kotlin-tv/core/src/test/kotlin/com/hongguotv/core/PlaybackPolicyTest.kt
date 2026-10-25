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
    @Test fun reorderedEpisodeUsesItsIdAndOriginalPosition() {
        assertEquals(ResumeTarget(2,45000),ResumePlayback.target(listOf("new","first","second"),"second",45000,false))
        assertEquals(ResumeTarget(2,0),ResumePlayback.target(listOf("new","first","second"),"first",45000,true))
    }
}
