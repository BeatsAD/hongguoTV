// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*

/** A remote-accessible side panel; selecting a number is the only action that changes episodes. */
class EpisodePanel(
    private val activity: Activity,private val count: Int,private val current: Int,
    private val locate: (Int,View,(Int)->Unit)->Unit,
    private val select: (Int)->Unit,private val closed: ()->Unit
) {
    private val dialog=Dialog(activity)
    private var group=current/20
    private var focused=current
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(6),dp(8),dp(6)) }
    private fun button(value: String,active: Boolean=false,action: ()->Unit)=label(value,16f).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER; minHeight=dp(46); isFocusable=true; isFocusableInTouchMode=true
        fun paint(focus: Boolean) { setTextColor(if(active) TvStyle.background else TvStyle.text); background=if(active && focus) TvStyle.focused(activity,TvStyle.text,8) else TvStyle.shape(activity,if(active) TvStyle.text else if(focus) TvStyle.raised else TvStyle.background,if(focus) TvStyle.text else Color.TRANSPARENT,8) }
        paint(false); setOnFocusChangeListener { _,hasFocus -> paint(hasFocus) }; setOnClickListener { action() }
    }
    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        render(); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            attributes=attributes.apply { x=(activity.resources.displayMetrics.widthPixels*.05f).toInt() }
            setLayout(minOf(dp(410),(activity.resources.displayMetrics.widthPixels*.8f).toInt()),(activity.resources.displayMetrics.heightPixels*.9f).toInt())
        }
    }
    fun dismiss()=dialog.dismiss()
    private fun render() {
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(22),dp(20),dp(22)); background=TvStyle.shape(activity,TvStyle.surface,radius=20) }
        val header=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        val close=button("×") { dismiss() }.apply { contentDescription="关闭选集"; textSize=23f }
        header.addView(label("选集",21f),LinearLayout.LayoutParams(0,-2,1f)); header.addView(close,LinearLayout.LayoutParams(dp(36),dp(38)))
        content.addView(header); content.addView(label("正在看第 ${current+1} 集",12f))
        val actions=LinearLayout(activity).apply { gravity=Gravity.END }
        lateinit var jump: TextView
        jump=button("跳转集数") { locate(focused,jump) { target -> focused=target; group=target/20; render() } }
        actions.addView(jump,LinearLayout.LayoutParams(-2,-2).apply { setMargins(dp(3),dp(10),dp(3),dp(8)) })
        content.addView(actions)
        val paging=LinearLayout(activity)
        val previous=button("‹ 上一组") { group--; focused=group*20; render() }.apply { isEnabled=group>0; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        val next=button("下一组 ›") { group++; focused=group*20; render() }.apply { isEnabled=(group+1)*20<count; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        listOf(previous,next).forEach { paging.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
        content.addView(paging)
        content.addView(label("第 ${group*20+1}—${minOf((group+1)*20,count)} 集 / 共 $count 集",14f))
        val scroll=ScrollView(activity); val grid=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }; scroll.addView(grid)
        content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val cells=mutableListOf<TextView>()
        (group*20 until minOf((group+1)*20,count)).toList().chunked(5).forEach { indexes ->
            val row=LinearLayout(activity)
            indexes.forEach { index ->
                val cell=button("${index+1}",index==current) { select(index) }
                cell.contentDescription="第 ${index+1} 集"+(if(index==current) "，当前播放" else "")
                row.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }); cells+=cell
            }
            repeat(5-indexes.size) { row.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f)) }
            grid.addView(row)
        }
        cells.forEachIndexed { i,cell ->
            cell.nextFocusLeftId=cells[if(i%5==0) i else i-1].id
            cell.nextFocusRightId=cells[if(i%5==4 || i==cells.lastIndex) i else i+1].id
            cell.nextFocusUpId=if(i>=5) cells[i-5].id else if(i<3 && previous.isFocusable) previous.id else if(next.isFocusable) next.id else jump.id
            cell.nextFocusDownId=cells[if(i+5<cells.size) i+5 else if(i/5<cells.lastIndex/5) cells.lastIndex else i].id
        }
        previous.nextFocusUpId=jump.id; next.nextFocusUpId=jump.id
        previous.nextFocusDownId=cells.first().id; next.nextFocusDownId=cells[minOf(4,cells.lastIndex)].id
        close.nextFocusDownId=jump.id
        jump.nextFocusDownId=if(next.isFocusable) next.id else cells[minOf(4,cells.lastIndex)].id
        close.nextFocusLeftId=close.id; close.nextFocusRightId=close.id; jump.nextFocusLeftId=jump.id; jump.nextFocusRightId=jump.id; jump.nextFocusUpId=close.id
        dialog.setContentView(content)
        cells[(focused-group*20).coerceIn(0,cells.lastIndex)].requestFocus()
    }
}
