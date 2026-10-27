// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Monotonic timestamps; failed refreshes retain content and retry with a bounded cadence. */
object ContentFreshness {
    const val MAX_AGE = 30 * 60_000L
    const val RETRY_DELAY = 5 * 60_000L
    const val MANUAL_DELAY = 10_000L
    fun due(now: Long, updatedAt: Long?, attemptedAt: Long?): Boolean {
        val stale = updatedAt == null || now < updatedAt || now - updatedAt >= MAX_AGE
        val retry = attemptedAt == null || now < attemptedAt || now - attemptedAt >= RETRY_DELAY
        return stale && retry
    }
}
