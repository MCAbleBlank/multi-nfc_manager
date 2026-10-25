package com.multinfc.manager.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.multinfc.manager.data.NdefRecordInfo
import com.multinfc.manager.data.NfcCard
import com.multinfc.manager.ui.components.AppIcons
import com.multinfc.manager.ui.components.NfcCardVisual
import com.multinfc.manager.ui.components.rememberDeviceTilt
import com.multinfc.manager.ui.formatTimestamp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    card: NfcCard,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRescan: () -> Unit,
    emulated: Boolean,
    onToggleEmulation: (Boolean) -> Unit,
    uidEmulationActive: Boolean,
    uidEmulationChecking: Boolean,
    uidEmulationSupported: Boolean,
    uidEmulationNeedsPermission: Boolean,
    uidEmulationEligible: Boolean,
    uidEmulationBusy: Boolean,
    onToggleUidEmulation: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onWriteNdef: () -> Unit,
    onWriteMifare: () -> Unit,
    tiltEnabled: Boolean,
    enhancedEffects: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Sensor-backed tilt lives only as long as this screen is composed.
    val tilt = rememberDeviceTilt(enabled = tiltEnabled)
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showMifareWarning by remember { mutableStateOf(false) }
    var showUidWarning by remember { mutableStateOf(false) }

    fun copy(text: String, message: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("卡片详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showRename = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("删除卡片", color = MaterialTheme.colorScheme.error)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                showDelete = true
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                NfcCardVisual(
                    card = card,
                    modifier = Modifier.fillMaxWidth(),
                    height = 200.dp,
                    enhanced = enhancedEffects,
                    tilt = tilt,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { copy(card.formattedUid, "已复制 UID") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("复制 UID")
                    }
                    Button(
                        onClick = onRescan,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Nfc,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("扫描更新")
                    }
                }
            }

            item {
                SectionCard(title = "内容与写入") {
                    ActionRow(
                        icon = Icons.Rounded.Edit,
                        title = "编辑卡片内容",
                        subtitle = "修改 NDEF 文本/链接、MIFARE 扇区数据与密钥",
                        onClick = onEdit,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ActionRow(
                        icon = AppIcons.Nfc,
                        title = "写入 NDEF 内容",
                        subtitle = if (card.hasNdef) {
                            "把应用内编辑好的 NDEF 消息写回实体卡"
                        } else {
                            "先编辑内容并添加文本或链接"
                        },
                        enabled = card.hasNdef,
                        onClick = onWriteNdef,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    ActionRow(
                        icon = Icons.Rounded.Lock,
                        title = "写入 MIFARE 扇区",
                        subtitle = if (card.mifare != null) {
                            "按保存的密钥逐扇区写回，尾块修改有风险"
                        } else {
                            "这张卡没有可写入的扇区数据"
                        },
                        enabled = card.mifare != null,
                        onClick = { showMifareWarning = true },
                    )
                }
            }

            item {
                SectionCard(title = "卡模拟（HCE）") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "NDEF Type 4 标签",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = when {
                                    emulated -> "正在模拟 · 被读卡器读取时返回下方 NDEF 内容"
                                    card.hasNdef -> "已停止 · 打开开关后即可被其他读卡器读取"
                                    else -> "这张卡没有 NDEF 内容，无法模拟"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (emulated) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = emulated,
                            onCheckedChange = onToggleEmulation,
                            enabled = (card.hasNdef || emulated) && !uidEmulationActive,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                    ) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                                }
                            },
                        ) {
                            Text("NFC 设置")
                        }
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                    )
                                }
                            },
                        ) {
                            Text("默认应用 / 钱包")
                        }
                    }
                }
            }

            item {
                SectionCard(title = "UID 模拟（实验性）") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "NFC-A UID",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = when {
                                    uidEmulationActive -> "正在模拟 · 仅向读卡器呈现 UID"

                                    uidEmulationChecking -> "正在检查支持情况…"
                                    !uidEmulationSupported -> "当前设备不支持，详见设置"
                                    uidEmulationNeedsPermission -> "需要 ADB 授权，详见设置"
                                    !uidEmulationEligible -> "这张卡的 UID 长度不支持"
                                    else -> "已停止"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uidEmulationActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = uidEmulationActive,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showUidWarning = true
                                } else {
                                    onToggleUidEmulation(false)
                                }
                            },
                            enabled = (
                                uidEmulationActive ||
                                    (uidEmulationSupported && uidEmulationEligible &&
                                        !uidEmulationNeedsPermission)
                                ) && !uidEmulationBusy,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }

            item {
                SectionCard(title = "基本信息") {
                    InfoRow("UID", card.formattedUid, mono = true)
                    InfoRow("卡片类型", card.typeLabel)
                    InfoRow("支持技术", card.techList.joinToString(" · "))
                    val radio = listOfNotNull(
                        card.atqa?.let { "ATQA $it" },
                        card.sak?.let { "SAK $it" },
                    ).joinToString(" · ")
                    if (radio.isNotBlank()) {
                        InfoRow("ATQA / SAK", radio, mono = true)
                    }
                    card.capacity?.let { InfoRow("存储容量", it) }
                    card.mifare?.let { dump ->
                        InfoRow("MIFARE 扇区", "${dump.readableSectors}/${dump.sectorCount} 可读")
                        InfoRow(
                            "已保存密钥",
                            "${dump.sectors.count { it.keyA != null }} 个 Key A · " +
                                "${dump.sectors.count { it.keyB != null }} 个 Key B",
                        )
                    }
                }
            }

            if (card.ndefRecords.isNotEmpty()) {
                item {
                    SectionCard(title = "NDEF 内容") {
                        card.ndefRecords.forEachIndexed { index, record ->
                            NdefRecordItem(
                                record = record,
                                onCopy = { copy(record.text, "已复制记录内容") },
                            )
                            if (index < card.ndefRecords.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "上次读取 ${formatTimestamp(card.scannedAt)} · 共读取 ${card.scanCount} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }

    if (showRename) {
        RenameDialog(
            current = card.name,
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                showRename = false
                onRename(newName)
            },
        )
    }

    if (showMifareWarning) {
        AlertDialog(
            onDismissRequest = { showMifareWarning = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("写入 MIFARE 扇区？") },
            text = {
                Text(
                    "应用会使用保存的密钥尝试写回所有可读扇区，包括扇区尾块和厂商块 0。" +
                        "尾块中的权限位写错可能让扇区永久无法访问；块 0 中的 UID/BCC " +
                        "写错会让卡片无法被识别。原厂卡会拒绝块 0 写入，只有 UID 可写卡" +
                        "（Gen1a/Gen2 魔术卡）能改写。写入不可撤销，请确认已经备份数据后再继续。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMifareWarning = false
                        onWriteMifare()
                    }
                ) {
                    Text("继续写入", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMifareWarning = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showUidWarning) {
        AlertDialog(
            onDismissRequest = { showUidWarning = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("开启 UID 模拟？") },
            text = {
                Text(
                    "开启后，系统 NFC 控制器会向读卡器呈现这张卡的 UID。" +
                        "仅重现 UID，不复制扇区数据或密钥；" +
                        "请仅在你拥有或获准测试的卡片上使用。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUidWarning = false
                        onToggleUidEmulation(true)
                    }
                ) {
                    Text("开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUidWarning = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("删除这张卡片？") },
            text = { Text("「${card.displayName}」将从卡包中移除，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        onDelete()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2.1f),
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.6f,
                ),
            )
        }
    }
}

@Composable
private fun NdefRecordItem(
    record: NdefRecordInfo,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = record.kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = AppIcons.ContentCopy,
                    contentDescription = "复制内容",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                text = record.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        record.detail?.let { detail ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名卡片") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("卡片名称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
