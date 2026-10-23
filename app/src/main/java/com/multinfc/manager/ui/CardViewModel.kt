package com.multinfc.manager.ui

import android.app.Application
import android.nfc.NdefMessage
import android.nfc.Tag
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multinfc.manager.data.AppSettings
import com.multinfc.manager.data.CardRepository
import com.multinfc.manager.data.CardStyle
import com.multinfc.manager.data.MifareDump
import com.multinfc.manager.data.NdefRecordInfo
import com.multinfc.manager.data.NdefTools
import com.multinfc.manager.data.NfcCard
import com.multinfc.manager.data.SettingsRepository
import com.multinfc.manager.nfc.CardWriter
import com.multinfc.manager.nfc.NfcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CardViewModel(application: Application) : AndroidViewModel(application) {

    enum class WriteMode { Ndef, Mifare }

    data class WriteTarget(
        val card: NfcCard,
        val mode: WriteMode,
    )

    private val repository = CardRepository(application)
    private val settingsRepository = SettingsRepository(application)

    var cards by mutableStateOf(
        repository.load().sortedByDescending { it.scannedAt }
    )
        private set

    /** Persisted UI preferences (home layout, motion and feedback). */
    var settings by mutableStateOf(settingsRepository.load())
        private set

    var scannerOpen by mutableStateOf(false)
        private set

    var pendingScan by mutableStateOf<NfcCard?>(null)
        private set

    /** Non-null while the scan sheet is waiting for the write target. */
    var writeTarget by mutableStateOf<WriteTarget?>(null)
        private set

    /** In-sheet hint, e.g. when the user taps a different card. */
    var writePrompt by mutableStateOf<String?>(null)
        private set

    var writeInProgress by mutableStateOf(false)
        private set

    var nfcEnabled by mutableStateOf(false)
        private set

    /**
     * UID of the card exposed through HCE. The APDU service reads the same
     * value from [CardRepository], so this survives process restarts.
     */
    var emulatedUid by mutableStateOf(repository.loadEmulatedUid())
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** True while the home card wheel can consume volume keys. */
    var carouselActive = false

    private var carouselItemCount = 0

    /**
     * Volume-key steps requested while the carousel is on screen. The wheel
     * owns the animation position, so the activity only signals the direction.
     */
    private val _volumeSteps = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val volumeSteps: SharedFlow<Int> = _volumeSteps

    private var lastReadUid: String? = null
    private var lastReadAt = 0L

    fun onNfcStateChanged(enabled: Boolean) {
        nfcEnabled = enabled
    }

    fun setCardStyle(style: CardStyle) = updateSettings { it.copy(cardStyle = style) }

    fun setVolumeKeysEnabled(enabled: Boolean) =
        updateSettings { it.copy(volumeKeysEnabled = enabled) }

    fun setHapticsEnabled(enabled: Boolean) = updateSettings { it.copy(hapticsEnabled = enabled) }

    fun setTiltEnabled(enabled: Boolean) = updateSettings { it.copy(tiltEnabled = enabled) }

    fun setEnhancedEffects(enabled: Boolean) =
        updateSettings { it.copy(enhancedEffects = enabled) }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(settings)
        if (updated == settings) return
        settings = updated
        settingsRepository.save(updated)
    }

    fun setCarouselItemCount(count: Int) {
        carouselItemCount = count
    }

    /** Whether the home screen is on screen with cards the volume keys can move through. */
    val volumeKeysActive: Boolean
        get() = settings.volumeKeysEnabled && carouselActive && carouselItemCount > 0

    /**
     * Handles VOLUME_UP/VOLUME_DOWN presses. Returns true when the key was
     * consumed by the card wheel; false lets the system change the volume.
     */
    fun onVolumeStep(direction: Int): Boolean {
        if (!volumeKeysActive) return false
        _volumeSteps.tryEmit(direction)
        return true
    }

    fun openScanner() {
        pendingScan = null
        writeTarget = null
        writePrompt = null
        scannerOpen = true
    }

    fun closeScanner() {
        if (writeInProgress) return
        scannerOpen = false
        pendingScan = null
        writeTarget = null
        writePrompt = null
    }

    fun rescan() {
        if (writeTarget != null) return
        pendingScan = null
    }

    /** Starts a "tap the card again to write" flow. */
    fun startWrite(card: NfcCard, mode: WriteMode) {
        when (mode) {
            WriteMode.Ndef -> if (!card.hasNdef) {
                _messages.tryEmit("请先在编辑器中添加 NDEF 内容")
                return
            }

            WriteMode.Mifare -> if (card.mifare == null) {
                _messages.tryEmit("这张卡还没有可写入的扇区数据")
                return
            }
        }
        pendingScan = null
        writePrompt = null
        writeTarget = WriteTarget(card, mode)
        scannerOpen = true
    }

    /** Called from the reader-mode callback thread. */
    fun onTagScanned(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        val target = writeTarget
        if (scannerOpen && target != null) {
            if (writeInProgress) return
            if (!uid.equals(target.card.uid, ignoreCase = true)) {
                writePrompt = "这张卡不是「${target.card.displayName}」，请换一张"
                return
            }
            performWrite(tag, target)
            return
        }

        // The tag stays in range between callbacks; while its result is already
        // on screen there is nothing new to read. "Rescan" clears pendingScan,
        // which re-arms reading for the same tag.
        if (scannerOpen && pendingScan?.uid.equals(uid, ignoreCase = true)) return
        val now = SystemClock.elapsedRealtime()
        if (uid == lastReadUid && now - lastReadAt < SCAN_DEBOUNCE_MS) return
        lastReadUid = uid
        lastReadAt = now

        viewModelScope.launch {
            val existing = cards.firstOrNull { it.uid.equals(uid, ignoreCase = true) }
            val parsed = withContext(Dispatchers.IO) {
                runCatching { NfcParser.parse(tag, existing) }.getOrNull()
            }
            if (parsed == null) {
                _messages.tryEmit("读取卡片失败，请重试")
                return@launch
            }
            pendingScan = parsed
            scannerOpen = true
        }
    }

    private fun performWrite(tag: Tag, target: WriteTarget) {
        writeInProgress = true
        writePrompt = "正在写入，请保持卡片贴合手机…"
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                when (target.mode) {
                    WriteMode.Ndef -> CardWriter.writeNdef(tag, target.card)
                    WriteMode.Mifare -> target.card.mifare
                        ?.let { CardWriter.writeMifare(tag, it) }
                        ?: CardWriter.Outcome(false, "没有可写入的扇区数据")
                }
            }

            if (outcome.success) {
                val card = cards.firstOrNull { it.uid == target.card.uid } ?: target.card
                val writtenRecords = outcome.ndefRaw
                    ?.let(NdefTools::hexToBytes)
                    ?.let { bytes ->
                        runCatching { NdefMessage(bytes).records.toList() }
                            .getOrNull()
                    }
                    ?.let(NdefTools::infoOf)
                // Writing the manufacturer block on a UID-writable card changes
                // its physical UID. Track the new identity so the next scan
                // still maps to this saved card.
                val newUid = outcome.newUid?.takeIf { !it.equals(card.uid, ignoreCase = true) }
                val updated = card.copy(
                    uid = newUid ?: card.uid,
                    ndefRaw = outcome.ndefRaw ?: card.ndefRaw,
                    ndefRecords = writtenRecords ?: card.ndefRecords,
                    mifare = outcome.mifare ?: card.mifare,
                    scannedAt = System.currentTimeMillis(),
                )
                cards = (
                    cards.filterNot {
                        it.uid.equals(updated.uid, ignoreCase = true) ||
                            it.uid.equals(card.uid, ignoreCase = true)
                    } + updated
                    ).sortedByDescending { it.scannedAt }
                repository.save(cards)
            }

            writeInProgress = false
            writeTarget = null
            writePrompt = null
            scannerOpen = false
            pendingScan = null
            _messages.tryEmit(
                if (outcome.success) {
                    "「${target.card.displayName}」${outcome.message}"
                } else {
                    "写入失败：${outcome.message}"
                }
            )
        }
    }

    fun savePending(name: String) {
        val card = pendingScan ?: return
        val finalName = name.trim().ifBlank { card.name.ifBlank { DEFAULT_CARD_NAME } }
        val updated = card.copy(name = finalName)
        cards = (cards.filterNot { it.uid == updated.uid } + updated)
            .sortedByDescending { it.scannedAt }
        repository.save(cards)
        scannerOpen = false
        pendingScan = null
        _messages.tryEmit("已保存「$finalName」")
    }

    fun rename(uid: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        if (cards.none { it.uid == uid }) return
        cards = cards.map { if (it.uid == uid) it.copy(name = trimmed) else it }
        repository.save(cards)
        _messages.tryEmit("已重命名为「$trimmed」")
    }

    /** Stores the result of the content editor (local only, no tag I/O). */
    fun updateContent(
        uid: String,
        ndefRecords: List<NdefRecordInfo>?,
        ndefRaw: String?,
        mifare: MifareDump?,
    ) {
        val card = cards.firstOrNull { it.uid == uid } ?: return
        val updated = card.copy(
            ndefRecords = ndefRecords ?: card.ndefRecords,
            ndefRaw = ndefRaw,
            mifare = mifare ?: card.mifare,
        )
        cards = cards.map { if (it.uid == uid) updated else it }
        repository.save(cards)
        // Emulating a card that no longer has NDEF content would expose an
        // empty tag; drop the selection so the UI reflects reality.
        if (emulatedUid == uid && !updated.hasNdef) {
            emulatedUid = null
            repository.saveEmulatedUid(null)
            _messages.tryEmit("NDEF 内容已清空，已停止卡模拟")
        } else {
            _messages.tryEmit("卡片内容已保存到应用")
        }
    }

    fun setEmulation(uid: String, enabled: Boolean) {
        if (!enabled) {
            emulatedUid = null
            repository.saveEmulatedUid(null)
            _messages.tryEmit("已停止卡模拟")
            return
        }
        val card = cards.firstOrNull { it.uid == uid } ?: return
        if (!card.hasNdef) {
            _messages.tryEmit("请先编辑这张卡，添加文本或链接内容")
            return
        }
        emulatedUid = uid
        repository.saveEmulatedUid(uid)
        _messages.tryEmit("正在模拟「${card.displayName}」· NDEF")
    }

    fun delete(uid: String) {
        val card = cards.firstOrNull { it.uid == uid } ?: return
        cards = cards.filterNot { it.uid == uid }
        repository.save(cards)
        if (emulatedUid == uid) {
            emulatedUid = null
            repository.saveEmulatedUid(null)
        }
        _messages.tryEmit("已删除「${card.displayName}」")
    }

    private companion object {
        const val SCAN_DEBOUNCE_MS = 1500L
        const val DEFAULT_CARD_NAME = "我的卡片"
    }
}
