package com.landrecords.app.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.landrecords.app.data.db.AppDatabase
import com.landrecords.app.data.identity.Identity
import com.landrecords.app.data.place.CascadeMatch
import com.landrecords.app.data.place.PlaceCodes
import com.landrecords.app.data.place.PlaceNames
import com.landrecords.app.data.storage.BlobStore
import com.landrecords.app.ui.property.CascadeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Migration — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §5.
 *
 * The app is already shipping with real data on dad's phone, so this is one-time and breaking:
 * every stored key changes once. The ordering below is the spec's, and the whole thing is
 * **re-runnable** — running it twice produces the same database — which is what makes it safe
 * to retry after an interruption instead of restoring a backup.
 *
 * Re-runnability rests on three properties, each verified elsewhere:
 *  - the tokenizer is idempotent (`token(token(x)) == token(x)`, asserted for every vector),
 *  - every uid is a pure function of content, never of a timestamp or a row order,
 *  - writes go through [MergeEngine], so re-writing an identical row is a NOOP, not an update.
 *
 * Nothing is deleted. The legacy Room tables (`properties`, `surveys`, `records`) are left
 * exactly as they are and keep driving the UI; the synced tables are populated alongside them.
 * If anything here is wrong, re-fetching is the ultimate safety net — but it must not be the
 * plan, so this reads the existing rows rather than going back to the network.
 */
object LegacyMigration {

    private const val ORIGIN = "app-migration"

    /** What one run did. Reported to the user and written to the log, never silently swallowed. */
    data class Report(
        val places: Int = 0,
        val provisionalPlaces: Int = 0,
        val placeNames: Int = 0,
        val surveys: Int = 0,
        val aliases: Int = 0,
        val recordSets: Int = 0,
        val staleTokens: Int = 0,
        val blobs: Int = 0,
        val blobBytes: Long = 0,
        val written: Int = 0,
        val unchanged: Int = 0,
        val problems: List<String> = emptyList(),
    ) {
        /** True when a re-run changed nothing — the property §5 requires. */
        val isNoop: Boolean get() = written == 0

        override fun toString(): String =
            "places=$places(prov=$provisionalPlaces) names=$placeNames surveys=$surveys " +
                "aliases=$aliases retokenized=$staleTokens recordSets=$recordSets blobs=$blobs/${blobBytes / 1024}KB " +
                "written=$written unchanged=$unchanged problems=${problems.size}"
    }

    /**
     * Step 1 of §5: back up before touching anything.
     *
     * Copies the Room database (and its WAL/journal siblings — copying the .db alone can
     * capture a torn state) next to itself with a timestamp. The visible
     * `Documents/LandRecords` tree is NOT copied: it can be 125 MB+, and it is never mutated
     * destructively by this migration, only added to.
     */
    suspend fun backup(context: Context, stamp: Long = System.currentTimeMillis()): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val db = context.getDatabasePath("land_records.db")
                if (!db.isFile) return@withContext null
                val dir = File(context.filesDir, "backups").apply { mkdirs() }
                val dest = File(dir, "land_records-$stamp.db")
                db.copyTo(dest, overwrite = true)
                for (suffix in listOf("-wal", "-shm")) {
                    val side = File(db.parentFile, db.name + suffix)
                    if (side.isFile) side.copyTo(File(dir, dest.name + suffix), overwrite = true)
                }
                dest
            }.onFailure { android.util.Log.w("LR", "migration backup failed: ${it.message}") }.getOrNull()
        }

    /**
     * Run the migration. Safe to call repeatedly; safe to call on every app start.
     *
     * [dryRun] computes everything and reports what would change without writing, so the
     * outcome can be inspected on the real device before committing to it.
     */
    suspend fun run(context: Context, dryRun: Boolean = false): Report = withContext(Dispatchers.IO) {
        PlaceNames.load(context)
        val room = AppDatabase.get(context)
        val db = room.openHelper.writableDatabase
        SyncDb.createTables(db)

        val problems = ArrayList<String>()
        var written = 0
        var unchanged = 0

        fun put(table: String, rows: List<Map<String, Any?>>): Int {
            if (rows.isEmpty()) return 0
            val stamped = rows.map { SyncDb.stamp(db, table, it, ORIGIN) }
            if (dryRun) {
                val local = SyncDb.rowsByUid(db, table, stamped.mapNotNull { it["uid"] as? String })
                val r = MergeEngine.mergeTable(table, stamped, local)
                written += r.writes.size
                unchanged += r.count(MergeEngine.Action.NOOP)
                return r.writes.size
            }
            val r = SyncDb.merge(db, table, stamped)
            written += r.writes.size
            unchanged += r.count(MergeEngine.Action.NOOP)
            return r.writes.size
        }

        // ---- steps 2 + 3: places, then surveys re-tokenized with aliases ----------------
        val properties =
            db.query("SELECT id, district, taluka, village, districtGu, talukaGu, villageGu FROM properties")
        val placeOf = HashMap<Long, String>()
        var places = 0
        var provisional = 0
        var placeNames = 0

        properties.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val district = c.getString(1) ?: ""
                val taluka = c.getString(2) ?: ""
                val village = c.getString(3) ?: ""
                val districtGu = c.getString(4) ?: ""
                val talukaGu = c.getString(5) ?: ""
                val villageGu = c.getString(6) ?: ""

                val codes = resolveCodesBoth(
                    context, district, taluka, village, districtGu, talukaGu, villageGu,
                )
                val placeId = if (codes != null) {
                    Identity.placeId(codes.first, codes.second, codes.third)
                } else {
                    // §7's honest flag: the crosswalk refuses to guess on ambiguity, so this
                    // village needs a manual pass. `gj?:` exists precisely so that pass can
                    // happen later, by uid, without blocking the rest of the migration.
                    provisional++
                    problems.add("no cascade codes for '$district / $taluka / $village' — provisional id")
                    Identity.provisionalPlaceId(district, taluka, village)
                }
                placeOf[id] = placeId
                places++

                put("place", listOf(mapOf(
                    "uid" to placeId,
                    "district_code" to codes?.first,
                    "taluka_code" to codes?.second,
                    "village_code" to codes?.third,
                )))

                // Names become attributes, never keys — the Nadiad duplicate dies here.
                val names = buildList {
                    if (district.isNotBlank()) add(Triple("district", scriptOf(district), district))
                    if (taluka.isNotBlank()) add(Triple("taluka", scriptOf(taluka), taluka))
                    if (village.isNotBlank()) add(Triple("village", scriptOf(village), village))
                    if (districtGu.isNotBlank()) add(Triple("district", "gu", districtGu))
                    if (talukaGu.isNotBlank()) add(Triple("taluka", "gu", talukaGu))
                    if (villageGu.isNotBlank()) add(Triple("village", "gu", villageGu))
                }
                placeNames += put("place_name", names.map { (level, script, name) ->
                    mapOf(
                        "uid" to Identity.uid("pn", placeId, level, script, name),
                        "place_id" to placeId, "script" to script,
                        "source" to "legacy:$level", "name" to name,
                    )
                })
            }
        }

        // ---- surveys + aliases ----------------------------------------------------------
        var surveys = 0
        var aliases = 0
        var staleTokens = 0
        val surveyUidOf = HashMap<Long, String>()

        db.query(
            "SELECT id, propertyId, surveyNo, normalized, area, assessment, tenure, landUse, asOf FROM surveys",
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val propertyId = c.getLong(1)
                val surveyNo = c.getString(2) ?: ""
                val legacyToken = c.getString(3) ?: ""
                val placeId = placeOf[propertyId]
                if (placeId == null) {
                    problems.add("survey $id ('$surveyNo') has no property $propertyId — skipped")
                    continue
                }
                val token = Identity.surveyToken(surveyNo)
                if (token.isEmpty()) {
                    problems.add("survey $id ('$surveyNo') tokenizes to empty — skipped")
                    continue
                }
                val uid = "$placeId/$token"
                surveyUidOf[id] = uid
                surveys++

                put("survey", listOf(mapOf(
                    "uid" to uid, "place_id" to placeId, "token" to token, "survey_no" to surveyNo,
                    "area" to c.getString(4), "assessment" to c.getString(5),
                    "tenure" to c.getString(6), "land_use" to c.getString(7), "as_of" to c.getString(8),
                )))

                // Every original raw form is preserved, including the app's OLD token — which
                // is how a folder written under the previous scheme is still findable, and how
                // `selectOption` keeps the exact string AnyRoR needs.
                val rawForms = linkedSetOf(surveyNo).apply {
                    if (legacyToken.isNotBlank()) add(legacyToken)
                }
                aliases += put("survey_alias", rawForms.map { raw ->
                    mapOf(
                        "uid" to Identity.uid("sa", uid, raw),
                        "survey_uid" to uid, "raw" to raw, "source" to "legacy",
                    )
                })

                // §5 step 2 applies to the LEGACY column too, not just the new tables.
                //
                // `surveys.normalized` was written by the old tokenizer, which only uppercased
                // and swapped '/'→'_' — so "845/અ" is stored as "845_અ" while the canonical
                // token is "845_A". Six places compare against this column (dedup on add,
                // findSurveyIdIn, library search, marked-export ordering, FetchFiler.resolve),
                // and every one of them now computes the canonical form. Leaving the column
                // stale made them all silently miss: the first symptom was the auto-fetch
                // logging "gj:15:03:030/845_A no longer exists — dropping" and skipping a real
                // survey. The original value is already preserved as a survey_alias row above,
                // so nothing is lost by rewriting it.
                //
                // Safe for files: the visible tree is named from `surveyNo`, never from this
                // column (see the stores' `safeSurvey`), so no folder moves.
                if (legacyToken != token) {
                    staleTokens++
                    if (!dryRun) {
                        db.execSQL(
                            "UPDATE surveys SET normalized = ? WHERE id = ?",
                            arrayOf<Any?>(token, id),
                        )
                    }
                    android.util.Log.i("LR", "migration: survey $id '$surveyNo' token '$legacyToken' -> '$token'")
                }
            }
        }

        // ---- record sets ----------------------------------------------------------------
        var recordSets = 0
        db.query("SELECT id, surveyId, type, docCount, asOf FROM records").use { c ->
            while (c.moveToNext()) {
                val surveyId = c.getLong(1)
                val su = surveyUidOf[surveyId]
                if (su == null) {
                    problems.add("record ${c.getLong(0)} has no survey $surveyId — skipped")
                    continue
                }
                val type = c.getString(2) ?: ""
                recordSets += put("record_set", listOf(mapOf(
                    "uid" to Identity.recordSetUid(su, type),
                    "survey_uid" to su, "record_type" to type,
                    // fetched_at and pdfPath are deliberately NOT carried: both are locally
                    // derived and would make two machines' identical records look different.
                    "doc_count" to c.getInt(3).toLong(), "as_of" to c.getString(4),
                )))
            }
        }

        // ---- step 4: ingest existing files into the blob store --------------------------
        var blobs = 0
        var blobBytes = 0L
        if (!dryRun) {
            val ingested = ingestExistingFiles(context, db, problems)
            blobs = ingested.first
            blobBytes = ingested.second
        }

        val report = Report(
            places = places, provisionalPlaces = provisional, placeNames = placeNames,
            surveys = surveys, aliases = aliases, staleTokens = staleTokens, recordSets = recordSets,
            blobs = blobs, blobBytes = blobBytes,
            written = written, unchanged = unchanged, problems = problems,
        )
        android.util.Log.i("LR", "LegacyMigration${if (dryRun) " (dry run)" else ""}: $report")
        for (p in problems.take(20)) android.util.Log.w("LR", "  migration: $p")
        report
    }

    /**
     * Step 4: every PDF already on disk becomes a blob, addressed by its own bytes.
     *
     * Nothing is moved or deleted — the visible tree keeps working untouched while the store
     * fills up behind it. That is what makes this step re-runnable and abort-safe: a second
     * run simply re-hashes the same files and finds them already present.
     */
    private suspend fun ingestExistingFiles(
        context: Context,
        db: SupportSQLiteDatabase,
        problems: MutableList<String>,
    ): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        val roots = listOfNotNull(
            File(context.filesDir, "source"),
            context.getExternalFilesDir(null),
        ).filter { it.isDirectory }

        val rows = ArrayList<Map<String, Any?>>()
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in setOf("pdf", "tif", "tiff", "png", "jpg") }
                .forEach { f ->
                    val sha = runCatching { BlobStore.putFile(context, f) }.getOrNull()
                    if (sha == null) {
                        problems.add("could not ingest ${f.name}")
                        return@forEach
                    }
                    n++
                    bytes += f.length()
                    rows.add(mapOf("uid" to sha, "size" to f.length(), "mime" to mimeOf(f)))
                }
        }
        if (rows.isNotEmpty()) {
            SyncDb.merge(db, "blob", rows.map { SyncDb.stamp(db, "blob", it, ORIGIN) })
        }
        return n to bytes
    }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "tif", "tiff" -> "image/tiff"
        "png" -> "image/png"
        else -> "image/jpeg"
    }

    /** Gujarati if it contains any Gujarati codepoint, else Latin. Good enough to label a name. */
    private fun scriptOf(s: String): String =
        if (s.any { it.code in 0x0A80..0x0AFF }) "gu" else "en"

    /**
     * Resolve a place to its (district, taluka, village) cascade codes, or null when the
     * catalogue cannot do it unambiguously.
     *
     * Deliberately strict, exactly like [PlaceNames.canonical]: it resolves a village only
     * within a matched district and taluka and gives up the moment anything is ambiguous.
     * Merging two genuinely different villages would corrupt real land records, so "don't
     * know" always beats a guess — the caller falls back to a provisional `gj?:` id, which
     * can be corrected later by uid without re-fetching anything.
     */
    fun resolveCodes(context: Context, district: String, taluka: String, village: String): Triple<String, String, String>? {
        // 1. Codes AnyRoR itself gave us when this village was added — always authoritative,
        //    because they are the site's own option values, not a name match at all.
        PlaceCodes.lookup(context, district, taluka, village)?.let { return it }

        // 2. Otherwise match the bundled catalogue the same tolerant way the live cascade
        //    prefill does. Exact-equality matching used to fail here on real data (Valetva:
        //    "Nadiad Gramya" vs "Nadiad", વળેટવા vs વલેટવા) and sent the place to a provisional
        //    id even though its codes were in the shipped asset. See CascadeMatch.
        val d = CascadeMatch.pick(district, CascadeData.districts(context)) { listOf(it.en, it.gu) }
            ?: return null
        val t = CascadeMatch.pick(taluka, CascadeData.talukasFor(context, d.code).orEmpty()) { listOf(it.en, it.gu) }
            ?: return null
        val v = CascadeMatch.pick(village, CascadeData.villagesFor(context, d.code, t.code).orEmpty()) { listOf(it.en, it.gu) }
            ?: return null

        return Triple(d.code, t.code, v.code)
    }

    /**
     * **The** place_id for a stored property. Every caller must use this one function.
     *
     * The migration, the fetch filer, the auto-fetch queue and the curation screen all have to
     * derive the same id for the same village — a disagreement would give one survey two
     * `survey_uid`s and silently duplicate every row hanging off it. Centralised here for
     * exactly that reason; do not re-derive it inline.
     */
    fun placeIdOf(context: Context, property: com.landrecords.app.data.db.PropertyEntity): String {
        val codes = resolveCodesBoth(
            context,
            property.district, property.taluka, property.village,
            property.districtGu, property.talukaGu, property.villageGu,
        )
        return if (codes != null) Identity.placeId(codes.first, codes.second, codes.third)
        else Identity.provisionalPlaceId(property.district, property.taluka, property.village)
    }

    /**
     * Resolve using BOTH spellings the app holds for a place. The English and Gujarati fields
     * each fail on different villages — "Nadiad Gramya" resolves only via English, વળેટવા only
     * via Gujarati — so trying both is what gets the coverage.
     */
    fun resolveCodesBoth(
        context: Context,
        district: String, taluka: String, village: String,
        districtGu: String, talukaGu: String, villageGu: String,
    ): Triple<String, String, String>? =
        resolveCodes(context, district, taluka, village)
            ?: resolveCodes(context, districtGu, talukaGu, villageGu)
            // Mixed: the catalogue's own pairing is per-level, so a place whose taluka is only
            // findable in one script and whose village is only findable in the other still
            // resolves rather than falling back to a provisional id.
            ?: resolveCodes(context, district, talukaGu, villageGu)
            ?: resolveCodes(context, districtGu, taluka, village)
}
