package com.hongguotv.core
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class SignerTest {
    @Test fun requestAndEncryptedSignatureUseTheSameRegisteredDevice() {
        val rows=JSONArray(javaClass.getResource("/signing-registered-device.json")!!.readText())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val device=row.getJSONObject("device")
            val values=VendorConstants.device+mapOf("device_id" to device.getString("device_id"),"iid" to device.getString("iid"),"version_name" to device.getString("version_name"),"video_id" to "123")
            var seed=17L
            val signer=Signer({row.getLong("now")}) { seed=(seed*1664525+1013904223) and 0xffffffffL;seed/4294967296.0 }
            val signed=signer.sign("https://example.test/play",values)
            assertEquals(row.getString("url"),signed.url)
            assertEquals(row.getString("gorgon"),signed.headers["x-gorgon"])
            assertEquals(row.getString("helios"),signed.headers["x-helios"])
            assertEquals(row.getString("medusa"),signed.headers["x-medusa"])
        }
    }
    @Test fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i); val ts=row.getInt("ts"); val url=row.getString("url"); val query=url.substringAfter('?')
            val body=if(row.isNull("body")) null else row.getString("body").toByteArray()
            var seed=17L
            val signer=Signer({ts.toLong()*1000}) { seed=(seed*1664525+1013904223) and 0xffffffffL; seed/4294967296.0 }
            assertEquals("branch $i",0,signer.branch(query,body,ts))
            assertEquals("hash $i",row.getString("hash"),signer.hashF13(sm3(query.toByteArray()),body?.let{digest("MD5",it)}?:ByteArray(16),ts).hex())
            assertEquals("gorgon $i",row.getString("gorgon"),signer.gorgon(query,body,ts,42131))
            assertEquals("helios $i",row.getString("helios"),signer.helios(ts))
            assertEquals("medusa $i",row.getString("medusa"),signer.medusa(url,body,ts))
        }
    }
}
