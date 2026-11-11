// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter
import com.hongguotv.core.Diagnostics

/** Software codecs share CPU with decryption; avoid asynchronous stale-buffer queueing on errors. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvRenderersFactory(context: Context): DefaultRenderersFactory(context) {
    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
        val standard=super.getCodecAdapterFactory()
        val synchronous=SynchronousMediaCodecAdapter.Factory()
        return MediaCodecAdapter.Factory { configuration ->
            val software=configuration.codecInfo.softwareOnly
            Diagnostics.record("解码队列","name=${configuration.codecInfo.name} mode=${if(software) "sync" else "default"}")
            if(software) synchronous.createAdapter(configuration) else standard.createAdapter(configuration)
        }
    }
}
