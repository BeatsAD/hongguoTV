// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View

internal object RemoteFocus {
    const val NETWORK_DELAY=250L
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var dispatchRevision=0L
    fun dispatch(event: KeyEvent,focus: ()->View?,action: ()->Boolean): Boolean {
        val directional=event.action==KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)
        // Android's default focus search may run in ViewRootImpl after Activity dispatch returns.
        // Keep intent for that input turn; explicit restoration scopes still override it.
        val ticket=++dispatchRevision;FocusIntent.pendingDirectional=directional
        main.post { if(ticket==dispatchRevision) FocusIntent.pendingDirectional=false }
        return FocusIntent.during(directional) {
            if(directional) binding(focus())?.exit(event.keyCode)
            action()
        }
    }
    fun binding(view: View?)=when(view) { is BrowseButton->view.focusSelection;is SidebarButton->view.focusSelection;else->null }
    data class Saved(val tag: Any?,val due: Long?)
    fun capture(parent: View): Saved { val view=parent.findFocus();return Saved(view?.tag,binding(view)?.deadline) }
    fun restore(parent: View,saved: Saved) {
        val view=saved.tag?.let { parent.findViewWithTag<View>(it) } ?: return
        FocusIntent.during(false) { view.requestFocus() }
        saved.due?.let { binding(view)?.resume(it) }
    }
}

internal class FocusSelection(view: View,delay: Long,action: ()->Unit,confirmSelected: Boolean=false,
    private val exitKeys: Set<Int> = setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
    private val gate=FocusActivation(delay,SystemClock::uptimeMillis,{ wait,task ->
        val runnable=Runnable { task() };view.postDelayed(runnable,wait);val cancel: ()->Unit={ view.removeCallbacks(runnable);Unit };cancel
    },{ view.isAttachedToWindow && view.isShown && view.hasFocus() && view.hasWindowFocus() },{ view.isSelected },{
        FocusIntent.during(false,action)
    },confirmSelected)
    val deadline get()=gate.deadline
    init {
        val previous=view.onFocusChangeListener
        view.setOnFocusChangeListener { target,focused ->
            previous?.onFocusChange(target,focused)
            if(focused) gate.gained(FocusIntent.directional) else gate.cancel()
        }
        view.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { gate.cancel() }
        })
    }
    fun confirm()=gate.confirm()
    fun exit(key: Int) { if(key in exitKeys) gate.flush() }
    fun resume(deadline: Long)=gate.resume(deadline)
}
