package com.immersive.ui.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Svate theme — a refined, fixed light scheme built on [SvateColors].
 *
 * Dynamic color (Material You) is intentionally OFF: it would tint Material components with
 * the wallpaper palette and break the controlled neutral + green look. The app is light-only
 * by product decision, so the system dark setting is ignored.
 */
private val SvateLightScheme = lightColorScheme(
    primary = SvateColors.Accent,
    onPrimary = SvateColors.TextOnAccent,
    primaryContainer = SvateColors.AccentSoft,
    onPrimaryContainer = SvateColors.AccentDeep,
    secondary = SvateColors.Accent,
    onSecondary = SvateColors.TextOnAccent,
    background = SvateColors.Canvas,
    onBackground = SvateColors.TextPrimary,
    surface = SvateColors.Surface,
    onSurface = SvateColors.TextPrimary,
    surfaceVariant = SvateColors.SurfaceMuted,
    onSurfaceVariant = SvateColors.TextSecondary,
    outline = SvateColors.Border,
    outlineVariant = SvateColors.Divider,
    error = SvateColors.Danger,
    onError = Color.White,
)

@Composable
fun UINavTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SvateLightScheme,
        typography = Typography,
        content = content,
    )
}

/**
 * Glassmorphism helpers. Both bars are translucent vertical gradients so the conversation
 * scrolls visibly behind them — the frosted, layered look — without a third-party blur lib.
 */
fun glassTopBrush(): Brush = Brush.verticalGradient(
    listOf(
        SvateColors.GlassTint.copy(alpha = 0.97f),
        SvateColors.GlassTint.copy(alpha = 0.82f),
    ),
)

fun glassBottomBrush(): Brush = Brush.verticalGradient(
    listOf(
        SvateColors.GlassTint.copy(alpha = 0.0f),
        SvateColors.GlassTint.copy(alpha = 0.86f),
        SvateColors.GlassTint.copy(alpha = 0.97f),
    ),
)
