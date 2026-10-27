// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable

/** Shared native colors and shapes; no blur, bitmap backgrounds or focus animation. */
object TvStyle {
    val background=Color.rgb(11,12,15)
    val surface=Color.rgb(32,33,37)
    val raised=Color.rgb(48,49,54)
    val accent=Color.rgb(255,112,92)
    val text=Color.rgb(243,244,246)
    val muted=Color.rgb(160,161,170)
    val outline=Color.rgb(50,51,57)
    const val POSTER_ASPECT=2f/3f
    fun posterColumns(context: Context)=if(context.resources.configuration.fontScale>1.2f || context.resources.displayMetrics.widthPixels/context.resources.displayMetrics.density<800) 4 else 5
    fun catalogColumns(context: Context)=if(context.resources.configuration.fontScale>1.2f) 4 else 6
    fun homeColumns(context: Context)=if(context.resources.configuration.fontScale>1.2f) 5 else 7
    @android.annotation.SuppressLint("WrongConstant")
    fun balancePosterTitle(view: android.widget.TextView) {
        // The API 23 Layout value also works on Android 8; SDK 36's IntDef names its API 29 alias.
        view.breakStrategy=android.text.Layout.BREAK_STRATEGY_BALANCED
    }
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,Color.TRANSPARENT,if(focused) text else Color.TRANSPARENT,radius=13)
    /** Keep a visible white focus ring even when the selected control itself is white. */
    fun focused(context: Context,color: Int,radius: Int): Drawable = LayerDrawable(arrayOf(
        shape(context,background,text,radius),shape(context,color,radius=radius)
    )).apply { val gap=(4*context.resources.displayMetrics.density).toInt(); setLayerInset(1,gap,gap,gap,gap) }
}
