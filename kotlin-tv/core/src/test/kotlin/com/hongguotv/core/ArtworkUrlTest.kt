package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class ArtworkUrlTest {
    private val key="f46efa4c55e9b146f7d4906fd88ceb4e"
    @Test fun `API26 uses the same public asset in JPEG without extra metadata requests`() {
        val source="https://p3-reading-sign.fqnovelpic.com/novel-pic/$key~tplv-81nmtwyey9-superreso-aifit:400:0.heic?x-signature=example"
        assertEquals("https://p6-novel.byteimg.com/novel-pic/$key~tplv-shrink:640:0.image",ArtworkUrl.forApi(source,26))
        assertEquals(source,ArtworkUrl.forApi(source,31))
        for(other in listOf(source.replace("fqnovelpic.com","other.example"),source.replace("$key~","invalid~"),source.replace(".heic?",".jpeg?"))) assertEquals(other,ArtworkUrl.forApi(other,26))
    }
}
