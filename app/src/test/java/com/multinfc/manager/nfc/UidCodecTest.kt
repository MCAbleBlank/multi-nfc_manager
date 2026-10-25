package com.multinfc.manager.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UidCodecTest {

    @Test
    fun parsesFourByteUid() {
        assertArrayEquals(
            byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()),
            UidCodec.parse("04A1B2C3"),
        )
    }

    @Test
    fun parsesSevenAndTenByteUids() {
        assertArrayEquals(
            byteArrayOf(
                0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(),
                0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte(),
            ),
            UidCodec.parse("04:A1:B2:C3:D4:E5:F6"),
        )
        assertArrayEquals(
            ByteArray(10) { it.toByte() },
            UidCodec.parse("00 01 02 03 04 05 06 07 08 09"),
        )
    }

    @Test
    fun acceptsLowercaseHex() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte()),
            UidCodec.parse("0abcdef0"),
        )
    }

    @Test
    fun rejectsUnsupportedLengths() {
        assertNull(UidCodec.parse("04A1B2"))
        assertNull(UidCodec.parse("04A1B2C3D4"))
        assertNull(UidCodec.parse("04A1B2C3D4E5F6A7"))
        assertNull(UidCodec.parse(""))
    }

    @Test
    fun rejectsInvalidHex() {
        assertNull(UidCodec.parse("04A1B2CG"))
        assertNull(UidCodec.parse("04A1B2C"))
    }

    @Test
    fun formatsUppercaseHex() {
        assertEquals(
            "04A1B2C3",
            UidCodec.toHex(byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte())),
        )
    }
}
