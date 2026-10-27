// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class BrowseLayoutTest {
    @Test fun approvedNormalDensityKeepsDistinctPageRoles() {
        assertEquals(7,BrowseLayout.home(960f,1f))
        assertEquals(6,BrowseLayout.catalog(960f,1f))
        assertEquals(5,BrowseLayout.ranks(960f,1f))
        assertEquals(3,BrowseLayout.search(960f,1f))
    }
    @Test fun compactScreenAndLargeFontsReserveReadableNavigationAndCards() {
        for(width in listOf(640f,960f)) for(font in listOf(1f,1.3f)) {
            val available=BrowseLayout.content(width,font)
            val columns=BrowseLayout.catalog(width,font)
            assertTrue("minimum readable catalog width",(available-12*(columns-1))/columns>=100*font)
            assertTrue("four Chinese characters plus icon and padding",BrowseLayout.sidebar(width,font)-38>=56*font+17+11+12)
        }
        assertTrue(BrowseLayout.home(640f,1f)<BrowseLayout.home(960f,1f))
        assertTrue(BrowseLayout.catalog(960f,1.3f)<BrowseLayout.catalog(960f,1f))
        assertEquals(1,BrowseLayout.search(640f,1.3f))
    }
}
