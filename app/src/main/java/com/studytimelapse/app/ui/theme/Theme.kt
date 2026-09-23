package com.studytimelapse.app.ui.theme

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.studytimelapse.app.R
import com.studytimelapse.app.data.prefs.ThemeMode

/*
 * Visual language: the structure of the attached DESIGN.md (gallery-like flat surfaces, no
 * shadows, 28dp cards, pill controls, large tightly-tracked semibold type, one accent used
 * sparingly) re-tuned to the product brief: warm paper instead of cold white, burnt-orange
 * "Launch Orange" #B64400 instead of blue, warm charcoal instead of black at night.
 */

@Immutable
data class ExtraColors(
    val slate: Color,
    val hairline: Color,
    val sage: Color,
    val heat: List<Color>,
    val studyBackground: Color,
    val studyText: Color,
)

private val LightExtra = ExtraColors(
    slate = Color(0xFF6F6A63),
    hairline = Color(0xFFE6E0D6),
    sage = Color(0xFF6B7A58),
    heat = listOf(
        Color(0xFFF0EAE1), Color(0xFFF1D4B4), Color(0xFFE5AE7C),
        Color(0xFFD08448), Color(0xFFB05A22), Color(0xFF7E3A12),
    ),
    studyBackground = Color(0xFF000000),
    studyText = Color(0xFF6B5E52),
)

private val DarkExtra = ExtraColors(
    slate = Color(0xFFA99F92),
    hairline = Color(0xFF3A342D),
    sage = Color(0xFF9FAE8A),
    heat = listOf(
        Color(0xFF2B2620), Color(0xFF4B3321), Color(0xFF6F4424),
        Color(0xFF975628), Color(0xFFC26C30), Color(0xFFE88E4B),
    ),
    studyBackground = Color(0xFF000000),
    studyText = Color(0xFF6B5E52),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB64400),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7E4D6),
    onPrimaryContainer = Color(0xFF4A1C00),
    secondary = Color(0xFF6B7A58),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6EBDD),
    onSecondaryContainer = Color(0xFF232C18),
    tertiary = Color(0xFF8A5A3C),
    background = Color(0xFFFAF8F4),
    onBackground = Color(0xFF1D1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1B19),
    surfaceVariant = Color(0xFFF2EEE8),
    onSurfaceVariant = Color(0xFF6F6A63),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4EF),
    surfaceContainerHigh = Color(0xFFF2EEE8),
    surfaceContainerHighest = Color(0xFFECE7DF),
    outline = Color(0xFF8A847C),
    outlineVariant = Color(0xFFE6E0D6),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE38A4E),
    onPrimary = Color(0xFF3A1600),
    primaryContainer = Color(0xFF4D2B16),
    onPrimaryContainer = Color(0xFFFFDCC6),
    secondary = Color(0xFF9FAE8A),
    onSecondary = Color(0xFF1E2614),
    secondaryContainer = Color(0xFF343D29),
    onSecondaryContainer = Color(0xFFE0E8D3),
    tertiary = Color(0xFFD7A57F),
    background = Color(0xFF1A1714),
    onBackground = Color(0xFFECE4D9),
    surface = Color(0xFF24201B),
    onSurface = Color(0xFFECE4D9),
    surfaceVariant = Color(0xFF2E2923),
    onSurfaceVariant = Color(0xFFA99F92),
    surfaceContainer = Color(0xFF24201B),
    surfaceContainerLow = Color(0xFF1F1B17),
    surfaceContainerHigh = Color(0xFF2E2923),
    surfaceContainerHighest = Color(0xFF38322B),
    outline = Color(0xFF8C8276),
    outlineVariant = Color(0xFF3A342D),
    error = Color(0xFFF2B8B5),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** Tabular figures keep the ticking timer from jittering sideways. */
private const val TABULAR = "tnum"

private fun style(size: Int, weight: Int, lineHeight: Float, tracking: Float, tabular: Boolean = false) = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = if (tabular) TABULAR else null,
)

val AppTypography = Typography(
    displayLarge = style(64, 600, 1.05f, -0.02f, tabular = true),
    displayMedium = style(44, 600, 1.08f, -0.015f, tabular = true),
    displaySmall = style(36, 600, 1.1f, -0.012f, tabular = true),
    headlineLarge = style(32, 600, 1.14f, -0.01f),
    headlineMedium = style(28, 600, 1.14f, -0.008f),
    headlineSmall = style(24, 600, 1.17f, -0.004f),
    titleLarge = style(21, 600, 1.2f, 0.011f),
    titleMedium = style(17, 600, 1.24f, -0.022f),
    titleSmall = style(14, 600, 1.29f, -0.016f),
    bodyLarge = style(17, 400, 1.47f, -0.022f),
    bodyMedium = style(14, 400, 1.43f, -0.016f),
    bodySmall = style(12, 400, 1.33f, -0.01f),
    labelLarge = style(14, 500, 1.29f, -0.016f),
    labelMedium = style(12, 500, 1.33f, -0.01f),
    labelSmall = style(11, 500, 1.33f, 0f),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** True when the user turned animations off in system settings (accessibility). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun StudyTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Status/navigation bar icons follow the app theme, not just the system theme.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalExtraColors provides if (dark) DarkExtra else LightExtra,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val section = 32.dp
    val gutter = 20.dp
}
