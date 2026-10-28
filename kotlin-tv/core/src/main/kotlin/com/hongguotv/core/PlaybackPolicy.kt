// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

enum class PlaybackFailureKind { NETWORK, SOURCE, FORMAT, UNSUPPORTED }

/** Safe, user-facing source failures; never includes media URLs, signatures or keys. */
class PlaybackSourceException(val kind: PlaybackFailureKind, message: String, val httpStatus: Int?=null): IOException(message)

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
    private var networkCeiling: Int?=null
    private val rejected=mutableSetOf<String>()
    fun begin(id: String, overrideQuality: Int?=null) {
        if(episode!=id) { clear(); episode=id }
        if(overrideQuality!=null) quality=PlaybackQuality.normalize(overrideQuality)
    }
    fun quality(default: Int)=quality ?: default
    fun rejectedVariants(): Set<String> = rejected.toSet()
    fun withinNetworkLimit(stream: StreamInfo)=networkCeiling?.let { ContentRepository.qualityNumber(stream.quality) in 1..it } ?: true
    fun lowerForNetwork(currentQuality: String,defaultQuality: Int): Int? {
        val ceiling=minOf(quality(defaultQuality),ContentRepository.qualityNumber(currentQuality).takeIf { it>0 } ?: defaultQuality)
        val lower=PlaybackQuality.options.lastOrNull { it<ceiling } ?: return null
        quality=lower; networkCeiling=lower
        return lower
    }
    fun rejectDecoder(stream: StreamInfo?): Boolean {
        if(decoderRetried || stream==null) return false
        decoderRetried=true; rejected+=stream.variantKey; return true
    }
    fun clear() { episode=""; quality=null; networkCeiling=null; decoderRetried=false; rejected.clear() }
}

/** Tracks consecutive buffering only while playback is requested. Pauses do not spend time. */
class BufferingWatchdog {
    private var since: Long?=null
    fun reset() { since=null }
    fun expired(now: Long,buffering: Boolean,shouldPlay: Boolean,hasPlayed: Boolean): Boolean {
        if(!buffering || !shouldPlay) { reset(); return false }
        val began=since ?: now.also { since=it }
        return now-began >= if(hasPlayed) 12_000L else 18_000L
    }
}
