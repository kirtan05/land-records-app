package com.landrecords.app.data.sync

/**
 * The synced schema — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §1–§3.
 *
 * Mirrored byte-for-byte on the desktop in `src/sync-schema.mjs`, and both are held to the
 * `syncedCols` section of `tools/identity/vectors.json`.
 *
 * [syncedCols] is the FIXED, ORDERED list of columns that make up a row's content hash. It
 * is not Room's column order and must never be reordered casually: changing the order
 * changes every content_hash, which makes every row on the other machine look modified.
 *
 * Excluded from every list, deliberately:
 *  - `uid` — it IS the identity, not part of the content
 *  - `content_hash` — derived from this list
 *  - `updated_at` — a clock, not content; two machines will differ
 *  - `origin` — which device wrote it; not a fact about the land
 *  - anything locally derived: file paths, `fetched_at`, row ordinals, `sr_no`,
 *    `case_index`, VF-7/12 `selectIndex`
 *
 * Included in every list: `deleted`, because a tombstone is content (§3).
 */
object SyncSchema {

    /** Columns every synced table carries. `deleted` is the only one inside the content hash. */
    val SYNC_META = listOf("uid", "content_hash", "updated_at", "origin", "deleted")

    /**
     * table → ordered content columns. `deleted` is appended by [syncedCols] so it can never
     * be forgotten when a table is added.
     */
    private val CONTENT_COLS: Map<String, List<String>> = linkedMapOf(
        // ---- §1.1 place ---------------------------------------------------
        // uid = place_id ("gj:15:03:029", or "gj?:…" while provisional)
        "place" to listOf("district_code", "taluka_code", "village_code"),
        // Names are attributes, never keys — this is what kills the Nadiad duplicate for good.
        // uid = hash("pn", place_id, script, source)
        "place_name" to listOf("place_id", "script", "source", "name"),
        // Rewrites a provisional gj?: id onto a real coded one, once, everywhere, by uid.
        "place_merge" to listOf("from_place_id", "to_place_id"),

        // ---- §1.2 survey --------------------------------------------------
        // uid = survey_uid = place_id + "/" + token
        "survey" to listOf("place_id", "token", "survey_no", "area", "assessment", "tenure", "land_use", "as_of"),
        // Every original raw form, including the exact <option value> ("226/p૧ ~~ ",
        // trailing space and all) that selectOption needs verbatim.
        "survey_alias" to listOf("survey_uid", "raw", "source"),

        // ---- §2 old survey numbers: USER KNOWLEDGE, not scraped data ------
        // Scrapers may only insert state='candidate', and a candidate may never overwrite
        // confirmed/rejected/manual. Enforced in MergeEngine.decide.
        "survey_link" to listOf("current_survey_uid", "old_token", "state", "source"),

        // ---- §1.3 everything below survey ---------------------------------
        "record_set" to listOf("survey_uid", "record_type", "doc_count", "as_of"),
        "ircms_case" to listOf(
            "survey_uid", "data_id", "case_no", "case_status", "office", "dtv", "parties", "survno",
            "disposal_date", "disposal_type", "no_appellant", "court_no",
        ),
        "ircms_order" to listOf("case_uid", "ordinal", "blob_sha"),
        "vf_scan" to listOf("survey_uid", "period", "thok", "block", "old_survey", "blob_sha"),
        "entry" to listOf("survey_uid", "entry_number", "entry_date", "entry_type", "detail", "blob_sha"),
        // A deed's identity is office+doc_year+doc_no under a PLACE, never a survey: one
        // document can touch several surveys and several parties (confirmed at Bhalej 174/પૈકી1).
        "deed" to listOf("place_id", "office", "doc_year", "doc_no", "doc_date", "doc_type", "detail"),
        "deed_party" to listOf("deed_uid", "party_type", "party_name", "ordinal"),
        "deed_link" to listOf("deed_uid", "survey_uid"),
        // uid = sha256(bytes), untruncated. Bytes are never carried in the row.
        "blob" to listOf("size", "mime"),

        // ---- user-authored, isolated from every scraper write path (§3) ---
        "mark" to listOf("target_uid", "color"),
    )

    /** Every synced table name, in a stable order (used by export/import). */
    val SYNC_TABLES: List<String> = CONTENT_COLS.keys.toList()

    /**
     * The fixed, ordered content columns for one table, with `deleted` last.
     * Throws on an unknown table rather than silently hashing nothing.
     */
    fun syncedCols(table: String): List<String> {
        val cols = CONTENT_COLS[table] ?: throw IllegalArgumentException("unknown synced table: $table")
        return cols + "deleted"
    }

    /**
     * Tables a scraper is NEVER allowed to write (§2, §3). These hold decisions the user
     * made, which cannot be recovered by re-fetching, so a re-fetch must not be able to
     * destroy them. `survey_link` has one narrow exception, enforced in
     * [MergeEngine.decide]: auto-matching may INSERT `candidate` rows, but may never
     * overwrite a curated state.
     */
    val USER_AUTHORED = setOf("mark", "survey_link")

    /** Valid `survey_link.state` values. */
    val LINK_STATES = listOf("candidate", "confirmed", "rejected", "manual")
}
