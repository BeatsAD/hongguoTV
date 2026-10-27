// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class ArtworkSizingTest {
    @Test fun tallCoverIsNotDecodedTooSmallAndEnlargedAgain() {
        val (width,height)=ArtworkSizing.target(900,1600,195,292,true)
        assertTrue(width>=195);assertTrue(height>=292)
        assertEquals(900.0/1600,width.toDouble()/height,.003)
    }
    @Test fun fitCenterKeepsWholeArtworkAndSmallSourcesAreNeverUpscaled() {
        val fit=ArtworkSizing.target(900,1600,195,292,false)
        assertTrue(fit.first<=195);assertTrue(fit.second<=292)
        assertEquals(90 to 160,ArtworkSizing.target(90,160,195,292,true))
    }
}
