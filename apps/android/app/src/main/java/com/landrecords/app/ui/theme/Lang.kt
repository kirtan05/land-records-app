package com.landrecords.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource

/** App-chrome language. Land DATA is never translated — this only affects labels. */
enum class Lang { GU, BOTH, EN }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalLang = staticCompositionLocalOf { Lang.BOTH }

/** Join a Gujarati / English label pair per the current language mode. */
fun Lang.join(gu: String, en: String): String = when (this) {
    Lang.GU -> gu
    Lang.EN -> en
    Lang.BOTH -> "$gu · $en"
}

/** Chrome label from a raw pair, honouring the ambient language. */
@Composable
@ReadOnlyComposable
fun L(gu: String, en: String): String = LocalLang.current.join(gu, en)

/** Chrome label from a `_gu` / `_en` string-resource pair. */
@Composable
@ReadOnlyComposable
fun Lr(@StringRes gu: Int, @StringRes en: Int): String =
    LocalLang.current.join(stringResource(gu), stringResource(en))

/** Cycle order for the header language pill: both → gu → en → both. */
fun Lang.next(): Lang = when (this) {
    Lang.BOTH -> Lang.GU
    Lang.GU -> Lang.EN
    Lang.EN -> Lang.BOTH
}

/** Short badge shown on the header pill. */
fun Lang.badge(): String = when (this) {
    Lang.GU -> "ગુ"
    Lang.BOTH -> "ગુ/EN"
    Lang.EN -> "EN"
}
