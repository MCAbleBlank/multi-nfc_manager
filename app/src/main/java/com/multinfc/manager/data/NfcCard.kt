package com.multinfc.manager.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * One NDEF record, already decoded into something we can render.
 */
@Immutable
data class NdefRecordInfo(
    val kind: String,
    val text: String,
    val detail: String? = null,
)

/**
 * Raw contents of one MIFARE Classic sector. [blocks] holds one uppercase hex
 * string per block (32 characters); a blank entry means the block could not be
 * read. [keyA]/[keyB] are the keys that authenticated this sector, if any.
 */
@Immutable
data class MifareSector(
    val index: Int,
    val keyA: String? = null,
    val keyB: String? = null,
    val blocks: List<String> = emptyList(),
    val readable: Boolean = false,
)

/**
 * A complete-ish dump of a MIFARE Classic card: 1K/2K/4K cards all fit in the
 * per-sector block lists. Sector trailers are included as ordinary blocks so
 * the editor can show (and deliberately modify) them.
 */
@Immutable
data class MifareDump(
    val sizeBytes: Int,
    val sectorCount: Int,
    val blockCount: Int,
    val sectors: List<MifareSector>,
) {
    val readableSectors: Int
        get() = sectors.count { it.readable }

    val isPartial: Boolean
        get() = readableSectors < sectors.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("sizeBytes", sizeBytes)
        put("sectorCount", sectorCount)
        put("blockCount", blockCount)
        put(
            "sectors",
            JSONArray().apply {
                sectors.forEach { sector ->
                    put(
                        JSONObject().apply {
                            put("index", sector.index)
                            put("keyA", sector.keyA ?: JSONObject.NULL)
                            put("keyB", sector.keyB ?: JSONObject.NULL)
                            put("readable", sector.readable)
                            put("blocks", JSONArray(sector.blocks))
                        }
                    )
                }
            },
        )
    }

    companion object {
        fun fromJson(json: JSONObject): MifareDump {
            val sectorsJson = json.optJSONArray("sectors") ?: JSONArray()
            val sectors = buildList {
                for (i in 0 until sectorsJson.length()) {
                    val sector = sectorsJson.optJSONObject(i) ?: continue
                    val blocksJson = sector.optJSONArray("blocks") ?: JSONArray()
                    val blocks = buildList {
                        for (b in 0 until blocksJson.length()) {
                            add(blocksJson.optString(b, ""))
                        }
                    }
                    add(
                        MifareSector(
                            index = sector.optInt("index", i),
                            keyA = sector.optStringOrNull("keyA"),
                            keyB = sector.optStringOrNull("keyB"),
                            blocks = blocks,
                            readable = sector.optBoolean("readable", blocks.any { it.isNotBlank() }),
                        )
                    )
                }
            }
            return MifareDump(
                sizeBytes = json.optInt("sizeBytes", 0),
                sectorCount = json.optInt("sectorCount", sectors.size),
                blockCount = json.optInt("blockCount", 0),
                sectors = sectors,
            )
        }
    }
}

/**
 * A card saved by the user. UID is the identity of the card.
 */
@Immutable
data class NfcCard(
    val uid: String,
    val name: String,
    val typeLabel: String,
    val techList: List<String> = emptyList(),
    val atqa: String? = null,
    val sak: String? = null,
    val capacity: String? = null,
    val ndefRecords: List<NdefRecordInfo> = emptyList(),
    /** Complete NDEF message as uppercase hex, used for HCE and lossless edits. */
    val ndefRaw: String? = null,
    /** MIFARE Classic sectors, when the card could be dumped. */
    val mifare: MifareDump? = null,
    val scannedAt: Long = 0L,
    val scanCount: Int = 1,
) {
    val displayName: String
        get() = name.ifBlank { "未命名卡片" }

    val formattedUid: String
        get() = uid.chunked(2).joinToString(" ")

    /** True when the card carries NDEF content we can emulate or write. */
    val hasNdef: Boolean
        get() = !ndefRaw.isNullOrBlank() ||
            ndefRecords.any { it.kind == "文本" || it.kind == "链接" }

    val hasEditableContent: Boolean
        get() = mifare != null || ndefRecords.isNotEmpty() || !ndefRaw.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("name", name)
        put("typeLabel", typeLabel)
        put("techList", JSONArray(techList))
        put("atqa", atqa ?: JSONObject.NULL)
        put("sak", sak ?: JSONObject.NULL)
        put("capacity", capacity ?: JSONObject.NULL)
        put(
            "ndefRecords",
            JSONArray().apply {
                ndefRecords.forEach { record ->
                    put(
                        JSONObject().apply {
                            put("kind", record.kind)
                            put("text", record.text)
                            put("detail", record.detail ?: JSONObject.NULL)
                        }
                    )
                }
            }
        )
        put("ndefRaw", ndefRaw ?: JSONObject.NULL)
        put("mifare", mifare?.toJson() ?: JSONObject.NULL)
        put("scannedAt", scannedAt)
        put("scanCount", scanCount)
    }

    companion object {
        fun fromJson(json: JSONObject): NfcCard {
            val techArray = json.optJSONArray("techList") ?: JSONArray()
            val tech = buildList {
                for (i in 0 until techArray.length()) {
                    techArray.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }

            val recordsArray = json.optJSONArray("ndefRecords") ?: JSONArray()
            val records = buildList {
                for (i in 0 until recordsArray.length()) {
                    val record = recordsArray.optJSONObject(i) ?: continue
                    add(
                        NdefRecordInfo(
                            kind = record.optString("kind", "记录"),
                            text = record.optString("text", ""),
                            detail = record.optStringOrNull("detail"),
                        )
                    )
                }
            }

            val dump = json.optJSONObject("mifare")
                ?.let { runCatching { MifareDump.fromJson(it) }.getOrNull() }

            return NfcCard(
                uid = json.optString("uid"),
                name = json.optString("name"),
                typeLabel = json.optString("typeLabel", "NFC 卡片"),
                techList = tech,
                atqa = json.optStringOrNull("atqa"),
                sak = json.optStringOrNull("sak"),
                capacity = json.optStringOrNull("capacity"),
                ndefRecords = records,
                ndefRaw = json.optStringOrNull("ndefRaw"),
                mifare = dump,
                scannedAt = json.optLong("scannedAt", 0L),
                scanCount = json.optInt("scanCount", 1),
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
