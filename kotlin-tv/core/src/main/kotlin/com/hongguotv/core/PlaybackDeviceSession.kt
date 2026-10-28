// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class PlaybackRegistrationException(cause: Throwable?=null): IOException("匿名播放设备注册失败，请查看诊断详情后稍后重试",cause)

/** One anonymous identity, shared by foreground/search/prefetch; never rotate it on errors. */
class PlaybackDeviceSession(private val clock: ()->Long=System::currentTimeMillis,
    private val register: (OkHttpClient,Map<String,String>)->DeviceRegister.Registered? = ::registerDevice) {
    internal data class Profile(val values: Map<String,String>,val cookie: String)
    private var cdid=UUID.randomUUID().toString()
    private var ready: Profile?=null
    private var retryAt=0L
    var changed: ((String)->Unit)?=null

    @Synchronized internal fun profile(http: OkHttpClient): Profile {
        ready?.let { return it }
        if(clock()<retryAt) throw ContentRequestDeferredException(retryAt-clock())
        try {
            val began=System.nanoTime()
            val registered=register(http,VendorConstants.device+("cdid" to cdid)) ?: throw PlaybackRegistrationException()
            if(!validId(registered.deviceId) || !validId(registered.installId)) throw PlaybackRegistrationException()
            val cookie=registered.cookies.filter { (name,value) -> validCookie(name,value) }.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val result=Profile(VendorConstants.device+mapOf("iid" to registered.installId,"device_id" to registered.deviceId,"cdid" to cdid),cookie)
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
            ready=result;retryAt=0
            changed?.invoke(snapshot())
            Diagnostics.record("匿名设备注册","成功，身份复用；elapsedMs=${(System.nanoTime()-began)/1_000_000}")
            return result
        } catch(error: IOException) {
            if(Thread.currentThread().isInterrupted || RequestScope.isCancelled()) throw error
            retryAt=clock()+maxOf(60_000L,(error as? ContentRequestDeferredException)?.retryAfterMillis ?: 0)
            changed?.invoke(snapshot())
            Diagnostics.failure("匿名设备注册",error)
            throw PlaybackRegistrationException(error)
        }
    }
    @Synchronized fun snapshot(): String = JSONObject().put("cdid",cdid).put("retryAt",retryAt).apply {
        ready?.let { put("iid",it.values["iid"]);put("device_id",it.values["device_id"]);put("cookie",it.cookie) }
    }.toString()
    @Synchronized fun restore(value: String?) {
        if(ready!=null || value==null) return
        runCatching {
            val o=JSONObject(value)
            retryAt=maxOf(retryAt,o.optLong("retryAt",0))
            val restored=o.str("cdid")
            if(runCatching { UUID.fromString(restored) }.isSuccess) cdid=restored
            val iid=o.str("iid");val did=o.str("device_id")
            val cookie=o.str("cookie")
            if(validId(iid) && validId(did) && cookie.split("; ").all { it.isEmpty() || validCookie(it.substringBefore('='),it.substringAfter('=',"")) }) {
                ready=Profile(VendorConstants.device+mapOf("iid" to iid,"device_id" to did,"cdid" to cdid),cookie)
            }
        }
    }
    companion object {
        val shared=PlaybackDeviceSession()
        private fun validId(value: String)=value.matches(Regex("[1-9][0-9]{0,29}"))
        private fun validCookie(name: String,value: String)=name in setOf("install_id","ttreq","store-region","store-region-src") && value.length in 1..2048 && value.all { it.code in 33..126 && it!=';' }
        private fun registerDevice(http: OkHttpClient,device: Map<String,String>): DeviceRegister.Registered? {
            val body=DeviceRegister.payload(device)
            val request=Request.Builder().url(DeviceRegister.url()).post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
            DeviceRegister.headers(body,device).forEach { (key,value) -> request.header(key,value) }
            val client=http.newBuilder().cookieJar(CookieJar.NO_COOKIES).connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build()
            return RequestScope.execute(client.newCall(request.build())) { response ->
                Diagnostics.record("匿名设备注册","HTTP=${response.code}")
                ContentRequestGate().observe(request.build().url.host,response.code,response.header("Retry-After"))
                if(!response.isSuccessful) throw PlaybackSourceException(if(PlaybackErrors.retryableHttp(response.code)) PlaybackFailureKind.NETWORK else PlaybackFailureKind.SOURCE,"匿名设备注册失败（HTTP ${response.code}）")
                val input=response.body?.byteStream() ?: throw PlaybackRegistrationException()
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true) { val n=input.read(buffer);if(n<0) break;if(out.size()+n>65536) throw PlaybackRegistrationException();out.write(buffer,0,n) }
                DeviceRegister.parse(out.toString("UTF-8"),response.headers("Set-Cookie"))
            }
        }
    }
}
