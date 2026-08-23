package com.landrecords.app.data.identity

import java.security.MessageDigest

/**
 * Canonical identity layer — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §1.
 *
 * EVERY rule in this file is duplicated, byte-for-byte, in the desktop scrapers at
 * `src/identity.mjs`, and both are held to the same fixture, `tools/identity/vectors.json`.
 * If you change a rule here you MUST change it there, add a vector, and run both tests:
 *
 *     node tools/identity/test.mjs
 *     cd android && ./gradlew :app:testDebugUnitTest
 *
 * The point of the whole file: two machines that scrape the same real record must produce
 * the same string for it, so importing one database into the other is a no-op.
 *
 * Deliberately pure Kotlin — no Android imports — so it can be unit-tested on the JVM and
 * reused by the migration, the merge engine and the fetch queue alike.
 */
object Identity {

    /** Unit separator. Joins uid parts and content-hash cells; never occurs in scraped text. */
    const val US = '\u001F'

    /** Sentinel for a NULL column in a content hash. Distinct from the empty string on purpose. */
    const val NULL_CELL = "\u0000"

    // -----------------------------------------------------------------------
    // §1.2  Survey token
    // -----------------------------------------------------------------------

    private val GU_DIGITS = mapOf(
        '૦' to '0', '૧' to '1', '૨' to '2', '૩' to '3', '૪' to '4',
        '૫' to '5', '૬' to '6', '૭' to '7', '૮' to '8', '૯' to '9',
    )

    /**
     * Gujarati letters that appear as survey SUFFIXES, transliterated to ASCII.
     *
     * The spec's original rule was to strip every non-`[A-Z0-9_]` character. Measured
     * against the 15,293 real dropdown values bundled in `assets/surveys`, that fused 250
     * genuinely different surveys onto shared tokens — e.g. in Bharoda (gj:15:03:029) all
     * eight of `40/1/અ 40/1/ક 40/1/ફ 40/1/બ 40/1/હ 40/1ઈ 40/1ગ 40/1ડ` collapsed onto
     * "40_1", and `1678` collapsed with `1678/અ`. Those are separate parcels with separate
     * owners. So suffix letters are transliterated instead of dropped.
     * See tools/identity/README.md.
     *
     * Every entry is distinct from every other, so no two letters can share a token.
     * Anything unlisted falls through to [foreignChar], which keeps that property for
     * characters the corpus has not shown us yet.
     */
    private val GU_LETTERS = mapOf(
        'અ' to "A", 'આ' to "AA", 'ઇ' to "I", 'ઈ' to "II", 'ઉ' to "U", 'ઊ' to "UU",
        'એ' to "E", 'ઐ' to "AI", 'ઓ' to "O", 'ઔ' to "AU",
        'ક' to "K", 'ખ' to "KH", 'ગ' to "G", 'ઘ' to "GH",
        'ચ' to "C", 'છ' to "CH", 'જ' to "J", 'ઝ' to "JH",
        'ટ' to "TT", 'ઠ' to "TTH", 'ડ' to "D", 'ઢ' to "DH", 'ણ' to "NN",
        'ત' to "T", 'થ' to "TH", 'દ' to "DD", 'ધ' to "DDH", 'ન' to "N",
        'ફ' to "F", 'બ' to "B", 'ભ' to "BH", 'મ' to "M",
        'ય' to "Y", 'ર' to "R", 'લ' to "L", 'ળ' to "LL", 'વ' to "V",
        'શ' to "SH", 'ષ' to "SS", 'સ' to "S", 'હ' to "H",
        // Dependent vowel signs. A sign only ever follows a consonant and an independent
        // vowel only ever starts a syllable, so sharing a form can never fuse two strings.
        'ા' to "AA", 'િ' to "I", 'ી' to "II", 'ુ' to "U", 'ૂ' to "UU",
        'ે' to "E", 'ૈ' to "AI", 'ો' to "O", 'ૌ' to "AU",
        'ં' to "NG", 'ઃ' to "HH",
        // Virama joins two consonants ("ક્ર" = KRA). Kept as a distinct mark rather than
        // dropped, so "ક્ર" and "કર" cannot become the same token.
        '્' to "Q",
    )

    /**
     * Any character with no explicit mapping becomes `U<hex codepoint>` rather than
     * vanishing. Ugly on purpose: visible in a filename, deterministic on both machines,
     * and incapable of silently fusing two different surveys the way stripping did.
     */
    private fun foreignChar(c: Char): String =
        "U" + c.code.toString(16).uppercase().padStart(4, '0')

    /** A "-" that is NOT between two digits — a suffix delimiter (233-અ), not a range. */
    private val SUFFIX_DASH = Regex("(?<![0-9])-|-(?![0-9])")
    private val SEPARATORS = Regex("[\\s/|\\\\]+")
    private val TRIM_SLASH = Regex("^/+|/+$")
    private val PAIKI_BINDS = Regex("p/(?=\\d)")
    private val NON_TOKEN = Regex("[^A-Z0-9_+-]")
    private val UNDERSCORE_RUN = Regex("_+")
    private val TRIM_UNDERSCORE = Regex("^_+|_+$")

    /**
     * The one canonical survey tokenizer. Rules, in this exact order:
     *
     *   `"~~"` → `" "`                    drop AnyRoR's dropdown marker
     *   `૦-૯` → `0-9`                     gujarati digits
     *   `"પૈકી"` → `"p"`, then `"પ"` → `"p"`   the word before the letter, else "પૈકી" leaves "pૈકી"
     *   `"-"` → `"/"` unless between digits   suffix dash delimits; 690-696 is a range
     *   `[\s/|\\]+` → `"/"`               every other separator, including handwritten bars
     *   trim `"/"`
     *   `"p/(?=\d)"` → `"p"`              paiki binds to its number: p/1 → p1
     *   uppercase
     *   `"/"` → `"_"`
     *   transliterate `[^A-Z0-9_+-]`      GU_LETTERS, else the U+XXXX escape — never dropped
     *   `"_+"` → `"_"`                    "101__3" → "101_3"
     *   trim `"_"`                        "100_1_" → "100_1"
     *
     * Two rules differ from the spec as written, because the spec's version was measured to
     * fuse different surveys together (see tools/identity/README.md):
     *   - Gujarati letters are TRANSLITERATED, not stripped: `"845/અ"` → `"845_A"`.
     *   - `"+"` and `"-"` are KEPT: `364+365` is one combined survey and `690-696` a range.
     *
     * The raw form is never lost either way — it is preserved verbatim in `survey_alias`.
     *
     * `uppercase()` is the locale-invariant overload; a Turkish locale must not produce "ı".
     */
    fun surveyToken(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            // "~~" → " "
            if (raw[i] == '~' && i + 1 < raw.length && raw[i + 1] == '~') {
                sb.append(' '); i += 2; continue
            }
            // "પૈકી" → "p" before the bare "પ" rule, or the tail would survive as "pૈકી".
            if (raw.startsWith("પૈકી", i)) {
                sb.append('p'); i += 4; continue
            }
            val c = raw[i]
            sb.append(GU_DIGITS[c] ?: if (c == 'પ') 'p' else c)
            i++
        }
        var s = sb.toString()
        s = SUFFIX_DASH.replace(s, "/")
        s = SEPARATORS.replace(s, "/")
        s = TRIM_SLASH.replace(s, "")
        s = PAIKI_BINDS.replace(s, "p")
        s = s.uppercase()
        s = s.replace('/', '_')
        // Transliterate whatever is left that is not token-safe; never drop it silently.
        s = NON_TOKEN.replace(s) { m ->
            val c = m.value[0]
            GU_LETTERS[c] ?: foreignChar(c)
        }
        s = UNDERSCORE_RUN.replace(s, "_")
        s = TRIM_UNDERSCORE.replace(s, "")
        return s
    }

    // -----------------------------------------------------------------------
    // §1.1  Place id
    // -----------------------------------------------------------------------

    /** Zero-pad a cascade code to its level's width. "3" → "03". Non-numeric codes pass through. */
    private fun padCode(code: String?, width: Int): String {
        val s = (code ?: "").trim()
        if (s.isEmpty() || !s.all { it in '0'..'9' }) return s
        return s.padStart(width, '0')
    }

    /**
     * A coded place id from the AnyRoR/iRCMS cascade codes — the same codes on both sites.
     * Zero-padding is part of the identity ("029" != "29"), so it is applied here rather than
     * trusted from the caller: `placeId("15","3","29") == placeId("15","03","029")`.
     */
    fun placeId(districtCode: String?, talukaCode: String?, villageCode: String?): String =
        "gj:${padCode(districtCode, 2)}:${padCode(talukaCode, 2)}:${padCode(villageCode, 3)}"

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Case- and whitespace-insensitive form of a place name, used ONLY to derive a provisional
     * id. Names are not transliterated: a Gujarati spelling and its English spelling get
     * different provisional ids, and a later place_merge row reconciles them by uid.
     */
    private fun foldName(s: String?): String =
        WHITESPACE_RUN.replace((s ?: "").trim(), " ").lowercase()

    /**
     * A deterministic id for legacy data with no cascade codes (the 188 existing `output/`
     * dirs, and app rows whose crosswalk is ambiguous). Visibly provisional — the `gj?:`
     * prefix — so it can be rewritten to a real coded id later, once, everywhere, by uid.
     */
    fun provisionalPlaceId(district: String?, taluka: String?, village: String?): String {
        val key = "name|" + listOf(district, taluka, village).joinToString("|") { foldName(it) }
        return "gj?:" + sha256Hex(key).substring(0, 12)
    }

    /** True for an id produced by [provisionalPlaceId] — i.e. one still awaiting real codes. */
    fun isProvisionalPlace(id: String?): Boolean = id?.startsWith("gj?:") == true

    // -----------------------------------------------------------------------
    // §1.2 / §1.3  Uids
    // -----------------------------------------------------------------------

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(d.size * 2)
        for (i in d.indices) {
            val b = d[i].toInt() and 0xff
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0f]
        }
        return String(out)
    }

    /** Readable, not hashed — a survey uid is a debuggable path: `gj:15:03:029/221_P`. */
    fun surveyUid(placeId: String, surveyRaw: String?): String = "$placeId/${surveyToken(surveyRaw)}"

    /**
     * `uid(kind, parts…) = kind + "_" + hex(sha256(kind ␟ parts…))[0:24]`
     *
     * Parts are joined with the unit separator so ("a","bc") and ("ab","c") differ and empty
     * parts stay significant. Nothing that varies between two scrapes of the same real record
     * may appear here — no timestamps, file paths, DOM ordinals, sr_no, case_index or
     * selectIndex. Those are payload columns, never identity.
     */
    fun uid(kind: String, vararg parts: Any?): String {
        val material = StringBuilder(kind)
        for (p in parts) {
            material.append(US).append(p?.toString() ?: "")
        }
        return kind + "_" + sha256Hex(material.toString()).substring(0, 24)
    }

    fun recordSetUid(surveyUid: String, recordType: String): String = uid("rs", surveyUid, recordType)

    fun caseUid(surveyUid: String, dataId: String): String = uid("ic", surveyUid, "ircms", dataId)

    fun orderUid(caseUid: String, ordinal: Int): String = uid("io", caseUid, ordinal)

    fun vfScanUid(surveyUid: String, period: String, thok: String, block: String, oldSurvey: String): String =
        uid("vs", surveyUid, "vf712", period, thok, block, oldSurvey)

    fun entryUid(surveyUid: String, entryNumber: String): String = uid("en", surveyUid, "entry", entryNumber)

    fun deedUid(placeId: String, office: String, docYear: String, docNo: String): String =
        uid("dd", placeId, "deed", office, docYear, docNo)

    fun deedPartyUid(deedUid: String, partyType: String, partyName: String, ordinal: Int): String =
        uid("dp", deedUid, partyType, partyName, ordinal)

    fun deedLinkUid(deedUid: String, surveyUid: String): String = uid("dl", deedUid, surveyUid)

    fun surveyLinkUid(currentSurveyUid: String, oldToken: String): String =
        uid("sl", currentSurveyUid, oldToken)

    /** Blobs are addressed by the UNtruncated hash of their bytes — no prefix, no slicing. */
    fun blobUid(bytes: ByteArray): String = sha256Hex(bytes)

    // -----------------------------------------------------------------------
    // §3  Content hash
    // -----------------------------------------------------------------------

    /**
     * One column's contribution to a content hash. SQLite is loosely typed and Kotlin/JS
     * disagree about number formatting, so the mapping is pinned:
     *   null    → " "  (a distinct value, NOT "")
     *   Boolean → "1"/"0"   (matches how Room stores them)
     *   integer → decimal, no exponent, no trailing ".0"
     *   other   → the string itself
     *
     * Floating-point values are rejected rather than formatted: no two languages agree on
     * their text form, and nothing in this schema is legitimately a float.
     */
    fun canonicalCell(v: Any?): String = when (v) {
        null -> NULL_CELL
        is Boolean -> if (v) "1" else "0"
        is Int, is Long, is Short, is Byte -> v.toString()
        is Double, is Float ->
            throw IllegalArgumentException("float in content hash: $v — store it as text")
        else -> v.toString()
    }

    /**
     * `content_hash(row) = sha256(join(canonical(col) for col in SYNCED_COLS[table], ␟))`
     *
     * [values] MUST be in the table's fixed SYNCED_COLS order, not SQLite's column order —
     * the two implementations have to agree on order or every row looks changed. Excludes
     * uid / content_hash / updated_at / origin; INCLUDES `deleted`.
     */
    fun contentHash(values: List<Any?>): String =
        sha256Hex(values.joinToString(US.toString()) { canonicalCell(it) })
}
