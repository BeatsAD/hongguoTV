// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.text.TextUtils
import android.widget.ImageView
import android.widget.LinearLayout
import com.hongguotv.core.Series

/** Flat detail composition. The episode section follows immediately, rather than a discovery Hero. */
internal class DetailHeader(context: Context,series: Series,episodes: Int,playLabel: String,load: (ImageView,String)->Unit,
    synopsis: ()->Unit,playAction: ()->Unit,favoriteAction: ()->Unit,laterAction: ()->Unit,
    favorite: Boolean,queued: Boolean): LinearLayout(context) {
    private val ui=BrowseUi(context)
    val play=BrowseButton(context,playLabel,BrowseButton.Role.PRIMARY,action=playAction).leadingIcon(TvGlyph.Kind.PLAY).apply { tag="detail:play" }
    val favoriteButton=BrowseButton(context,"",action=favoriteAction).apply { tag="detail:favorite" }
    val later=BrowseButton(context,"",action=laterAction).apply { tag="detail:later" }
    val synopsisButton=BrowseButton(context,"完整简介",BrowseButton.Role.TEXT,action=synopsis).trailingIcon(TvGlyph.Kind.NEXT).apply { tag="detail:synopsis";textSize=12f }
    val actions get()=listOf(play,favoriteButton,later)
    fun setFavorite(value: Boolean) { favoriteButton.text=if(value) "已收藏" else "收藏";favoriteButton.leadingIcon(if(value) TvGlyph.Kind.CHECK else TvGlyph.Kind.PLUS) }
    fun setQueued(value: Boolean) { later.text=if(value) "已在稍后看" else "稍后看";later.leadingIcon(if(value) TvGlyph.Kind.CHECK else TvGlyph.Kind.PLUS) }
    init {
        setFavorite(favorite);setQueued(queued)
        orientation=HORIZONTAL;clipChildren=false;gravity=android.view.Gravity.TOP
        val compact=BrowseLayout.compact(TvStyle.windowDp(context));val posterWidth=if(compact) 112 else 162
        val poster=ImageView(context).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP;background=TvStyle.shape(context,TvStyle.surface,radius=8);clipToOutline=true
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(poster,LayoutParams(ui.dp(posterWidth),ui.dp(posterWidth*3/2)));load(poster,series.cover)
        val copy=ui.column()
        copy.addView(ui.text(series.title,if(compact) 21f else 24f).apply {
            typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);maxLines=2;ellipsize=TextUtils.TruncateAt.END;TvStyle.balancePosterTitle(this)
        })
        copy.addView(ui.text(listOf("全${episodes}集",series.tags).filter(String::isNotBlank).joinToString(" · "),13f,0xFFBFC4D0.toInt()).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END },LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
        copy.addView(ui.text(series.description.ifBlank { "选择剧集开始观看" },13f,0xFFB1B6C3.toInt()).apply {
            maxLines=if(compact) 2 else 3;ellipsize=TextUtils.TruncateAt.END;setLineSpacing(ui.dp(3).toFloat(),1f)
        },LayoutParams(-1,-2).apply { topMargin=ui.dp(10) })
        copy.addView(synopsisButton,LayoutParams(-2,-2))
        val actionRow=BrowseFlow(context)
        actions.forEach { actionRow.addView(it) }
        copy.addView(actionRow,LayoutParams(-1,-2).apply { topMargin=ui.dp(if(compact) 8 else 24) })
        addView(copy,LayoutParams(0,-2,1f).apply { leftMargin=ui.dp(if(compact) 16 else 22) })
    }
}
