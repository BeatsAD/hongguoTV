// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.text.StaticLayout
import android.text.Layout
import android.widget.Switch
import android.widget.TextView

/** A normal focusable TextView, with label/value columns matching the approved settings rows. */
@SuppressLint("ViewConstructor")
class PreferenceRow(context: Context,label: String,kind: TvGlyph.Kind): TextView(context) {
    private val title=label.substringBefore("  ")
    private val glyph=TvGlyph(kind,TvStyle.text)
    private val chevron=TvGlyph(TvGlyph.Kind.NEXT,TvStyle.muted)
    private val ink=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val badge=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=TvStyle.selected }
    private fun dp(n: Int)=n*resources.displayMetrics.density
    init { text=label; textSize=16f; includeFontPadding=false; minHeight=dp(60).toInt() }
    private fun layout(value: String,size: Float,width: Int,color: Int): StaticLayout {
        val p=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=size*resources.displayMetrics.scaledDensity;typeface=Typeface.create(if(size>=16f) "sans-serif-medium" else "sans-serif",Typeface.NORMAL);this.color=color }
        return StaticLayout.Builder.obtain(value,0,value.length,p,width.coerceAtLeast(1)).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }
    private fun value()=text.toString().removePrefix(title).trim().trimStart('·').trim()
    override fun onMeasure(w: Int,h: Int) {
        super.onMeasure(w,h)
        val valueWidth=(measuredWidth*.36f).toInt()
        val titleWidth=(measuredWidth-dp(92)-valueWidth).toInt()
        val content=maxOf(layout(title,16f,titleWidth,TvStyle.text).height,layout(value(),14f,valueWidth,TvStyle.muted).height)
        setMeasuredDimension(measuredWidth,resolveSize(maxOf(dp(60).toInt(),content+dp(24).toInt()),h))
    }
    override fun onDraw(canvas: Canvas) {
        val center=height/2f
        canvas.drawRoundRect(dp(12),center-dp(16),dp(44),center+dp(16),dp(8),dp(8),badge)
        glyph.setBounds(dp(18).toInt(),(center-dp(10)).toInt(),dp(38).toInt(),(center+dp(10)).toInt());glyph.draw(canvas)
        chevron.setBounds((width-dp(28)).toInt(),(center-dp(8)).toInt(),(width-dp(12)).toInt(),(center+dp(8)).toInt());chevron.draw(canvas)
        val valueWidth=(width*.36f).toInt()
        val titleLayout=layout(title,16f,(width-dp(92)-valueWidth).toInt(),TvStyle.text)
        val valueLayout=layout(value(),14f,valueWidth,TvStyle.muted)
        var save=canvas.save();canvas.translate(dp(56),center-titleLayout.height/2);titleLayout.draw(canvas);canvas.restoreToCount(save)
        save=canvas.save();canvas.translate(width-dp(36)-valueWidth,center-valueLayout.height/2);valueLayout.draw(canvas);canvas.restoreToCount(save)
    }

}

/** Native switch semantics, with the same leading icon and row geometry as the other preferences. */
class PreferenceSwitch(context: Context): Switch(context) {
    private val badge=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=TvStyle.selected }
    private val glyph=TvGlyph(TvGlyph.Kind.PLAY,TvStyle.text)
    private fun dp(n: Int)=n*resources.displayMetrics.density
    init { textSize=16f; setTextColor(TvStyle.text); minHeight=dp(60).toInt(); setPadding(dp(56).toInt(),dp(12).toInt(),dp(15).toInt(),dp(12).toInt()) }
    override fun onDraw(canvas: Canvas) {
        val center=height/2f
        canvas.drawRoundRect(dp(15),center-dp(14),dp(43),center+dp(14),dp(8),dp(8),badge)
        glyph.setBounds(dp(20).toInt(),(center-dp(9)).toInt(),dp(38).toInt(),(center+dp(9)).toInt()); glyph.draw(canvas)
        super.onDraw(canvas)
    }
}
