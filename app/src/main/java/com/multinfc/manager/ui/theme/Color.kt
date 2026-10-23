package com.multinfc.manager.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

internal val LightColors = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF4C54C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF060B6E),
    inversePrimary = Color(0xFFBEC2FF),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA4EEF4),
    onTertiaryContainer = Color(0xFF002022),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

internal val DarkColors = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFFBEC2FF),
    onPrimary = Color(0xFF1A2084),
    primaryContainer = Color(0xFF343BA8),
    onPrimaryContainer = Color(0xFFE1E0FF),
    inversePrimary = Color(0xFF4C54C4),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434659),
    onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF88D1D7),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFFA4EEF4),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Gradient pairs used for the wallet-style card artwork. They stay the same
 * in light and dark mode so saved cards are always recognisable.
 */
@Immutable
data class CardGradient(val start: Color, val end: Color)

val CardGradients = listOf(
    CardGradient(Color(0xFF4F46A8), Color(0xFF8B7AD6)),
    CardGradient(Color(0xFF0F5A78), Color(0xFF42A3C4)),
    CardGradient(Color(0xFF0C5751), Color(0xFF46A79C)),
    CardGradient(Color(0xFF7A3E12), Color(0xFFD08B4C)),
    CardGradient(Color(0xFF7C2948), Color(0xFFC96585)),
    CardGradient(Color(0xFF3A5828), Color(0xFF85A75C)),
)

fun cardGradientFor(uid: String): CardGradient {
    if (CardGradients.isEmpty()) return CardGradient(Color(0xFF4F46A8), Color(0xFF8B7AD6))
    return CardGradients[(uid.hashCode() and Int.MAX_VALUE) % CardGradients.size]
}
