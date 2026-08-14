package com.landrecords.app.data.place

/**
 * Tolerant place-name matching — the Kotlin port of the `nn()`/`pick()` pair inside
 * [com.landrecords.app.web.AnyRorInjection.prefillStepJs].
 *
 * That JS is proven against the live AnyRoR cascade; this is the same algorithm applied to the
 * bundled catalogue, so a village the app can *fetch* is also a village the app can *resolve to
 * codes*. Before this existed the two disagreed, and Valetva was the proof:
 *
 * ```
 * stored     taluka "Nadiad Gramya"     village "Valetva" / "વળેટવા"
 * catalogue  taluka "Nadiad" / "નડીઆદ ગ્રામ્ય"   village "Valetva" / "વલેટવા"
 * ```
 *
 * Every level failed an exact compare — the English taluka carries a "Gramya" qualifier the
 * catalogue keeps only in Gujarati, the two Gujarati spellings differ (નડિયાદ vs નડીઆદ), and the
 * village differs by ળ vs લ. [PlaceNames.canonical] gave up, so the place fell back to a
 * provisional `gj?:` id even though its codes were sitting in the shipped asset.
 *
 * **It still never guesses.** Every stage requires a UNIQUE winner; anything ambiguous returns
 * null and the caller falls back to a provisional id. Merging two genuinely different villages
 * would corrupt real land records, so "don't know" always beats a guess.
 */
object CascadeMatch {

    private val GU_DIGITS = "૦૧૨૩૪૫૬૭૮૯"

    /**
     * Normalize for comparison, exactly as the injector's `nn()` does:
     * Gujarati digits → ASCII, પ→p, and the three vowel/consonant pairs that the government
     * data uses interchangeably — ળ→લ, ી→િ, ૂ→ુ — then lowercase and drop everything that is
     * not a letter or digit.
     */
    fun nn(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            val d = GU_DIGITS.indexOf(c)
            when {
                d >= 0 -> sb.append(d)
                c == 'પ' -> sb.append('p')
                c == 'ળ' -> sb.append('લ')
                c == 'ી' -> sb.append('િ')
                c == 'ૂ' -> sb.append('ુ')
                else -> sb.append(c)
            }
        }
        return sb.toString().lowercase().filter { it.isLetterOrDigit() }
    }

    /** Split into comparable tokens, treating the usual separators as spaces. */
    fun tokens(s: String?): List<String> =
        (s ?: "").replace(Regex("[()\\[\\].,\\-/_]+"), " ")
            .split(Regex("\\s+"))
            .map { nn(it) }
            .filter { it.isNotEmpty() }

    /**
     * Qualifiers that distinguish a rural entry from its urban twin. Anchoring on these can
     * only ever separate ગ્રામ્ય (rural) from શહેર (city) / નગર — never conflate them.
     */
    private val QUALIFIERS = listOf("ગ્રામ્ય", "શહેર", "નગર", "નગરપાલિકા").map { nn(it) }

    /**
     * Pick the single candidate matching [want], or null if nothing is unambiguous.
     *
     * [names] gives every name a candidate answers to (its English AND Gujarati spelling), so a
     * place written in either script resolves against a catalogue written in the other.
     *
     * Stages, each requiring a unique winner:
     *  1. exact normalized equality
     *  2. unique substring, either direction ("Nadiad" ⊂ "Nadiad Gramya")
     *  3. unique token-subset — every wanted token appears in the candidate
     *  4. unique reverse token-subset — the catalogue name is shorter than ours
     *  5. unique qualifier anchor — same ગ્રામ્ય/શહેર marker
     */
    fun <T> pick(want: String?, candidates: List<T>, names: (T) -> List<String>): T? {
        val w = nn(want)
        if (w.isEmpty() || candidates.isEmpty()) return null

        fun uniq(matching: List<T>): T? = matching.distinct().singleOrNull()

        uniq(candidates.filter { c -> names(c).any { nn(it) == w } })?.let { return it }

        uniq(
            candidates.filter { c ->
                names(c).any { n ->
                    val t = nn(n)
                    t.isNotEmpty() && (t.contains(w) || w.contains(t))
                }
            },
        )?.let { return it }

        val wt = tokens(want)
        if (wt.isEmpty()) return null

        uniq(
            candidates.filter { c ->
                names(c).any { n -> val t = nn(n); wt.all { t.contains(it) } }
            },
        )?.let { return it }

        uniq(
            candidates.filter { c ->
                names(c).any { n ->
                    val ot = tokens(n)
                    ot.isNotEmpty() && ot.all { wt.contains(it) }
                }
            },
        )?.let { return it }

        val wq = wt.filter { it in QUALIFIERS }
        if (wq.isNotEmpty()) {
            uniq(
                candidates.filter { c ->
                    names(c).any { n -> val ot = tokens(n); wq.all { ot.contains(it) } }
                },
            )?.let { return it }
        }
        return null
    }
}
