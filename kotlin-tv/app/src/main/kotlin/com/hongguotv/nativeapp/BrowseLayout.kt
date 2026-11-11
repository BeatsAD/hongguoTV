// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Logical window units; kept independent of Android so width/font rules can be verified. */
internal object BrowseLayout {
    const val FAILURE_ACTION_GAP=12
    const val FAILURE_CONTENT_GAP=24
    fun compact(width: Float)=width<800f
    fun sidebar(width: Float,font: Float)=if(compact(width)) 148f.coerceAtLeast(128f*font) else 165f.coerceAtLeast(128f*font)
    fun inset(width: Float)=if(compact(width)) 16f else 27f
    fun content(width: Float,font: Float)=(width-sidebar(width,font)-inset(width)*2).coerceAtLeast(1f)
    fun columns(available: Float,maximum: Int,minimum: Float,font: Float): Int =
        ((available+12f)/(minimum*font.coerceAtLeast(1f)+12f)).toInt().coerceIn(1,maximum)
    fun home(width: Float,font: Float)=columns(content(width,font),6,110f,font)
    fun catalog(width: Float,font: Float)=columns(content(width,font),5,128f,font)
    fun ranks(width: Float,font: Float)=columns(content(width,font),5,132f,font)
    fun search(width: Float,font: Float)=columns(content(width,font)-qrPanel(width)-(if(compact(width)) 16f else 20f),4,116f,font)
    // Keep two complete result rows when the viewport can fit readable posters and titles.
    fun searchPosterHeight(viewport: Float,font: Float): Float {
        val title=32f*font.coerceAtLeast(1f)
        val rows=if(viewport>=2*(80f+title+10f)+10f) 2 else 1
        return ((viewport-10f)/rows-10f-title).coerceAtLeast(80f).coerceAtMost(140f)
    }
    fun qrSize(width: Float)=if(compact(width)) 128 else 148
    fun qrPanel(width: Float)=if(compact(width)) 156f else 180f
}
