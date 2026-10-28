// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.text.TextUtils
import android.widget.ImageView
import android.widget.LinearLayout
import com.hongguotv.core.Series

/** Compact normal layout leaves room for two rows of remote-sized episodes. */
internal class DetailHeader(context: Context,series: Series,episodes: Int,playLabel: String,load: (ImageView,String)->Unit,
    synopsis: ()->Unit,playAction: ()->Unit,favoriteAction: ()->Unit,laterAction: ()->Unit,
    favorite: Boolean,queued: Boolean): LinearLayout(context) {
    private val ui=BrowseUi(context)
    val play=BrowseButton(context,playLabel,BrowseButton.Role.PRIMARY,action=playAction).leadingIcon(TvGlyph.Kind.PLAY).apply { tag="detail:play" }
    val favoriteButton=BrowseButton(context,"",action=favoriteAction).apply { tag="detail:favorite" }
    val later=BrowseButton(context,"",action=laterAction).apply { tag="detail:later" }
    val synopsisButton=BrowseButton(context,"简介",BrowseButton.Role.TEXT,action=synopsis).trailingIcon(TvGlyph.Kind.NEXT).apply { tag="detail:synopsis";contentDescription="查看完整简介" }
    val actions get()=listOf(play,favoriteButton,later,synopsisButton)
    fun setFavorite(value: Boolean) { favoriteButton.text=if(value) "已收藏" else "收藏";favoriteButton.leadingIcon(if(value) TvGlyph.Kind.CHECK else TvGlyph.Kind.STAR) }
    fun setQueued(value: Boolean) { later.text=if(value) "已在稍后看" else "稍后看";later.leadingIcon(if(value) TvGlyph.Kind.CHECK else TvGlyph.Kind.HISTORY) }
    init {
        setFavorite(favorite);setQueued(queued)
        orientation=HORIZONTAL;clipChildren=false;gravity=android.view.Gravity.TOP
        val poster=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;background=TvStyle.shape(context,TvStyle.surface);clipToOutline=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(poster,LayoutParams(ui.dp(124),ui.dp(186)));load(poster,series.cover)
        val copy=ui.column()
        copy.addView(ui.text(series.title,26f).apply { typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);maxLines=2;ellipsize=TextUtils.TruncateAt.END;TvStyle.balancePosterTitle(this) })
        copy.addView(ui.text(listOf("全${episodes}集",series.tags).filter(String::isNotBlank).joinToString(" · "),14f,TvStyle.muted).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END },LayoutParams(-1,-2).apply { topMargin=ui.dp(4) })
        copy.addView(ui.text(series.description.ifBlank { "选择剧集开始观看" },14f,TvStyle.muted).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END },LayoutParams(-1,-2).apply { topMargin=ui.dp(4) })
        val actionRow=BrowseFlow(context,12).apply { setPadding(ui.dp(6),ui.dp(6),ui.dp(6),ui.dp(6)) }
        actions.forEach { actionRow.addView(it) }
        copy.addView(actionRow,LayoutParams(-1,-2).apply { topMargin=ui.dp(4) })
        addView(copy,LayoutParams(0,-2,1f).apply { leftMargin=ui.dp(18) })
    }
}
