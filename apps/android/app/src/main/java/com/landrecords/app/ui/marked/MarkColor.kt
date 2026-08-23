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

/**
 * A resolved export mark: either one of the 6 built-in [MarkColor]s or a colour the user added.
 * Everywhere that used to take a [MarkColor] now takes this, so custom colours behave identically.
 */
data class MarkSwatch(
    val id: String,
    val color: Color,
    val custom: Boolean,
    private val builtinGu: String? = null,
    private val builtinEn: String? = null,
) {
    @Composable
    fun defaultLabel(): String =
        if (builtinEn != null) LocalLang.current.join(builtinGu ?: builtinEn, builtinEn)
        else LocalLang.current.join("રંગ", "Colour")

    /** Effective display name: the user's custom name if set, else the built-in / generic default. */
    @Composable
    fun label(): String = landApp().appState.markName(id) ?: defaultLabel()
}

/** Unifies the built-in [MarkColor]s with the user's custom colours (from AppState). */
object Marks {
    @Composable
    fun all(): List<MarkSwatch> =
        MarkColor.ordered.map { MarkSwatch(it.id, it.swatch, false, it.labelGu, it.labelEn) } +
            landApp().appState.customMarks.map { MarkSwatch(it.id, Color(it.argb), true) }

    @Composable
    fun from(id: String?): MarkSwatch? {
        if (id.isNullOrBlank()) return null
        return all().firstOrNull { it.id == id }
    }

    /** Extra preset colours offered in the "Add colour" picker (a spread beyond the 6 built-ins). */
    val extraPalette: List<Color> = listOf(
        Color(0xFFEF6F6F), Color(0xFFF08A3C), Color(0xFFF2C037), Color(0xFF9CCC3B),
        Color(0xFF57B368), Color(0xFF3FBFA8), Color(0xFF35B3C9), Color(0xFF5B8DEF),
        Color(0xFF6C6CE0), Color(0xFF8E63D6), Color(0xFFB861C4), Color(0xFFE06699),
        Color(0xFFCE5B5B), Color(0xFF9A6A4B), Color(0xFF6E7A88), Color(0xFF8A9A3B),
        Color(0xFF3E7D5A), Color(0xFF34608F),
    )
}
