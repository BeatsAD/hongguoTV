// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.media.MediaCodecList
import com.hongguotv.core.StreamInfo

/** Query installed decoders once; no device database or persistent blacklist. */
class VideoDecoderSupport {
    private val codecs by lazy { runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder } }.getOrNull() }
    fun softwareOnly(stream: StreamInfo): Boolean {
        val mime=when(stream.codec.lowercase()) {
            "h264","avc","avc1" -> "video/avc"
            "h265","hevc","hvc1","hev1","bytevc1" -> "video/hevc"
            else -> return false
        }
        val available=codecs?.filter { codec -> codec.supportedTypes.any { it.equals(mime,true) } }.orEmpty()
        return available.isNotEmpty() && available.all { codec ->
            if(android.os.Build.VERSION.SDK_INT>=29) codec.isSoftwareOnly
            else codec.name.startsWith("OMX.google.") || codec.name.startsWith("c2.android.")
        }
    }
    fun supports(stream: StreamInfo): Boolean {
        val mime=when(stream.codec.lowercase()) {
            "h264","avc","avc1" -> "video/avc"
            "h265","hevc","hvc1","hev1","bytevc1" -> "video/hevc"
            "av1","av01" -> "video/av01"
            "vp9","vp09" -> "video/x-vnd.on2.vp9"
            else -> return true // Missing legacy labels are checked by Media3 at prepare time.
        }
        val available=codecs ?: return true // A broken vendor query must not disable all playback.
        return available.any { codec ->
            runCatching {
                if(codec.supportedTypes.none { it.equals(mime,true) }) return@runCatching false
                val caps=codec.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching false
                val width=stream.width; val height=stream.height; val fps=stream.frameRate
                if(width==null || height==null) true
                else if(fps==null) caps.isSizeSupported(width,height)
                else caps.areSizeAndRateSupported(width,height,fps)
            }.getOrDefault(false)
        }
    }
}
