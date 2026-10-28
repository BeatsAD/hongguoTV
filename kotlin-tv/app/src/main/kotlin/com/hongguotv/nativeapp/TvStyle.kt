// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import android.view.View

/** Shared V2 semantic tokens. Focus and persistent selection are independent. */
object TvStyle {
    val background=Color.rgb(16,17,20)
    val sidebar=Color.rgb(20,21,25)
    val subtle=Color.rgb(27,28,33)
    val surface=Color.rgb(37,38,44)
    val selected=Color.rgb(52,54,63)
    val raised=Color.rgb(58,61,70)
    val primary=Color.rgb(187,61,93)
    val primaryEnd=Color.rgb(174,84,54)
    val accent=Color.rgb(213,128,152)
    val text=Color.rgb(245,243,242)
    val focus=Color.rgb(255,248,245)
    val muted=Color.rgb(174,176,186)
    val disabled=Color.rgb(133,135,145)
    val outline=Color.rgb(70,73,82)
    val divider=Color.rgb(48,50,58)
    val current=Color.rgb(52,35,43)
    val currentOutline=Color.rgb(184,104,128)
    val error=Color.rgb(229,162,162)
    const val POSTER_ASPECT=2f/3f
    const val ACTION_HEIGHT=44
    const val CHOICE_HEIGHT=40
    fun windowDp(context: Context)=context.resources.configuration.screenWidthDp.toFloat()
    fun posterColumns(context: Context)=BrowseLayout.ranks(windowDp(context),context.resources.configuration.fontScale)
    fun catalogColumns(context: Context)=BrowseLayout.catalog(windowDp(context),context.resources.configuration.fontScale)
    fun homeColumns(context: Context)=BrowseLayout.home(windowDp(context),context.resources.configuration.fontScale)
    fun searchColumns(context: Context)=BrowseLayout.search(windowDp(context),context.resources.configuration.fontScale)
    @android.annotation.SuppressLint("WrongConstant")
    fun balancePosterTitle(view: TextView) { view.breakStrategy=android.text.Layout.BREAK_STRATEGY_BALANCED }
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=8,stroke: Float=1f)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke(if(border==Color.TRANSPARENT) 0 else (stroke*context.resources.displayMetrics.density).toInt().coerceAtLeast(1),border)
    }
    fun bind(view: View,primary: Boolean=false,episode: Boolean=false,quiet: Boolean=false) {
        view.backgroundTintList=null
        view.background=TvControlDrawable(view.context,primary,episode,quiet)
        if(view is TextView) view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(disabled,if(primary) focus else text)))
    }
    fun card(context: Context,focused: Boolean): Drawable=TvControlDrawable(context,outlineOnly=true,forceFocus=focused)
    fun focused(context: Context,color: Int,radius: Int): Drawable=TvControlDrawable(context,fill=color,forceFocus=true,radius=radius)
}

/** Focus is drawn in the fixed hit region so hardware clipping cannot cut the ring. */
internal class TvControlDrawable(context: Context,private val primary: Boolean=false,private val episode: Boolean=false,
    private val quiet: Boolean=false,private val outlineOnly: Boolean=false,private val fill: Int?=null,
    private val forceFocus: Boolean=false,private val radius: Int=8): Drawable() {
    private val unit=context.resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun isStateful()=true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf();return true }
    override fun draw(canvas: Canvas) {
        val focused=state.contains(android.R.attr.state_window_focused) && (forceFocus || state.contains(android.R.attr.state_focused))
        val enabled=outlineOnly || forceFocus || state.contains(android.R.attr.state_enabled)
        val selected=state.contains(android.R.attr.state_selected) || state.contains(android.R.attr.state_checked)
        val box=RectF(bounds);val r=radius*unit
        if(!outlineOnly) {
            paint.style=Paint.Style.FILL
            paint.color=fill ?: when { !enabled->TvStyle.subtle;episode && selected->TvStyle.current;primary->TvStyle.primary;focused->TvStyle.raised;selected->TvStyle.selected;quiet->TvStyle.subtle;else->TvStyle.surface }
            paint.shader=if(primary && enabled) LinearGradient(box.left,box.top,box.right,box.top,TvStyle.primary,TvStyle.primaryEnd,Shader.TileMode.CLAMP) else null
            canvas.drawRoundRect(box,r,r,paint);paint.shader=null
            if(episode && selected) { paint.style=Paint.Style.STROKE;paint.strokeWidth=unit;paint.color=TvStyle.currentOutline;box.inset(unit/2,unit/2);canvas.drawRoundRect(box,r,r,paint);box.inset(-unit/2,-unit/2) }
            if(enabled && state.contains(android.R.attr.state_pressed)) { paint.style=Paint.Style.FILL;paint.color=0x14000000;canvas.drawRoundRect(box,r,r,paint) }
        }
        if(focused && enabled) {
            box.inset(3.5f*unit,3.5f*unit);paint.style=Paint.Style.STROKE;paint.strokeWidth=7*unit;paint.color=TvStyle.background
            canvas.drawRoundRect(box,r,r,paint)
            box.inset(-2*unit,-2*unit);paint.strokeWidth=3*unit;paint.color=TvStyle.focus
            canvas.drawRoundRect(box,r,r,paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
