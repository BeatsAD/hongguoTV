// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.text.TextUtils
import android.widget.*
import com.hongguotv.core.Series

/** Real series identity is available before the network returns; episode actions are not. */
internal class DetailLoadingView(context: Context,series: Series,load: (ImageView,String)->Unit,back: ()->Unit): LinearLayout(context) {
    private val ui=BrowseUi(context)
    val backButton=BrowseButton(context,"返回",BrowseButton.Role.TEXT,action=back).leadingIcon(TvGlyph.Kind.BACK)
    val status=ui.column()
    private val loading=ui.text("正在加载剧集…",14f,TvStyle.muted)
    fun failed() { loading.text="剧集加载失败，可重试或返回" }
    init {
        orientation=VERTICAL;setPadding(ui.dp(6),ui.dp(6),ui.dp(6),ui.dp(6))
        addView(backButton,LayoutParams(-2,-2).apply { bottomMargin=ui.dp(2) })
        val identity=ui.row().apply { gravity=android.view.Gravity.TOP }
        val poster=ImageView(context).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP; background=TvStyle.shape(context,TvStyle.surface)
            clipToOutline=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        identity.addView(poster,LayoutParams(ui.dp(124),ui.dp(186)))
        val copy=ui.column()
        copy.addView(ui.text(series.title,26f).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END;TvStyle.balancePosterTitle(this) })
        copy.addView(loading)
        identity.addView(copy,LayoutParams(0,-2,1f).apply { leftMargin=ui.dp(18) })
        addView(identity,LayoutParams(-1,-2));addView(status,LayoutParams(-1,-2));load(poster,series.cover)
    }
}
