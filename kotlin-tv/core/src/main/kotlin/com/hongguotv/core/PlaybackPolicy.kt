// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

enum class PlaybackFailureKind { NETWORK, SOURCE, FORMAT, UNSUPPORTED }

/** Safe, user-facing source failures; never includes media URLs, signatures or keys. */
class PlaybackSourceException(val kind: PlaybackFailureKind, message: String): IOException(message)

object PlaybackErrors {
    fun source(error: Throwable): PlaybackSourceException? = generateSequence(error) { it.cause }.take(8).filterIsInstance<PlaybackSourceException>().firstOrNull()
    fun retryable(error: Throwable): Boolean = source(error)?.let { it.kind==PlaybackFailureKind.NETWORK }
        ?: generateSequence(error) { it.cause }.take(8).any { it is IOException }
    fun retryableHttp(status: Int)=status==408 || status==429 || status in 500..599
}

/** One episode's explicit quality override and one bounded decoder recovery. */
class PlaybackAttempt {
    private var episode=""
    private var quality: Int?=null
    private var decoderRetried=false
    private val rejected=mutableSetOf<String>()
    fun begin(id: String, overrideQuality: Int?=null) {
        if(episode!=id) { clear(); episode=id }
        if(overrideQuality!=null) quality=PlaybackQuality.normalize(overrideQuality)
    }
    fun quality(default: Int)=quality ?: default
    fun rejectedVariants(): Set<String> = rejected.toSet()
    fun rejectDecoder(stream: StreamInfo?): Boolean {
        if(decoderRetried || stream==null) return false
        decoderRetried=true; rejected+=stream.variantKey; return true
    }
    fun clear() { episode=""; quality=null; decoderRetried=false; rejected.clear() }
}
