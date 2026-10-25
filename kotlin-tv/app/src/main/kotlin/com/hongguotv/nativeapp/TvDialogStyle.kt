// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.TextView

/** Shared modal geometry; leave each owner's dismissal and focus-return callbacks intact. */
object TvDialogStyle {
    fun apply(dialog: AlertDialog) {
        val context=dialog.context; val metrics=context.resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.dialog_surface)
            setLayout(minOf((metrics.widthPixels*.46f).toInt(),(460*metrics.density).toInt()),ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.listView?.apply {
            selector=TvStyle.shape(context,TvStyle.raised,TvStyle.text,radius=12)
            divider=ColorDrawable(TvStyle.outline); dividerHeight=(metrics.density*.5f).toInt().coerceAtLeast(1)
        }
        listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply { textSize=13f; setTextColor(TvStyle.text); minHeight=(42*metrics.density).toInt() }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply { textSize=13f; setTextColor(Color.rgb(174,176,187)) }
    }
}
