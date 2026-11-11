package com.hongguotv.core

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class PlaybackDeviceSessionTest {
    @Test fun compatibilityIdentityIsPersistedAndDoesNotReplaceCatalogIdentity() {
        var calls=0
        val session=PlaybackDeviceSession(register={ _,_->DeviceRegister.Registered("123","456") },
            registerPlayer={ calls++;PlayerDevice("789","234","cdid","openudid","") })
        val catalog=session.profile(OkHttpClient())
        val player=session.playerProfile(OkHttpClient())
        assertEquals(player,session.playerProfile(OkHttpClient()));assertEquals(1,calls)
        assertEquals(catalog,session.profile(OkHttpClient()))
        val restored=PlaybackDeviceSession(registerPlayer={error("must reuse persisted identity")})
        restored.restore(session.snapshot())
        assertEquals(player,restored.playerProfile(OkHttpClient()))
        assertEquals(catalog,restored.profile(OkHttpClient()))
    }
    @Test fun failedCompatibilityRegistrationHasAPersistedCooldown() {
        var calls=0
        val session=PlaybackDeviceSession(clock={1000},registerPlayer={calls++;throw java.io.IOException("offline")})
        assertThrows(java.io.IOException::class.java) { session.playerProfile(OkHttpClient()) }
        assertThrows(ContentRequestDeferredException::class.java) { session.playerProfile(OkHttpClient()) }
        val restored=PlaybackDeviceSession(clock={1000},registerPlayer={error("must respect cooldown")})
        restored.restore(session.snapshot())
        assertThrows(ContentRequestDeferredException::class.java) { restored.playerProfile(OkHttpClient()) }
        assertEquals(1,calls)
    }
    @Test fun registrationWirePayloadHasRequiredTypesAndNoInventedServerIds() {
        val payload=DeviceRegister.payload(VendorConstants.device+("cdid" to "fixture"),1234)
        assertArrayEquals(byteArrayOf(0x74,0x63,5,0x10,0,0),payload.copyOfRange(0,6))
        val salt=hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
        val derived=digest("SHA-512",digest("SHA-512",payload.copyOfRange(6,38))+salt)
        val plain=aes("CBC",derived.copyOfRange(0,16),derived.copyOfRange(16,32),payload.copyOfRange(38,payload.size))
        val compressed=plain.copyOfRange(64,plain.size-plain.last().u())
        assertArrayEquals(digest("SHA-512",compressed),plain.copyOfRange(0,64))
        val json=JSONObject(GZIPInputStream(compressed.inputStream()).bufferedReader().readText())
        val header=json.getJSONObject("header")
        assertEquals("ss_app_log",json.getString("magic_tag"));assertEquals(1234,json.getLong("_gen_time"))
        assertTrue(header.get("os_version") is String)
        assertEquals("fixture",header.getString("cdid"))
        assertFalse(header.has("device_id"));assertFalse(header.has("install_id"))
        val headers=DeviceRegister.headers(payload,VendorConstants.device,1234)
        assertEquals(digest("MD5",payload).hex().uppercase(),headers["x-ss-stub"])
        assertFalse(headers.containsKey("Accept-Encoding"));assertFalse(headers.containsKey("x-argus"))
    }
    @Test fun oneRegistrationIsReusedAndPrivateSnapshotSurvivesRestart() {
        var registrations=0
        val session=PlaybackDeviceSession(register={ _,_->
            registrations++
            DeviceRegister.Registered("123","456",cookies=mapOf("install_id" to "456","ttreq" to "safe","sessionid" to "account-secret","store-region" to "cn"))
        })
        val first=session.profile(OkHttpClient());val again=session.profile(OkHttpClient())
        assertEquals(first,again);assertEquals(1,registrations)
        assertFalse(first.cookie.contains("sessionid"));assertFalse(session.snapshot().contains("account-secret"))
        val restored=PlaybackDeviceSession(register={ _,_->error("must not register again") })
        restored.restore(session.snapshot())
        assertEquals(first,restored.profile(OkHttpClient()))
    }
    @Test fun serviceCooldownPersistsAndDoesNotRotateIdentityOrFloodRegistration() {
        var now=0L;var calls=0
        val session=PlaybackDeviceSession(clock={now},register={ _,_->calls++;throw ContentRequestDeferredException(3_600_000) })
        assertThrows(PlaybackRegistrationException::class.java) { session.profile(OkHttpClient()) }
        assertThrows(ContentRequestDeferredException::class.java) { session.profile(OkHttpClient()) }
        assertEquals(1,calls)
        val next=PlaybackDeviceSession(clock={now},register={ _,_->calls++;DeviceRegister.Registered("123","456") })
        next.restore(session.snapshot());now=1000
        assertThrows(ContentRequestDeferredException::class.java) { next.profile(OkHttpClient()) };assertEquals(1,calls)
        now=3_600_001;next.profile(OkHttpClient());assertEquals(2,calls)
    }
    @Test fun invalidIdsAndHeaderInjectionAreRejected() {
        val session=PlaybackDeviceSession(register={ _,_->DeviceRegister.Registered("0","456") })
        assertThrows(PlaybackRegistrationException::class.java) { session.profile(OkHttpClient()) }
        val clean=PlaybackDeviceSession(register={ _,_->DeviceRegister.Registered("123","456",cookies=mapOf("ttreq" to "secret\r\nOther: injected")) })
        assertEquals("",clean.profile(OkHttpClient()).cookie)
        assertNull(DeviceRegister.parse("""{"device_id":0,"install_id":456}"""))
    }
    @Test fun leavingPageDuringRegistrationDoesNotPutNextPageIntoCooldown() {
        val scope=RequestScope()
        val session=PlaybackDeviceSession(register={ _,_-> scope.cancel();throw java.io.IOException("cancelled") })
        assertThrows(java.io.IOException::class.java) { scope.run { session.profile(OkHttpClient()) } }
        assertEquals(0,JSONObject(session.snapshot()).getLong("retryAt"))
    }
}
