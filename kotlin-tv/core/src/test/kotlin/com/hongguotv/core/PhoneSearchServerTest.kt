package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneSearchServerTest {
    @Test fun `listener is bound to the selected address`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            val uri=URI(server.url)
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
            Socket().use { socket ->
                assertTrue(runCatching { socket.connect(InetSocketAddress("127.0.0.2",uri.port),300) }.isFailure)
            }
        }
    }
    @Test fun `close interrupts an accepted incomplete request`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            val uri=URI(server.url)
            Socket("127.0.0.1",uri.port).use { socket ->
                socket.soTimeout=1500
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: ".toByteArray())
                server.close()
                try { assertEquals(-1,socket.getInputStream().read()) } catch(_: java.net.SocketException) { /* Reset also proves the accepted socket was closed. */ }
            }
        }
    }
    @Test fun `request deadline frees the single worker for another phone`() {
        PhoneSearchServer("127.0.0.1",{},requestTimeoutMillis=150).use { server ->
            val uri=URI(server.url)
            Socket("127.0.0.1",uri.port).use { socket ->
                socket.soTimeout=1500
                socket.getOutputStream().write("GET / HTTP/1.1\r\nX-Slow: ".toByteArray())
                val sending=java.util.concurrent.atomic.AtomicBoolean(true)
                val writer=Thread {
                    while(sending.get()) {
                        if(runCatching { socket.getOutputStream().write('.'.code); Thread.sleep(30) }.isFailure) break
                    }
                }.apply { start() }
                try { assertEquals(-1,socket.getInputStream().read()) }
                finally { sending.set(false); writer.join(1000) }
                assertFalse(writer.isAlive)
            }
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `session expiry closes an active connection and notifies once`() {
        val expired=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ fail("expired query") },{ expired.countDown() },150).use { server ->
            val uri=URI(server.url)
            Socket("127.0.0.1",uri.port).use { socket ->
                socket.soTimeout=1500
                socket.getOutputStream().write("GET / HTTP/1.1\r\nX-Slow: ".toByteArray())
                assertEquals(-1,socket.getInputStream().read())
                assertTrue(expired.await(1,TimeUnit.SECONDS))
            }
        }
    }
    private fun request(server: PhoneSearchServer,method: String="GET",path: String?=null,body: String="",headers: String="",host: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n"+headers+if(method=="POST") "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\n\r\n" else "\r\n").toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `phone form accepts one unicode search without reflecting it in HTML`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ query=it;received.countDown() }).use { server ->
            val page=request(server)
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store")); assertTrue(page.contains("form-action 'self'"))
            val value="测试 <script>alert(1)</script>"
            val reply=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode(value,"UTF-8"))
            assertTrue(reply.startsWith("HTTP/1.1 200")); assertFalse(reply.contains(value))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals(value,query)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `phone form preserves its origin and accepts a browser style post`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ query=it; received.countDown() }).use { server ->
            val uri=URI(server.url)
            val page=request(server)
            assertTrue(page.contains("Referrer-Policy: same-origin\r\n"))
            val response=request(server,"POST",uri.path+"/submit","query="+URLEncoder.encode("逆江湖","UTF-8"),
                "Origin: http://${uri.authority}\r\nSec-Fetch-Site: same-origin\r\n")
            assertTrue(response,response.startsWith("HTTP/1.1 200"))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals("逆江湖",query)
        }
    }
    @Test fun `opaque origin stays rejected without consuming the session`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            val uri=URI(server.url)
            val response=request(server,"POST",uri.path+"/submit","query=test","Origin: null\r\n")
            assertTrue(response,response.startsWith("HTTP/1.1 403")); assertFalse(submitted)
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `wrong token host and cross site posts cannot submit`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            assertTrue(request(server,path="/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=test","Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    @Test fun `invalid and oversized input does not consume the session`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            for(body in listOf("query=","query="+"x".repeat(81),"query=a&query=b","query=hello%0Aworld","query=%zz")) {
                val response=request(server,"POST",URI(server.url).path+"/submit",body)
                assertTrue(response,response.startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query="+"x".repeat(4096)).startsWith("HTTP/1.1 413"))
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `closing or expiry makes a session unreachable`() {
        val closed=PhoneSearchServer("127.0.0.1",{}); closed.close()
        assertTrue(runCatching { request(closed) }.isFailure)
        val expired=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ fail("expired session submitted") },{ expired.countDown() },1).use { server ->
            assertTrue(expired.await(2,TimeUnit.SECONDS))
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
}
