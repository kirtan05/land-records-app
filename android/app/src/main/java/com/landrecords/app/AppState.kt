package com.landrecords.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.landrecords.app.ui.theme.Lang
import com.landrecords.app.ui.theme.ThemeMode
import com.landrecords.app.ui.theme.next
import org.json.JSONArray
import org.json.JSONObject

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

    // ── User-added mark colours (beyond the 6 built-ins) ────────────────────────────────────────
    /** A custom export colour the user added: a stable id + its ARGB. Names reuse [markName]. */
    data class CustomMark(val id: String, val argb: Int)

    private var markSeq = prefs.getInt(MARK_SEQ, 0)
    private val _customMarks = mutableStateListOf<CustomMark>().apply {
        runCatching {
            prefs.getString(CUSTOM_MARKS, null)?.let { json ->
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CustomMark(o.getString("id"), o.getInt("argb")))
                }
            }
        }
    }

    /** The user-added colours, in add order. Observable in Compose. */
    val customMarks: List<CustomMark> get() = _customMarks

    /** Add a colour and return its new (never-reused) id. */
    fun addCustomMark(argb: Int): String {
        markSeq += 1
        val id = "c$markSeq"
        _customMarks.add(CustomMark(id, argb))
        prefs.edit().putInt(MARK_SEQ, markSeq).apply()
        persistCustomMarks()
        return id
    }

    /** Remove a user-added colour (its name is cleared too). Built-in colours can't be removed. */
    fun removeCustomMark(id: String) {
        if (_customMarks.removeAll { it.id == id }) {
            setMarkName(id, "")
            persistCustomMarks()
        }
    }

    private fun persistCustomMarks() {
        val arr = JSONArray()
        _customMarks.forEach { arr.put(JSONObject().put("id", it.id).put("argb", it.argb)) }
        prefs.edit().putString(CUSTOM_MARKS, arr.toString()).apply()
    }

    private fun <T : Enum<T>> load(key: String, values: List<T>, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return values.firstOrNull { it.name == name } ?: default
    }

    private companion object {
        const val MARK_NAME_PREFIX = "markname_"
        const val MARK_NAME_MAX = 20
        const val CUSTOM_MARKS = "custom_marks"
        const val MARK_SEQ = "mark_seq"
    }
}
