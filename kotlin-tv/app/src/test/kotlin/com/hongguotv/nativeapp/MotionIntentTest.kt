// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class MotionIntentTest {
    @Test fun reversingHideCannotLetOldCompletionHideShownControls() {
        val intent = MotionIntent()
        val hide = intent.request(false)!!
        val show = intent.request(true)!!
        assertFalse(intent.accepts(hide)); assertTrue(intent.accepts(show)); assertEquals(true, intent.visible)
    }
    @Test fun repeatedPlaybackTicksDoNotRestartTheSameEntrance() {
        val intent = MotionIntent()
        val show = intent.request(true)!!
        repeat(10) { assertNull(intent.request(true)) }
        assertTrue(intent.accepts(show))
    }
    @Test fun detachmentInvalidatesQueuedCompletionAndAllowsSameIntentOnReattach() {
        val intent = MotionIntent()
        val hide = intent.request(false)!!
        intent.reset()
        assertFalse(intent.accepts(hide)); assertNotNull(intent.request(false))
    }
}
