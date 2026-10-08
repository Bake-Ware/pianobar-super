package org.pianobarsuper.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The web player's palette: paper, ink, a muted grey-green and the pianobar orange. */
object Palette {
    val Orange = Color(0xFFE68B55)
    val PlayerDark = Color(0xFF172B2B)
}

private val Light = lightColorScheme(
    primary = Color(0xFF172B2B), onPrimary = Color(0xFFF5F4EF),
    secondary = Palette.Orange, onSecondary = Color(0xFF172B2B),
    tertiary = Color(0xFF365A47),
    background = Color(0xFFF5F4EF), onBackground = Color(0xFF172B2B),
    surface = Color(0xFFFAFAF5), onSurface = Color(0xFF172B2B),
    surfaceVariant = Color(0xFFE9ECE1), onSurfaceVariant = Color(0xFF78807B),
    surfaceContainer = Color(0xFFF0F0E8), surfaceContainerHigh = Color(0xFFE9ECE1), surfaceContainerHighest = Color(0xFFE6E9DE),
    surfaceContainerLow = Color(0xFFFAFAF5), surfaceContainerLowest = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E9DE), onSecondaryContainer = Color(0xFF172B2B),
    primaryContainer = Color(0xFF172B2B), onPrimaryContainer = Color(0xFFE3E8DE),
    outline = Color(0xFFC9CCC0), outlineVariant = Color(0xFFDDDED5),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Palette.Orange, onPrimary = Color(0xFF172B2B),
    secondary = Palette.Orange, onSecondary = Color(0xFF172B2B),
    tertiary = Color(0xFFA6C4AB),
    background = Color(0xFF101A1A), onBackground = Color(0xFFE3E8DE),
    surface = Color(0xFF101A1A), onSurface = Color(0xFFE3E8DE),
    surfaceVariant = Color(0xFF20312D), onSurfaceVariant = Color(0xFF9BA99F),
    surfaceContainer = Color(0xFF172323), surfaceContainerHigh = Color(0xFF20312D), surfaceContainerHighest = Color(0xFF2B4036),
    surfaceContainerLow = Color(0xFF131F1F), surfaceContainerLowest = Color(0xFF0C1414),
    secondaryContainer = Color(0xFF2B4036), onSecondaryContainer = Color(0xFFE3E8DE),
    primaryContainer = Color(0xFF2B4036), onPrimaryContainer = Color(0xFFE3E8DE),
    outline = Color(0xFF4A5C55), outlineVariant = Color(0xFF30403B),
    error = Color(0xFFF2B8B5),
)

private val Type = Typography().let {
    it.copy(
        headlineLarge = it.headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun PianobarTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
