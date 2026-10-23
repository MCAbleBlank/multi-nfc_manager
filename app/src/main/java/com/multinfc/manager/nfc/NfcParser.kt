package com.multinfc.manager.nfc

import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import com.multinfc.manager.data.MifareDump
import com.multinfc.manager.data.MifareSector
import com.multinfc.manager.data.NdefRecordInfo
import com.multinfc.manager.data.NdefTools
import com.multinfc.manager.data.NfcCard
import java.nio.charset.Charset

/**
 * Turns a raw [Tag] into a persisted [NfcCard]. NDEF is read from the cached
 * message; MIFARE Classic cards additionally get a best-effort sector dump
 * using the usual factory/default key dictionary.
 *
 * This performs real tag I/O, so callers must run it off the main thread.
 */
object NfcParser {

    /**
     * Keys tried when dumping a MIFARE Classic card. Covers factory defaults,
     * the NFC Forum public key and the most common keys used by access-control
     * installations that leave cards unlocked.
     */
    private val DEFAULT_KEYS = listOf(
        "FFFFFFFFFFFF",
        "000000000000",
        "A0A1A2A3A4A5",
        "D3F7D3F7D3F7",
        "1A982C7E459A",
        "4D3A99C351DD",
        "AABBCCDDEEFF",
        "714C5C886E97",
        "587EE5F9350F",
        "A0478CC39091",
        "533CB6C723F6",
        "8FD0A4F256E9",
        "0000014B5C31",
        "B0B1B2B3B4B5",
        "4B791BEA7B6C",
        "6A4709286B75",
        "434F4D4D4F44",
        "484558414354",
        "A64598A77478",
        "26940B21FF5D",
        "FC00018778F7",
        "5C8FF9990DA2",
        "D01AFEEB890A",
        "75CCB59C9BED",
        "000000000001",
    ).distinct()

    private val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:",
        "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
        "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:",
        "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:",
        "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
        "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    fun parse(tag: Tag, existing: NfcCard?): NfcCard {
        val uid = tag.id.toHex()
        val techList = tag.techList.mapNotNull { it.substringAfterLast('.') }
        val nfcA = runCatching { NfcA.get(tag) }.getOrNull()
        val classic = runCatching { MifareClassic.get(tag) }.getOrNull()
        val ultralight = runCatching { MifareUltralight.get(tag) }.getOrNull()
        val ndef = runCatching { Ndef.get(tag) }.getOrNull()
        val message = runCatching { ndef?.cachedNdefMessage }.getOrNull()
        val ndefRaw = runCatching {
            message?.toByteArray()?.let(NdefTools::bytesToHex)
        }.getOrNull()

        val typeLabel = when {
            classic != null -> "MIFARE Classic"
            ultralight != null -> when (ultralight.type) {
                MifareUltralight.TYPE_ULTRALIGHT_C -> "MIFARE Ultralight C"
                else -> "MIFARE Ultralight"
            }
            "IsoDep" in techList -> "ISO-DEP 智能卡"
            "NfcV" in techList -> "NFC-V 标签"
            "NfcF" in techList -> "FeliCa 卡片"
            "NfcB" in techList -> "NFC-B 标签"
            ndef != null -> "NFC 标签"
            "NdefFormatable" in techList -> "可格式化标签"
            else -> "NFC 卡片"
        }

        val freshDump = classic?.let { readMifareDump(it, existing?.mifare) }
        val dump = mergeDumps(freshDump, existing?.mifare)

        val capacity = when {
            classic != null -> buildString {
                append("${classic.size} 字节 · ${classic.sectorCount} 个扇区")
                if (dump != null) {
                    append(" · 已读取 ${dump.readableSectors}/${dump.sectorCount}")
                }
            }
            ndef != null && ndef.maxSize > 0 -> "NDEF 可用 ${ndef.maxSize} 字节"
            else -> null
        }

        return NfcCard(
            uid = uid,
            name = existing?.name.orEmpty(),
            typeLabel = typeLabel,
            techList = techList,
            atqa = nfcA?.atqa?.toHex(),
            sak = nfcA?.let { "%02X".format(it.sak) },
            capacity = capacity,
            ndefRecords = message?.records?.map(::parseRecord).orEmpty(),
            ndefRaw = ndefRaw,
            mifare = dump,
            scannedAt = System.currentTimeMillis(),
            scanCount = (existing?.scanCount ?: 0) + 1,
        )
    }

    /**
     * Reads every sector it can authenticate. Keys the user entered earlier
     * (stored on the existing dump) are tried before the built-in dictionary.
     */
    fun readMifareDump(classic: MifareClassic, existing: MifareDump?): MifareDump? {
        return runCatching {
            classic.connect()
            try {
                val sectors = (0 until classic.sectorCount).map { index ->
                    readSector(classic, index, existing)
                }
                MifareDump(
                    sizeBytes = classic.size,
                    sectorCount = classic.sectorCount,
                    blockCount = classic.blockCount,
                    sectors = sectors,
                )
            } finally {
                runCatching { classic.close() }
            }
        }.getOrNull()
    }

    private fun readSector(
        classic: MifareClassic,
        index: Int,
        existing: MifareDump?,
    ): MifareSector {
        val old = existing?.sectors?.firstOrNull { it.index == index }
        val keyACandidates = candidateKeys(old?.keyA)
        val keyBCandidates = candidateKeys(old?.keyB)
        val blockCount = runCatching { classic.getBlockCountInSector(index) }.getOrDefault(4)

        var authenticatedWithA: String? = null
        var authenticatedWithB: String? = null

        for (key in keyACandidates) {
            val bytes = NdefTools.hexToBytes(key) ?: continue
            if (auth { classic.authenticateSectorWithKeyA(index, bytes) }) {
                authenticatedWithA = key
                break
            }
        }
        if (authenticatedWithA == null) {
            for (key in keyBCandidates) {
                val bytes = NdefTools.hexToBytes(key) ?: continue
                if (auth { classic.authenticateSectorWithKeyB(index, bytes) }) {
                    authenticatedWithB = key
                    break
                }
            }
        }

        if (authenticatedWithA == null && authenticatedWithB == null) {
            return MifareSector(
                index = index,
                keyA = old?.keyA,
                keyB = old?.keyB,
                blocks = List(blockCount) { "" },
                readable = false,
            )
        }

        val blocks = buildList {
            val firstBlock = runCatching { classic.sectorToBlock(index) }.getOrDefault(index * 4)
            repeat(blockCount) { offset ->
                val hex = runCatching {
                    classic.readBlock(firstBlock + offset).toHex()
                }.getOrElse { "" }
                add(hex)
            }
        }

        // Discover the other key as well when the card still allows it; this
        // is helpful if the user later edits the sector trailer.
        if (authenticatedWithA != null && authenticatedWithB == null) {
            for (key in keyBCandidates) {
                val bytes = NdefTools.hexToBytes(key) ?: continue
                if (auth { classic.authenticateSectorWithKeyB(index, bytes) }) {
                    authenticatedWithB = key
                    break
                }
            }
        }

        return MifareSector(
            index = index,
            keyA = authenticatedWithA ?: old?.keyA,
            keyB = authenticatedWithB ?: old?.keyB,
            blocks = blocks,
            readable = blocks.any { it.isNotBlank() },
        )
    }

    private inline fun auth(block: () -> Boolean): Boolean =
        runCatching(block).getOrDefault(false)

    private fun candidateKeys(preferred: String?): List<String> {
        val list = ArrayList<String>(DEFAULT_KEYS.size + 1)
        preferred?.takeIf { it.length == 12 }?.let(list::add)
        list.addAll(DEFAULT_KEYS)
        return list.distinct()
    }

    private fun mergeDumps(fresh: MifareDump?, old: MifareDump?): MifareDump? {
        if (fresh == null) return old
        if (old == null) return fresh
        val oldByIndex = old.sectors.associateBy { it.index }
        val sectors = fresh.sectors.map { sector ->
            if (sector.readable) {
                sector
            } else {
                oldByIndex[sector.index]?.takeIf { it.readable } ?: sector
            }
        }
        return fresh.copy(sectors = sectors)
    }

    fun parseRecord(record: NdefRecord): NdefRecordInfo {
        val payload = record.payload
        return when {
            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                val text = runCatching { decodeText(payload) }.getOrNull()
                NdefRecordInfo(
                    kind = "文本",
                    text = text ?: "无法解析的文本记录",
                )
            }

            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_URI) -> {
                val uri = runCatching { decodeUri(payload) }.getOrNull()
                NdefRecordInfo(
                    kind = "链接",
                    text = uri ?: "无法解析的链接",
                )
            }

            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_SMART_POSTER) ->
                NdefRecordInfo("智能海报", "包含 ${payload.size} 字节数据")

            record.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                val mime = runCatching { record.toMimeType() }.getOrNull() ?: "未知类型"
                val body = runCatching {
                    String(payload, Charsets.UTF_8).trim().take(400)
                }.getOrNull()
                NdefRecordInfo(
                    kind = "MIME",
                    text = body?.takeIf { it.isNotBlank() } ?: mime,
                    detail = body?.takeIf { it.isNotBlank() }?.let { mime },
                )
            }

            record.tnf == NdefRecord.TNF_ABSOLUTE_URI ->
                NdefRecordInfo("链接", String(payload, Charsets.UTF_8))

            record.tnf == NdefRecord.TNF_EMPTY ->
                NdefRecordInfo("空记录", "没有内容")

            else -> NdefRecordInfo("未知记录", "${payload.size} 字节")
        }
    }

    private fun decodeText(payload: ByteArray): String {
        require(payload.isNotEmpty()) { "empty text payload" }
        val status = payload[0].toInt() and 0xFF
        val charset = if (status and 0x80 != 0) Charsets.UTF_8 else Charset.forName("UTF-16")
        val languageLength = status and 0x3F
        val textStart = 1 + languageLength
        require(textStart <= payload.size) { "invalid text payload" }
        return String(payload, textStart, payload.size - textStart, charset)
    }

    private fun decodeUri(payload: ByteArray): String {
        require(payload.isNotEmpty()) { "empty uri payload" }
        val prefixIndex = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrElse(prefixIndex) { "" }
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it) }
}
