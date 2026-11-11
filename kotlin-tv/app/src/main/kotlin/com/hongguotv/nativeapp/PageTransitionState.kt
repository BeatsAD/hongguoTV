// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Page ownership independent of Android's animation scheduler. Only the latest ticket may retire a page. */
internal class PageTransitionState<T> {
    var current: T? = null
        private set
    var outgoing: T? = null
        private set
    private var revision = 0L
    fun begin(next: T): Long {
        check(outgoing == null) { "Finish the outgoing transition before attaching another page" }
        outgoing = current
        current = next
        return ++revision
    }
    fun accepts(ticket: Long) = revision == ticket
    fun complete(ticket: Long): T? {
        if (!accepts(ticket)) return null
        return outgoing.also { outgoing = null }
    }
    fun finish(): T? { revision++; return outgoing.also { outgoing = null } }
    fun clear() { finish(); current = null }
}
