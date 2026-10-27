package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class ContentFreshnessTest {
    @Test fun startupRefreshesButReturningToFreshContentDoesNotRefetch() {
        assertTrue(ContentFreshness.due(0,null,null))
        assertFalse(ContentFreshness.due(1_799_999,0,0))
        assertTrue(ContentFreshness.due(1_800_000,0,0))
    }
    @Test fun failedRequestsBackOffWithoutMakingOldContentFresh() {
        assertFalse(ContentFreshness.due(2_000_000,0,1_800_001))
        assertTrue(ContentFreshness.due(2_100_001,0,1_800_001))
        assertFalse(ContentFreshness.due(299_999,null,0))
        assertTrue(ContentFreshness.due(300_000,null,0))
    }
    @Test fun monotonicClockResetDoesNotKeepContentFreshForever() {
        assertTrue(ContentFreshness.due(0,500_000,500_000))
    }
}
