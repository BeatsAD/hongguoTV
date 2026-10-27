// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Logical window units; kept independent of Android so width/font rules can be verified. */
internal object BrowseLayout {
    fun compact(width: Float)=width<800f
    fun sidebar(width: Float,font: Float)=if(compact(width)) 148f.coerceAtLeast(122f*font) else 165f.coerceAtLeast(122f*font)
    fun inset(width: Float)=if(compact(width)) 16f else 27f
    fun content(width: Float,font: Float)=(width-sidebar(width,font)-inset(width)*2).coerceAtLeast(1f)
    fun columns(available: Float,maximum: Int,minimum: Float,font: Float): Int =
        ((available+12f)/(minimum*font.coerceAtLeast(1f)+12f)).toInt().coerceIn(1,maximum)
    fun home(width: Float,font: Float)=columns(content(width,font),7,90f,font)
    fun catalog(width: Float,font: Float)=columns(content(width,font),6,100f,font)
    fun ranks(width: Float,font: Float)=columns(content(width,font),5,120f,font)
    fun search(width: Float,font: Float)=columns(content(width,font)-qrPanel(width)-20f,3,106f,font)
    fun qrPanel(width: Float)=if(compact(width)) 166f else 246f
}
