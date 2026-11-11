package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegisteredPlayerTest {
    @Test fun objectRenditionsDecodeUrlsAndRetainCodecEncryptionAndDimensions() {
        val rows=JSONObject().put("bad",JSONObject().put("main_url","invalid"))
            .put("v720",JSONObject().put("main_url","https://example.com/main".toByteArray().base64())
                .put("backup_url_1","https://example.com/backup".toByteArray().base64())
                .put("vwidth",720).put("vheight",1280).put("codec_type","h264").put("encrypt",false))
        val model=JSONObject().put("video_list",rows).put("key_seed",ByteArray(32).base64())
        val response=JSONObject().put("code",0).put("data",JSONObject().put("video_model",model.toString()))
        val normalized=JSONObject(RegisteredPlayer.normalize(response.toString(),"123"))
            .getJSONObject("data").getJSONObject("123").getJSONObject("video_model").getJSONArray("video_list")
        assertEquals(1,normalized.length())
        val row=normalized.getJSONObject(0)
        assertEquals("https://example.com/main",row.getString("main_url"))
        assertEquals("https://example.com/backup",row.getString("backup_url"))
        assertEquals("720p",row.getJSONObject("video_meta").getString("definition"))
        assertEquals("h264",row.getJSONObject("video_meta").getString("codec_type"))
        assertFalse(row.getJSONObject("encrypt_info").getBoolean("encrypt"))
    }
    @Test fun rejectedOrMismatchedModelsNeverBecomePlayable() {
        assertThrows(PlaybackSourceException::class.java) { RegisteredPlayer.normalize("""{"code":110001}""","123") }
        assertThrows(PlaybackSourceException::class.java) { RegisteredPlayer.normalize("""{"code":0,"data":{"456":{"video_model":{}}}}""","123") }
        assertThrows(EmptyContentException::class.java) { RegisteredPlayer.normalize("","123") }
    }
}
