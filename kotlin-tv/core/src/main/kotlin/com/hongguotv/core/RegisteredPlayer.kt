// SPDX-License-Identifier: GPL-3.0-only
// Compatibility route adapted from N3urda/hongguoTV 7444bb0 PlayerSource.kt / PlaybackModels.kt.
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The upstream's registered single-episode API; separate from catalog/search identity. */
internal object RegisteredPlayer {
    fun request(id: String,device: PlayerDevice): Signer.Request {
        require(id.matches(Regex("[0-9]{1,30}")))
        val query=Signer.encode(linkedMapOf("iid" to device.installId,"device_id" to device.deviceId,
            "aid" to "1967","app_name" to "novelapp","version_code" to "72132","version_name" to "7.2.1.32",
            "device_platform" to "android","device_brand" to "Xiaomi","os_version" to "13","cdid" to device.cdid))
        val body=JSONObject().put("video_id",id).put("content_type",1004)
            .put("biz_param",JSONObject().put("detail_page_version",0).put("device_level",3)
                .put("disable_digg_stat",false).put("disable_video_relate_book",false).put("from_video_id","")
                .put("need_all_video_definition",true).put("need_mp4_align",false).put("source",4)
                .put("use_os_player",false).put("use_server_dns",false).put("video_platform",3))
            .toString().toByteArray()
        return Signer.Request("https://api3-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/?$query",
            PlayerSigner.headers(query,body)+mapOf("User-Agent" to "com.dragon.read","Content-Type" to "application/json; charset=utf-8"),body)
    }

    /** Normalize object-shaped Spade renditions into the existing capability-aware TV parser. */
    fun normalize(response: String,id: String): String {
        if(response.isBlank()) throw EmptyContentException("兼容播放接口返回空响应，请稍后重试")
        val root=try { JSONObject(response) } catch(_: Exception) { throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"兼容播放信息格式异常") }
        val code=root.optInt("code",root.optInt("Code",0))
        if(code!=0) throw PlaybackSourceException(PlaybackFailureKind.SOURCE,"兼容播放接口暂不可用（错误码 $code）")
        val data=root.optJSONObject("data") ?: throw EmptyContentException("兼容播放信息为空")
        val entry=data.optJSONObject(id) ?: data.takeIf { it.has("video_model") }
            ?: throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"兼容播放信息没有对应剧集")
        val model=when(val raw=entry.opt("video_model")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: throw PlaybackSourceException(PlaybackFailureKind.FORMAT,"兼容播放模型格式异常")
        val raw=model.opt("video_list")
        if(raw is JSONObject) {
            val rows=JSONArray()
            raw.keys().asSequence().forEach { name ->
                runCatching {
                    val item=raw.getJSONObject(name)
                    val meta=JSONObject(item.toString())
                    val w=item.optInt("vwidth");val h=item.optInt("vheight")
                    meta.put("definition",if(w>0 && h>0) "${minOf(w,h)}p" else item.str("definition").ifEmpty { name })
                    val encryption=JSONObject().put("encrypt",item.opt("encrypt") ?: true)
                    item.str("spade_a").takeIf(String::isNotBlank)?.let { encryption.put("spade_a",it) }
                    val main=resolveUrl(item.str("main_url"),model.str("key_seed")) ?: return@runCatching
                    val backup=resolveUrl(item.str("backup_url_1"),model.str("key_seed"))
                    rows.put(JSONObject().put("main_url",main).put("backup_url",backup.orEmpty())
                        .put("video_meta",meta).put("encrypt_info",encryption))
                } // A malformed rendition must not discard valid siblings.
            }
            model.put("video_list",rows)
        }
        return JSONObject().put("code",0).put("data",JSONObject().put(id,JSONObject().put("video_model",model))).toString()
    }

    private fun resolveUrl(raw: String,seed: String): String? {
        if(raw.isBlank()) return null
        fun valid(value: String)=cleanUrl(value).toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
        return valid(raw) ?: valid(if(seed.isNotBlank()) MediaCrypto.decryptUrl(raw,decode64(seed)) else String(decode64(raw),Charsets.UTF_8))
    }
}
