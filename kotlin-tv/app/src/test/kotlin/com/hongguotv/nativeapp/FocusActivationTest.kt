// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class FocusActivationTest {
    private class Fixture {
        var now=0L
        var selected=false
        var eligible=true
        var calls=0
        data class Task(val at: Long,val action: ()->Unit,var cancelled: Boolean=false)
        val tasks=mutableListOf<Task>()
        fun gate(delay: Long=250)=FocusActivation(delay,{ now },{ wait,action ->
            val task=Task(now+wait,action);tasks+=task;val cancel: ()->Unit={ task.cancelled=true };cancel
        },{ eligible },{ selected },{ calls++;selected=true })
        fun advance(value: Long) { now=value;tasks.toList().filter { it.at<=now && !it.cancelled }.forEach { tasks.remove(it);it.action() } }
    }
    @Test fun programmaticRestorationDoesNotSelect() {
        val f=Fixture();f.gate().gained(false);f.advance(1000);assertEquals(0,f.calls)
    }
    @Test fun fastTraversalCancelsItemsOnlyPassedThrough() {
        val f=Fixture();val a=f.gate();a.gained(true);f.advance(100);a.cancel()
        val b=f.gate();b.gained(true);f.advance(349);assertEquals(0,f.calls)
        f.advance(350);assertEquals(1,f.calls)
    }
    @Test fun confirmCommitsImmediatelyAndQueuedCallbackCannotDuplicateIt() {
        val f=Fixture();val g=f.gate();g.gained(true);f.advance(40);g.confirm()
        assertEquals(1,f.calls)
        // Even if an already dequeued callback could no longer be removed, its ticket is stale.
        f.tasks.toList().forEach { it.action() };g.confirm();assertEquals(1,f.calls)
    }
    @Test fun leavingSelectorCanFlushBeforeEnteringContent() {
        val f=Fixture();val g=f.gate();g.gained(true);g.flush();g.cancel();f.advance(500)
        assertEquals(1,f.calls)
    }
    @Test fun rebuildingControlsTransfersTheOriginalDeadline() {
        val f=Fixture();val old=f.gate();old.gained(true);f.advance(180)
        val deadline=old.deadline!!;old.cancel();val rebuilt=f.gate();rebuilt.gained(false);rebuilt.resume(deadline)
        f.advance(249);assertEquals(0,f.calls);f.advance(250);assertEquals(1,f.calls)
    }
    @Test fun selectedItemAndInvisiblePageDoNotRequestAgain() {
        val f=Fixture();f.selected=true;f.gate().gained(true);f.advance(300);assertEquals(0,f.calls)
        f.selected=false;val g=f.gate();g.gained(true);f.eligible=false;f.advance(600);assertEquals(0,f.calls)
    }
    @Test fun localSwitchStillDefersUntilTheDirectionalDispatchEnds() {
        val f=Fixture();val g=f.gate(0);g.gained(true);assertEquals(0,f.calls);f.advance(0);assertEquals(1,f.calls)
    }
    @Test fun nestedRestorationAndExceptionsRestoreDirectionalIntent() {
        assertFalse(FocusIntent.directional)
        FocusIntent.during(true) {
            assertTrue(FocusIntent.directional)
            try { FocusIntent.during(false) { assertFalse(FocusIntent.directional);error("restore") } } catch(_: IllegalStateException) {}
            assertTrue(FocusIntent.directional)
        }
        assertFalse(FocusIntent.directional)
    }
    @Test fun defaultAndroidFocusSearchRetainsIntentButRestorationOverridesIt() {
        FocusIntent.pendingDirectional=true
        try {
            FocusIntent.during(true) { assertTrue(FocusIntent.directional) }
            assertTrue(FocusIntent.directional)
            FocusIntent.during(false) { assertFalse(FocusIntent.directional) }
        } finally { FocusIntent.pendingDirectional=false }
        assertFalse(FocusIntent.directional)
    }
}
