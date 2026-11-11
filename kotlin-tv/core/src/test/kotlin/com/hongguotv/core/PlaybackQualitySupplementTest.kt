package com.hongguotv.core

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class PlaybackQualitySupplementTest {
    private fun primary(height: Int=720): JSONObject {
        val row=JSONObject().put("main_url","https://media.example/inline")
            .put("video_meta",JSONObject().put("definition","${height}p").put("codec_type","h264"))
            .put("encrypt_info",JSONObject().put("encrypt",false))
        return JSONObject().put("video_id","source-123").put("fallback_api","https://fallback.example/info")
            .put("video_list",JSONArray().put(row))
    }
    private fun fallback(height: Int=1080,codec: String="h264"): JSONObject = JSONObject()
        .put("video_id","source-123").put("key_seed",ByteArray(32).base64())
        .put("video_list",JSONObject().put("high",JSONObject().put("definition","${height}p")
            .put("main_url","https://media.example/high".toByteArray().base64())
            .put("backup_url_1","https://backup.example/high".toByteArray().base64())
            .put("vwidth",height).put("vheight",height*16/9).put("fps",30)
            .put("codec_type",codec).put("encrypt",false)))
    private fun response(chain: Interceptor.Chain,body: String,status: Int=200)=Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
        .body(body.toResponseBody()).build()
    private fun primaryBody(model: JSONObject)=JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",model))).toString()
    private fun fallbackBody(model: JSONObject)=JSONObject().put("video_info",JSONObject().put("data",model)).toString()
    private fun repository(model: JSONObject=primary(),reply: (Interceptor.Chain)->Response): ContentRepository {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            if(chain.request().url.host=="fallback.example") reply(chain) else response(chain,primaryBody(model))
        }.build()
        return ContentRepository(client,playbackSession=PlaybackDeviceSession(register={ _,_->DeviceRegister.Registered("12345","67890") }))
    }

    @Test fun missing1080IsSupplementedOnceAndBase64UrlsKeepBackupsAndPortraitDimensions() {
        var calls=0
        val repo=repository { chain ->
            calls++;assertEquals(TimeUnit.SECONDS.toNanos(3),chain.call().timeout().timeoutNanos())
            response(chain,fallbackBody(fallback()))
        }
        val selected=repo.stream("123",1080)
        assertEquals(1,calls);assertEquals("1080P",selected.quality);assertEquals("h264",selected.codec)
        assertEquals(1080,selected.width);assertEquals(1920,selected.height)
        assertEquals("https://media.example/high",selected.url);assertEquals(listOf("https://backup.example/high"),selected.backupUrls)
    }
    @Test fun satisfiedPreferenceNeverCallsFallback() {
        for((source,target) in listOf(720 to 720,1080 to 1080,480 to 480)) {
            val selected=repository(primary(source)) { throw AssertionError("Unnecessary fallback") }.stream("123",target)
            assertEquals("${source}P",selected.quality)
        }
    }
    @Test fun failuresKeepThePlayableInlineStream() {
        for(failure in listOf("timeout","empty","malformed","network","wrong-video","missing-video","invalid-key","encrypted-without-key","unsupported-codec")) {
            var calls=0
            val repo=repository { chain ->
                calls++
                when(failure) {
                    "timeout" -> throw SocketTimeoutException("Fixture timeout")
                    "network" -> throw IOException("Fixture offline")
                    "empty" -> response(chain,"")
                    "malformed" -> response(chain,"<html>error</html>")
                    else -> {
                        val info=fallback()
                        val row=info.getJSONObject("video_list").getJSONObject("high")
                        when(failure) {
                            "wrong-video" -> info.put("video_id","other-video")
                            "missing-video" -> info.remove("video_id")
                            "invalid-key" -> row.put("spade_a","invalid-base64!")
                            "encrypted-without-key" -> row.put("encrypt",true)
                            "unsupported-codec" -> row.put("codec_type","bytevc2")
                        }
                        response(chain,fallbackBody(info))
                    }
                }
            }
            assertEquals(failure,"https://media.example/inline",repo.stream("123",1080).url)
            assertEquals(failure,1,calls)
        }
    }
    @Test fun fallbackAccessRejectionsObserveCooldownWithoutRetryingOrChangingIdentity() {
        for(status in listOf(401,403,429,503)) {
            var calls=0
            val repo=repository { chain -> calls++;response(chain,"",status).newBuilder().header("Retry-After","60").build() }
            assertEquals("720P",repo.stream("123").quality)
            val identity=repo.playbackSession.snapshot()
            assertEquals("720P",repo.stream("123").quality)
            assertEquals(1,calls);assertEquals(identity,repo.playbackSession.snapshot())
            assertFalse(repo.playbackSession.compatibilityPlayback)
        }
    }
    @Test fun unsupportedHighTierAndLowerFallbackNeverReplaceHealthyInlineQuality() {
        assertEquals("720P",repository { response(it,fallbackBody(fallback())) }.stream("123",1080) { it.width!=1080 }.quality)
        assertEquals("720P",repository { response(it,fallbackBody(fallback(480))) }.stream("123",1080).quality)
    }
    @Test fun rejectedMetadataNeverOverridesAnExistingPlayableStream() {
        for(topLevel in listOf(true,false)) {
            val body=JSONObject(fallbackBody(fallback()))
            if(topLevel) body.put("code",110001) else body.getJSONObject("video_info").put("code",110001)
            assertEquals("720P",repository { response(it,body.toString()) }.stream("123").quality)
        }
    }
    @Test fun horizontal1080UsesTheShorterDimension() {
        val info=fallback();info.getJSONObject("video_list").getJSONObject("high").put("vwidth",1920).put("vheight",1080)
        val selected=repository { response(it,fallbackBody(info)) }.stream("123")
        assertEquals("1080P",selected.quality);assertEquals(1920,selected.width);assertEquals(1080,selected.height)
    }
    @Test fun missingPrimaryVideoIdentityDoesNotTrustSupplementalTiers() {
        val model=primary();model.remove("video_id")
        assertEquals("720P",repository(model) { throw AssertionError("Cannot bind supplemental video") }.stream("123").quality)
    }
    @Test fun cancellationDuringSupplementNeverReturnsAnInlineSuccess() {
        val scope=RequestScope();var calls=0
        val repo=repository { calls++;scope.cancel();throw IOException("Canceled") }
        assertThrows(IOException::class.java) { scope.run { repo.stream("123") } }
        assertEquals(1,calls)
    }
    @Test fun decoderRetryCanReject1080AndNetworkRetryDoesNotFetchItAgain() {
        var calls=0
        val repo=repository { calls++;response(it,fallbackBody(fallback())) }
        val attempt=PlaybackAttempt();attempt.begin("123")
        val high=repo.stream("123",1080)
        assertTrue(attempt.rejectDecoder(high))
        val lower=repo.stream("123",1080) { it.variantKey !in attempt.rejectedVariants() }
        assertEquals("720P",lower.quality);assertEquals(2,calls)
        assertEquals(720,attempt.lowerForNetwork(high.quality,1080))
        assertEquals("720P",repo.stream("123",attempt.quality(1080),attempt::withinNetworkLimit).quality)
        assertEquals(2,calls)
    }
    @Test fun requiredFallbackStillFailsAndDoesNotPretendPlaybackSucceeded() {
        val model=primary();model.remove("video_list")
        assertThrows(IOException::class.java) { repository(model) { response(it,"bad-json") }.stream("123") }
        assertThrows(PlaybackSourceException::class.java) { repository(model) { response(it,fallbackBody(fallback().put("video_id","other"))) }.stream("123") }
    }
}
