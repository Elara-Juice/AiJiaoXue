package edu.neu.aijiaoxue.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AppColors = lightColorScheme(
    primary = Navy,
    onPrimary = White,
    primaryContainer = PaleBlue,
    onPrimaryContainer = NavyDeep,
    secondary = Blue,
    onSecondary = White,
    secondaryContainer = Color(0xFFEAF2FA),
    onSecondaryContainer = NavyDeep,
    tertiary = Success,
    onTertiary = White,
    background = Mist,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF3F8),
    onSurfaceVariant = MutedInk,
    outline = Line,
    outlineVariant = Color(0xFFE8EDF3),
    error = Danger,
    onError = White,
    errorContainer = Color(0xFFFFEBED),
    onErrorContainer = Color(0xFF7A2D38),
)

private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
@Suppress("UNUSED_PARAMETER")
fun AiJiaoXueTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
