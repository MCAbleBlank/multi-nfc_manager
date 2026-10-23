package com.multinfc.manager.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.multinfc.manager.ui.CardViewModel.WriteMode
import com.multinfc.manager.ui.screens.CardDetailScreen
import com.multinfc.manager.ui.screens.CardEditorScreen
import com.multinfc.manager.ui.screens.HomeScreen
import com.multinfc.manager.ui.screens.ScanSheet
import com.multinfc.manager.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun NfcApp(
    viewModel: CardViewModel,
    nfcSupported: Boolean,
) {
    val cards = viewModel.cards
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var editingUid by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Shared with HomeScreen so the wheel keeps its position while the user
    // opens a card and comes back, and so volume keys drive the same animation.
    val wheelPosition = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedUid, editingUid, viewModel.scannerOpen, settingsOpen) {
        viewModel.carouselActive =
            selectedUid == null && editingUid == null &&
                !viewModel.scannerOpen && !settingsOpen
    }

    // Leaving a card always closes its editor as well.
    LaunchedEffect(selectedUid) {
        if (selectedUid == null) editingUid = null
    }

    // A new search result set starts the wheel at the first match.
    LaunchedEffect(query) {
        wheelPosition.snapTo(0f)
    }

    val cardStyle = viewModel.settings.cardStyle
    LaunchedEffect(viewModel, wheelPosition, cardStyle) {
        viewModel.volumeSteps.collect { delta ->
            // Grid and list handle their own scrolling directly on HomeScreen.
            if (!cardStyle.isCarousel) return@collect
            val base = if (wheelPosition.isRunning) {
                wheelPosition.targetValue
            } else {
                wheelPosition.value
            }
            scope.launch {
                wheelPosition.animateTo(
                    targetValue = base + delta,
                    animationSpec = tween(
                        durationMillis = 300,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(cards) {
        if (selectedUid != null && cards.none { it.uid == selectedUid }) {
            selectedUid = null
            editingUid = null
        }
    }

    BackHandler(enabled = settingsOpen || selectedUid != null) {
        when {
            settingsOpen -> settingsOpen = false
            editingUid != null -> editingUid = null
            else -> selectedUid = null
        }
    }

    val openNfcSettings: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }.onFailure {
            Toast.makeText(context, "请在系统设置中开启 NFC", Toast.LENGTH_SHORT).show()
        }
    }

    val startScan: () -> Unit = {
        when {
            !nfcSupported ->
                Toast.makeText(context, "此设备不支持 NFC", Toast.LENGTH_SHORT).show()

            !viewModel.nfcEnabled -> openNfcSettings()
            else -> viewModel.openScanner()
        }
    }

    AnimatedContent(
        targetState = settingsOpen,
        transitionSpec = {
            // Settings slides over the home screen just like a detail page.
            val enter = tween<IntOffset>(
                durationMillis = 360,
                easing = FastOutSlowInEasing,
            )
            val exit = tween<IntOffset>(
                durationMillis = 300,
                easing = FastOutSlowInEasing,
            )
            if (targetState) {
                (slideInHorizontally(enter) { it / 5 } + fadeIn(tween(240, delayMillis = 30))) togetherWith
                    (slideOutHorizontally(exit) { -it / 8 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(enter) { -it / 5 } + fadeIn(tween(240, delayMillis = 30))) togetherWith
                    (slideOutHorizontally(exit) { it / 8 } + fadeOut(tween(160)))
            }
        },
        label = "settings",
    ) { showSettings ->
        if (showSettings) {
            SettingsScreen(
                settings = viewModel.settings,
                onBack = { settingsOpen = false },
                onCardStyle = viewModel::setCardStyle,
                onVolumeKeys = viewModel::setVolumeKeysEnabled,
                onHaptics = viewModel::setHapticsEnabled,
                onTilt = viewModel::setTiltEnabled,
                onEnhancedEffects = viewModel::setEnhancedEffects,
            )
        } else {
            AnimatedContent(
                targetState = selectedUid,
                transitionSpec = {
                    // Entrance is slightly slower than the fade-out so the
                    // outgoing screen never leaves an empty frame behind.
                    val enter = tween<IntOffset>(
                        durationMillis = 360,
                        easing = FastOutSlowInEasing,
                    )
                    val exit = tween<IntOffset>(
                        durationMillis = 300,
                        easing = FastOutSlowInEasing,
                    )
                    if (targetState != null) {
                        (slideInHorizontally(enter) { it / 5 } +
                            fadeIn(tween(240, delayMillis = 30))) togetherWith
                            (slideOutHorizontally(exit) { -it / 8 } + fadeOut(tween(160)))
                    } else {
                        (slideInHorizontally(enter) { -it / 5 } +
                            fadeIn(tween(240, delayMillis = 30))) togetherWith
                            (slideOutHorizontally(exit) { it / 8 } + fadeOut(tween(160)))
                    }
                },
                label = "screen",
            ) { uid ->
                if (uid == null) {
                    HomeScreen(
                        cards = cards,
                        query = query,
                        onQueryChange = { query = it },
                        nfcSupported = nfcSupported,
                        nfcEnabled = viewModel.nfcEnabled,
                        snackbarHostState = snackbarHostState,
                        onOpenNfcSettings = openNfcSettings,
                        onScan = startScan,
                        onCardClick = { selectedUid = it.uid },
                        wheelPosition = wheelPosition,
                        cardStyle = cardStyle,
                        volumeKeysEnabled = viewModel.settings.volumeKeysEnabled,
                        hapticsEnabled = viewModel.settings.hapticsEnabled,
                        enhancedEffects = viewModel.settings.enhancedEffects,
                        volumeSteps = viewModel.volumeSteps,
                        onFilteredCountChange = viewModel::setCarouselItemCount,
                        onOpenSettings = { settingsOpen = true },
                    )
                } else {
            val card = cards.firstOrNull { it.uid == uid }
            if (card != null) {
                AnimatedContent(
                    targetState = editingUid == uid,
                    transitionSpec = {
                        val enter = tween<IntOffset>(
                            durationMillis = 320,
                            easing = FastOutSlowInEasing,
                        )
                        val exit = tween<IntOffset>(
                            durationMillis = 260,
                            easing = FastOutSlowInEasing,
                        )
                        if (targetState) {
                            (slideInHorizontally(enter) { it / 4 } +
                                fadeIn(tween(220, delayMillis = 20))) togetherWith
                                (slideOutHorizontally(exit) { -it / 8 } + fadeOut(tween(140)))
                        } else {
                            (slideInHorizontally(enter) { -it / 4 } +
                                fadeIn(tween(220, delayMillis = 20))) togetherWith
                                (slideOutHorizontally(exit) { it / 8 } + fadeOut(tween(140)))
                        }
                    },
                    label = "editor",
                ) { isEditing ->
                    if (isEditing) {
                        CardEditorScreen(
                            card = card,
                            snackbarHostState = snackbarHostState,
                            onBack = { editingUid = null },
                            onSave = { records, raw, mifare ->
                                viewModel.updateContent(card.uid, records, raw, mifare)
                                editingUid = null
                            },
                        )
                    } else {
                        val beginWrite: (WriteMode) -> Unit = { mode ->
                            if (viewModel.nfcEnabled) {
                                viewModel.startWrite(card, mode)
                            } else {
                                openNfcSettings()
                            }
                        }
                        CardDetailScreen(
                            card = card,
                            snackbarHostState = snackbarHostState,
                            onBack = { selectedUid = null },
                            onRename = { newName -> viewModel.rename(card.uid, newName) },
                            onDelete = {
                                viewModel.delete(card.uid)
                                selectedUid = null
                            },
                            onRescan = {
                                if (viewModel.nfcEnabled) {
                                    viewModel.openScanner()
                                } else {
                                    openNfcSettings()
                                }
                            },
                            emulated = viewModel.emulatedUid == card.uid,
                            onToggleEmulation = { enabled ->
                                viewModel.setEmulation(card.uid, enabled)
                            },
                            onEdit = { editingUid = card.uid },
                            onWriteNdef = { beginWrite(WriteMode.Ndef) },
                            onWriteMifare = { beginWrite(WriteMode.Mifare) },
                            tiltEnabled = viewModel.settings.tiltEnabled,
                            enhancedEffects = viewModel.settings.enhancedEffects,
                        )
                    }
                }
            }
                }
            }
        }
    }

    if (viewModel.scannerOpen) {
        val writeTarget = viewModel.writeTarget
        ScanSheet(
            pending = viewModel.pendingScan,
            existing = viewModel.pendingScan?.let { pending ->
                cards.firstOrNull { it.uid == pending.uid }
            },
            writeTargetName = writeTarget?.card?.displayName,
            writeModeLabel = when (writeTarget?.mode) {
                WriteMode.Ndef -> "写入 NDEF 内容"
                WriteMode.Mifare -> "写入 MIFARE 扇区数据"
                null -> null
            },
            writePrompt = viewModel.writePrompt,
            writeInProgress = viewModel.writeInProgress,
            onDismiss = viewModel::closeScanner,
            onRescan = viewModel::rescan,
            onOpenNfcSettings = openNfcSettings,
            onSave = viewModel::savePending,
        )
    }
}
