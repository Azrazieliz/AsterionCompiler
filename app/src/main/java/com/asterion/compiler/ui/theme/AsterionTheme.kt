package com.asterion.compiler.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.asterion.compiler.app.ThemePreference

object AsterionPalette {
    val MatteBlack = Color(0xFF080808)
    val Carbon = Color(0xFF141414)
    val Graphite = Color(0xFF1A1A1A)
    val MetallicGold = Color(0xFFB89B5F)
    val AntiqueGold = Color(0xFF8A7244)
    val Amber = Color(0xFFDDBA78)
    val Ivory = Color(0xFFF2F2F2)
    val MutedIvory = Color(0xFFC9C9C9)
    val Outline = Color(0xFF3F3F3B)
    val LightSilver = Color(0xFFE5E5E5)
}

private val AsterionColorScheme = darkColorScheme(
    primary = AsterionPalette.MetallicGold,
    onPrimary = AsterionPalette.MatteBlack,
    primaryContainer = AsterionPalette.AntiqueGold,
    onPrimaryContainer = AsterionPalette.Ivory,
    secondary = AsterionPalette.LightSilver,
    onSecondary = AsterionPalette.MatteBlack,
    background = AsterionPalette.MatteBlack,
    onBackground = AsterionPalette.Ivory,
    surface = AsterionPalette.Carbon,
    onSurface = AsterionPalette.Ivory,
    surfaceVariant = AsterionPalette.Graphite,
    onSurfaceVariant = AsterionPalette.MutedIvory,
    outline = AsterionPalette.Outline,
    error = AsterionPalette.LightSilver,
    onError = AsterionPalette.MatteBlack,
)

private val AsterionLightColorScheme = lightColorScheme(
    primary = AsterionPalette.AntiqueGold,
    onPrimary = AsterionPalette.Ivory,
    primaryContainer = AsterionPalette.MetallicGold,
    onPrimaryContainer = AsterionPalette.MatteBlack,
    secondary = AsterionPalette.LightSilver,
    onSecondary = AsterionPalette.MatteBlack,
    background = AsterionPalette.Ivory,
    onBackground = AsterionPalette.MatteBlack,
    surface = Color(0xFFF7F7F3),
    onSurface = AsterionPalette.MatteBlack,
    surfaceVariant = Color(0xFFE8E3D8),
    onSurfaceVariant = AsterionPalette.MatteBlack,
    outline = AsterionPalette.Outline,
    error = AsterionPalette.LightSilver,
    onError = AsterionPalette.MatteBlack,
)

private val AsterionTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    ),
)

@Composable
fun AsterionTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themePreference) {
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDarkTheme) AsterionColorScheme else AsterionLightColorScheme,
        typography = AsterionTypography,
        content = content,
    )
}