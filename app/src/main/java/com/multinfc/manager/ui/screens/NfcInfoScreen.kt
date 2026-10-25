package com.multinfc.manager.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multinfc.manager.nfc.UidEmulation
import com.multinfc.manager.ui.components.AppIcons

/**
 * Single place for NFC capability details and the long-form explanations that
 * used to sit on every card detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcInfoScreen(
    capability: UidEmulation.Capability?,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRestoreDefaults: () -> Unit,
) {
    val context = LocalContext.current
    val pathLabel = when (capability?.path) {
        UidEmulation.Path.OEM -> "Android OEM 扩展"
        UidEmulation.Path.ONEPLUS_VENDOR -> "OnePlus vendor HCE（Android 15 实验）"
        null -> if (capability == null) "正在检测…" else "不可用"
    }
    val granted = capability?.secureSettingsGranted == true
    val needsPermission = capability?.needsSecureSettings == true && !granted
    val pendingRestore = capability?.pendingRestore == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("NFC 能力与说明") },
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
            item {
                InfoCard(title = "当前通道") {
                    InfoLine("UID 模拟", pathLabel)
                    if (capability?.supported == true) {
                        InfoLine("HCE", "NDEF Type 4（只读）")
                    }
                    if (capability?.needsSecureSettings == true) {
                        InfoLine("ADB 授权", if (granted) "已授予" else "未授予")
                    }
                    if (pendingRestore) {
                        InfoLine("待恢复", "存在未完成的模拟配置")
                    }
                    if (capability?.supported == false) {
                        capability.reason?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onRefresh, enabled = !busy) {
                            Text("重新检测")
                        }
                        if (pendingRestore) {
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = onRestoreDefaults, enabled = !busy) {
                                Text("恢复默认配置")
                            }
                        }
                    }
                }
            }

            if (needsPermission) {
                item {
                    InfoCard(title = "需要 ADB 授权") {
                        Text(
                            text = "OnePlus vendor 通道需要写入系统 HCE 设置。连接 ADB 后执行一次：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = "adb shell pm grant ${context.packageName} " +
                                    "android.permission.WRITE_SECURE_SETTINGS",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            item {
                InfoCard(title = "HCE") {
                    Bullet("只模拟 ISO-DEP 的 NDEF Type 4 标签，不包含 MIFARE 扇区或密钥。")
                    Bullet("后台路由由系统默认钱包决定，应用无法强制覆盖。")
                }
            }

            item {
                InfoCard(title = "UID 模拟（实验性）") {
                    Bullet("只向读卡器呈现 UID，不复制扇区数据、密钥或认证过程。")
                    Bullet("支持 4 / 7 / 10 字节 UID，与 HCE 互斥。")
                    Bullet("Android 16 QPR2+ 使用系统 OEM 扩展；OnePlus Android 15 使用 vendor 通道。")
                    Bullet("关闭 NFC、重启或进程结束后可能丢失，需要重新开启。")
                }
            }

            item {
                InfoCard(title = "使用边界") {
                    Bullet("仅对你有权使用的卡片启用模拟。")
                    Bullet("依赖扇区密钥、滚动码或服务端校验的门禁仍可能拒绝。")
                    Bullet("实验通道会先保存原始配置，停止时按快照恢复。")
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
