package edu.neu.aijiaoxue.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = BrandOrange, onPrimary = Color.White,
    primaryContainer = PaleOrange, onPrimaryContainer = Color(0xFF9A3412),
    secondary = MutedInk, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3F4F6), onSecondaryContainer = Ink,
    tertiary = Color(0xFF166534), onTertiary = Color.White,
    background = WarmBackground, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFF3F4F6), onSurfaceVariant = MutedInk,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White, surfaceContainerLow = WarmBackground,
    surfaceContainer = Color(0xFFF3F4F6), surfaceContainerHigh = Color(0xFFEBEDF0),
    surfaceContainerHighest = Color(0xFFE3E6EA),
    outline = Color(0xFF737982), outlineVariant = Color(0xFFD8DCE2),
    error = Color(0xFFB91C1C), onError = Color.White,
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB68C), onPrimary = Color(0xFF562000),
    primaryContainer = Color(0xFF713315), onPrimaryContainer = Color(0xFFFFDBC7),
    background = Color(0xFF191817), onBackground = Color(0xFFF0EDE9),
    surface = Color(0xFF211F1D), onSurface = Color(0xFFF0EDE9),
    onSurfaceVariant = Color(0xFFCDC6C0),
    surfaceContainer = Color(0xFF2A2724), surfaceTint = Color.Transparent,
)

@Composable
fun AiJiaoXueTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val semantic = if (darkTheme) AppSemanticColors(
        success = Color(0xFF86EFAC), warning = Color(0xFFFCD34D), info = Color(0xFF93C5FD),
    ) else AppSemanticColors()
    CompositionLocalProvider(LocalAppSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme, typography = Typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ), content = content,
        )
    }
}
