package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchingTest {
    @Test fun liveListUpdatesFollowCurrentEpisodeAndDeferRemovalUntilPlaybackEnds() {
        assertEquals(2,ResumePlayback.refreshedIndex(listOf("new","10","20","30"),"20",1,true))
        assertNull(ResumePlayback.refreshedIndex(listOf("10","30"),"20",1,true))
        assertEquals(1,ResumePlayback.refreshedIndex(listOf("10","30"),"20",10,false))
        assertEquals(0,ResumePlayback.refreshedIndex(listOf("10","20","30"),"10",0,true))
    }
    @Test fun episodeReplacementIsVisibleEvenWhenCountDoesNotGrow() {
        val first=FavoriteUpdate.first(2,1,FavoriteUpdate.fingerprint(listOf("10","20")))
        val replaced=first.observe(2,2,fingerprint=FavoriteUpdate.fingerprint(listOf("10","30")))
        assertEquals(0,replaced.added); assertTrue(replaced.contentChanged); assertTrue(replaced.hasChanges)
        assertEquals(replaced,FavoriteUpdate.decode(replaced.json()))
        assertFalse(replaced.observe(2,3,acknowledge=true).hasChanges)
        assertEquals(replaced,replaced.observe(3,0,fingerprint="stale"))
    }
    @Test fun existingCountOnlyRecordsEstablishFingerprintWithoutFalseNotification() {
        val old=FavoriteUpdate.decode(JSONObject("""{"total":2,"seen":2,"checkedAt":1}"""))!!
        assertFalse(old.observe(2,2,fingerprint=FavoriteUpdate.fingerprint(listOf("10","20"))).hasChanges)
    }
    @Test fun `favorite baseline is quiet and multiple checks accumulate unread episodes`() {
        val first=FavoriteUpdate.first(80,100)
        assertEquals(0,first.added)
        val one=first.observe(82,200)
        assertEquals(2,one.added)
        val two=FavoriteUpdate.decode(one.json())!!.observe(85,300)
        assertEquals(5,two.added)
        assertEquals(0,two.observe(85,400,acknowledge=true).added)
        assertEquals(1,two.observe(85,400,true).observe(86,500).added)
    }
    @Test fun `temporary shrink and stale requests do not invent updates or undo acknowledgements`() {
        val first=FavoriteUpdate.first(80,100)
        assertEquals(0,first.observe(75,200).observe(80,300).added)
        val acknowledged=first.observe(85,400,true)
        assertEquals(acknowledged,acknowledged.observe(83,300))
        assertEquals(0,acknowledged.observe(70,500,true).observe(85,600).added)
    }
    @Test fun `corrupt update metadata falls back to a fresh baseline`() {
        assertNull(FavoriteUpdate.decode(null))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":0,"seen":1,"checkedAt":1}""")))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":10,"seen":-1,"checkedAt":1}""")))
        assertEquals(0,FavoriteUpdate.first(100,20).added)
    }
    @Test fun `resume follows episode identity after reordering and completed episode advances`() {
        val episodes=listOf("new","a","b","c")
        assertEquals(ResumeTarget(2,52000),ResumePlayback.target(episodes,"b",52000,false))
        assertEquals(ResumeTarget(3,0),ResumePlayback.target(episodes,"b",52000,true))
        assertEquals(ResumeTarget(3,0),ResumePlayback.target(episodes,"c",52000,true))
        assertEquals(ResumeTarget(0,0),ResumePlayback.target(episodes,"removed",52000,false))
        assertEquals(ResumeTarget(1,0),ResumePlayback.target(episodes,"a",-100,false))
    }
    @Test fun `automatic recovery is bounded until the user starts a new attempt`() {
        val budget=RecoveryBudget()
        assertEquals(1000L,budget.delayMillis); assertTrue(budget.consume())
        assertEquals(2500L,budget.delayMillis); assertTrue(budget.consume())
        assertEquals(5000L,budget.delayMillis); assertTrue(budget.consume())
        repeat(10) { assertFalse(budget.consume()); assertNull(budget.delayMillis) }
        assertEquals(3,budget.attempts)
        budget.reset(); assertEquals(1000L,budget.delayMillis); assertTrue(budget.consume())
    }
    @Test fun `legacy global defaults stay valid and invalid values have safe defaults`() {
        assertEquals(720,PlaybackQuality.normalize(720)); assertEquals(1080,PlaybackQuality.normalize(1080))
        assertEquals(1080,PlaybackQuality.normalize(-1)); assertEquals(480,PlaybackQuality.normalize(480))
        assertEquals(1.5f,PlaybackSpeed.normalize(1.5f)); assertEquals(1f,PlaybackSpeed.normalize(Float.NaN))
    }
}
