package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDataSourceTest {
    @Test fun remainingVideoAboveTwoGiBStillReturnsRequestedBytes() {
        assertEquals(16384,videoReadCount(16384,512*1024,3L*1024*1024*1024))
        assertEquals(16384,videoReadCount(16384,512*1024,Long.MAX_VALUE))
    }
    @Test fun endOfBufferAndEndOfFileBoundTheLastRead() {
        assertEquals(30,videoReadCount(16384,30,3L*1024*1024*1024))
        assertEquals(7,videoReadCount(16384,30,7))
        assertEquals(0,videoReadCount(16384,30,0))
    }
}
