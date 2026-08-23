package com.landrecords.app.ui.components

import androidx.compose.runtime.Composable
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.theme.Lang
import com.landrecords.app.ui.theme.Lr

/** Single-letter stamp glyph: I · V · D · C. */
fun RecordType.stampLetter(): String = when (this) {
    RecordType.INTEGRATED -> "I"
    RecordType.VF712 -> "V"
    RecordType.DEEDS -> "D"
    RecordType.IRCMS -> "C"
}

/** Bilingual record-type name for the current language mode. */
@Composable
fun RecordType.label(): String = when (this) {
    RecordType.INTEGRATED -> Lr(R.string.rt_integrated_gu, R.string.rt_integrated_en)
    RecordType.VF712 -> Lr(R.string.rt_vf712_gu, R.string.rt_vf712_en)
    RecordType.DEEDS -> Lr(R.string.rt_deeds_gu, R.string.rt_deeds_en)
    RecordType.IRCMS -> Lr(R.string.rt_ircms_gu, R.string.rt_ircms_en)
}

/** Uppercased Latin sub-line under the record name (hidden in gu/en modes by the caller). */
fun RecordType.englishSubLabel(): String = englishLabel.uppercase()

/** The unit under the document count, e.g. "period scans" / "cases". */
fun RecordType.unit(lang: Lang, count: Int): String = when (this) {
    RecordType.INTEGRATED -> lang.joinUnit("દસ્તાવેજ", if (count == 1) "document" else "documents")
    RecordType.VF712 -> lang.joinUnit("સમયગાળા સ્કેન", "period scans")
    RecordType.DEEDS -> lang.joinUnit("દસ્તાવેજ", if (count == 1) "deed" else "deeds")
    RecordType.IRCMS -> lang.joinUnit("કેસ", if (count == 1) "case" else "cases")
}

private fun Lang.joinUnit(gu: String, en: String): String = when (this) {
    Lang.GU -> gu
    else -> en // mono uppercase unit reads as a technical label; keep Latin in both/en
}
