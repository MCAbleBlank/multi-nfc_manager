package com.multinfc.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.multinfc.manager.data.MifareDump
import com.multinfc.manager.data.MifareSector
import com.multinfc.manager.data.NdefRecordInfo
import com.multinfc.manager.data.NdefTools
import com.multinfc.manager.data.NfcCard
import com.multinfc.manager.nfc.NfcParser
import kotlinx.coroutines.launch
import android.nfc.NdefRecord

/**
 * MIFARE Classic Tool-style content editor.
 *
 * Sector data is edited as raw 16-byte hex blocks; the sector trailer is shown
 * but flagged because it also contains the keys and access bits. NDEF records
 * are edited as text/links and re-encoded into a complete message.
 *
 * Saving here only updates the app's copy. Writing to the physical card is a
 * separate, explicit action on the detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditorScreen(
    card: NfcCard,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: (
        ndefRecords: List<NdefRecordInfo>?,
        ndefRaw: String?,
        mifare: MifareDump?,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sectors by remember(card.uid) {
        mutableStateOf(card.mifare?.sectors.orEmpty())
    }
    var ndefItems by remember(card.uid) {
        mutableStateOf(buildNdefItems(card))
    }
    var tab by rememberSaveable(card.uid) {
        mutableIntStateOf(if (card.mifare == null) 1 else 0)
    }
    var editingSector by remember { mutableStateOf<MifareSector?>(null) }
    var editingBlock by remember { mutableStateOf<BlockEditTarget?>(null) }
    var nextNdefId by remember { mutableIntStateOf(ndefItems.size) }

    fun notify(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    fun save() {
        val records = ArrayList<NdefRecord>(ndefItems.size)
        for (item in ndefItems) {
            if (!item.editable) {
                item.original?.let(records::add)
                continue
            }
            val text = item.text.trim()
            if (text.isEmpty()) {
                notify("「${item.info.kind}」内容不能为空")
                return
            }
            val record = runCatching {
                when (item.info.kind) {
                    "文本" -> NdefTools.buildText(item.text)
                    "链接" -> NdefTools.buildUri(text)
                    else -> item.original
                }
            }.getOrElse {
                notify(it.message ?: "无法生成 NDEF 记录")
                return
            } ?: continue
            records += record
        }

        val raw = NdefTools.hexOf(records)
        if (raw != null && raw.length / 2 > NdefTools.MAX_EMULATED_NDEF_BYTES) {
            notify("NDEF 内容超过 ${NdefTools.MAX_EMULATED_NDEF_BYTES} 字节上限")
            return
        }
        // Preserve decoded records we cannot rebuild from legacy data.
        val preserved = if (card.ndefRaw == null) {
            card.ndefRecords.filter {
                it.kind != "文本" && it.kind != "链接"
            }
        } else {
            emptyList()
        }
        val infos = NdefTools.infoOf(records) + preserved
        val newDump = card.mifare?.copy(sectors = sectors)
        onSave(infos, raw, newDump)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("编辑卡片内容") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    TextButton(onClick = ::save) {
                        Text("保存", fontWeight = FontWeight.SemiBold)
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 6.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EditorHeader(card)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        label = {
                            Text(
                                if (card.mifare != null) {
                                    "扇区数据 ${card.mifare.readableSectors}/${card.mifare.sectorCount}"
                                } else {
                                    "扇区数据"
                                }
                            )
                        },
                    )
                    FilterChip(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        label = { Text("NDEF 内容 ${ndefItems.size}") },
                    )
                }
            }

            if (tab == 0) {
                if (sectors.isEmpty()) {
                    item {
                        EmptyEditorCard(
                            title = "没有扇区数据",
                            body = "扫描时未能读取 MIFARE Classic 扇区。可能不是 MIFARE Classic 卡，" +
                                "或扇区使用了未知密钥。在详情页重新扫描，或在“密钥”中填写正确密钥后再扫描。",
                        )
                    }
                } else {
                    items(sectors, key = { it.index }) { sector ->
                        SectorCard(
                            sector = sector,
                            onEditBlock = { offset ->
                                editingBlock = BlockEditTarget(sector, offset)
                            },
                            onEditKeys = { editingSector = sector },
                        )
                    }
                }
            } else {
                items(ndefItems, key = { it.id }) { item ->
                    NdefEditorItem(
                        item = item,
                        onChange = { newText ->
                            ndefItems = ndefItems.map {
                                if (it.id == item.id) it.copy(text = newText) else it
                            }
                        },
                        onDelete = {
                            ndefItems = ndefItems.filterNot { it.id == item.id }
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = {
                                ndefItems = ndefItems + EditableNdef(
                                    id = nextNdefId++,
                                    info = NdefRecordInfo("文本", ""),
                                    original = null,
                                    editable = true,
                                    text = "",
                                )
                            },
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加文本")
                        }
                        TextButton(
                            onClick = {
                                ndefItems = ndefItems + EditableNdef(
                                    id = nextNdefId++,
                                    info = NdefRecordInfo("链接", ""),
                                    original = null,
                                    editable = true,
                                    text = "https://",
                                )
                            },
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加链接")
                        }
                    }
                }
                item {
                    NdefSizeHint(ndefItems)
                }
                if (card.ndefRecords.any { it.kind != "文本" && it.kind != "链接" } &&
                    card.ndefRaw == null
                ) {
                    item {
                        EmptyEditorCard(
                            title = "存在只读记录",
                            body = "这张卡是旧版本保存的，缺少原始 NDEF 数据。" +
                                "MIME / 智能海报等记录只能保留显示，重新扫描一次即可完整编辑。",
                        )
                    }
                }
            }

            item {
                Text(
                    text = "保存只会更新应用内数据。回到详情页点击「写入卡片」，" +
                        "再把实体卡贴近手机即可写回。扇区尾块包含密钥和权限位，修改前请确认。" +
                        "块 0 是厂商块，原厂卡会拒绝写入，只有 UID 可写卡（魔术卡）才能改写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    editingSector?.let { sector ->
        KeyEditDialog(
            sector = sector,
            onDismiss = { editingSector = null },
            onConfirm = { keyA, keyB ->
                editingSector = null
                sectors = sectors.map {
                    if (it.index == sector.index) it.copy(keyA = keyA, keyB = keyB) else it
                }
            },
        )
    }

    editingBlock?.let { target ->
        BlockEditDialog(
            target = target,
            blockCount = target.sector.blocks.size.coerceAtLeast(1),
            onDismiss = { editingBlock = null },
            onConfirm = { hex ->
                editingBlock = null
                sectors = sectors.map { sector ->
                    if (sector.index != target.sector.index) return@map sector
                    val blocks = sector.blocks.toMutableList()
                    while (blocks.size <= target.offset) blocks += ""
                    blocks[target.offset] = hex
                    sector.copy(blocks = blocks)
                }
            },
        )
    }
}

private data class EditableNdef(
    val id: Int,
    val info: NdefRecordInfo,
    val original: NdefRecord?,
    val editable: Boolean,
    val text: String,
)

private data class BlockEditTarget(
    val sector: MifareSector,
    val offset: Int,
)

private fun buildNdefItems(card: NfcCard): List<EditableNdef> {
    val raw = NdefTools.rawRecords(card)
    if (raw != null) {
        return raw.mapIndexed { index, record ->
            val info = NfcParser.parseRecord(record)
            EditableNdef(
                id = index,
                info = info,
                original = record,
                editable = info.kind == "文本" || info.kind == "链接",
                text = if (info.kind == "文本" || info.kind == "链接") info.text else "",
            )
        }
    }
    return card.ndefRecords
        .filter { it.kind == "文本" || it.kind == "链接" }
        .mapIndexed { index, info ->
            EditableNdef(
                id = index,
                info = info,
                original = null,
                editable = true,
                text = info.text,
            )
        }
}

@Composable
private fun EditorHeader(card: NfcCard) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = card.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${card.typeLabel} · ${card.formattedUid}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectorCard(
    sector: MifareSector,
    onEditBlock: (Int) -> Unit,
    onEditKeys: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "扇区 ${sector.index}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!sector.readable) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "未读取",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "A ${sector.keyA ?: "—"}   B ${sector.keyB ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEditKeys) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "编辑扇区密钥",
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            sector.blocks.forEachIndexed { offset, hex ->
                val isTrailer = offset == sector.blocks.lastIndex
                BlockRow(
                    globalIndex = globalBlockIndex(sector.index, offset),
                    hex = hex,
                    isTrailer = isTrailer,
                    isManufacturer = sector.index == 0 && offset == 0,
                    onClick = {
                        if (hex.isNotBlank()) onEditBlock(offset)
                    },
                )
            }
        }
    }
}

@Composable
private fun BlockRow(
    globalIndex: Int,
    hex: String,
    isTrailer: Boolean,
    isManufacturer: Boolean,
    onClick: () -> Unit,
) {
    val value = remember(hex) { decodeValueBlock(hex) }
    val label = when {
        isManufacturer -> "块 $globalIndex · 厂商 / UID"
        isTrailer -> "块 $globalIndex · 尾块"
        value != null -> "块 $globalIndex · 值 $value"
        else -> "块 $globalIndex"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isManufacturer -> MaterialTheme.colorScheme.tertiary
                    isTrailer -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isTrailer) FontWeight.Medium else FontWeight.Normal,
            )
            Spacer(Modifier.height(2.dp))
            if (hex.isBlank()) {
                Text(
                    text = "无法读取（需要正确的扇区密钥）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Text(
                    text = hex.twoLineHex(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (hex.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = asciiOf(hex),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun NdefEditorItem(
    item: EditableNdef,
    onChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (item.editable) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = item.info.kind,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.editable) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.editable) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "删除记录",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (item.editable) {
                OutlinedTextField(
                    value = item.text,
                    onValueChange = onChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    label = {
                        Text(if (item.info.kind == "链接") "链接地址" else "文本内容")
                    },
                    minLines = if (item.info.kind == "链接") 1 else 2,
                    maxLines = 6,
                    singleLine = item.info.kind == "链接",
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = if (item.info.kind == "链接") {
                        KeyboardOptions(keyboardType = KeyboardType.Uri)
                    } else {
                        KeyboardOptions.Default
                    },
                )
            } else {
                Text(
                    text = item.info.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "此记录类型仅支持查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NdefSizeHint(items: List<EditableNdef>) {
    val size = remember(items) {
        val records = items.mapNotNull { item ->
            if (!item.editable) item.original else runCatching {
                when (item.info.kind) {
                    "文本" -> NdefTools.buildText(item.text)
                    "链接" -> NdefTools.buildUri(item.text.trim())
                    else -> item.original
                }
            }.getOrNull()
        }
        NdefTools.hexOf(records)?.length?.div(2) ?: 0
    }
    val overLimit = size > NdefTools.MAX_EMULATED_NDEF_BYTES
    Text(
        text = "NDEF 消息 $size 字节 · 模拟上限 ${NdefTools.MAX_EMULATED_NDEF_BYTES} 字节",
        style = MaterialTheme.typography.labelSmall,
        color = if (overLimit) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun EmptyEditorCard(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyEditDialog(
    sector: MifareSector,
    onDismiss: () -> Unit,
    onConfirm: (keyA: String?, keyB: String?) -> Unit,
) {
    var keyA by remember(sector.index) { mutableStateOf(sector.keyA.orEmpty()) }
    var keyB by remember(sector.index) { mutableStateOf(sector.keyB.orEmpty()) }
    val validA = keyA.isBlank() || isHexKey(keyA)
    val validB = keyB.isBlank() || isHexKey(keyB)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扇区 ${sector.index} 密钥") },
        text = {
            Column {
                Text(
                    text = "密钥用于认证扇区。填入从门禁系统获知的密钥后保存，再重新扫描即可读取对应扇区。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyA,
                    onValueChange = { keyA = sanitizeHex(it, 12) },
                    label = { Text("Key A（12 位十六进制）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !validA,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyB,
                    onValueChange = { keyB = sanitizeHex(it, 12) },
                    label = { Text("Key B（12 位十六进制）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !validB,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        keyA.takeIf { it.isNotBlank() },
                        keyB.takeIf { it.isNotBlank() },
                    )
                },
                enabled = validA && validB,
            ) {
                Text("保存密钥")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun BlockEditDialog(
    target: BlockEditTarget,
    blockCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val original = target.sector.blocks.getOrElse(target.offset) { "" }
    val initialDigits = remember(target.sector.index, target.offset) {
        original.filter { it.digitToIntOrNull(16) != null }
            .uppercase()
            .padEnd(32, ' ')
            .take(32)
    }
    var digits by remember(target.sector.index, target.offset) {
        mutableStateOf(initialDigits)
    }
    var cursor by remember(target.sector.index, target.offset) {
        mutableIntStateOf(0)
    }
    val filled = digits.count { it != ' ' }
    val valid = filled == 32
    val isTrailer = target.offset == blockCount - 1
    val isManufacturer = target.sector.index == 0 && target.offset == 0
    val manufacturer = if (isManufacturer && valid) parseManufacturerBlock(digits) else null

    val typeDigit: (Char) -> Unit = { digit ->
        val chars = digits.toCharArray()
        chars[cursor] = digit
        digits = String(chars)
        if (cursor < chars.lastIndex) cursor += 1
    }
    val backspace: () -> Unit = {
        if (cursor > 0) {
            val chars = digits.toCharArray()
            chars[cursor - 1] = ' '
            digits = String(chars)
            cursor -= 1
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.94f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier.padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = 16.dp,
                        bottom = 8.dp,
                    ),
                ) {
                    Text(
                        text = "编辑扇区 ${target.sector.index} · 块 " +
                            globalBlockIndex(target.sector.index, target.offset),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                    ) {
                        if (isManufacturer) {
                            BlockWarning(
                                "这是厂商块（块 0）：前 4 字节为 UID（低字节在前），" +
                                    "第 5 字节为 BCC 校验，其余为厂商数据。原厂卡对该块只读，" +
                                    "写入会被卡片拒绝；只有 UID 可写卡（Gen1a/Gen2 魔术卡）能改写。" +
                                    "UID 或 BCC 写错会使卡片无法被任何读卡器识别。",
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (isTrailer) {
                            BlockWarning(
                                "这是扇区尾块：前 6 字节 Key A，中间 4 字节权限位，" +
                                    "后 6 字节 Key B。权限位写错可能使扇区永久无法访问。",
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        HexByteGrid(
                            digits = digits,
                            cursor = cursor,
                            onSelectByte = { cursor = it * 2 },
                        )

                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "ASCII  ${asciiOf(digits)}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        decodeValueBlock(digits)?.let { value ->
                            Text(
                                text = "值 block 解码：$value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        manufacturer?.let { info ->
                            Text(
                                text = "UID ${info.uid}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = if (info.bccOk) {
                                    "BCC 校验通过"
                                } else {
                                    "BCC 校验不匹配：第 5 字节应为 ${info.expectedBcc}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (info.bccOk) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HexCursorBar(
                        cursor = cursor,
                        onMove = { delta -> cursor = (cursor + delta).coerceIn(0, 31) },
                        onRestore = {
                            digits = initialDigits
                            cursor = 0
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    HexKeypad(onDigit = typeDigit, onBackspace = backspace)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$filled/32",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = if (valid) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text("取消")
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { onConfirm(digits) }, enabled = valid) {
                            Text("应用", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockWarning(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 16 bytes rendered as two rows of eight, with the active nibble highlighted. */
@Composable
private fun HexByteGrid(
    digits: String,
    cursor: Int,
    onSelectByte: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(8) { column ->
                    val byteIndex = row * 8 + column
                    val start = byteIndex * 2
                    val selected = cursor in start until start + 2
                    val cellShape = RoundedCornerShape(8.dp)
                    val content = buildAnnotatedString {
                        repeat(2) { nibble ->
                            val position = start + nibble
                            val char = digits.getOrElse(position) { ' ' }
                            val style = when {
                                position == cursor -> SpanStyle(
                                    color = scheme.onPrimary,
                                    background = scheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                char == ' ' -> SpanStyle(color = scheme.outline)
                                else -> SpanStyle(color = scheme.onSurface)
                            }
                            withStyle(style) {
                                append(if (char == ' ') '·' else char)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(cellShape)
                            .background(
                                if (selected) {
                                    scheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    scheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                            )
                            .then(
                                if (selected) {
                                    Modifier.border(1.dp, scheme.primary, cellShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onSelectByte(byteIndex) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                letterSpacing = 0.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HexCursorBar(
    cursor: Int,
    onMove: (Int) -> Unit,
    onRestore: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onMove(-1) },
            enabled = cursor > 0,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "向左移动",
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "字节 %02X · %s".format(
                cursor / 2,
                if (cursor % 2 == 0) "高半字节" else "低半字节",
            ),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onMove(1) },
            enabled = cursor < 31,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "向右移动",
                modifier = Modifier.size(20.dp),
            )
        }
        TextButton(
            onClick = onRestore,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) {
            Text("复原", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private val HexKeypadRows = listOf(
    listOf("1", "2", "3", "4"),
    listOf("5", "6", "7", "8"),
    listOf("9", "A", "B", "C"),
    listOf("D", "E", "F", "DEL"),
)

@Composable
private fun HexKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        HexKeypadRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { key ->
                    val isBackspace = key == "DEL"
                    HexKey(
                        label = key,
                        modifier = Modifier.weight(1f),
                        container = if (isBackspace) scheme.errorContainer else scheme.surfaceVariant,
                        content = if (isBackspace) scheme.onErrorContainer else scheme.onSurfaceVariant,
                        onClick = {
                            if (isBackspace) onBackspace() else onDigit(key[0])
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HexKey(
    label: String,
    modifier: Modifier = Modifier,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (label.length == 1) 19.sp else 13.sp,
                letterSpacing = 0.sp,
            ),
            color = content,
        )
    }
}

private data class ManufacturerBlockInfo(
    val uid: String,
    val bccOk: Boolean,
    val expectedBcc: String,
)

/** Decodes the 4-byte-UID layout of a MIFARE Classic manufacturer block. */
private fun parseManufacturerBlock(hex: String): ManufacturerBlockInfo? {
    val bytes = NdefTools.hexToBytes(hex) ?: return null
    if (bytes.size != 16) return null
    val bcc = bytes.take(4).fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }
    return ManufacturerBlockInfo(
        uid = bytes.take(4).joinToString(" ") { "%02X".format(it) },
        bccOk = (bytes[4].toInt() and 0xFF) == bcc,
        expectedBcc = "%02X".format(bcc),
    )
}

private fun sanitizeHex(input: String, maxBytes: Int): String =
    input.filter { !it.isWhitespace() && it.digitToIntOrNull(16) != null }
        .uppercase()
        .take(maxBytes)

private fun isHexKey(key: String): Boolean =
    key.length == 12 && key.all { it.digitToIntOrNull(16) != null }

private fun String.twoLineHex(): String =
    chunked(2)
        .chunked(8)
        .joinToString("\n") { it.joinToString(" ") }

private fun asciiOf(hex: String): String = buildString {
    hex.chunked(2).forEach { pair ->
        val value = pair.toIntOrNull(16) ?: return@forEach
        append(if (value in 0x20..0x7E) value.toChar() else '.')
    }
}

/** Absolute block number on 1K/2K/4K cards. */
private fun globalBlockIndex(sector: Int, offset: Int): Int =
    if (sector < 32) sector * 4 + offset else 128 + (sector - 32) * 16 + offset

/**
 * MIFARE value blocks store a signed 32-bit value three times (plus inverted
 * copies and an address byte). Returns the value when [hex] matches.
 */
private fun decodeValueBlock(hex: String): Int? {
    val bytes = NdefTools.hexToBytes(hex) ?: return null
    if (bytes.size != 16) return null
    fun le(offset: Int): Long {
        var value = 0L
        for (i in 0..3) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }
    val value = le(0)
    val inverted = le(4)
    if ((value xor 0xFFFFFFFFL) != inverted) return null
    if (le(8) != value) return null
    val address = bytes[12].toLong() and 0xFF
    if ((bytes[13].toLong() and 0xFF) != (address xor 0xFF)) return null
    if ((bytes[14].toLong() and 0xFF) != address) return null
    return value.toInt()
}
