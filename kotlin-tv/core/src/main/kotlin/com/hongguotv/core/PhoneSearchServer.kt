// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** A page-owned, short-lived LAN form with repeated submissions. No cookies, platform credentials or external resources. */
class PhoneSearchServer(
    address: String,
    private val onQuery: (String)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = 900_000,
    private val requestTimeoutMillis: Int = 10_000,
    private val onConnected: ()->Unit = {}
): Closeable {
    private val stopped=AtomicBoolean(false)
    @Volatile var revision=0L; private set
    private var lastSubmission=0L
    private val localAddress=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }.hostAddress
    private val listener=ServerSocket(0,4,InetAddress.getByName(localAddress)).apply { soTimeout=500 }
    private val expiry=Timer("hongguotv-phone-expiry",true)
    @Volatile private var active: Socket?=null
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,900_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="$localAddress:${listener.localPort}"
    val url="http://$host/$token"
    private val thread=Thread({ serve() },"hongguotv-phone-input").apply { isDaemon=true }
    init {
        require(requestTimeoutMillis in 1..10_000)
        expiry.schedule(object: TimerTask() { override fun run()=expire() },ttlMillis.coerceIn(1,900_000))
        thread.start()
    }
    private fun expire() { if(stopped.compareAndSet(false,true)) { close(); onExpired() } }
    override fun close() { stopped.set(true); expiry.cancel(); runCatching { listener.close() }; runCatching { active?.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val client=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                active=client
                client.use { socket ->
                    val timeout=object: TimerTask() { override fun run() { runCatching { socket.close() } } }
                    expiry.schedule(timeout,requestTimeoutMillis.toLong())
                    try { socket.soTimeout=minOf(2000,requestTimeoutMillis); if(!stopped.get()) handle(socket) }
                    catch(_: Exception) { /* Discard malformed, slow or disconnected requests. */ }
                    finally { timeout.cancel() }
                }
                active=null
            }
        } catch(_: Exception) { /* Closing the dialog interrupts accept(). */ }
        finally {
            if(!stopped.get()) expire() else close()
        }
    }
    private fun line(input: InputStream, max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read()
            if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered()
        val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>()
        var bytes=0
        while(true) {
            val next=line(input,4096); bytes+=next.length+2
            if(bytes>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            val key=next.substring(0,at).lowercase(Locale.ROOT)
            if(headers.put(key,next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已失效，请在电视上重新打开手机输入")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/submit")) return respond(socket,404,"页面不存在")
        if(request[0]=="GET" && request[1]=="/$token") {
            onConnected()
            return respond(socket,200,form())
        }
        if(request[0]!="POST" || request[1]!="/$token/submit") return respond(socket,405,"不支持的操作")
        if(headers["origin"]!=null && headers["origin"]!="http://$host") return respond(socket,403,"来源不匹配")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/x-www-form-urlencoded") return respond(socket,400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4096 } ?: return respond(socket,413,"输入内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val fields=String(body,Charsets.UTF_8).split('&').map { it.split('=',limit=2) }
        if(fields.size!=1 || fields[0].size!=2 || fields[0][0]!="query") return respond(socket,400,"请填写剧名")
        val query=runCatching { URLDecoder.decode(fields[0][1],"UTF-8").trim() }.getOrNull()
        if(query.isNullOrBlank() || query.length>80 || query.any { it.isISOControl() }) return respond(socket,400,"请输入 1—80 个字符的剧名")
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已失效")
        val now=System.nanoTime()
        if(lastSubmission!=0L && now-lastSubmission<400_000_000L) return respond(socket,429,form("发送太快，请稍后再试"))
        lastSubmission=now; revision++
        // Deliver only while owned; the UI also checks its page/session generation.
        if(!stopped.get()) onQuery(query)
        respond(socket,200,form("已发送到电视 · 第 $revision 次"))
    }
    private fun form(status: String="连接成功，请输入剧名")="""<div class="brand">hongguo</div><h1>手机输入剧名</h1><p>按电视当前选择的范围搜索</p><div role="status">$status</div><form method="post" action="/$token/submit"><label for="query">剧名或关键词</label><input id="query" name="query" maxlength="80" required autofocus placeholder="输入中文剧名" autocomplete="off"><button>发送到电视</button></form><p>可连续发送，最长15分钟；离开电视搜索页或刷新二维码后失效。</p>"""

    private fun respond(socket: Socket, code: Int, message: String) {
        val body="""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>hongguo · 手机输入</title><style>body{font:18px sans-serif;background:#101114;color:#F5F3F2;max-width:500px;margin:40px auto;padding:20px}input,button{box-sizing:border-box;width:100%;font:inherit;min-height:44px;padding:16px;margin:10px 0;border-radius:8px}input{background:#25262C;color:#F5F3F2;border:1px solid #464952}button{background:linear-gradient(90deg,#BB3D5D,#AE5436);color:#FFF8F5;border:0}p{line-height:1.6}button:disabled{background:#1B1C21;color:#858791}button:focus-visible,input:focus-visible{outline:2px solid #D58098;outline-offset:2px}</style></head><body>$message</body></html>""".toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: same-origin\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
