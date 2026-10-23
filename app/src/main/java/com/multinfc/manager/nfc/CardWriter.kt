package com.multinfc.manager.nfc

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.multinfc.manager.data.MifareDump
import com.multinfc.manager.data.NdefTools
import com.multinfc.manager.data.NfcCard

/**
 * Writes edited card content back to a physical tag.
 *
 * NDEF writes go through the platform's Type 4 / NDEF stack, so they work on
 * any NDEF-formatted card. MIFARE writes authenticate each sector with the
 * keys captured during the dump and rewrite its blocks. The manufacturer block
 * (sector 0, block 0) is written too when the user edited it: factory cards
 * reject that write, UID-writable "magic" cards accept it.
 */
object CardWriter {

    data class Outcome(
        val success: Boolean,
        val message: String,
        val ndefRaw: String? = null,
        val mifare: MifareDump? = null,
        /** New UID when a rewritten block 0 changed it, otherwise null. */
        val newUid: String? = null,
    )

    fun writeNdef(tag: Tag, card: NfcCard): Outcome {
        val records = NdefTools.recordsFor(card)
        if (records.isEmpty()) {
            return Outcome(false, "没有可写入的 NDEF 内容")
        }
        val message = runCatching { NdefMessage(records.toTypedArray()) }
            .getOrElse { return Outcome(false, "NDEF 内容无效：${it.message ?: "格式错误"}") }
        val bytes = message.toByteArray()

        val ndef = runCatching { Ndef.get(tag) }.getOrNull()
        if (ndef != null) {
            return runCatching {
                ndef.connect()
                try {
                    if (!ndef.isWritable) {
                        return Outcome(false, "这张卡已设置为只读，无法写入")
                    }
                    if (ndef.maxSize > 0 && bytes.size > ndef.maxSize) {
                        return Outcome(
                            false,
                            "内容需要 ${bytes.size} 字节，超过卡片可用容量 ${ndef.maxSize} 字节",
                        )
                    }
                    ndef.writeNdefMessage(message)
                    Outcome(
                        success = true,
                        message = "NDEF 已写入（${bytes.size} 字节）",
                        ndefRaw = NdefTools.bytesToHex(bytes),
                    )
                } finally {
                    runCatching { ndef.close() }
                }
            }.getOrElse { error ->
                Outcome(false, "写入失败：${error.describe()}")
            }
        }

        val formatable = runCatching { NdefFormatable.get(tag) }.getOrNull()
            ?: return Outcome(false, "这张卡不支持 NDEF 写入")
        return runCatching {
            formatable.connect()
            try {
                formatable.format(message)
                Outcome(
                    success = true,
                    message = "已格式化并写入 NDEF（${bytes.size} 字节）",
                    ndefRaw = NdefTools.bytesToHex(bytes),
                )
            } finally {
                runCatching { formatable.close() }
            }
        }.getOrElse { error ->
            Outcome(false, "写入失败：${error.describe()}")
        }
    }

    fun writeMifare(tag: Tag, dump: MifareDump): Outcome {
        val classic = runCatching { MifareClassic.get(tag) }.getOrNull()
            ?: return Outcome(false, "这张卡不是 MIFARE Classic，无法写入扇区数据")

        var written = 0
        var skipped = 0
        var unchanged = 0
        var manufacturerWritten = false
        var manufacturerFailure: String? = null
        val failedSectors = mutableListOf<Int>()
        // Keys are refreshed after a trailer is actually rewritten, so the
        // verification read below can authenticate with the new keys.
        val updatedSectors = dump.sectors.associateBy { it.index }.toMutableMap()

        val ioFailure = runCatching {
            classic.connect()
            try {
                for (sector in dump.sectors) {
                    val blockCount = runCatching {
                        classic.getBlockCountInSector(sector.index)
                    }.getOrDefault(0)
                    if (blockCount == 0) continue
                    val firstBlock = classic.sectorToBlock(sector.index)

                    val keyA = sector.keyA?.let(NdefTools::hexToBytes)
                    val keyB = sector.keyB?.let(NdefTools::hexToBytes)
                    val authenticated = when {
                        keyA != null && runCatching {
                            classic.authenticateSectorWithKeyA(sector.index, keyA)
                        }.getOrDefault(false) -> true

                        keyB != null && runCatching {
                            classic.authenticateSectorWithKeyB(sector.index, keyB)
                        }.getOrDefault(false) -> true

                        else -> false
                    }

                    val hasContent = sector.blocks.any { it.isNotBlank() }
                    if (!authenticated) {
                        if (hasContent) failedSectors += sector.index
                        continue
                    }

                    var trailerWritten = false
                    blockLoop@ for (offset in 0 until minOf(blockCount, sector.blocks.size)) {
                        val hex = sector.blocks[offset].trim()
                        if (hex.isBlank()) {
                            skipped++
                            continue
                        }
                        val data = NdefTools.hexToBytes(hex)
                        if (data == null || data.size != 16) {
                            skipped++
                            continue
                        }
                        val targetBlock = firstBlock + offset
                        // Never rewrite a block that already holds these bytes.
                        // This matters most for the trailer: rewriting it with
                        // an unreadable Key B (returned as zeros) can lock the
                        // sector even when the user only changed a data block.
                        val current = runCatching { classic.readBlock(targetBlock) }.getOrNull()
                        if (current != null && current.contentEquals(data)) {
                            unchanged++
                            continue
                        }
                        val isManufacturer = sector.index == 0 && offset == 0
                        if (isManufacturer) {
                            // Sector 0 / block 0 holds the UID and BCC. Factory
                            // cards NAK this write; magic cards accept it. A
                            // rejected write must not abort the whole dump, so
                            // it is recorded and the remaining blocks continue.
                            try {
                                classic.writeBlock(targetBlock, data)
                                written++
                                manufacturerWritten = true
                            } catch (error: android.nfc.TagLostException) {
                                throw error
                            } catch (error: Exception) {
                                manufacturerFailure = error.describe()
                                // A rejected write can drop the sector
                                // authentication; restore it so the remaining
                                // blocks of sector 0 are still written.
                                val restored = (keyA != null && runCatching {
                                    classic.authenticateSectorWithKeyA(sector.index, keyA)
                                }.getOrDefault(false)) || (keyB != null && runCatching {
                                    classic.authenticateSectorWithKeyB(sector.index, keyB)
                                }.getOrDefault(false))
                                if (!restored) {
                                    failedSectors += sector.index
                                    break@blockLoop
                                }
                            }
                            continue
                        }
                        classic.writeBlock(targetBlock, data)
                        written++
                        if (offset == blockCount - 1) trailerWritten = true
                    }

                    if (trailerWritten) {
                        val trailer = sector.blocks.getOrNull(blockCount - 1)?.trim().orEmpty()
                        if (trailer.length == 32) {
                            updatedSectors[sector.index] = sector.copy(
                                keyA = trailer.substring(0, 12),
                                keyB = trailer.substring(24, 32),
                            )
                        }
                    }
                }
                null
            } finally {
                runCatching { classic.close() }
            }
        }.exceptionOrNull()

        if (ioFailure != null) {
            return Outcome(
                false,
                "写入中断：${ioFailure.describe()}（已写入 $written 个块）",
            )
        }
        if (written == 0 && manufacturerFailure != null) {
            return Outcome(
                false,
                "块 0 写入失败：$manufacturerFailure。原厂卡的厂商块是只读的，" +
                    "只有 UID 可写卡（Gen1a/Gen2 魔术卡）才能改写",
            )
        }
        if (written == 0 && failedSectors.isEmpty()) {
            return Outcome(
                success = true,
                message = "内容与卡片上已有数据一致，无需写入",
                mifare = dump,
            )
        }
        if (written == 0) {
            return Outcome(
                false,
                "扇区 ${failedSectors.joinToString("、")} 没有可用密钥，没有写入任何块",
            )
        }

        val updatedDump = dump.copy(
            sectors = dump.sectors.map { updatedSectors[it.index] ?: it },
        )

        // Re-read after writing so the stored dump reflects the real card.
        // The updated sector keys are used here, so a rewritten trailer with
        // new keys can still be verified.
        val verified = runCatching {
            MifareClassic.get(tag)?.let { NfcParser.readMifareDump(it, updatedDump) }
        }.getOrNull()

        val finalDump = verified ?: updatedDump
        val oldUid = uidFromBlock0(dump.block0Hex())
        val newUid = uidFromBlock0(finalDump.block0Hex())
            ?.takeIf { !it.equals(oldUid, ignoreCase = true) }

        val details = buildString {
            append("已写入 $written 个块")
            if (unchanged > 0) append("，$unchanged 个块无需修改")
            if (skipped > 0) append("，跳过 $skipped 个")
            if (manufacturerWritten) append("，块 0（厂商块）已改写，卡片 UID 可能已变更")
            if (manufacturerFailure != null) {
                append("；块 0 未能写入（$manufacturerFailure），其余块已写入")
            }
            if (failedSectors.isNotEmpty()) {
                append("；扇区 ${failedSectors.joinToString("、")} 缺少可用密钥")
            }
        }
        return Outcome(
            success = true,
            message = details,
            mifare = finalDump,
            newUid = newUid,
        )
    }

    private fun MifareDump.block0Hex(): String? =
        sectors.firstOrNull { it.index == 0 }?.blocks?.firstOrNull()

    /**
     * Parses a 4-byte-UID manufacturer block (UID0..UID3, BCC, 8 data bytes).
     * Bytes are kept in on-air order, which is the order [Tag.getId] uses and
     * therefore the order this app keys cards by. Only returns a value when the
     * BCC checksum matches, so a corrupted edit is never mistaken for a new UID.
     */
    private fun uidFromBlock0(hex: String?): String? {
        val bytes = hex?.let(NdefTools::hexToBytes) ?: return null
        if (bytes.size != 16) return null
        val bcc = bytes.take(4).fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }
        if ((bytes[4].toInt() and 0xFF) != bcc) return null
        return bytes.take(4).joinToString("") { "%02X".format(it) }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
