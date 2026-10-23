package com.multinfc.manager.data

import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.multinfc.manager.nfc.NfcParser

/**
 * Helpers for turning saved card data into an actual NDEF message and back.
 *
 * The app stores the raw NDEF bytes captured from the tag whenever possible.
 * That keeps MIME / smart-poster / unknown records intact while still letting
 * the editor rewrite text and URI records.
 */
object NdefTools {

    /** Standard "NDEF Tag Application" AID for Type 4 tag emulation. */
    const val NDEF_AID = "D2760000850101"

    /** Largest NDEF file the emulated Type 4 tag advertises. */
    const val MAX_EMULATED_NDEF_BYTES = 0x7FFE

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02X".format(it) }

    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.filter { !it.isWhitespace() }
        if (clean.length % 2 != 0) return null
        if (clean.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** Decodes the captured NDEF message; returns null when absent/corrupt. */
    fun rawRecords(card: NfcCard): List<NdefRecord>? {
        val raw = card.ndefRaw?.takeIf { it.isNotBlank() } ?: return null
        val bytes = hexToBytes(raw) ?: return null
        return runCatching { NdefMessage(bytes).records.toList() }.getOrNull()
    }

    /**
     * Best-effort records for a card. Uses the raw message when available and
     * falls back to rebuilding the text/URI records we decoded at scan time.
     */
    fun recordsFor(card: NfcCard): List<NdefRecord> {
        rawRecords(card)?.let { return it }
        return card.ndefRecords.mapNotNull { info ->
            when (info.kind) {
                "文本" -> runCatching { buildText(info.text) }.getOrNull()
                "链接" -> runCatching { buildUri(info.text) }.getOrNull()
                else -> null
            }
        }
    }

    fun messageFor(card: NfcCard): NdefMessage? {
        val records = recordsFor(card)
        if (records.isEmpty()) return null
        return runCatching { NdefMessage(records.toTypedArray()) }.getOrNull()
    }

    fun messageBytesFor(card: NfcCard): ByteArray? = messageFor(card)?.toByteArray()

    fun buildText(text: String): NdefRecord =
        NdefRecord.createTextRecord("zh", text)

    fun buildUri(uri: String): NdefRecord {
        require(uri.isNotBlank()) { "URI 不能为空" }
        require(Uri.parse(uri).scheme != null) { "链接需要包含 http:// 等协议前缀" }
        return NdefRecord.createUri(uri)
    }

    fun hexOf(records: List<NdefRecord>): String? {
        if (records.isEmpty()) return null
        return runCatching {
            bytesToHex(NdefMessage(records.toTypedArray()).toByteArray())
        }.getOrNull()
    }

    fun infoOf(records: List<NdefRecord>): List<NdefRecordInfo> =
        records.map(NfcParser::parseRecord)
}
