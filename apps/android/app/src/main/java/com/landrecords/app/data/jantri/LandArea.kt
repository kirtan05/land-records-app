package com.landrecords.app.data.jantri

import com.landrecords.app.ui.components.guToLatinDigits
import kotlin.math.roundToLong

/**
 * AnyRoR states a parcel's area as hectare-are-square metre, e.g. "૩-૩૧-૮૪".
 * 1 are = 100 m², so that is 3 ha + 31 are + 84 m² = 33,184 m².
 *
 * Everything downstream (unit switching, jantri valuation) works from the m² figure,
 * so the parse lives here alone.
 */
object LandArea {
    const val SQM_PER_ACRE = 4046.856422
    private const val SQM_PER_HECTARE = 10_000.0
    private const val SQM_PER_GUNTHA = 101.171     // 1 guntha = 1/40 acre (1,089 sq ft)

    /**
     * Gunthas per vigha/bigha. The vigha is a CUSTOMARY unit, not a revenue one, and it
     * genuinely differs across Gujarat — 20 guntha is the usual figure, Saurashtra works
     * to 16, and parts of the state use 24–25. 20 (= half an acre, 2,023.43 m²) is the
     * standard, and [format] prints the basis alongside the number so a reader on a
     * different local convention can see immediately that it does not match theirs.
     */
    const val GUNTHA_PER_BIGHA = 20
    private const val SQM_PER_BIGHA = SQM_PER_GUNTHA * GUNTHA_PER_BIGHA

    /** Square metres, or null when the string is absent or not in h-a-m form. */
    fun toSqm(areaGu: String): Double? {
        val parts = areaGu.guToLatinDigits().trim().split('-', '.', '/')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
        if (parts.isEmpty()) return null
        val ha = parts.getOrElse(0) { 0 }
        val are = parts.getOrElse(1) { 0 }
        val sqm = parts.getOrElse(2) { 0 }
        val total = ha * SQM_PER_HECTARE + are * 100.0 + sqm
        return total.takeIf { it > 0 }
    }

    enum class Unit { HA_A_M, ACRE, BIGHA, GUNTHA, SQM;
        fun next(): Unit = entries[(ordinal + 1) % entries.size]
    }

    /** The area rendered in [unit]; [areaGu] is the raw AnyRoR string. */
    fun format(areaGu: String, unit: Unit): String {
        val sqm = toSqm(areaGu) ?: return ""
        return when (unit) {
            Unit.HA_A_M -> "${areaGu.guToLatinDigits()} ha-a-m²"
            Unit.ACRE -> "${trim(sqm / SQM_PER_ACRE, 2)} acre"
            // basis shown inline: the vigha differs by region, so never state it bare
            Unit.BIGHA -> "${trim(sqm / SQM_PER_BIGHA, 2)} vigha (@$GUNTHA_PER_BIGHA guntha)"
            Unit.GUNTHA -> "${trim(sqm / SQM_PER_GUNTHA, 1)} guntha"
            Unit.SQM -> "${grouped(sqm.roundToLong())} m²"
        }
    }

    private fun trim(v: Double, dp: Int): String {
        val s = String.format("%.${dp}f", v)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /** Indian digit grouping: 33,184. */
    fun grouped(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val head = s.dropLast(3)
        val tail = s.takeLast(3)
        val parts = mutableListOf<String>()
        var rest = head
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + tail
    }
}
