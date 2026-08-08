package com.landrecords.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

    // ── Mark-colour custom names (id -> user name; absent = use the colour's built-in default) ──
    private val markNames = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(MARK_NAME_PREFIX) && v is String && v.isNotBlank()) put(k.removePrefix(MARK_NAME_PREFIX), v)
        }
    }

    /** The user's name for a mark colour, or null if they haven't renamed it. Observable in Compose. */
    fun markName(id: String): String? = markNames[id]

    /** Set (blank clears) a mark colour's name; capped so it stays chip-sized. */
    fun setMarkName(id: String, name: String) {
        val trimmed = name.trim().take(MARK_NAME_MAX)
        if (trimmed.isBlank()) {
            markNames.remove(id)
            prefs.edit().remove("$MARK_NAME_PREFIX$id").apply()
        } else {
            markNames[id] = trimmed
            prefs.edit().putString("$MARK_NAME_PREFIX$id", trimmed).apply()
        }
    }

    private fun <T : Enum<T>> load(key: String, values: List<T>, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return values.firstOrNull { it.name == name } ?: default
    }

    private companion object {
        const val MARK_NAME_PREFIX = "markname_"
        const val MARK_NAME_MAX = 20
    }
}
