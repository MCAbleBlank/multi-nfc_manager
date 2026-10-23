package com.multinfc.manager.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.multinfc.manager.data.NfcCard
import com.multinfc.manager.ui.components.NfcCardVisual
import com.multinfc.manager.ui.theme.cardGradientFor
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Degrees of wheel rotation between two adjacent cards. */
private const val WHEEL_STEP_DEGREES = 27f

/** Number of cards kept composed on each side of the focused one. */
private const val WHEEL_VISIBLE_RADIUS = 3.5f

/** Degrees between two cards in the horizontal coverflow. */
private const val COVERFLOW_STEP_DEGREES = 40f

private const val COVERFLOW_VISIBLE_RADIUS = 3f

private const val STACK_VISIBLE_RADIUS = 2.6f

/**
 * Vertical cylinder carousel. The focused card sits square in the middle,
 * neighbouring cards tilt and recede along the cylinder, and cards wrap around
 * so the wheel keeps spinning in either direction.
 *
 * The visible card set is only recomputed when the wheel crosses a card
 * boundary; each card reads the live position inside `graphicsLayer`, so the
 * animation itself does not recompose anything.
 */
@Composable
internal fun CardWheel(
    cards: List<NfcCard>,
    position: Animatable<Float, AnimationVector1D>,
    hapticsEnabled: Boolean,
    enhancedEffects: Boolean,
    modifier: Modifier = Modifier,
    onOpenCard: (NfcCard) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val stepPx = remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .draggable(
                orientation = Orientation.Vertical,
                enabled = cards.size > 1,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        position.snapTo(position.value - delta / stepPx.floatValue)
                    }
                },
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    scope.launch { settle(position, velocity / stepPx.floatValue) }
                },
            )
    ) {
        val cardWidth = (maxWidth - 72.dp).coerceAtMost(420.dp)
        val cardHeight = (cardWidth * 0.62f).coerceIn(160.dp, 222.dp)
        val radiusPx = with(density) { (cardHeight * 1.9f).toPx() }
        val stepRadians = Math.toRadians(WHEEL_STEP_DEGREES.toDouble())
        val currentStepPx = radiusPx * sin(stepRadians).toFloat()
        androidx.compose.runtime.SideEffect {
            stepPx.floatValue = currentStepPx.coerceAtLeast(1f)
        }

        val centeredStep by remember(cards.size) {
            derivedStateOf { Math.round(position.value) }
        }

        // A light tick for every card the finger drags past.
        LaunchedEffect(centeredStep, hapticsEnabled) {
            if (dragging && hapticsEnabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        CarouselGlow(
            cards = cards,
            centeredStep = centeredStep,
            radius = cardWidth * 1.15f,
        )

        val laidOut = remember(cards.size, centeredStep) {
            laidOutCards(cards.size, centeredStep.toFloat(), WHEEL_VISIBLE_RADIUS)
        }

        laidOut.forEach { (index, _) ->
            key(index) {
                WheelCard(
                    card = cards[index],
                    index = index,
                    count = cards.size,
                    position = position,
                    radiusPx = radiusPx,
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    enhancedEffects = enhancedEffects,
                    modifier = Modifier.align(Alignment.Center),
                    onOpen = { onOpenCard(cards[index]) },
                    onFocus = { focusCard(scope, position, index, cards.size) },
                )
            }
        }

        EdgeScrim(alignTop = true, Modifier.align(Alignment.TopCenter))
        EdgeScrim(alignTop = false, Modifier.align(Alignment.BottomCenter))
        CarouselIndicator(
            cards = cards,
            position = position,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun WheelCard(
    card: NfcCard,
    index: Int,
    count: Int,
    position: Animatable<Float, AnimationVector1D>,
    radiusPx: Float,
    cardWidth: Dp,
    cardHeight: Dp,
    enhancedEffects: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val focused by remember(index, count) {
        derivedStateOf {
            abs(circularOffset(index, position.value, count)) < 0.5f
        }
    }

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer {
                val offset = circularOffset(index, position.value, count)
                val angle = offset * WHEEL_STEP_DEGREES
                val radians = Math.toRadians(angle.toDouble())
                val distance = abs(offset)

                // Position on a cylinder seen slightly from the front: cards
                // move along Y and sink into the screen as they leave focus.
                translationY = (radiusPx * sin(radians)).toFloat()
                translationX = offset * 1.5.dp.toPx()
                // Convex wheel: the edge facing the middle is closer to the
                // viewer.
                rotationX = -angle * 0.88f
                rotationZ = offset * 0.55f
                val depth = radiusPx * (1f - cos(radians).toFloat())
                val perspective = radiusPx * 2.35f
                var layerScale = perspective / (perspective + depth) *
                    (1f - distance.coerceAtMost(4f) * 0.016f)
                // A whisper of extra size makes the focused card read as the
                // nearest object without a hard snapping effect.
                layerScale *= 1f + 0.032f * (1f - distance.coerceIn(0f, 1f))
                scaleX = layerScale
                scaleY = layerScale
                alpha = (1f - distance * 0.23f).coerceIn(0f, 1f)
                cameraDistance = 16f * density
            }
            .shadow(
                elevation = if (focused) 22.dp else 6.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .drawWithContent {
                drawContent()
                drawFocusRim(index, position, count)
            }
            .clickable {
                val base = if (position.isRunning) position.targetValue else position.value
                if (abs(circularOffset(index, base, count)) < 0.5f) {
                    onOpen()
                } else {
                    onFocus()
                }
            },
    ) {
        NfcCardVisual(
            card = card,
            modifier = Modifier.fillMaxSize(),
            height = cardHeight,
        )
        if (enhancedEffects && focused) {
            FocusSheen(Modifier.matchParentSize())
        }
    }
}

/** Horizontal coverflow carousel: the focused card faces the viewer while the
 *  neighbours swing around it with perspective. */
@Composable
internal fun CardCoverflow(
    cards: List<NfcCard>,
    position: Animatable<Float, AnimationVector1D>,
    hapticsEnabled: Boolean,
    enhancedEffects: Boolean,
    modifier: Modifier = Modifier,
    onOpenCard: (NfcCard) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val stepPx = remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = cards.size > 1,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        position.snapTo(position.value - delta / stepPx.floatValue)
                    }
                },
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    scope.launch { settle(position, velocity / stepPx.floatValue) }
                },
            )
    ) {
        val cardWidth = (maxWidth - 84.dp).coerceAtMost(380.dp)
        val cardHeight = (cardWidth * 0.62f).coerceIn(150.dp, 216.dp)
        val radiusPx = with(density) { (cardWidth * 1.02f).toPx() }
        val stepRadians = Math.toRadians(COVERFLOW_STEP_DEGREES.toDouble())
        val currentStepPx = radiusPx * sin(stepRadians).toFloat()
        androidx.compose.runtime.SideEffect {
            stepPx.floatValue = currentStepPx.coerceAtLeast(1f)
        }

        val centeredStep by remember(cards.size) {
            derivedStateOf { Math.round(position.value) }
        }

        LaunchedEffect(centeredStep, hapticsEnabled) {
            if (dragging && hapticsEnabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        CarouselGlow(
            cards = cards,
            centeredStep = centeredStep,
            radius = cardWidth * 1.25f,
        )

        val laidOut = remember(cards.size, centeredStep) {
            laidOutCards(cards.size, centeredStep.toFloat(), COVERFLOW_VISIBLE_RADIUS)
        }

        laidOut.forEach { (index, _) ->
            key(index) {
                CoverflowCard(
                    card = cards[index],
                    index = index,
                    count = cards.size,
                    position = position,
                    radiusPx = radiusPx,
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    enhancedEffects = enhancedEffects,
                    modifier = Modifier.align(Alignment.Center),
                    onOpen = { onOpenCard(cards[index]) },
                    onFocus = { focusCard(scope, position, index, cards.size) },
                )
            }
        }

        EdgeScrim(alignTop = true, Modifier.align(Alignment.TopCenter))
        EdgeScrim(alignTop = false, Modifier.align(Alignment.BottomCenter))
        CarouselIndicator(
            cards = cards,
            position = position,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun CoverflowCard(
    card: NfcCard,
    index: Int,
    count: Int,
    position: Animatable<Float, AnimationVector1D>,
    radiusPx: Float,
    cardWidth: Dp,
    cardHeight: Dp,
    enhancedEffects: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val focused by remember(index, count) {
        derivedStateOf {
            abs(circularOffset(index, position.value, count)) < 0.5f
        }
    }

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer {
                val offset = circularOffset(index, position.value, count)
                val angle = offset * COVERFLOW_STEP_DEGREES
                val radians = Math.toRadians(angle.toDouble())
                val distance = abs(offset)

                translationX = radiusPx * sin(radians).toFloat()
                rotationY = angle * 0.92f
                rotationZ = offset * 0.35f
                val depth = radiusPx * (1f - cos(radians).toFloat())
                val perspective = radiusPx * 2.3f
                var layerScale = perspective / (perspective + depth) *
                    (1f - distance.coerceAtMost(3f) * 0.02f)
                layerScale *= 1f + 0.025f * (1f - distance.coerceIn(0f, 1f))
                scaleX = layerScale
                scaleY = layerScale
                alpha = (1f - distance * 0.22f).coerceIn(0f, 1f)
                cameraDistance = 16f * density
            }
            .shadow(
                elevation = if (focused) 22.dp else 6.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .drawWithContent {
                drawContent()
                drawFocusRim(index, position, count)
            }
            .clickable {
                val base = if (position.isRunning) position.targetValue else position.value
                if (abs(circularOffset(index, base, count)) < 0.5f) {
                    onOpen()
                } else {
                    onFocus()
                }
            },
    ) {
        NfcCardVisual(
            card = card,
            modifier = Modifier.fillMaxSize(),
            height = cardHeight,
        )
        if (enhancedEffects && focused) {
            FocusSheen(Modifier.matchParentSize())
        }
    }
}

/** A deck of cards: the focused one on top, the rest peeking out underneath. */
@Composable
internal fun CardStack(
    cards: List<NfcCard>,
    position: Animatable<Float, AnimationVector1D>,
    hapticsEnabled: Boolean,
    enhancedEffects: Boolean,
    modifier: Modifier = Modifier,
    onOpenCard: (NfcCard) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val stepPx = remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .draggable(
                orientation = Orientation.Vertical,
                enabled = cards.size > 1,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        position.snapTo(position.value - delta / stepPx.floatValue)
                    }
                },
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    scope.launch { settle(position, velocity / stepPx.floatValue) }
                },
            )
    ) {
        val cardWidth = (maxWidth - 84.dp).coerceAtMost(400.dp)
        val cardHeight = (cardWidth * 0.62f).coerceIn(154.dp, 212.dp)
        val currentStepPx = with(density) { 94.dp.toPx() }
        androidx.compose.runtime.SideEffect {
            stepPx.floatValue = currentStepPx
        }

        val centeredStep by remember(cards.size) {
            derivedStateOf { Math.round(position.value) }
        }

        LaunchedEffect(centeredStep, hapticsEnabled) {
            if (dragging && hapticsEnabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        CarouselGlow(
            cards = cards,
            centeredStep = centeredStep,
            radius = cardWidth * 1.05f,
        )

        val laidOut = remember(cards.size, centeredStep) {
            laidOutCards(cards.size, centeredStep.toFloat(), STACK_VISIBLE_RADIUS)
        }

        laidOut.forEach { (index, _) ->
            key(index) {
                StackCard(
                    card = cards[index],
                    index = index,
                    count = cards.size,
                    position = position,
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    enhancedEffects = enhancedEffects,
                    modifier = Modifier.align(Alignment.Center),
                    onOpen = { onOpenCard(cards[index]) },
                    onFocus = { focusCard(scope, position, index, cards.size) },
                )
            }
        }

        EdgeScrim(alignTop = true, Modifier.align(Alignment.TopCenter))
        EdgeScrim(alignTop = false, Modifier.align(Alignment.BottomCenter))
        CarouselIndicator(
            cards = cards,
            position = position,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun StackCard(
    card: NfcCard,
    index: Int,
    count: Int,
    position: Animatable<Float, AnimationVector1D>,
    cardWidth: Dp,
    cardHeight: Dp,
    enhancedEffects: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val focused by remember(index, count) {
        derivedStateOf {
            abs(circularOffset(index, position.value, count)) < 0.5f
        }
    }

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer {
                val offset = circularOffset(index, position.value, count)
                val distance = abs(offset)
                translationY = offset * 25.dp.toPx()
                translationX = offset * 3.dp.toPx()
                rotationZ = offset * 2.1f
                val layerScale = (1f - distance * 0.055f).coerceAtLeast(0.7f)
                scaleX = layerScale
                scaleY = layerScale
                alpha = (1f - distance * 0.23f).coerceIn(0f, 1f)
                cameraDistance = 18f * density
            }
            .shadow(
                elevation = if (focused) 24.dp else 10.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .drawWithContent {
                drawContent()
                drawFocusRim(index, position, count)
            }
            .clickable {
                val base = if (position.isRunning) position.targetValue else position.value
                if (abs(circularOffset(index, base, count)) < 0.5f) {
                    onOpen()
                } else {
                    onFocus()
                }
            },
    ) {
        NfcCardVisual(
            card = card,
            modifier = Modifier.fillMaxSize(),
            height = cardHeight,
        )
        if (enhancedEffects && focused) {
            FocusSheen(Modifier.matchParentSize())
        }
    }
}

/** Two-column grid. Volume keys move the grid one row at a time. */
@Composable
internal fun CardGrid(
    cards: List<NfcCard>,
    query: String,
    volumeSteps: SharedFlow<Int>,
    modifier: Modifier = Modifier,
    onOpenCard: (NfcCard) -> Unit,
) {
    val state = rememberLazyGridState()
    // A volume step can arrive while the previous scroll animation is still
    // running, when firstVisibleItemIndex has not caught up yet. Accumulating
    // the target ourselves keeps rapid presses moving forward instead of
    // re-targeting the same row.
    var targetIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(query) {
        targetIndex = 0
        if (cards.isNotEmpty()) state.scrollToItem(0)
    }

    // A manual drag becomes the new base for the next volume step.
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                targetIndex = state.firstVisibleItemIndex
            }
        }
    }

    LaunchedEffect(volumeSteps, state, cards.size) {
        volumeSteps.collect { delta ->
            if (cards.isNotEmpty()) {
                val base = targetIndex.coerceIn(0, cards.lastIndex)
                targetIndex = (base + delta * 2).coerceIn(0, cards.lastIndex)
                state.animateScrollToItem(targetIndex)
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 108.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItems(items = cards, key = { it.uid }) { card ->
            NfcCardVisual(
                card = card,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onOpenCard(card) },
                height = 146.dp,
            )
        }
    }
}

/** Compact vertical list. Volume keys move one card at a time. */
@Composable
internal fun CardList(
    cards: List<NfcCard>,
    query: String,
    volumeSteps: SharedFlow<Int>,
    modifier: Modifier = Modifier,
    onOpenCard: (NfcCard) -> Unit,
) {
    val state = rememberLazyListState()
    // Same accumulator as the grid: rapid volume presses must not be lost
    // while the previous animated scroll is still in flight.
    var targetIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(query) {
        targetIndex = 0
        if (cards.isNotEmpty()) state.scrollToItem(0)
    }

    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                targetIndex = state.firstVisibleItemIndex
            }
        }
    }

    LaunchedEffect(volumeSteps, state, cards.size) {
        volumeSteps.collect { delta ->
            if (cards.isNotEmpty()) {
                val base = targetIndex.coerceIn(0, cards.lastIndex)
                targetIndex = (base + delta).coerceIn(0, cards.lastIndex)
                state.animateScrollToItem(targetIndex)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items = cards, key = { it.uid }) { card ->
            NfcCardVisual(
                card = card,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onOpenCard(card) },
                height = 112.dp,
            )
        }
    }
}

/** Soft colour wash behind the focused card, tinted from its own gradient. */
@Composable
private fun CarouselGlow(
    cards: List<NfcCard>,
    centeredStep: Int,
    radius: Dp,
) {
    if (cards.isEmpty()) return
    val index = positiveModulo(centeredStep, cards.size)
    val gradient = cardGradientFor(cards[index].uid)
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        gradient.end.copy(alpha = 0.17f),
                        gradient.start.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    radius = radiusPx,
                )
            )
    )
}

/** Focus rim drawn on top of every card; brightness falls off with distance. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusRim(
    index: Int,
    position: Animatable<Float, AnimationVector1D>,
    count: Int,
) {
    val focus = (1f - abs(circularOffset(index, position.value, count))).coerceIn(0f, 1f)
    if (focus <= 0.01f) return
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.42f * focus),
                Color.White.copy(alpha = 0.06f * focus),
                Color.White.copy(alpha = 0.24f * focus),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        cornerRadius = CornerRadius(28.dp.toPx()),
        style = Stroke(width = 1.2.dp.toPx()),
    )
}

/** Static glass highlight for the card that currently has focus. */
@Composable
private fun FocusSheen(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.10f),
                0.42f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.06f),
            )
        )
    )
}

/** Fades cards into the page background at the top and bottom edges. */
@Composable
private fun EdgeScrim(alignTop: Boolean, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val colors = if (alignTop) {
        listOf(background, background.copy(alpha = 0f))
    } else {
        listOf(background.copy(alpha = 0f), background)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(if (alignTop) 34.dp else 54.dp)
            .background(Brush.verticalGradient(colors))
    )
}

/** Name and position of the focused card, pinned below the carousel. */
@Composable
private fun CarouselIndicator(
    cards: List<NfcCard>,
    position: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    if (cards.size <= 1) return
    val index by remember(cards.size) {
        derivedStateOf { positiveModulo(Math.round(position.value), cards.size) }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                label = "indicator",
            ) { current ->
                val card = cards[current.coerceIn(cards.indices)]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${current + 1}/${cards.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun laidOutCards(
    count: Int,
    position: Float,
    visibleRadius: Float,
): List<Pair<Int, Float>> =
    (0 until count)
        .map { index -> index to circularOffset(index, position, count) }
        .filter { (_, offset) -> abs(offset) < visibleRadius + 0.5f }
        .sortedByDescending { (_, offset) -> abs(offset) }

private fun focusCard(
    scope: kotlinx.coroutines.CoroutineScope,
    position: Animatable<Float, AnimationVector1D>,
    index: Int,
    count: Int,
) {
    val base = if (position.isRunning) position.targetValue else position.value
    scope.launch {
        position.animateTo(
            targetValue = base + circularOffset(index, base, count),
            animationSpec = tween(320, easing = FastOutSlowInEasing),
        )
    }
}

private suspend fun settle(
    position: Animatable<Float, AnimationVector1D>,
    velocitySteps: Float = 0f,
) {
    val projected = position.value + (velocitySteps * 0.085f).coerceIn(-4f, 4f)
    position.animateTo(
        targetValue = Math.round(projected).toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        ),
    )
}

private fun positiveModulo(value: Int, modulus: Int): Int =
    if (modulus <= 0) 0 else ((value % modulus) + modulus) % modulus

/**
 * Signed distance from [position] to [index] on a circular wheel of [count]
 * cards, in the range [-count/2, count/2]. Positions are never wrapped, which
 * lets the wheel keep turning in either direction indefinitely.
 */
private fun circularOffset(index: Int, position: Float, count: Int): Float {
    if (count <= 1) return 0f
    val half = count / 2f
    var offset = (index - position) % count
    if (offset > half) offset -= count
    if (offset < -half) offset += count
    return offset
}
