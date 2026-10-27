// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** One card for home, catalog, ranks and local records. Overlays never stretch the image. */
class PosterCardView(context: Context): LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val poster=FrameLayout(context).apply { background=TvStyle.shape(context,TvStyle.surface,radius=8); clipToOutline=true }
    private val chips=LinearLayout(context).apply { orientation=HORIZONTAL; clipChildren=true }
    private val count=label(10f).apply { setPadding(dp(4),dp(2),dp(4),dp(2)); background=TvStyle.shape(context,0xB3000000.toInt(),radius=3) }
    private val title=label(13f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(5),0,dp(3)) }
    private val subtitle=label(10f,TvStyle.muted).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    private val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(TvStyle.accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(0x66000000) }
    private var names=emptyList<String>()
    private var fitted=""
    private fun label(size: Float,color: Int=TvStyle.text)=TextView(context).apply { textSize=size;setTextColor(color);includeFontPadding=false }
    init {
        orientation=VERTICAL; id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; clipChildren=false
        setPadding(dp(3),dp(3),dp(3),dp(3))
        poster.addView(image,FrameLayout.LayoutParams(-1,-1)); poster.addView(chips,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { setMargins(dp(4),dp(5),dp(4),0) })
        poster.addView(count,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { setMargins(0,0,dp(5),dp(6)) })
        poster.addView(progress,FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM))
        addView(poster,LayoutParams(-1,dp(120)));addView(title,LayoutParams(-1,-2));addView(subtitle,LayoutParams(-1,-2))
        setOnFocusChangeListener { _,focused -> poster.foreground=TvStyle.card(context,focused) }
    }
    fun bind(series: Series,caption: String="",fraction: Float?=null) {
        title.text=series.title; subtitle.text=caption;subtitle.visibility=if(caption.isBlank()) GONE else VISIBLE
        count.text=series.episodeCount?.let { "全${it}集" }.orEmpty();count.visibility=if(count.text.isEmpty()) GONE else VISIBLE
        names=series.tagNames.distinct().filter(String::isNotBlank).take(3)
        progress.visibility=if(fraction==null) GONE else VISIBLE; progress.progress=((fraction ?: 0f).coerceIn(0f,1f)*1000).toInt()
        contentDescription=listOf(series.title,series.tags,count.text.toString(),caption).filter(String::isNotBlank).joinToString("，")
        fitted="";fitTags(poster.width-dp(8))
    }
    private fun fitTags(available: Int) {
        if(available<=0) return
        val key="$available:${names.joinToString("|")}"
        if(fitted==key) return
        fitted=key
        chips.removeAllViews(); var used=0
        names.forEach { name ->
            val chip=label(9f).apply { text=name;setPadding(dp(4),dp(2),dp(4),dp(2));background=TvStyle.shape(context,0xB3000000.toInt(),radius=3);maxLines=1 }
            val width=kotlin.math.ceil(chip.paint.measureText(name)).toInt()+dp(8)
            if(used+width<=available) { chips.addView(chip,LayoutParams(width,-2).apply { rightMargin=dp(3) });used+=width+dp(3) }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight
        val height=(w/TvStyle.POSTER_ASPECT).toInt()
        if(poster.layoutParams.height!=height) poster.layoutParams=poster.layoutParams.apply { this.height=height }
        fitTags(w-dp(8))
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
