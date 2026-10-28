package edu.neu.aijiaoxue.ui.theme

import androidx.compose.ui.graphics.Color

internal val WarmBackground = Color(0xFFFBFAF8)
internal val BrandOrange = Color(0xFFC2410C)
internal val PaleOrange = Color(0xFFFFF1E6)
internal val Ink = Color(0xFF20242C)
internal val MutedInk = Color(0xFF626873)

@androidx.compose.runtime.Immutable
data class AppSemanticColors(
    val accentBright: Color = Color(0xFFFF8A3D),
    val onAccentBright: Color = Color(0xFF3B1B0B),
    val success: Color = Color(0xFF166534),
    val warning: Color = Color(0xFF92400E),
    val info: Color = Color(0xFF1D4ED8),
)

internal val LocalAppSemanticColors = androidx.compose.runtime.staticCompositionLocalOf { AppSemanticColors() }
object AppTheme {
    val colors: AppSemanticColors
        @androidx.compose.runtime.Composable get() = LocalAppSemanticColors.current
}
