// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PageTransitionStateTest {
    @Test fun fastNavigationCannotLetAnOldCompletionRemoveTheNewOutgoingPage() {
        val pages=PageTransitionState<String>()
        pages.begin("home")
        val old=pages.begin("catalog")
        assertEquals("home",pages.finish())
        val latest=pages.begin("detail")
        assertNull(pages.complete(old));assertEquals("catalog",pages.outgoing)
        assertEquals("catalog",pages.complete(latest));assertEquals("detail",pages.current)
        assertNull(pages.complete(latest))
    }
    @Test fun lifecycleFinishRetiresOnlyTheOutgoingPageAndInvalidatesPendingPredraw() {
        val pages=PageTransitionState<String>()
        pages.begin("home");val ticket=pages.begin("settings")
        assertEquals("home",pages.finish());assertFalse(pages.accepts(ticket))
        assertEquals("settings",pages.current);assertNull(pages.outgoing)
    }
    @Test fun clearingPlayerWidgetsAllowsAnUnanimatedFirstPage() {
        val pages=PageTransitionState<String>()
        val old=pages.begin("detail");pages.clear()
        val next=pages.begin("home")
        assertNull(pages.complete(old));assertNull(pages.complete(next));assertEquals("home",pages.current)
    }
    @Test(expected=IllegalStateException::class)
    fun attachingAThirdPageWithoutRetiringTheOldOneIsRejected() {
        val pages=PageTransitionState<String>()
        pages.begin("home");pages.begin("catalog");pages.begin("detail")
    }
}
