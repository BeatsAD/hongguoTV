// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** Full-bleed cinematic cover and fixed copy hierarchy from the approved design. */
class CinemaHero(context: Context,private val load: (ImageView,String)->Unit,private val open: (Series,Boolean)->Unit): FrameLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(size: Float,color: Int=TvStyle.text)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val cover=CinematicArtwork(context).apply { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val title=label(58f).apply { typeface=Typeface.create("serif",Typeface.NORMAL); letterSpacing=.04f; gravity=Gravity.BOTTOM; setAutoSizeTextTypeUniformWithConfiguration(32,58,2,android.util.TypedValue.COMPLEX_UNIT_SP); maxLines=2; ellipsize=TextUtils.TruncateAt.END }
    private val meta=label(12f,android.graphics.Color.rgb(193,197,206)).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    private val description=label(14f,android.graphics.Color.rgb(214,216,221)).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END }
    private val eyebrowContext=label(11f).apply { text="热门发现"; letterSpacing=.1f }
    private val copy=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.BOTTOM }
    private var series: Series?=null
    private var url=""
    var onFocused: (View)->Unit={}
    val play=action("开始观看","home:play") { series?.let { open(it,true) } }
    val details=action("剧集详情","home:detail") { series?.let { open(it,false) } }
    val actions get()=listOf(play,details)
    private fun action(value: String,key: String,click: ()->Unit)=TextView(context).apply {
        val glyph=TvGlyph(if(key=="home:play") TvGlyph.Kind.PLAY else TvGlyph.Kind.INFO).apply { setBounds(0,0,dp(17),dp(17)) }; setCompoundDrawables(glyph,null,null,null); compoundDrawablePadding=dp(8)
        id=View.generateViewId(); tag=key; text=value; textSize=14f; gravity=Gravity.CENTER; isFocusable=true; isFocusableInTouchMode=true
        setPadding(dp(17),dp(10),dp(17),dp(10)); minHeight=dp(42)
        fun paint(f: Boolean) { glyph.color(if(f || key=="home:play") TvStyle.background else TvStyle.text); setTextColor(if(f || key=="home:play") TvStyle.background else TvStyle.text); background=if(f) TvStyle.focused(context,TvStyle.text,10) else TvStyle.shape(context,if(key=="home:play") TvStyle.text else TvStyle.raised,radius=10) }
        paint(false); setOnFocusChangeListener { view,f -> paint(f); if(f) onFocused(view) }; setOnClickListener { click() }
    }
    init {
        minimumHeight=dp(390)
        addView(cover,LayoutParams(-1,-1))
        addView(View(context).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(TvStyle.background,android.graphics.Color.argb(210,11,12,15),android.graphics.Color.TRANSPARENT)) },LayoutParams(-1,-1))
        addView(View(context).apply { background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(TvStyle.background,android.graphics.Color.TRANSPARENT)) },LayoutParams(-1,dp(170),Gravity.BOTTOM))
        val eyebrow=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        eyebrow.addView(label(10f,TvStyle.background).apply { text="红果"; setPadding(dp(5),dp(2),dp(5),dp(2)); background=TvStyle.shape(context,TvStyle.accent,radius=4) })
        eyebrow.addView(eyebrowContext,LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(9) })
        copy.addView(eyebrow,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        copy.addView(title,LinearLayout.LayoutParams(-1,dp(135))); copy.addView(meta,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(9) })
        copy.addView(description,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val buttons=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
        buttons.addView(play,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(10) }); buttons.addView(details)
        copy.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(17) })
        addView(copy,LayoutParams(-1,-1).apply { rightMargin=dp(360); bottomMargin=dp(32) })
        show(null,"","开始观看")
    }
    fun show(value: Series?,status: String,playLabel: String) {
        series=value; val longTitle=(value?.title?.length ?: 0)>8; title.setAutoSizeTextTypeUniformWithConfiguration(28,if(longTitle) 48 else 58,2,android.util.TypedValue.COMPLEX_UNIT_SP); title.layoutParams=title.layoutParams.apply { height=dp(if(longTitle) 110 else 78) }; title.text=value?.title ?: "红果 TV"; meta.text=value?.let { listOf(it.tags,it.badge,status).filter(String::isNotBlank).distinct().joinToString("  ") }.orEmpty()
        description.text=value?.description.orEmpty(); play.text=playLabel
        actions.forEach { it.isEnabled=value!=null; it.isFocusable=value!=null; it.alpha=if(value==null) .4f else 1f }
        if(value?.cover.orEmpty()!=url) { url=value?.cover.orEmpty(); cover.setImageDrawable(null); if(url.isNotBlank()) load(cover,url) }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        cover.layoutParams=cover.layoutParams.apply { width=w }; copy.setPadding((w*42f/1024).toInt(),0,0,0)
        (copy.layoutParams as LayoutParams).let { it.rightMargin=(w*.46f).toInt(); copy.layoutParams=it }
    }
    fun contextLabel(value: String) { eyebrowContext.text=value }
    fun reload() { if(url.isNotBlank()) load(cover,url) }
}
