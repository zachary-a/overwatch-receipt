package dev.owreceipt.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.owreceipt.core.AppTheme

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 20.dp
    val xl = 28.dp
    val page = 24.dp
}
private val Light = lightColorScheme(
    primary = Color(0xFF232725), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E8E3), onPrimaryContainer = Color(0xFF232725),
    secondary = Color(0xFF606660), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EBE6), onSecondaryContainer = Color(0xFF303630),
    tertiary = Color(0xFF606660),
    background = Color(0xFFF5F5F2), onBackground = Color(0xFF202421),
    surface = Color(0xFFFEFEFB), onSurface = Color(0xFF202421),
    surfaceVariant = Color(0xFFEEEFEB), onSurfaceVariant = Color(0xFF666C66),
    surfaceContainer = Color(0xFFF0F1ED), surfaceContainerHigh = Color(0xFFE9EBE6),
    surfaceContainerHighest = Color(0xFFE3E6E0),
    outline = Color(0xFF7A8079), outlineVariant = Color(0xFFDDE0D9),
    error = Color(0xFF8B3838), errorContainer = Color(0xFFF4E3DF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFE4E7DF), onPrimary = Color(0xFF202420),
    primaryContainer = Color(0xFF363D35), onPrimaryContainer = Color(0xFFE4E7DF),
    secondary = Color(0xFFB9C0B5), onSecondary = Color(0xFF272D26),
    secondaryContainer = Color(0xFF333A31), onSecondaryContainer = Color(0xFFDDE4D7),
    background = Color(0xFF151815), onBackground = Color(0xFFE2E6DC),
    surface = Color(0xFF1D211C), onSurface = Color(0xFFE2E6DC),
    surfaceVariant = Color(0xFF2B3129), onSurfaceVariant = Color(0xFFB6BEB1),
    surfaceContainer = Color(0xFF242923), surfaceContainerHigh = Color(0xFF2D332A),
    surfaceContainerHighest = Color(0xFF373E33),
    outline = Color(0xFF899480), outlineVariant = Color(0xFF3C4537),
)
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)
@Composable
fun ReceiptTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val target = if (dark) Dark else Light
    @Composable fun color(value: Color) = animateColorAsState(value, tween(TransitionMillis), label = "theme").value
    val colors = target.copy(
        primary = color(target.primary), onPrimary = color(target.onPrimary),
        primaryContainer = color(target.primaryContainer), onPrimaryContainer = color(target.onPrimaryContainer),
        secondary = color(target.secondary), onSecondary = color(target.onSecondary),
        secondaryContainer = color(target.secondaryContainer), onSecondaryContainer = color(target.onSecondaryContainer),
        tertiary = color(target.tertiary), onTertiary = color(target.onTertiary),
        tertiaryContainer = color(target.tertiaryContainer), onTertiaryContainer = color(target.onTertiaryContainer),
        background = color(target.background), onBackground = color(target.onBackground),
        surface = color(target.surface), onSurface = color(target.onSurface),
        surfaceVariant = color(target.surfaceVariant), onSurfaceVariant = color(target.onSurfaceVariant),
        surfaceTint = color(target.surfaceTint), inverseSurface = color(target.inverseSurface),
        inverseOnSurface = color(target.inverseOnSurface), inversePrimary = color(target.inversePrimary),
        error = color(target.error), onError = color(target.onError),
        errorContainer = color(target.errorContainer), onErrorContainer = color(target.onErrorContainer),
        outline = color(target.outline), outlineVariant = color(target.outlineVariant),
        surfaceBright = color(target.surfaceBright), surfaceDim = color(target.surfaceDim),
        surfaceContainer = color(target.surfaceContainer), surfaceContainerHigh = color(target.surfaceContainerHigh),
        surfaceContainerHighest = color(target.surfaceContainerHighest),
        surfaceContainerLow = color(target.surfaceContainerLow), surfaceContainerLowest = color(target.surfaceContainerLowest),
    )
    MaterialTheme(colorScheme = colors, typography = Type, content = content)
}
