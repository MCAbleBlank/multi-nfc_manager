package com.multinfc.manager.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multinfc.manager.data.AppSettings
import com.multinfc.manager.data.CardStyle
import com.multinfc.manager.ui.components.AppIcons
import com.multinfc.manager.ui.components.hasTiltSensor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onCardStyle: (CardStyle) -> Unit,
    onVolumeKeys: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onTilt: (Boolean) -> Unit,
    onEnhancedEffects: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val tiltAvailable = remember(context) { hasTiltSensor(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
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
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle("首页展示") }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardStyle.entries.forEach { style ->
                        StyleOption(
                            style = style,
                            selected = settings.cardStyle == style,
                            onClick = { onCardStyle(style) },
                        )
                    }
                }
            }

            item { SectionTitle("交互与反馈", Modifier.padding(top = 10.dp)) }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                ) {
                    Column {
                        SwitchRow(
                            title = "音量键切换卡片",
                            subtitle = "在首页用音量上/下键切换上一张/下一张",
                            checked = settings.volumeKeysEnabled,
                            onCheckedChange = onVolumeKeys,
                        )
                        SettingDivider()
                        SwitchRow(
                            title = "滑动触感反馈",
                            subtitle = "拖动轮盘、封面流或卡堆时，每切换一张轻振一次",
                            checked = settings.hapticsEnabled,
                            onCheckedChange = onHaptics,
                        )
                        SettingDivider()
                        SwitchRow(
                            title = "详情页倾斜反馈",
                            subtitle = if (tiltAvailable) {
                                "陀螺仪融合感应手机倾斜，卡片与高光随之移动"
                            } else {
                                "此设备未检测到陀螺仪或旋转矢量传感器"
                            },
                            checked = settings.tiltEnabled && tiltAvailable,
                            onCheckedChange = onTilt,
                            enabled = tiltAvailable,
                        )
                        SettingDivider()
                        SwitchRow(
                            title = "卡片光影效果",
                            subtitle = "详情页环境光与扫光、聚焦卡片的边缘高光",
                            checked = settings.enhancedEffects,
                            onCheckedChange = onEnhancedEffects,
                        )
                    }
                }
            }

            item { SectionTitle("关于", Modifier.padding(top = 10.dp)) }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                imageVector = AppIcons.Nfc,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "NFC 卡包",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "1.0",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "卡片、NDEF 内容与 MIFARE 扇区数据仅保存在本机。" +
                                    "卡模拟受 Android 平台限制，只能模拟 ISO-DEP / NDEF " +
                                    "Type 4 标签，无法模拟 MIFARE Classic 的 UID 与加密扇区。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "设置会自动保存并在下次启动时恢复",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun StyleOption(
    style: CardStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            colors.primaryContainer.copy(alpha = 0.55f)
        } else {
            colors.surfaceVariant.copy(alpha = 0.26f)
        },
        border = if (selected) {
            BorderStroke(1.5.dp, colors.primary.copy(alpha = 0.6f))
        } else {
            BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f))
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StylePreview(style = style, selected = selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = style.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = style.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(
                selected = selected,
                onClick = null,
            )
        }
    }
}

/** Tiny abstract preview of each home layout. */
@Composable
private fun StylePreview(style: CardStyle, selected: Boolean) {
    val accent = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    }
    val soft = accent.copy(alpha = 0.34f)
    Canvas(Modifier.size(width = 54.dp, height = 40.dp)) {
        val corner = CornerRadius(4.dp.toPx())
        val w = size.width
        val h = size.height
        when (style) {
            CardStyle.Wheel -> {
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(w * 0.18f, h * 0.02f),
                    size = Size(w * 0.64f, h * 0.25f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(w * 0.09f, h * 0.36f),
                    size = Size(w * 0.82f, h * 0.28f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(w * 0.18f, h * 0.73f),
                    size = Size(w * 0.64f, h * 0.25f),
                    cornerRadius = corner,
                )
            }

            CardStyle.Coverflow -> {
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(0f, h * 0.24f),
                    size = Size(w * 0.32f, h * 0.52f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(w * 0.29f, h * 0.09f),
                    size = Size(w * 0.42f, h * 0.82f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(w * 0.68f, h * 0.24f),
                    size = Size(w * 0.32f, h * 0.52f),
                    cornerRadius = corner,
                )
            }

            CardStyle.Stack -> {
                drawRoundRect(
                    color = soft.copy(alpha = 0.55f),
                    topLeft = Offset(w * 0.24f, h * 0.34f),
                    size = Size(w * 0.60f, h * 0.46f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(w * 0.17f, h * 0.19f),
                    size = Size(w * 0.64f, h * 0.48f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(w * 0.10f, h * 0.04f),
                    size = Size(w * 0.68f, h * 0.50f),
                    cornerRadius = corner,
                )
            }

            CardStyle.Grid -> {
                val cellW = w * 0.44f
                val cellH = h * 0.42f
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(0f, 0f),
                    size = Size(cellW, cellH),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(w * 0.52f, 0f),
                    size = Size(cellW, cellH),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = soft,
                    topLeft = Offset(0f, h * 0.54f),
                    size = Size(cellW, cellH),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.7f),
                    topLeft = Offset(w * 0.52f, h * 0.54f),
                    size = Size(cellW, cellH),
                    cornerRadius = corner,
                )
            }

            CardStyle.List -> {
                repeat(3) { index ->
                    drawRoundRect(
                        color = if (index == 0) accent else soft,
                        topLeft = Offset(0f, h * (0.04f + index * 0.33f)),
                        size = Size(w, h * 0.24f),
                        cornerRadius = corner,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.65f,
                ),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}
