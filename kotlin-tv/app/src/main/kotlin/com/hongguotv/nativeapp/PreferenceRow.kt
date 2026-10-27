// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Switch
import android.widget.TextView

/** A normal focusable TextView, with label/value columns matching the approved settings rows. */
@SuppressLint("ViewConstructor")
class PreferenceRow(context: Context,label: String,kind: TvGlyph.Kind): TextView(context) {
    private val title=label.substringBefore("  ")
    private val glyph=TvGlyph(kind,TvStyle.text)
    private val chevron=TvGlyph(TvGlyph.Kind.NEXT,TvStyle.muted)
    private val ink=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val badge=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(52,53,59) }
    private fun dp(n: Int)=n*resources.displayMetrics.density
    init { text=label; textSize=14f; includeFontPadding=false; minHeight=dp(54).toInt() }
    override fun onDraw(canvas: Canvas) {
        val center=height/2f
        canvas.drawRoundRect(dp(15),center-dp(14),dp(43),center+dp(14),dp(8),dp(8),badge)
        glyph.setBounds(dp(21).toInt(),(center-dp(8)).toInt(),dp(37).toInt(),(center+dp(8)).toInt()); glyph.draw(canvas)
        chevron.setBounds((width-dp(25)).toInt(),(center-dp(6)).toInt(),(width-dp(13)).toInt(),(center+dp(6)).toInt()); chevron.draw(canvas)
        val value=text.toString().removePrefix(title).trim().trimStart('·').trim()
        ink.typeface=Typeface.create("sans-serif",Typeface.NORMAL); ink.textSize=13*resources.displayMetrics.scaledDensity; ink.color=TvStyle.muted
        val valueWidth=ink.measureText(value).coerceAtMost(width*.42f)
        val shortenedValue=TextUtils.ellipsize(value,ink,valueWidth,TextUtils.TruncateAt.END).toString()
        val right=width-dp(35); val metrics=ink.fontMetrics
        canvas.drawText(shortenedValue,right-ink.measureText(shortenedValue),center-(metrics.ascent+metrics.descent)/2,ink)
        ink.textSize=14*resources.displayMetrics.scaledDensity; ink.color=TvStyle.text
        val available=(right-valueWidth-dp(14)-dp(56)).coerceAtLeast(dp(20))
        val shortenedTitle=TextUtils.ellipsize(title,ink,available,TextUtils.TruncateAt.END).toString()
        val titleMetrics=ink.fontMetrics
        canvas.drawText(shortenedTitle,dp(56),center-(titleMetrics.ascent+titleMetrics.descent)/2,ink)
    }
}

/** Native switch semantics, with the same leading icon and row geometry as the other preferences. */
class PreferenceSwitch(context: Context): Switch(context) {
    private val badge=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(52,53,59) }
    private val glyph=TvGlyph(TvGlyph.Kind.PLAY,TvStyle.text)
    private fun dp(n: Int)=n*resources.displayMetrics.density
    init { textSize=14f; setTextColor(TvStyle.text); minHeight=dp(54).toInt(); setPadding(dp(56).toInt(),dp(12).toInt(),dp(15).toInt(),dp(12).toInt()) }
    override fun onDraw(canvas: Canvas) {
        val center=height/2f
        canvas.drawRoundRect(dp(15),center-dp(14),dp(43),center+dp(14),dp(8),dp(8),badge)
        glyph.setBounds(dp(21).toInt(),(center-dp(8)).toInt(),dp(37).toInt(),(center+dp(8)).toInt()); glyph.draw(canvas)
        super.onDraw(canvas)
    }
}
