// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class HomeRefreshStateTest {
    @Test fun repeatedConfirmAndOutOfOrderResponsesCannotFinishEarly() {
        val state=HomeRefreshState()
        assertTrue(state.begin(listOf("真人剧","漫剧","AI剧")))
        assertFalse(state.begin(listOf("真人剧","漫剧","AI剧")))
        state.complete("AI剧",false);state.complete("AI剧",false)
        assertTrue(state.running);assertEquals("刷新中 · 1/3",state.message)
        state.complete("真人剧",false);state.complete("漫剧",false)
        assertFalse(state.running);assertEquals("首页已更新",state.message)
    }
    @Test fun partialFailureIsKeptUntilAnExplicitRetryCompletes() {
        val state=HomeRefreshState();state.begin(listOf("真人剧","AI剧"))
        state.complete("AI剧",true);state.complete("真人剧",false)
        assertTrue(state.message.contains("AI剧更新失败"))
        state.complete("AI剧",false)
        assertTrue(state.message.contains("AI剧更新失败"))
        assertTrue(state.begin(listOf("真人剧","AI剧")))
        state.complete("真人剧",false);state.complete("AI剧",false)
        assertEquals("首页已更新",state.message)
    }
    @Test fun LeavingThePageCancelsFeedbackAndIgnoresLateResponses() {
        val state=HomeRefreshState();state.begin(listOf("AI剧"));state.cancel()
        state.complete("AI剧",true)
        assertFalse(state.running);assertEquals("",state.message)
    }
}
