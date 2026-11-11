// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Reversing a visual transition invalidates every completion belonging to the previous intent. */
internal class MotionIntent {
    var visible: Boolean? = null
        private set
    private var revision = 0L
    fun request(next: Boolean): Long? {
        if (visible == next) return null
        visible = next
        return ++revision
    }
    fun accepts(ticket: Long) = ticket == revision
    fun reset() { revision++; visible = null }
}
