// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class TvNavigationTest {
    @Test fun incompleteLastRowStaysReachableWithoutWrappingSides() {
        assertEquals(10,TvGridFocus.left(10,5))
        assertEquals(9,TvGridFocus.right(9,12,5))
        assertEquals(11,TvGridFocus.right(11,12,5))
        assertEquals(11,TvGridFocus.down(9,12,5))
        assertNull(TvGridFocus.down(11,12,5))
    }
    @Test fun oneItemAndAllSupportedColumnCountsHaveNoMissingDestinations() {
        for(columns in listOf(3,4,5,10)) for(size in 1..30) for(index in 0 until size) {
            assertTrue(TvGridFocus.left(index,columns) in 0 until size)
            assertTrue(TvGridFocus.right(index,size,columns) in 0 until size)
            TvGridFocus.down(index,size,columns)?.let { assertTrue(it in 0 until size); assertTrue(it>index) }
        }
    }
    @Test fun librarySectionsKeepTheirExistingStorageAndPageIdentities() {
        assertEquals(LibrarySection.FAVORITES,LibrarySection.fromTab(3))
        assertEquals(LibrarySection.HISTORY,LibrarySection.fromTab(4))
        assertEquals(LibrarySection.LATER,LibrarySection.fromTab(6))
        for(tab in listOf(0,1,2,5)) assertNull(LibrarySection.fromTab(tab))
    }
}
