// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Directional intent, rather than every programmatic focus restoration, arms activation. */
internal object FocusIntent {
    private var scoped: Boolean?=null
    var pendingDirectional=false
    val directional get()=scoped ?: pendingDirectional
    fun <T> during(value: Boolean,action: ()->T): T {
        val before=scoped;scoped=value
        return try { action() } finally { scoped=before }
    }
}

/** Deterministic debounce/confirm policy; Android supplies the clock and cancellable scheduler. */
internal class FocusActivation(private val delay: Long,private val clock: ()->Long,
    private val schedule: (Long,()->Unit)->(()->Unit),private val eligible: ()->Boolean,
    private val selected: ()->Boolean,private val action: ()->Unit,private val confirmSelected: Boolean=false) {
    var deadline: Long?=null
        private set
    private var revision=0L
    private var cancelTask: (()->Unit)?=null
    fun gained(directional: Boolean) { if(directional && !selected()) resume(clock()+delay) }
    fun cancel() { revision++;cancelTask?.invoke();cancelTask=null;deadline=null }
    fun resume(due: Long) {
        cancel();if(selected()) return
        deadline=due;val ticket=revision
        cancelTask=schedule((due-clock()).coerceAtLeast(0)) {
            if(ticket==revision) { deadline=null;cancelTask=null;if(eligible() && !selected()) action() }
        }
    }
    fun flush() { if(deadline!=null) { cancel();if(eligible() && !selected()) action() } }
    fun confirm() { cancel();if(eligible() && (confirmSelected || !selected())) action() }
}
