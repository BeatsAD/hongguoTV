// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.OneShotPreDrawListener

/** At most one current page and one inert outgoing page; no screenshot or permanent page cache. */
internal class PageTransitionHost(context: Context): FrameLayout(context) {
    private val pages=PageTransitionState<View>()
    init { clipChildren = false }

    fun replace(next: View, duration: Long = TvMotion.PAGE, direction: Float = 1f, animate: Boolean = true) {
        finish()
        val previous = pages.current?.takeIf { it.parent === this }
        if(previous==null) { pages.clear();if(childCount>0) super.removeAllViews() }
        val ticket=pages.begin(next)
        previous?.let {
            it.clearFocus()
            freeze(it)
            if (it is ViewGroup) it.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        addView(next, LayoutParams(-1, -1))
        if (previous == null || !animate || !TvMotion.enabled() || !isAttachedToWindow) {
            pages.complete(ticket)?.let(::removeView)
            return
        }
        next.alpha = 0f
        OneShotPreDrawListener.add(next) {
            if (!pages.accepts(ticket) || pages.current !== next) return@add
            TvMotion.appear(next, duration, x = direction * 8f)
            previous.animate().alpha(0f).setDuration(100L).withEndAction {
                pages.complete(ticket)?.let(::removeView)
            }.start()
        }
    }
    private fun freeze(view: View) {
        view.setTag(R.id.tv_motion_retired,true)
        view.isFocusable=false;view.isClickable=false;view.isLongClickable=false
        if(view is ViewGroup) {
            view.descendantFocusability=FOCUS_BLOCK_DESCENDANTS
            if(view is androidx.recyclerview.widget.RecyclerView) view.stopScroll()
            for(index in 0 until view.childCount) freeze(view.getChildAt(index))
        }
    }
    fun finish() {
        pages.finish()?.let { it.animate().setListener(null).withEndAction(null).cancel(); removeView(it) }
        pages.current?.let(TvMotion::settle)
    }
    override fun removeAllViews() { finish(); pages.clear(); super.removeAllViews() }
    override fun onDetachedFromWindow() { finish(); super.onDetachedFromWindow() }
}
