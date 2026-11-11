// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** Portrait poster and independent copy; never enlarge a cover into a landscape backdrop. */
class CinemaHero(context: Context,private val load: (ImageView,String)->Unit,private val open: (Series,Boolean)->Unit): FrameLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(size: Float,color: Int=TvStyle.text)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val cover=CinematicArtwork(context).apply { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val poster=FrameLayout(context).apply { background=TvStyle.shape(context,TvStyle.surface,radius=12); clipToOutline=true; addView(cover,LayoutParams(-1,-1)) }
    private val title=label(40f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); gravity=Gravity.CENTER_VERTICAL; maxLines=2; TvStyle.balancePosterTitle(this); ellipsize=TextUtils.TruncateAt.END }
    private val meta=label(12f,Color.rgb(193,197,206)).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END }
    private val description=label(14f,Color.rgb(214,216,221)).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END }
    private val eyebrowContext=label(11f).apply { text="热门发现"; letterSpacing=.1f }
    private val copy=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL }
    private var series: Series?=null
    private var url=""
    val preferredHeight get()=dp(if(resources.configuration.fontScale>1.2f) 350 else 300)
    var onFocused: (View)->Unit={}
    val play=action("开始观看","home:play") { series?.let { open(it,true) } }
    val details=action("剧集详情","home:detail") { series?.let { open(it,false) } }
    val actions get()=listOf(play,details)
    private fun action(value: String,key: String,click: ()->Unit)=TextView(context).apply {
        val glyph=TvGlyph(if(key=="home:play") TvGlyph.Kind.PLAY else TvGlyph.Kind.INFO).apply { setBounds(0,0,dp(17),dp(17)) }; setCompoundDrawables(glyph,null,null,null); compoundDrawablePadding=dp(8)
        val compact=resources.displayMetrics.widthPixels/resources.displayMetrics.density<800
        id=View.generateViewId(); tag=key; text=value; textSize=if(compact) 13f else 14f; gravity=Gravity.CENTER; isFocusable=true; isFocusableInTouchMode=true
        val horizontal=dp(if(compact) 12 else 17); setPadding(horizontal,dp(10),horizontal,dp(10)); minHeight=dp(42)
        fun paint(f: Boolean) { glyph.color(if(f || key=="home:play") TvStyle.background else TvStyle.text); setTextColor(if(f || key=="home:play") TvStyle.background else TvStyle.text); background=if(f) TvStyle.focused(context,TvStyle.text,10) else TvStyle.shape(context,if(key=="home:play") TvStyle.text else TvStyle.raised,radius=10) }
        paint(false); setOnFocusChangeListener { view,f -> paint(f); if(f) onFocused(view) }; setOnClickListener { click() }
    }
    init {
        background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.rgb(25,26,31),Color.rgb(17,18,22))).apply { cornerRadius=dp(18).toFloat() }
        addView(poster,LayoutParams(dp(173),dp(260),Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin=dp(20) })
        val eyebrow=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        eyebrow.addView(label(10f,TvStyle.background).apply { text="hongguo"; setPadding(dp(5),dp(2),dp(5),dp(2)); background=TvStyle.shape(context,TvStyle.accent,radius=4) })
        eyebrow.addView(eyebrowContext,LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(9) })
        copy.addView(eyebrow,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        copy.addView(title,LinearLayout.LayoutParams(-1,dp(90))); copy.addView(meta,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        copy.addView(description,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val buttons=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
        buttons.addView(play,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(10) }); buttons.addView(details)
        copy.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
        addView(copy,LayoutParams(-1,-1).apply { leftMargin=dp(221); rightMargin=dp(24); topMargin=dp(20); bottomMargin=dp(20) })
        show(null,"","开始观看")
    }
    fun show(value: Series?,status: String,playLabel: String) {
        series=value
        val longTitle=(value?.title?.length ?: 0)>8; val large=resources.configuration.fontScale>1.2f
        title.setAutoSizeTextTypeUniformWithConfiguration(24,if(longTitle) 34 else 40,2,android.util.TypedValue.COMPLEX_UNIT_SP)
        title.layoutParams=title.layoutParams.apply { height=dp(if(longTitle) if(large) 108 else 90 else if(large) 82 else 62) }
        title.text=value?.title ?: "hongguo"; meta.text=value?.let { listOf(it.tags,it.badge,status).filter(String::isNotBlank).distinct().joinToString("  ") }.orEmpty()
        description.text=value?.description.orEmpty(); description.visibility=if(description.text.isBlank()) GONE else VISIBLE; play.text=playLabel
        actions.forEach { it.isEnabled=value!=null; it.isFocusable=value!=null; it.alpha=if(value==null) .4f else 1f }
        if(value?.cover.orEmpty()!=url) { url=value?.cover.orEmpty(); cover.setImageDrawable(null); if(url.isNotBlank()) load(cover,url) }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val posterWidth=minOf(((h-dp(40))*TvStyle.POSTER_ASPECT).toInt(),(w*.28f).toInt()).coerceAtLeast(1)
        poster.layoutParams=poster.layoutParams.apply { width=posterWidth; height=(posterWidth/TvStyle.POSTER_ASPECT).toInt() }
        copy.layoutParams=(copy.layoutParams as LayoutParams).apply { leftMargin=dp(48)+posterWidth }
    }
    fun contextLabel(value: String) { eyebrowContext.text=value }
    fun reload() { if(url.isNotBlank()) load(cover,url) }
}
