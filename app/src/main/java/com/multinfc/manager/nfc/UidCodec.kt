package com.multinfc.manager.nfc

/**
 * UID parsing shared by the NFC-A emulation code and its JVM tests.
 *
 * Emulation only accepts the UID lengths the Android OEM extension documents:
 * 4, 7, or 10 bytes. Input is the same uppercase hex form the rest of the app
 * stores (`NfcCard.uid`, built from `Tag.getId()`), optionally separated by
 * spaces, `:` or `-`.
 */
object UidCodec {

    val supportedByteLengths = intArrayOf(4, 7, 10)

    fun parse(hex: String): ByteArray? {
        val clean = hex.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (clean.length % 2 != 0) return null
        val bytes = clean.length / 2
        if (bytes !in supportedByteLengths) return null
        if (!clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        return ByteArray(bytes) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}
