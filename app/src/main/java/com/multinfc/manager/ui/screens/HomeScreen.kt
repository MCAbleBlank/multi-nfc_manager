package com.multinfc.manager.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multinfc.manager.data.CardStyle
import com.multinfc.manager.data.NfcCard
import com.multinfc.manager.ui.components.AppIcons
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun HomeScreen(
    cards: List<NfcCard>,
    query: String,
    onQueryChange: (String) -> Unit,
    nfcSupported: Boolean,
    nfcEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenNfcSettings: () -> Unit,
    onScan: () -> Unit,
    onCardClick: (NfcCard) -> Unit,
    wheelPosition: Animatable<Float, AnimationVector1D>,
    cardStyle: CardStyle,
    volumeKeysEnabled: Boolean,
    hapticsEnabled: Boolean,
    enhancedEffects: Boolean,
    volumeSteps: SharedFlow<Int>,
    onFilteredCountChange: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val filtered = remember(cards, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            cards
        } else {
            val compact = needle.replace(" ", "")
            cards.filter { card ->
                card.name.contains(needle, ignoreCase = true) ||
                    card.uid.contains(compact, ignoreCase = true) ||
                    card.typeLabel.contains(needle, ignoreCase = true)
            }
        }
    }

    // Lets the activity know whether volume keys should drive the home screen.
    LaunchedEffect(filtered.size) {
        onFilteredCountChange(filtered.size)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            HomeHeader(
                count = cards.size,
                style = cardStyle,
                showSearch = cards.isNotEmpty(),
                volumeKeysEnabled = volumeKeysEnabled,
                query = query,
                onQueryChange = onQueryChange,
                onOpenSettings = onOpenSettings,
            )
        },
        floatingActionButton = {
            if (nfcSupported) {
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                    icon = { Icon(AppIcons.Nfc, contentDescription = null) },
                    text = { Text(if (nfcEnabled) "扫描卡片" else "开启 NFC") },
                )
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(
                visible = !nfcSupported || !nfcEnabled,
                enter = expandVertically(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(220)) + scaleIn(
                    initialScale = 0.98f,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(140)) + scaleOut(
                    targetScale = 0.98f,
                    animationSpec = tween(200),
                ),
            ) {
                NfcStatusBanner(
                    supported = nfcSupported,
                    onOpenSettings = onOpenNfcSettings,
                )
            }

            when {
                cards.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        nfcSupported = nfcSupported,
                        onScan = onScan,
                    )
                }

                filtered.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NoResults(onClear = { onQueryChange("") })
                }

                else -> AnimatedContent(
                    targetState = cardStyle,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        (
                            fadeIn(tween(260)) + scaleIn(
                                initialScale = 0.96f,
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                            )
                            ) togetherWith (
                            fadeOut(tween(160)) + scaleOut(
                                targetScale = 1.02f,
                                animationSpec = tween(220),
                            )
                            )
                    },
                    label = "card-style",
                ) { style ->
                    when (style) {
                        CardStyle.Wheel -> CardWheel(
                            cards = filtered,
                            position = wheelPosition,
                            hapticsEnabled = hapticsEnabled,
                            enhancedEffects = enhancedEffects,
                            modifier = Modifier.fillMaxSize(),
                            onOpenCard = onCardClick,
                        )

                        CardStyle.Coverflow -> CardCoverflow(
                            cards = filtered,
                            position = wheelPosition,
                            hapticsEnabled = hapticsEnabled,
                            enhancedEffects = enhancedEffects,
                            modifier = Modifier.fillMaxSize(),
                            onOpenCard = onCardClick,
                        )

                        CardStyle.Stack -> CardStack(
                            cards = filtered,
                            position = wheelPosition,
                            hapticsEnabled = hapticsEnabled,
                            enhancedEffects = enhancedEffects,
                            modifier = Modifier.fillMaxSize(),
                            onOpenCard = onCardClick,
                        )

                        CardStyle.Grid -> CardGrid(
                            cards = filtered,
                            query = query,
                            volumeSteps = volumeSteps,
                            modifier = Modifier.fillMaxSize(),
                            onOpenCard = onCardClick,
                        )

                        CardStyle.List -> CardList(
                            cards = filtered,
                            query = query,
                            volumeSteps = volumeSteps,
                            modifier = Modifier.fillMaxSize(),
                            onOpenCard = onCardClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    count: Int,
    style: CardStyle,
    showSearch: Boolean,
    volumeKeysEnabled: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "我的卡包",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    AnimatedContent(
                        targetState = count,
                        transitionSpec = {
                            (slideInVertically(
                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                            ) { it / 2 } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(
                                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                                ) { -it / 2 } + fadeOut(tween(140)))
                        },
                        label = "card-count",
                    ) { total ->
                        val hint = if (total == 0) {
                            "用 NFC 管理你的实体卡片"
                        } else {
                            buildString {
                                append("共 $total 张 · ${style.title}")
                                if (volumeKeysEnabled) append(" · 音量键")
                            }
                        }
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(2.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(
                        modifier = Modifier.size(46.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = AppIcons.Nfc,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showSearch,
                enter = expandVertically(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(240, delayMillis = 40)),
                exit = shrinkVertically(
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(120)),
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索名称、UID 或类型") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NfcStatusBanner(
    supported: Boolean,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (supported) "NFC 已关闭" else "此设备不支持 NFC",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = if (supported) "开启后即可读取并保存卡片" else "你仍然可以查看已保存的卡片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            if (supported) {
                TextButton(onClick = onOpenSettings) {
                    Text("去开启")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(nfcSupported: Boolean, onScan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        ) {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(54.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "还没有卡片",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (nfcSupported) {
                "点击下方按钮，把 NFC 卡片贴近手机背面\n即可读取并保存到卡包"
            } else {
                "此设备不支持 NFC，暂时无法读取卡片"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (nfcSupported) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onScan,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(AppIcons.Nfc, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始扫描")
            }
        }
    }
}

@Composable
private fun NoResults(onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "没有找到相关卡片",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "试试其他名称、UID 或卡片类型",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClear) {
            Text("清除搜索")
        }
    }
}
