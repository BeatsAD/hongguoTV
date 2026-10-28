// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Small vector-like glyphs, drawn at the requested density without bitmap assets. */
class TvGlyph(private var kind: Kind,private var color: Int=TvStyle.text,private val filled: Boolean=false): Drawable() {
    enum class Kind { HOME, RANK, BOOKMARK, LAYERS, AI, SEARCH, PLUS, CHECK, ARROW, SETTINGS, PHONE, HISTORY, PLAY, PAUSE, PREVIOUS, SKIP, REWIND, FORWARD, NEXT, QUALITY, SPEED, FRAME, FILM, TRANSFER, EYE, INFO, CODE }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=1.7f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        val scale=minOf(bounds.width(),bounds.height())/24f
        if(scale<=0f) return
        val save=canvas.save()
        canvas.translate(bounds.exactCenterX()-12f*scale,bounds.exactCenterY()-12f*scale)
        canvas.scale(scale,scale);paint.color=color;paint.style=if(filled) Paint.Style.FILL else Paint.Style.STROKE
        fun line(x: Float,y: Float,a: Float,b: Float)=canvas.drawLine(x,y,a,b,paint)
        fun circle(x: Float,y: Float,r: Float)=canvas.drawCircle(x,y,r,paint)
        when(kind) {
            Kind.HOME -> { val p=Path().apply { moveTo(3f,10f);lineTo(12f,2f);lineTo(21f,10f);lineTo(21f,21f);lineTo(15f,21f);lineTo(15f,14f);lineTo(9f,14f);lineTo(9f,21f);lineTo(3f,21f);close() };canvas.drawPath(p,paint) }
            Kind.RANK -> { val p=Path().apply { moveTo(13f,3f);cubicTo(14f,8f,20f,10f,20f,15f);cubicTo(20f,20f,16f,22f,12f,22f);cubicTo(7f,22f,4f,19f,4f,15f);cubicTo(4f,12f,5f,9f,8f,7f);cubicTo(7f,11f,9f,14f,12f,14f);cubicTo(15f,14f,16f,10f,13f,3f);close() };canvas.drawPath(p,paint) }
            Kind.BOOKMARK -> { val p=Path().apply { moveTo(5f,3f);lineTo(19f,3f);lineTo(19f,22f);lineTo(12f,17f);lineTo(5f,22f);close() };canvas.drawPath(p,paint) }
            Kind.LAYERS -> { for(y in listOf(8f,13f,18f)) { val p=Path().apply { moveTo(3f,y);lineTo(12f,y+5);lineTo(21f,y) };canvas.drawPath(p,paint) };line(3f,8f,12f,3f);line(12f,3f,21f,8f) }
            Kind.AI -> { canvas.drawRoundRect(3f,6f,21f,21f,3f,3f,paint);line(12f,2f,12f,6f);circle(8f,12f,1f);circle(16f,12f,1f);line(8f,17f,16f,17f) }

            Kind.SEARCH -> { circle(10f,10f,6.5f); line(15f,15f,21f,21f) }
            Kind.PLUS -> { line(4f,12f,20f,12f); line(12f,4f,12f,20f) }
            Kind.CHECK -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
            Kind.ARROW -> { line(3f,12f,21f,12f); line(14f,5f,21f,12f); line(21f,12f,14f,19f) }
            Kind.SETTINGS -> { circle(12f,12f,3f); circle(12f,12f,7f); repeat(8) { val a=it*Math.PI/4; line(12+cos(a).toFloat()*7,12+sin(a).toFloat()*7,12+cos(a).toFloat()*9.5f,12+sin(a).toFloat()*9.5f) } }
            Kind.PHONE -> { canvas.drawRoundRect(6f,2f,18f,22f,2f,2f,paint); line(10f,18f,14f,18f) }
            Kind.HISTORY,Kind.SPEED -> { circle(12f,12f,9f); line(12f,6f,12f,12f); line(12f,12f,17f,14f) }
            Kind.PLAY -> { val p=Path().apply { moveTo(7f,4f); lineTo(19f,12f); lineTo(7f,20f); close() }; canvas.drawPath(p,paint) }
            Kind.PAUSE -> { line(8f,5f,8f,19f); line(16f,5f,16f,19f) }
            Kind.PREVIOUS,Kind.SKIP -> { val previous=kind==Kind.PREVIOUS; if(previous) { canvas.save(); canvas.rotate(180f,12f,12f) }; val p=Path().apply { moveTo(5f,5f); lineTo(16f,12f); lineTo(5f,19f); close() }; canvas.drawPath(p,paint); line(19f,5f,19f,19f); if(previous) canvas.restore() }
            Kind.REWIND,Kind.FORWARD -> { val forward=kind==Kind.FORWARD; if(forward) { canvas.save(); canvas.rotate(180f,12f,12f) }; canvas.drawArc(4f,4f,20f,20f,210f,280f,false,paint); line(3f,5f,4f,12f); line(4f,12f,11f,10f); if(forward) canvas.restore() }
            Kind.NEXT -> { line(9f,5f,16f,12f); line(16f,12f,9f,19f) }
            Kind.QUALITY -> { canvas.drawRoundRect(3f,4f,21f,17f,2f,2f,paint);line(12f,17f,12f,21f);line(8f,21f,16f,21f) }
            Kind.FRAME -> { canvas.drawRoundRect(3f,5f,21f,19f,2f,2f,paint);line(8f,9f,8f,15f);line(16f,9f,16f,15f) }
            Kind.FILM -> { canvas.drawRoundRect(2f,4f,22f,20f,1f,1f,paint);line(7f,4f,7f,20f);line(17f,4f,17f,20f);for(y in listOf(8f,12f,16f)) { line(2f,y,7f,y);line(17f,y,22f,y) } }
            Kind.TRANSFER -> { line(7f,4f,7f,20f); line(3f,8f,7f,4f); line(7f,4f,11f,8f); line(17f,4f,17f,20f); line(13f,16f,17f,20f); line(17f,20f,21f,16f) }
            Kind.EYE -> { val p=Path().apply { moveTo(2f,12f); quadTo(12f,-1f,22f,12f); quadTo(12f,25f,2f,12f) }; canvas.drawPath(p,paint); circle(12f,12f,3f) }
            Kind.INFO -> { circle(12f,12f,9f); line(12f,10f,12f,17f); circle(12f,6.5f,.5f) }
            Kind.CODE -> { line(8f,6f,2f,12f); line(2f,12f,8f,18f); line(16f,6f,22f,12f); line(22f,12f,16f,18f); line(14f,3f,10f,21f) }
        }
        canvas.restoreToCount(save)
    }
    fun kind(value: Kind) { if(kind!=value) { kind=value; invalidateSelf() } }
    fun color(value: Int) { color=value; invalidateSelf() }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
