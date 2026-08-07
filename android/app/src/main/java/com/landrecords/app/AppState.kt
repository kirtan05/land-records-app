package com.landrecords.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.landrecords.app.ui.theme.Lang
import com.landrecords.app.ui.theme.ThemeMode
import com.landrecords.app.ui.theme.next

/**
 * App-wide persisted preferences, exposed as Compose state so the whole tree
 * recomposes when the user changes language or theme. Land data is never affected.
 */
class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("land_prefs", Context.MODE_PRIVATE)

    private var _lang by mutableStateOf(load("lang", Lang.entries, Lang.BOTH))
    private var _theme by mutableStateOf(load("theme", ThemeMode.entries, ThemeMode.LIGHT))

    val lang: Lang get() = _lang
    val themeMode: ThemeMode get() = _theme

    fun setLang(value: Lang) {
        _lang = value
        prefs.edit().putString("lang", value.name).apply()
    }

    fun cycleLang() = setLang(_lang.next())

    fun setTheme(value: ThemeMode) {
        _theme = value
        prefs.edit().putString("theme", value.name).apply()
    }

    private fun <T : Enum<T>> load(key: String, values: List<T>, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return values.firstOrNull { it.name == name } ?: default
    }
}
