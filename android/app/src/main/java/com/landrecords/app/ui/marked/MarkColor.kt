package com.landrecords.app.ui.marked

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.join

/**
 * The fixed palette of export marks (6 colours). A record carries one mark id (stored in
 * [com.landrecords.app.data.db.RecordEntity.mark]); the Marked screen groups by it so dad can send
 * or print everything of one colour together.
 *
 * Each colour has a built-in default name, but the user can rename it (persisted in
 * [com.landrecords.app.AppState.markName]) e.g. "To print", "Ramesh" — use [label] to read the
 * effective name. The [swatch] tones read on BOTH the light (#FBFCFB) and dark (#151B1E) card
 * surfaces, so a single value per colour works without theme plumbing — these are functional
 * highlighter colours, deliberately outside the ochre "Cadastre" chrome palette.
 */
enum class MarkColor(
    val id: String,
    val labelGu: String,
    val labelEn: String,
    val swatch: Color,
) {
    RED("red", "લાલ", "Red", Color(0xFFD64545)),
    AMBER("amber", "કેસરી", "Amber", Color(0xFFE0940C)),
    GREEN("green", "લીલો", "Green", Color(0xFF3E9E5B)),
    BLUE("blue", "વાદળી", "Blue", Color(0xFF3B7DD8)),
    PURPLE("purple", "જાંબલી", "Purple", Color(0xFF9B5DE5)),
    TEAL("teal", "ફિરોજી", "Teal", Color(0xFF14A9A0));

    /** The default (built-in) name in the current language, before any user rename. */
    @Composable
    fun defaultLabel(): String = LocalLang.current.join(labelGu, labelEn)

    /** Effective display name: the user's custom name if set, else the built-in default. */
    @Composable
    fun label(): String = landApp().appState.markName(id) ?: defaultLabel()

    companion object {
        val ordered: List<MarkColor> = entries.toList()
        fun from(id: String?): MarkColor? = if (id == null) null else entries.firstOrNull { it.id == id }

        /** Max length of a user-assigned colour name (keeps chips/menus fitting the phone UI). */
        const val NAME_MAX = 20
    }
}
