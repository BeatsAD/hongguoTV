// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlin.math.max

/** Landscape presentation from the real cover; keep its upper subject in the crop. */
class CinematicArtwork(context: Context): ImageView(context) {
    init { scaleType=ScaleType.MATRIX }
    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); fit() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); fit() }
    private fun fit() {
        val image=drawable ?: return
        if(width<=0 || height<=0 || image.intrinsicWidth<=0 || image.intrinsicHeight<=0) return
        val scale=max(width.toFloat()/image.intrinsicWidth,height.toFloat()/image.intrinsicHeight)
        imageMatrix=Matrix().apply { setScale(scale,scale); postTranslate((width-image.intrinsicWidth*scale)/2f,(height-image.intrinsicHeight*scale)*.18f) }
    }
}
