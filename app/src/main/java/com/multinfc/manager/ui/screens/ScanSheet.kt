package com.multinfc.manager.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multinfc.manager.data.NfcCard
import com.multinfc.manager.ui.components.AppIcons
import com.multinfc.manager.ui.components.NfcCardVisual
import com.multinfc.manager.ui.components.PulsingRings
import com.multinfc.manager.ui.formatTimestamp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun ScanSheet(
    pending: NfcCard?,
    existing: NfcCard?,
    writeTargetName: String? = null,
    writeModeLabel: String? = null,
    writePrompt: String? = null,
    writeInProgress: Boolean = false,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onSave: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !writeInProgress },
    )
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(pending?.uid) {
        name = if (pending == null) "" else pending.name.ifBlank { "我的卡片" }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!writeInProgress) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        if (writeTargetName != null) {
            WritingContent(
                targetName = writeTargetName,
                modeLabel = writeModeLabel,
                prompt = writePrompt,
                inProgress = writeInProgress,
                onDismiss = onDismiss,
            )
        } else {
        AnimatedContent(
            targetState = pending,
            transitionSpec = {
                val enteringResult = targetState != null
                val enter = fadeIn(
                    animationSpec = tween(280, delayMillis = 40, easing = FastOutSlowInEasing),
                ) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                ) + slideInVertically(
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    initialOffsetY = { if (enteringResult) it / 12 else it / 20 },
                )
                val exit = fadeOut(
                    animationSpec = tween(150),
                ) + scaleOut(
                    targetScale = 0.985f,
                    animationSpec = tween(150),
                )
                (enter togetherWith exit).using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.TopCenter,
            label = "scan-content",
        ) { card ->
            if (card == null) {
                ScanningContent(
                    onDismiss = onDismiss,
                    onOpenNfcSettings = onOpenNfcSettings,
                )
            } else {
                ScanResultContent(
                    card = card,
                    isExisting = existing != null,
                    name = name,
                    onNameChange = { name = it },
                    onRescan = onRescan,
                    onDismiss = onDismiss,
                    onSave = {
                        val finalName = name
                        scope.launch {
                            runCatching { sheetState.hide() }
                        }.invokeOnCompletion { onSave(finalName) }
                    },
                )
            }
        }
        }
    }
}

@Composable
private fun WritingContent(
    targetName: String,
    modeLabel: String?,
    prompt: String?,
    inProgress: Boolean,
    onDismiss: () -> Unit,
) {
    val iconTransition = rememberInfiniteTransition(label = "write-icon")
    val iconScale by iconTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "write-icon-scale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "写入卡片",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, enabled = !inProgress) {
                Icon(Icons.Rounded.Close, contentDescription = "取消写入")
            }
        }

        Box(
            modifier = Modifier.size(230.dp),
            contentAlignment = Alignment.Center,
        ) {
            PulsingRings(Modifier.fillMaxSize())
            Surface(
                modifier = Modifier
                    .size(104.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }
        }

        Text(
            text = "请将「$targetName」贴近手机背面",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        modeLabel?.let {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (inProgress) {
                "正在写入，请保持卡片静止，不要移开"
            } else {
                "保持卡片静止，识别到相同 UID 的卡片后开始写入"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        prompt?.let {
            Spacer(Modifier.height(14.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (inProgress) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onDismiss, enabled = !inProgress) {
            Text(if (inProgress) "写入中…" else "取消")
        }
    }
}

@Composable
private fun ScanningContent(
    onDismiss: () -> Unit,
    onOpenNfcSettings: () -> Unit,
) {
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    val iconTransition = rememberInfiniteTransition(label = "scan-icon")
    val iconScale by iconTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-icon-scale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "扫描 NFC 卡片",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "取消扫描")
            }
        }

        Box(
            modifier = Modifier.size(230.dp),
            contentAlignment = Alignment.Center,
        ) {
            PulsingRings(Modifier.fillMaxSize())
            Surface(
                modifier = Modifier
                    .size(104.dp)
                    .graphicsLayer {
                        // Read the animated value inside the layer block so the
                        // breathing icon only redraws instead of recomposing.
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }
        }

        Text(
            text = "请将卡片贴近手机背面",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "保持卡片静止，识别后会尝试读取 MIFARE 扇区数据",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onDismiss) {
            Text("取消")
        }
        TextButton(onClick = { helpOpen = true }) {
            Text(
                text = "系统钱包总是抢读？",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            title = { Text("让本应用优先读卡") },
            text = {
                Text(
                    "应用在前台时会优先接管 NFC 读卡事件，" +
                        "扫描时还会独占读卡通道。\n\n" +
                        "如果系统钱包仍然抢读，请前往「系统设置 → NFC / " +
                        "更多连接」，把默认 NFC 应用（或默认钱包）改为本应用，" +
                        "或关闭系统钱包的快捷唤起。\n\n" +
                        "部分厂商钱包属于系统级应用，可能无法被替换。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        helpOpen = false
                        onOpenNfcSettings()
                    }
                ) {
                    Text("打开 NFC 设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { helpOpen = false }) {
                    Text("知道了")
                }
            },
        )
    }
}

@Composable
private fun ScanResultContent(
    card: NfcCard,
    isExisting: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var entered by remember(card.scannedAt) { mutableStateOf(false) }
    LaunchedEffect(card.scannedAt) { entered = true }

    val successScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.3f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "success-scale",
    )
    val contentScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "result-scale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "result-alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        scaleX = successScale
                        scaleY = successScale
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isExisting) "已识别到已保存卡片" else "识别成功",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (isExisting) "保存后将更新卡片信息" else "检查信息并为卡片命名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭")
            }
        }

        Spacer(Modifier.height(18.dp))

        NfcCardVisual(
            card = card.copy(name = name),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = contentScale
                    scaleY = contentScale
                    alpha = contentAlpha
                },
            height = 176.dp,
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("卡片名称") },
            placeholder = { Text("例如：门禁卡、公交卡") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SummaryRow("UID", card.formattedUid, mono = true)
                SummaryRow("类型", card.typeLabel)
                SummaryRow("技术", card.techList.joinToString(" · "))
                card.capacity?.let { SummaryRow("容量", it) }
                card.mifare?.let { dump ->
                    SummaryRow(
                        "扇区",
                        "${dump.readableSectors}/${dump.sectorCount} 可读" +
                            if (dump.isPartial) "（部分扇区需要密钥）" else "",
                    )
                }
            }
        }

        if (card.ndefRecords.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.Notes,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "包含 ${card.ndefRecords.size} 条 NDEF 记录",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val first = card.ndefRecords.first()
                        Text(
                            text = first.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新扫描")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isExisting) "更新卡片" else "保存卡片")
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "读取于 ${formatTimestamp(card.scannedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}
