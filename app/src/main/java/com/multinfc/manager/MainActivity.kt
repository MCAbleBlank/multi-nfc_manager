package com.multinfc.manager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.multinfc.manager.nfc.NdefHostApduService
import com.multinfc.manager.ui.CardViewModel
import com.multinfc.manager.ui.NfcApp
import com.multinfc.manager.ui.theme.NfcTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * How this activity currently interacts with the NFC controller:
     *
     * - [Dispatch]: app is visible but not scanning. Foreground dispatch gives
     *   this app priority for tag taps, so the system wallet does not steal
     *   them. The controller is not locked and card emulation keeps working.
     * - [Reader]: the scan sheet is waiting for a tag. Reader mode owns the
     *   controller so the card is read by us, no matter what else is installed.
     * - [Off]: a result is already on screen; release the controller so other
     *   apps/readers are not disturbed until the user rescans.
     */
    private enum class NfcMode { Dispatch, Reader, Off }

    private val viewModel: CardViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var receiverRegistered = false
    private var readerModeActive = false
    private var foregroundDispatchActive = false
    private var preferredServiceActive = false
    private var lastVolumeStepAt = 0L

    private val hostApduService: ComponentName by lazy {
        ComponentName(this, NdefHostApduService::class.java)
    }

    private val tagDispatchIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.onNfcStateChanged(nfcAdapter?.isEnabled == true)
            // Re-apply after the adapter comes back; a disabled adapter also
            // tears down any reader session it was holding.
            applyNfcMode(currentMode())
            updatePreferredService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            NfcTheme {
                NfcApp(
                    viewModel = viewModel,
                    nfcSupported = nfcAdapter != null,
                )
            }
        }

        // Reader mode is only held while the sheet is actually waiting for a
        // tag, so the RF field is not occupied the rest of the time. When idle,
        // foreground dispatch quietly gives this app priority for tag taps
        // without locking the controller or disabling card emulation.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    snapshotFlow { currentMode() }
                        .distinctUntilChanged()
                        .collect(::applyNfcMode)
                }
                // While this activity is in front, ask the NFC controller to
                // route our AID to this app ahead of other HCE services. This
                // does not affect what happens after the app is backgrounded;
                // the system default wallet rules apply there.
                launch {
                    snapshotFlow { viewModel.emulatedUid }
                        .distinctUntilChanged()
                        .collect { updatePreferredService() }
                }
            }
        }

        // Cold start from a tag tap (manifest intent filters).
        if (savedInstanceState == null) handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    /**
     * Volume keys step through the card wheel while the home screen is
     * showing. On the detail screen or over the scan sheet they keep their
     * normal volume behaviour.
     *
     * Intercepting in dispatchKeyEvent (rather than onKeyDown) makes sure a
     * focused search field or the system volume panel cannot swallow the keys
     * first. Holding a key repeats at a throttled rate so the wheel spins at a
     * readable speed instead of jumping many cards at once.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            else -> return super.dispatchKeyEvent(event)
        }
        if (viewModel.volumeKeysActive) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastVolumeStepAt >= VOLUME_STEP_INTERVAL_MS) {
                    lastVolumeStepAt = now
                    viewModel.onVolumeStep(direction)
                    if (viewModel.settings.hapticsEnabled) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        viewModel.onNfcStateChanged(adapter.isEnabled)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                adapterStateReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        updatePreferredService()
    }

    override fun onPause() {
        // Release the controller as soon as the app leaves the foreground.
        disableNfcModes()
        releasePreferredService()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(adapterStateReceiver) }
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun currentMode(): NfcMode = when {
        !viewModel.scannerOpen -> NfcMode.Dispatch
        viewModel.pendingScan == null -> NfcMode.Reader
        else -> NfcMode.Off
    }

    private fun applyNfcMode(mode: NfcMode) {
        val adapter = nfcAdapter
        if (adapter == null || !adapter.isEnabled ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            disableNfcModes()
            return
        }
        when (mode) {
            NfcMode.Reader -> {
                // Reader mode and foreground dispatch are mutually exclusive.
                disableForegroundDispatch(adapter)
                enableReaderMode(adapter)
            }

            NfcMode.Dispatch -> {
                disableReaderMode(adapter)
                enableForegroundDispatch(adapter)
            }

            NfcMode.Off -> disableNfcModes()
        }
    }

    private fun enableReaderMode(adapter: NfcAdapter) {
        if (readerModeActive) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        readerModeActive = runCatching {
            adapter.enableReaderMode(this, { tag -> onTagFromReader(tag) }, flags, extras)
        }.isSuccess
    }

    private fun disableReaderMode(adapter: NfcAdapter) {
        if (!readerModeActive) return
        runCatching { adapter.disableReaderMode(this) }
        readerModeActive = false
    }

    /**
     * Takes tag dispatch priority while the app is visible. Passing null
     * filters/tech lists means "deliver every discovered tag to this activity",
     * which is what a card reader app wants. Unlike reader mode this does not
     * disable card emulation or hold the field exclusively.
     */
    private fun enableForegroundDispatch(adapter: NfcAdapter) {
        if (foregroundDispatchActive) return
        foregroundDispatchActive = runCatching {
            adapter.enableForegroundDispatch(this, tagDispatchIntent, null, null)
        }.isSuccess
    }

    private fun disableForegroundDispatch(adapter: NfcAdapter) {
        if (!foregroundDispatchActive) return
        runCatching { adapter.disableForegroundDispatch(this) }
        foregroundDispatchActive = false
    }

    private fun disableNfcModes() {
        val adapter = nfcAdapter ?: return
        disableReaderMode(adapter)
        disableForegroundDispatch(adapter)
    }

    /**
     * Prefers our HCE service for the NDEF AID while the app is visible. The
     * preference is activity-scoped and is dropped in [onPause]; background
     * emulation is governed by the system's default NFC/wallet app.
     */
    private fun updatePreferredService() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            releasePreferredService()
            return
        }
        val cardEmulation = runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
            ?: return
        if (viewModel.emulatedUid != null) {
            preferredServiceActive = runCatching {
                cardEmulation.setPreferredService(this, hostApduService)
            }.isSuccess
        } else {
            releasePreferredService(cardEmulation)
        }
    }

    private fun releasePreferredService(cardEmulation: CardEmulation? = null) {
        if (!preferredServiceActive) return
        val service = cardEmulation ?: nfcAdapter
            ?.let { runCatching { CardEmulation.getInstance(it) }.getOrNull() }
        runCatching { service?.unsetPreferredService(this) }
        preferredServiceActive = false
    }

    private fun onTagFromReader(tag: Tag) {
        window.decorView.post {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        viewModel.onTagScanned(tag)
    }

    /** Handles a tag delivered through foreground dispatch or a manifest filter. */
    private fun handleNfcIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            return
        }
        val tag = IntentCompat.getParcelableExtra(
            intent,
            NfcAdapter.EXTRA_TAG,
            Tag::class.java,
        ) ?: return
        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        viewModel.onTagScanned(tag)
    }

    private companion object {
        /** Minimum gap between repeated volume-key wheel steps. */
        const val VOLUME_STEP_INTERVAL_MS = 140L
    }
}
