package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppReleaseUpdatesTest {
    private val packageName = "com.hongguotv.nativeapp.playbackfix"
    private val repository = "BeatsAD/hongguoTV"
    private val api = "https://api.github.com/repos/$repository/releases/latest"
    // This fixture is produced by the Python publisher, including its hidden metadata and release-note sections.
    private fun release() = JSONObject(javaClass.getResourceAsStream("/updates/release-1.0.0.json")!!.bufferedReader().use { it.readText() })
    private fun parse(value: JSONObject) = AppUpdate.parseRelease(value.toString(), packageName, repository)
    private fun changeMetadata(value: JSONObject, change: (JSONObject) -> Unit): JSONObject {
        val body = value.getString("body")
        val marker = "<!-- hongguo-update:"
        val start = body.indexOf(marker)
        val end = body.indexOf("-->", start)
        val data = JSONObject(body.substring(start + marker.length, end).trim())
        change(data)
        return value.put("body", body.substring(0, start) + marker + data.toString() + " -->")
    }
    private fun reject(value: JSONObject) { assertThrows(Exception::class.java) { parse(value) } }
    private fun client(requested: MutableList<String>, respond: (String) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertNull(request.header("Authorization"))
        requested += request.url.toString()
        val (code, body) = respond(request.url.toString())
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()
    }.build()

    @Test fun acceptsPublisherFixtureAndCleanNamesWithoutManifestAttachment() {
        val update = parse(release())
        assertEquals("1.0.0", update.versionName)
        assertEquals(10004L, update.versionCode)
        assertEquals("hongguo-1.0.0.apk", update.fileName)
        assertEquals("https://github.com/BeatsAD/hongguoTV/releases/download/v1.0.0/hongguo-1.0.0.apk", update.downloadUrl)
        assertTrue(update.notes.contains("## 新增功能")); assertTrue(update.notes.contains("## 问题修复"))
        assertFalse(update.notes.contains("hongguo-update:")); assertFalse(update.notes.contains("10004"))
        assertTrue(update.newerThan(10003, 26)); assertFalse(update.newerThan(10004, 26))
    }
    @Test fun ignoresUntrustedDownloadAndRepositoryFields() {
        val value = release().put("downloadUrl", "http://attacker.example/file").put("repository", "attacker/other")
        val update = parse(value)
        assertEquals("https://github.com/$repository/releases/download/v1.0.0/hongguo-1.0.0.apk", update.downloadUrl)
    }
    @Test fun rejectsDraftsPrereleasesAndTagVersionMismatch() {
        reject(release().put("draft", true)); reject(release().put("prerelease", true))
        reject(release().put("tag_name", "v1.0.1")); reject(release().put("tag_name", "../../bad"))
        reject(changeMetadata(release()) { it.put("versionName", "1.0.0-playbackfix") })
        reject(changeMetadata(release()) { it.put("versionName", "01.0.0") })
    }
    @Test fun rejectsMissingDuplicateOrWrongMetadata() {
        reject(release().put("body", "## 新增功能\n- none\n## 问题修复\n- none"))
        val value = release(); reject(value.put("body", value.getString("body") + "<!-- hongguo-update:{} -->"))
        reject(changeMetadata(release()) { it.put("schema", 1) })
        reject(changeMetadata(release()) { it.put("packageName", "com.other.app") })
    }
    @Test fun rejectsAmbiguousApksUnfinishedUploadsAndWrongNames() {
        reject(release().put("assets", JSONArray()))
        val value = release(); value.getJSONArray("assets").put(JSONObject(value.getJSONArray("assets").getJSONObject(0).toString())); reject(value)
        for (name in listOf("evil.apk", "../hongguo-1.0.0.apk", "hongguo-1.0.1.apk")) {
            val r = release(); r.getJSONArray("assets").getJSONObject(0).put("name", name); reject(r)
        }
        val r = release(); r.getJSONArray("assets").getJSONObject(0).put("state", "new"); reject(r)
    }
    @Test fun requiresGithubDigestAndBoundedSizeVersionAndSdk() {
        for (digest in listOf("", "sha256:bad", "md5:" + "0".repeat(64))) {
            val r = release(); r.getJSONArray("assets").getJSONObject(0).put("digest", digest); reject(r)
        }
        for (size in listOf(0L, -1L, AppUpdate.MAX_APK_BYTES + 1)) {
            val r = release(); r.getJSONArray("assets").getJSONObject(0).put("size", size); reject(r)
        }
        for (code in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) reject(changeMetadata(release()) { it.put("versionCode", code) })
        for (sdk in listOf(25, 101)) reject(changeMetadata(release()) { it.put("minSdk", sdk) })
    }
    @Test fun modernCheckMakesOneApiRequestAndDoesNotFetchUpdateJson() {
        val requested = mutableListOf<String>()
        AppUpdateRepository(repository, packageName, client(requested) { 200 to release().toString() }).use {
            assertEquals("1.0.0", it.latest().versionName)
        }
        assertEquals(listOf(api), requested)
    }
    @Test fun malformedModernReleaseOrRateLimitDoesNotFallBackToOtherSources() {
        for ((code, body) in listOf(200 to release().put("body", "missing metadata").toString(), 429 to "rate limited", 403 to "forbidden", 200 to "x".repeat(65537))) {
            val requested = mutableListOf<String>()
            AppUpdateRepository(repository, packageName, client(requested) { code to body }).use {
                assertThrows(IOException::class.java) { it.latest() }
            }
            assertEquals(listOf(api), requested)
        }
    }
    @Test fun historicalFallbackIsPinnedToTheApiTag() {
        val tag = "kotlin-v0.10.5-build.10003"
        val historical = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", tag).put("body", "legacy")
        val manifest = JSONObject().put("schema", 1).put("packageName", packageName).put("versionCode", 10003).put("versionName", "0.10.5+3-playbackfix")
            .put("minSdk", 26).put("tag", tag).put("apk", JSONObject().put("name", "hongguotv-kotlin-0.10.5-build.10003-android8.apk").put("size", 10).put("sha256", "0".repeat(64)))
        val requested = mutableListOf<String>()
        AppUpdateRepository(repository, packageName, client(requested) { url -> 200 to if (url == api) historical.toString() else manifest.toString() }).use {
            assertEquals(10003L, it.latest().versionCode)
        }
        assertEquals(listOf(api, "https://github.com/$repository/releases/download/$tag/update.json"), requested)
        manifest.put("tag", "kotlin-v0.10.5-build.10002")
        AppUpdateRepository(repository, packageName, client(mutableListOf()) { url -> 200 to if (url == api) historical.toString() else manifest.toString() }).use {
            assertThrows(IOException::class.java) { it.latest() }
        }
    }
}
