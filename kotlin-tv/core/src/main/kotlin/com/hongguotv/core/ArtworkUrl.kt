// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.net.URI

/** The website publishes the same novel-pic asset through this JPEG rendition. */
object ArtworkUrl {
    fun forApi(value: String,api: Int): String {
        if(api>=28) return value
        return runCatching {
            val uri=URI(value);val host=uri.host.orEmpty().lowercase()
            if(uri.scheme !in setOf("http","https") || uri.userInfo!=null || !host.endsWith(".fqnovelpic.com")) return value
            val key=Regex("^/novel-pic/([a-fA-F0-9]{32})~[^/]+\\.heic$").matchEntire(uri.path)?.groupValues?.get(1) ?: return value
            "https://p6-novel.byteimg.com/novel-pic/$key~tplv-shrink:640:0.image"
        }.getOrDefault(value)
    }
}
