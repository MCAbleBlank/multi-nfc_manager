package com.multinfc.manager.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.multinfc.manager.data.CardRepository
import com.multinfc.manager.data.NdefTools

/**
 * Host Card Emulation of a read-only NDEF Type 4 tag.
 *
 * Android's HCE framework only exposes ISO-DEP (ISO 14443-4) communication to
 * apps: it cannot present a MIFARE Classic UID or answer raw crypto1 commands.
 * What we *can* do is expose the saved NDEF message as a standard Type 4 tag,
 * which other phones, NDEF readers and many kiosks understand.
 *
 * The active card is read from SharedPreferences on every command, so turning
 * emulation on/off or editing the content takes effect immediately.
 */
class NdefHostApduService : HostApduService() {

    private enum class SelectedFile { None, Cc, Ndef }

    private var selectedFile = SelectedFile.None

    private val repository by lazy { CardRepository(applicationContext) }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return SW_FILE_NOT_FOUND
        if (apdu.size < 4) return SW_FILE_NOT_FOUND

        return when (apdu[1].toInt() and 0xFF) {
            INS_SELECT -> handleSelect(apdu)
            INS_READ_BINARY -> handleReadBinary(apdu)
            INS_UPDATE_BINARY -> SW_COMMAND_NOT_ALLOWED
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = SelectedFile.None
    }

    private fun handleSelect(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)

        return when {
            // Select by DF name: the NDEF Tag Application.
            p1 == 0x04 -> {
                val aid = NdefTools.bytesToHex(data)
                if (aid.equals(NdefTools.NDEF_AID, ignoreCase = true)) {
                    selectedFile = SelectedFile.None
                    SW_OK
                } else {
                    SW_FILE_NOT_FOUND
                }
            }

            // Select by file identifier: CC (E103) or NDEF (E104).
            p1 == 0x00 && p2 == 0x0C -> {
                if (data.size != 2) return SW_WRONG_LENGTH
                when (NdefTools.bytesToHex(data)) {
                    "E103" -> {
                        selectedFile = SelectedFile.Cc
                        SW_OK
                    }

                    "E104" -> {
                        selectedFile = SelectedFile.Ndef
                        SW_OK
                    }

                    else -> SW_FILE_NOT_FOUND
                }
            }

            else -> SW_FILE_NOT_FOUND
        }
    }

    private fun handleReadBinary(apdu: ByteArray): ByteArray {
        val file = when (selectedFile) {
            SelectedFile.Cc -> CC_FILE
            SelectedFile.Ndef -> ndefFile() ?: return SW_FILE_NOT_FOUND
            SelectedFile.None -> return SW_COMMAND_NOT_ALLOWED
        }
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        if (offset >= file.size) return SW_WRONG_OFFSET

        val le = if (apdu.size > 4) {
            (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        } else {
            256
        }
        val end = minOf(offset + le, file.size)
        val body = file.copyOfRange(offset, end)
        return body + SW_OK
    }

    /**
     * NLEN (2 bytes) followed by the saved NDEF message. Returns null when no
     * card is selected for emulation or it has no NDEF content.
     */
    private fun ndefFile(): ByteArray? {
        val uid = repository.loadEmulatedUid() ?: return null
        val card = repository.load().firstOrNull {
            it.uid.equals(uid, ignoreCase = true)
        } ?: return null
        val message = NdefTools.messageBytesFor(card) ?: return null
        if (message.size > NdefTools.MAX_EMULATED_NDEF_BYTES) return null
        val length = message.size
        return byteArrayOf(
            (length shr 8).toByte(),
            length.toByte(),
        ) + message
    }

    private companion object {
        const val INS_SELECT = 0xA4
        const val INS_READ_BINARY = 0xB0
        const val INS_UPDATE_BINARY = 0xD6

        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val SW_COMMAND_NOT_ALLOWED = byteArrayOf(0x69.toByte(), 0x86.toByte())
        val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        val SW_WRONG_OFFSET = byteArrayOf(0x6B.toByte(), 0x00)
        val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        /**
         * Capability Container for a read-only Type 4 tag:
         * CCLEN 000F, mapping 2.0, MLe 003B, MLc 0034,
         * NDEF file E104, max 7FFE bytes, read free, write denied.
         */
        val CC_FILE = byteArrayOf(
            0x00, 0x0F,
            0x20,
            0x00, 0x3B,
            0x00, 0x34,
            0x04, 0x06,
            0xE1.toByte(), 0x04,
            0x7F, 0xFE.toByte(),
            0x00,
            0xFF.toByte(),
        )
    }
}
