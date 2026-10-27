// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import kotlin.math.max
import kotlin.math.min

internal object ArtworkSizing {
    /** Preserve aspect; never upscale source pixels. Cover requests retain enough pixels on both axes. */
    fun target(sourceWidth: Int,sourceHeight: Int,width: Int,height: Int,cover: Boolean): Pair<Int,Int> {
        val x=width.toDouble()/sourceWidth;val y=height.toDouble()/sourceHeight
        val scale=min(1.0,if(cover) max(x,y) else min(x,y))
        return max(1,kotlin.math.ceil(sourceWidth*scale).toInt()) to max(1,kotlin.math.ceil(sourceHeight*scale).toInt())
    }
}
