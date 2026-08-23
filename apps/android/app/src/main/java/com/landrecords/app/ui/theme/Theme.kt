package com.landrecords.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Ambient access to the Cadastre palette: `Land.colors.accent`, etc. */
object Land {
    val colors: LandColors
        @Composable @ReadOnlyComposable get() = LocalLandColors.current
}

private val LandTypography = Typography(
    titleLarge = LandType.screenTitle,
    bodyLarge = LandType.body,
    bodyMedium = LandType.meta,
    labelSmall = LandType.label,
)

@Composable
fun LandTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    lang: Lang = Lang.BOTH,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colors = if (dark) DarkLandColors else LightLandColors
    val scheme = if (dark) colors.toMaterialDark() else colors.toMaterialLight()

    CompositionLocalProvider(
        LocalLandColors provides colors,
        LocalLang provides lang,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = LandTypography,
            content = content,
        )
    }
}
