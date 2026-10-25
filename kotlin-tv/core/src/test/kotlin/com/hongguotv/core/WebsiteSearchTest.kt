package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WebsiteSearchTest {
    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )
    private fun card(id: String, genre: String, docType: Int = 23) = JSONObject().put("doc_type",docType)
        .put("video_data",JSONObject().put("series_id",id).put("series_title","测试剧集$id").put("genre",genre).put("episode_cnt",12))
    private fun page(number: Int = 1, more: Boolean = false, vararg rows: JSONObject) = JSONObject()
        .put("searchList",JSONArray(rows.toList())).put("pageNum",number).put("totalPages",if(more) number+1 else number).put("hasMore",more).toString()

    @Test fun emptyAppResponseUsesWebsiteWithExplicitGenreForEachType() {
        for(type in ContentType.entries) {
            val paths=mutableListOf<String>()
            val repo=repository { request ->
                paths+=request.url.encodedPath
                if(paths.size==1) "" else {
                    assertEquals("逆江湖",request.url.queryParameter("query"))
                    assertNull(request.url.queryParameter("page"))
                    page(1,false,card("101","205"),card("102","203"),card("103","261"),card("104",""),card("105","205",46),card("101","205"))
                }
            }
            val result=repo.search("逆江湖",type=type)
            assertEquals(listOf(if(type==ContentType.COMIC) "101" else "102"),result.items.map { it.id })
            assertEquals("全 12 集",result.items.single().badge)
            assertFalse(result.hasMore)
            assertEquals(listOf("/reading/bookapi/search/tab/v","/api/search/result"),paths)
        }
    }

    @Test fun websitePaginationDoesNotRetryAppOrMixItsDifferentPageSize() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) "" else {
                val number=request.url.queryParameter("page")?.toInt() ?: 1
                page(number,number<3,card((100+number).toString(),"205"))
            }
        }
        val first=repo.search("逆江湖",type=ContentType.COMIC)
        val second=repo.search("逆江湖",2,ContentType.COMIC)
        val last=repo.search("逆江湖",3,ContentType.COMIC)
        assertEquals(listOf("101","102","103"),(first.items+second.items+last.items).map { it.id })
        assertTrue(first.hasMore); assertTrue(second.hasMore); assertFalse(last.hasMore)
        assertEquals(1,paths.count { it=="/reading/bookapi/search/tab/v" })
        assertEquals(3,paths.count { it=="/api/search/result" })
    }

    @Test fun expiredOrDifferentTypedWebsiteSessionRequiresRestart() {
        var now=0L; var calls=0
        val repo=repository(clock={now}) {
            calls++
            if(calls==1) "" else page(1,true,card("101","205"))
        }
        repo.search("逆江湖",type=ContentType.COMIC)
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("逆江湖",2,ContentType.SHORT) }
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("其他剧名",2,ContentType.COMIC) }
        now=300001
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("逆江湖",2,ContentType.COMIC) }
        assertEquals(2,calls)
    }

    @Test fun restartingSearchCanReturnToHealthyAppAndUseItsCursor() {
        var appCalls=0; var webCalls=0
        val repo=repository { request ->
            if(request.url.encodedPath=="/api/search/result") { webCalls++; page(1,true,card("101","205")) }
            else {
                appCalls++
                if(appCalls==1) "" else {
                    if(appCalls==3) assertEquals("app-session",request.url.queryParameter("search_id"))
                    """{"code":0,"search_tabs":[{"tab_type":19,"data":[{"video_data":[{"series_id":"201","title":"应用搜索结果"}]}],"has_more":true,"search_id":"app-session"}]}"""
                }
            }
        }
        repo.search("逆江湖",type=ContentType.COMIC)
        assertEquals("201",repo.search("逆江湖",type=ContentType.COMIC).items.single().id)
        repo.search("逆江湖",2,ContentType.COMIC)
        assertEquals(3,appCalls); assertEquals(1,webCalls)
    }

    @Test fun appFailureOnLaterPageDoesNotSwitchSourceAndSkipResults() {
        var calls=0
        val repo=repository { request ->
            calls++; assertEquals("/reading/bookapi/search/tab/v",request.url.encodedPath)
            if(calls==1) """{"code":0,"search_tabs":[{"tab_type":19,"data":[{"video_data":[{"series_id":"201","title":"应用搜索结果"}]}],"has_more":true}]}""" else ""
        }
        repo.search("逆江湖",type=ContentType.COMIC)
        val failure=assertThrows(IOException::class.java) { repo.search("逆江湖",2,ContentType.COMIC) }
        assertEquals("搜索接口未返回数据，请稍后重试",failure.message)
        assertEquals(2,calls)
    }

    @Test fun malformedOrWrongWebsitePageRemainsFailureInsteadOfNoResults() {
        val invalid=listOf("", "<html>页面外壳</html>", "{}", """{"code":"upstream_unavailable"}""",
            """{"searchList":[],"pageNum":2,"totalPages":2,"hasMore":false}""",
            """{"searchList":[],"pageNum":1}""")
        for(payload in invalid) {
            var calls=0
            val repo=repository { calls++; if(calls==1) "" else payload }
            val failure=assertThrows(IOException::class.java) { repo.search("逆江湖",type=ContentType.COMIC) }
            assertEquals(1,failure.suppressed.size)
            assertEquals("搜索接口未返回数据，请稍后重试",failure.suppressed.single().message)
        }
    }

    @Test fun validEmptyPageKeepsServerPaginationAfterFilteringOtherTypes() {
        var calls=0
        val repo=repository { calls++; if(calls==1) "" else page(1,true,card("101","203")) }
        val result=repo.search("逆江湖",type=ContentType.COMIC)
        assertTrue(result.items.isEmpty()); assertTrue(result.hasMore)
    }
}
