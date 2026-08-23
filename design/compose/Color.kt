package com.landrecords.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * "Cadastre" palette — approved design direction.
 * Neutrals carry a slight cool green bias; the single accent is a surveyor ochre.
 * No elevation anywhere: separation comes from 1dp [line] borders.
 */
@Immutable
data class LandColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val hair: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
)

val LightLandColors = LandColors(
    bg = Color(0xFFE9EEEC),
    surface = Color(0xFFFBFCFB),
    surfaceAlt = Color(0xFFDFE6E4),
    ink = Color(0xFF0F1614),
    ink2 = Color(0xFF4B5754),
    ink3 = Color(0xFF7C8785),
    line = Color(0xFFC9D3D0),
    hair = Color(0xFFDCE3E1),
    accent = Color(0xFFB4531B),
    accentSoft = Color(0xFFF7E7DA),
    onAccent = Color(0xFFFFF9F4),
)

val DarkLandColors = LandColors(
    bg = Color(0xFF0D1214),
    surface = Color(0xFF151B1E),
    surfaceAlt = Color(0xFF1E2629),
    ink = Color(0xFFE9EEEE),
    ink2 = Color(0xFFA2AFB0),
    ink3 = Color(0xFF6F7C7E),
    line = Color(0xFF283336),
    hair = Color(0xFF212A2D),
    accent = Color(0xFFE58A55),
    accentSoft = Color(0xFF2A1C13),
    onAccent = Color(0xFF150D07),
)

val LocalLandColors = staticCompositionLocalOf { LightLandColors }

/** Material's scheme is derived so M3 components inherit the same palette. */
fun LandColors.toMaterialLight() = lightColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = accent,
    background = bg, onBackground = ink, surface = surface, onSurface = ink,
    surfaceVariant = surfaceAlt, onSurfaceVariant = ink2, outline = line, outlineVariant = hair,
)

fun LandColors.toMaterialDark() = darkColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = accent,
    background = bg, onBackground = ink, surface = surface, onSurface = ink,
    surfaceVariant = surfaceAlt, onSurfaceVariant = ink2, outline = line, outlineVariant = hair,
)
