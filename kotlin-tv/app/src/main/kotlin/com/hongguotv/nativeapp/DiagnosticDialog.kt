// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.Diagnostics

internal object DiagnosticDialog {
    fun create(context: Context,extra: String=""): AlertDialog {
        val report="hongguo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n"+
            "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}\n"+
            "时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.CHINA).format(java.util.Date())}\n"+
            extra+"\n最近诊断（本次运行，最多 80 条）：\n"+Diagnostics.snapshot().ifBlank { "暂无记录，请复现问题后再查看。" }
        val density=context.resources.displayMetrics.density
        val content=TextView(context).apply {
            text=report;textSize=12f;setTextColor(TvStyle.text)
            setPadding((20*density).toInt(),(12*density).toInt(),(20*density).toInt(),(12*density).toInt())
        }
        val scroll=object: ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val limit=TvDialogStyle.diagnosticBodyHeight(context)
                val available=if(View.MeasureSpec.getMode(heightMeasureSpec)==View.MeasureSpec.UNSPECIFIED) limit else minOf(limit,View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.AT_MOST))
            }
        }.apply { isFocusable=true;isFocusableInTouchMode=true;addView(content) }
        return AlertDialog.Builder(context).setTitle("诊断详情").setView(scroll).setPositiveButton("关闭",null).setNeutralButton("复制信息") { _,_->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("hongguo 诊断",report))
            Toast.makeText(context,"诊断信息已复制",Toast.LENGTH_SHORT).show()
        }.create().also { dialog -> dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEUTRAL).requestFocus() } }
    }
}
