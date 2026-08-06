package com.landrecords.app.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.landrecords.app.R

/**
 * Space Grotesk (headings/body) + IBM Plex Mono (numbers, all-caps labels) with
 * Noto Sans Gujarati listed in EVERY family as the fallback — mixed
 * Gujarati/Latin strings render boxes otherwise (see docs/PROJECT_NOTES.md).
 *
 * Noto + Plex Mono ship as static .ttf weights; Space Grotesk ships as a single
 * variable .ttf whose weight axis is set per style via [FontVariation]. All SIL OFL.
 */
private val Gujarati = listOf(
    Font(R.font.noto_sans_gujarati_regular, FontWeight.Normal),
    Font(R.font.noto_sans_gujarati_semibold, FontWeight.SemiBold),
)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: FontWeight, axis: Int) =
    Font(
        R.font.space_grotesk,
        weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
    )

val Grotesk = FontFamily(
    listOf(
        grotesk(FontWeight.Normal, 400),
        grotesk(FontWeight.Medium, 500),
        grotesk(FontWeight.SemiBold, 600),
        grotesk(FontWeight.Bold, 700),
    ) + Gujarati,
)

val Mono = FontFamily(
    listOf(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    ) + Gujarati,
)

/** Type scale at the approved "comfy" density. */
object LandType {
    val screenTitle = TextStyle(fontFamily = Grotesk, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 27.sp)
    val surveyHero = TextStyle(fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.03).em, lineHeight = 37.sp)
    val surveyTile = TextStyle(fontFamily = Mono, fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em)
    val body = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val meta = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, lineHeight = 18.sp)
    val metaMono = TextStyle(fontFamily = Mono, fontSize = 13.sp)
    val count = TextStyle(fontFamily = Mono, fontSize = 20.sp)

    /** all-caps label: always Mono, always tracked */
    val label = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.12.em)
    val stamp = TextStyle(fontFamily = Mono, fontSize = 8.5.sp)
}
