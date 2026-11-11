package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/** Keep signing and media-key byte semantics while adapting upstream codecs. */
class BytesCompatibilityTest {
    @Test fun paddedAndUnpaddedKeysDecodeToTheSameBytes() {
        assertArrayEquals(byteArrayOf(1,2,3,4),decode64("AQIDBA=="))
        assertArrayEquals(byteArrayOf(1,2,3,4),decode64("AQIDBA"))
    }
    @Test fun urlSafeKeysPreserveUnsignedBytes() {
        assertArrayEquals(byteArrayOf(255.toByte(),255.toByte()),decode64("__8"))
        assertEquals("//8=",byteArrayOf(255.toByte(),255.toByte()).base64())
    }
    @Test fun invalidLengthsAndCharactersAreRejected() {
        assertThrows(RuntimeException::class.java) { decode64("A") }
        assertThrows(RuntimeException::class.java) { decode64("****") }
    }
    @Test fun emptyPayloadRemainsEmpty() {
        assertArrayEquals(byteArrayOf(),decode64(""))
        assertEquals("",byteArrayOf().base64())
    }
}
