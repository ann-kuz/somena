package ru.somena.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

// Палитра «ночная туманность»: почти чёрный фон, фиолетово-синее свечение, золотые акценты.
val BgBase = Color(0xFF0A0912)
val Violet = Color(0xFF8B7CFF)
val Indigo = Color(0xFF6D8DFF)
val Gold = Color(0xFFFFC94D)
val TextMuted = Color(0xFFA8A4C4)
val CardBorder = Color.White.copy(alpha = 0.08f)

/** Главный градиент акцентов — фиолет → синева, как свечение на референсах. */
val AccentBrush = Brush.linearGradient(listOf(Violet, Indigo))

private val DarkScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF130D24),
    primaryContainer = Color(0xFF332A5E),
    onPrimaryContainer = Color(0xFFDDD6FF),
    secondary = Indigo,
    onSecondary = Color(0xFF0D1024),
    secondaryContainer = Color(0xFF25305C),
    onSecondaryContainer = Color(0xFFD6DEFF),
    tertiary = Gold,
    onTertiary = Color(0xFF241A05),
    background = BgBase,
    onBackground = Color(0xFFF1EFFB),
    surface = Color(0xFF141021),
    onSurface = Color(0xFFF1EFFB),
    surfaceVariant = Color(0xFF1B1730),
    onSurfaceVariant = TextMuted,
    outline = Color(0xFF3A3560),
    outlineVariant = Color(0xFF272244),
    error = Color(0xFFFF7A93),
    onError = Color(0xFF2B0713),
)

@Composable
fun SomenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme) {
        CompositionLocalProvider(LocalContentColor provides DarkScheme.onBackground) {
            content()
        }
    }
}

/** Фон-«туманность»: базовый цвет + мягкие радиальные свечки фиолета, синевы и золота. */
@Composable
fun NebulaBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(BgBase)) {
        Canvas(Modifier.fillMaxSize()) {
            drawGlow(Violet.copy(alpha = 0.30f), Offset(x = size.width * 0.78f, y = -size.height * 0.08f), 560f)
            drawGlow(Indigo.copy(alpha = 0.20f), Offset(x = -size.width * 0.12f, y = size.height * 0.40f), 640f)
            drawGlow(Gold.copy(alpha = 0.07f), Offset(x = size.width * 0.55f, y = size.height * 1.10f), 540f)
        }
    }
}

private fun DrawScope.drawGlow(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center,
    )
}
