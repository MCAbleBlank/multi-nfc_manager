package com.multinfc.manager.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multinfc.manager.data.NfcCard
import com.multinfc.manager.ui.theme.CardGradient
import com.multinfc.manager.ui.theme.cardGradientFor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Wallet-style card artwork, tinted deterministically from the card UID.
 *
 * [enhanced] adds the "physical card" treatment used on the detail screen:
 * an ambient halo, a machined bevel along the edge, brushed micro-texture and
 * moving specular light. It is off by default so the wheel stays cheap.
 *
 * When a [tilt] source is supplied, the card follows the device's orientation
 * and the specular highlight tracks the same movement. Both values are read
 * inside the layer/draw phases, so sensor updates only invalidate drawing.
 */
@Composable
fun NfcCardVisual(
    card: NfcCard,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    enhanced: Boolean = false,
    tilt: DeviceTilt? = null,
) {
    val gradient = cardGradientFor(card.uid)
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (enhanced) {
            CardAmbientGlow(
                gradient = gradient,
                modifier = Modifier.matchParentSize(),
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val source = tilt
                    if (source != null) {
                        rotationX = source.rotateX.floatValue
                        rotationY = source.rotateY.floatValue
                        cameraDistance = 16f * density
                    }
                }
                // Clip here rather than at each call site: the decorative rings
                // are drawn outside the card bounds by design, but must never
                // spill over the buttons/sections below or the page background.
                .clip(shape)
                .background(
                    brush = Brush.linearGradient(listOf(gradient.start, gradient.end)),
                    shape = shape,
                )
        ) {
            Canvas(Modifier.matchParentSize()) {
                val unit = size.minDimension
                val center = Offset(size.width * 0.94f, size.height * 0.02f)
                repeat(3) { index ->
                    drawCircle(
                        color = Color.White.copy(alpha = 0.10f),
                        radius = unit * (0.30f + index * 0.20f),
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
                drawCircle(
                    color = Color.White.copy(alpha = 0.06f),
                    radius = unit * 0.55f,
                    center = Offset(size.width * 0.05f, size.height * 1.08f),
                )
            }

            if (enhanced) {
                CardEdgeTexture(Modifier.matchParentSize())
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = AppIcons.Nfc,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = card.typeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = AppIcons.Nfc,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(30.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = card.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = card.formattedUid,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.4.sp,
                )
            }

            if (enhanced) {
                CardLightSweep(Modifier.matchParentSize(), tilt = tilt)
            }
        }
    }
}

/**
 * Colored halo that bleeds out from behind the card. Drawn before the card
 * surface so only the soft fringe around the outline remains visible.
 */
@Composable
private fun CardAmbientGlow(
    gradient: CardGradient,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "card-glow")
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow-breath",
    )

    Canvas(modifier) {
        val corner = 28.dp.toPx()
        val layers = listOf(
            14.dp to 0.030f,
            9.dp to 0.040f,
            4.dp to 0.055f,
        )
        layers.forEach { (spreadDp, alpha) ->
            val spread = spreadDp.toPx()
            drawRoundRect(
                color = gradient.end.copy(alpha = alpha * breath),
                topLeft = Offset(-spread, -spread * 0.8f),
                size = Size(size.width + spread * 2f, size.height + spread * 1.6f),
                cornerRadius = CornerRadius(corner + spread),
                style = Stroke(width = spread),
            )
        }
    }
}

/**
 * Static surface texture: brushed hairlines, an engraved bevel just inside
 * the rim, and a bottom shade that keeps the labels readable.
 */
@Composable
private fun CardEdgeTexture(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val corner = 28.dp.toPx()

        // Brushed metal grain: barely-there diagonal hairlines.
        val grainStep = 6.dp.toPx()
        val grainColor = Color.White.copy(alpha = 0.018f)
        var x = -h
        while (x < w) {
            drawLine(
                color = grainColor,
                start = Offset(x, h),
                end = Offset(x + h, 0f),
                strokeWidth = 0.6.dp.toPx(),
            )
            x += grainStep
        }

        // Two hairline strokes: a dark seam and a bright inner bevel.
        val darkInset = 2.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.10f),
            topLeft = Offset(darkInset, darkInset),
            size = Size(w - darkInset * 2, h - darkInset * 2),
            cornerRadius = CornerRadius(corner - darkInset),
            style = Stroke(width = 0.8.dp.toPx()),
        )
        val lightInset = 5.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            topLeft = Offset(lightInset, lightInset),
            size = Size(w - lightInset * 2, h - lightInset * 2),
            cornerRadius = CornerRadius(corner - lightInset),
            style = Stroke(width = 0.9.dp.toPx()),
        )

        drawRect(
            brush = Brush.verticalGradient(
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.18f),
            )
        )
    }
}

/**
 * Moving light: a warm metal rim, a hot spot gliding around the perimeter,
 * a diagonal specular sweep and a breathing corner glint.
 */
@Composable
private fun CardLightSweep(
    modifier: Modifier = Modifier,
    tilt: DeviceTilt? = null,
) {
    val transition = rememberInfiniteTransition(label = "card-light")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val rimPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rim-phase",
    )
    val glint by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glint",
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val corner = 28.dp.toPx()
        val rimInset = 0.75.dp.toPx()

        // Static bevel: light from the top-left, shadow toward the bottom-right.
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFF4DA).copy(alpha = 0.72f),
                    Color.White.copy(alpha = 0.22f),
                    Color.White.copy(alpha = 0.44f),
                    Color.White.copy(alpha = 0.14f),
                    Color(0xFFFFE9C4).copy(alpha = 0.58f),
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            ),
            topLeft = Offset(rimInset, rimInset),
            size = Size(w - rimInset * 2, h - rimInset * 2),
            cornerRadius = CornerRadius(corner - rimInset),
            style = Stroke(width = 1.4.dp.toPx()),
        )

        // A narrow highlight travelling around the outline.
        val rimColors = List(49) { index ->
            val t = index / 48f
            val wave = (((cos(2 * PI * (t - rimPhase)) + 1.0) * 0.5).pow(10)).toFloat()
            Color.White.copy(alpha = 0.04f + 0.52f * wave)
        }
        drawRoundRect(
            brush = Brush.sweepGradient(colors = rimColors, center = center),
            topLeft = Offset(rimInset, rimInset),
            size = Size(w - rimInset * 2, h - rimInset * 2),
            cornerRadius = CornerRadius(corner - rimInset),
            style = Stroke(width = 1.4.dp.toPx()),
        )

        // Diagonal specular band sweeping across the surface.
        val band = w * 0.52f
        // Travel far enough off both edges that the highlight fades out
        // completely before the animation restarts.
        val startX = w * (sweep * 2.2f - 0.9f) - band / 2f
        rotate(degrees = -17f, pivot = center) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.22f to Color.White.copy(alpha = 0.015f),
                    0.40f to Color.White.copy(alpha = 0.06f),
                    0.50f to Color.White.copy(alpha = 0.16f),
                    0.60f to Color.White.copy(alpha = 0.06f),
                    0.78f to Color.White.copy(alpha = 0.015f),
                    1f to Color.Transparent,
                    startX = startX,
                    endX = startX + band,
                ),
                topLeft = Offset(-w, -h),
                size = Size(w * 3f, h * 3f),
            )
        }

        // Soft glint pooled near the top-left corner. With a tilt source the
        // anchor follows the device, so the highlight slides across the card
        // like a real specular reflection as the phone is angled.
        val anchorX = tilt?.lightX?.floatValue ?: 0.22f
        val anchorY = tilt?.lightY?.floatValue ?: 0.10f
        val glintCenter = Offset(w * anchorX, h * anchorY)
        val glintRadius = w * 0.5f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.16f * glint),
                    Color.White.copy(alpha = 0.05f * glint),
                    Color.Transparent,
                ),
                center = glintCenter,
                radius = glintRadius,
            ),
            radius = glintRadius,
            center = glintCenter,
        )
    }
}

/**
 * Concentric breathing rings used while waiting for a tag.
 */
@Composable
fun PulsingRings(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "nfc-pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse-progress",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse-spin",
    )
    val breath by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-breath",
    )

    Canvas(modifier) {
        val base = size.minDimension * 0.21f
        val max = size.minDimension * 0.49f

        // Soft breathing glow underneath the icon.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.16f * breath),
                    Color.Transparent,
                ),
                center = center,
                radius = base * 1.9f,
            ),
            radius = base * 1.9f,
            center = center,
        )

        // Ripples that slow down and thin out as they fade.
        repeat(3) { index ->
            val phase = (progress + index / 3f) % 1f
            val eased = FastOutSlowInEasing.transform(phase)
            drawCircle(
                color = color.copy(alpha = (1f - eased) * 0.34f),
                radius = base + (max - base) * eased,
                center = center,
                style = Stroke(width = (2.4f - eased * 1.5f).dp.toPx()),
            )
        }

        // Three small satellites orbiting the outer edge.
        rotate(spin) {
            repeat(3) { index ->
                rotate(index * 120f) {
                    drawCircle(
                        color = color.copy(alpha = 0.5f * breath),
                        radius = 2.2.dp.toPx(),
                        center = Offset(center.x, center.y - max),
                    )
                }
            }
        }
    }
}
