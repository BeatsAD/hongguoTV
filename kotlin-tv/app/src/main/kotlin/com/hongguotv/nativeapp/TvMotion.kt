// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/** Short, interruptible effects. Visual completion never commits navigation or a business action. */
internal object TvMotion {
    const val PAGE = 180L
    const val DETAIL = 220L
    const val FOCUS = 140L
    const val IMAGE = 120L
    const val POSTER_SCALE = 1.025f
    const val POSTER_INSET_DP = 4
    private class State(view: View) {
        val intent = MotionIntent()
        var focusability: Int? = (view as? ViewGroup)?.descendantFocusability
        var accessibility = view.importantForAccessibility
    }
    fun isVisible(view: View) = (view.getTag(R.id.tv_motion_state) as? State)?.intent?.visible ?: (view.visibility==View.VISIBLE)
    fun enabled() = ValueAnimator.areAnimatorsEnabled()
    fun spatial(context: Context) = enabled() && context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true
    fun pixels(view: View, dp: Float) = dp * view.resources.displayMetrics.density
    private fun state(view: View): State = (view.getTag(R.id.tv_motion_state) as? State)
        ?: State(view).also {
            view.setTag(R.id.tv_motion_state, it)
            view.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { settle(v) }
            })
        }
    fun settle(view: View) {
        val value = view.getTag(R.id.tv_motion_state) as? State
        val visible = value?.intent?.visible
        value?.intent?.reset()
        view.animate().setListener(null).withEndAction(null).cancel()
        view.alpha = 1f; view.translationX = 0f; view.translationY = 0f
        if (visible != null) view.visibility = if (visible) View.VISIBLE else View.GONE
    }
    fun appear(view: View, duration: Long = PAGE, x: Float = 0f, y: Float = 0f) {
        settle(view)
        if (!enabled()) return
        view.alpha = 0f
        if (spatial(view.context)) { view.translationX = pixels(view, x); view.translationY = pixels(view, y) }
        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(duration)
            .setInterpolator(DecelerateInterpolator()).withEndAction(null).start()
    }
    fun visible(view: View, show: Boolean, immediate: Boolean = false) {
        val value = state(view)
        val ticket = value.intent.request(show) ?: if(immediate) {
            settle(view);value.intent.request(show)!!
        } else return
        view.animate().setListener(null).withEndAction(null).cancel()
        if (show) {
            if (view is ViewGroup) value.focusability?.let { view.descendantFocusability = it }
            view.importantForAccessibility = value.accessibility
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.translationY = if (spatial(view.context)) pixels(view, 8f) else 0f
            }
            view.visibility = View.VISIBLE
        } else {
            if (view is ViewGroup) {
                if (view.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) value.focusability = view.descendantFocusability
                view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            if(view.importantForAccessibility!=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) value.accessibility = view.importantForAccessibility
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.clearFocus()
        }
        fun complete() {
            if (!value.intent.accepts(ticket)) return
            view.visibility = if (show) View.VISIBLE else View.GONE
            view.alpha = 1f; view.translationY = 0f
        }
        if (immediate || !enabled() || !view.isAttachedToWindow) { complete(); return }
        view.animate().alpha(if (show) 1f else 0f)
            .translationY(if (!show && spatial(view.context)) pixels(view, 8f) else 0f)
            .setDuration(if (show) 160L else 120L)
            .setInterpolator(if (show) DecelerateInterpolator() else AccelerateInterpolator())
            .withEndAction(::complete).start()
    }
}
