package com.landrecords.app.data

import com.landrecords.app.data.db.AppDatabase
import com.landrecords.app.data.db.PropertyEntity
import com.landrecords.app.data.db.RecordEntity
import com.landrecords.app.data.db.SurveyEntity
import com.landrecords.app.data.model.RecordType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** A survey plus the document count of each record type it holds (0 = missing). */
data class SurveyWithCounts(
    val survey: SurveyEntity,
    val counts: Map<RecordType, Int>,
)

/**
 * Single point of truth for the library data. On first run it seeds the surveys we already have
 * records for (Bharoda) plus the queued ones (Sundalpura, Valetva) so browse works with zero network.
 * Real fetched records replace/augment these rows as the WebView engine files them.
 */
class LandRecordsRepository(private val db: AppDatabase) {

    fun observeProperties(): Flow<List<PropertyEntity>> = db.propertyDao().observeAll()
    fun observeSurveys(propertyId: Long): Flow<List<SurveyEntity>> = db.surveyDao().observeForProperty(propertyId)
    fun observeSurvey(surveyId: Long): Flow<SurveyEntity?> = db.surveyDao().observeById(surveyId)
    fun observeRecords(surveyId: Long): Flow<List<RecordEntity>> = db.recordDao().observeForSurvey(surveyId)
    fun searchSurveys(query: String): Flow<List<SurveyEntity>> = db.surveyDao().search(query)

    /** Every survey with its per-type document counts — drives the Library. */
    fun observeAllSurveyCards(): Flow<List<SurveyWithCounts>> =
        combine(db.surveyDao().observeAll(), db.recordDao().observeAll()) { surveys, records ->
            val byS = records.groupBy { it.surveyId }
            surveys.map { s ->
                SurveyWithCounts(s, byS[s.id].orEmpty().associate { it.type to it.docCount })
            }
        }

    suspend fun propertyById(id: Long) = db.propertyDao().byId(id)

    /** Delete a single survey number (its records) — the saved PDF files are left in Documents. */
    suspend fun deleteSurvey(surveyId: Long) {
        runCatching {
            db.recordDao().deleteForSurvey(surveyId)
            db.surveyDao().deleteById(surveyId)
        }.onFailure { android.util.Log.w("LR", "deleteSurvey failed: ${it.message}") }
    }

    /**
     * Delete a property (village card) and everything under it — its surveys + their records — from
     * the database. The saved PDF files in Documents/LandRecords are left in place (the app just
     * "forgets" the village); they can be removed from the file manager if wanted.
     */
    suspend fun deleteProperty(propertyId: Long) {
        runCatching {
            db.recordDao().deleteForProperty(propertyId)
            db.surveyDao().deleteForProperty(propertyId)
            db.propertyDao().deleteById(propertyId)
        }.onFailure { android.util.Log.w("LR", "deleteProperty failed: ${it.message}") }
    }

    /** Survey row id for a full-place (district/taluka/village) + survey number, or null — links seeded records. */
    suspend fun findSurveyId(district: String, taluka: String, village: String, surveyNo: String): Long? =
        db.surveyDao().findByVillageAndNo(district, taluka, village, surveyNo)?.id

    /**
     * The survey id for [surveyNo] inside a property we already know the id of. Name-independent,
     * so it still resolves when the row is stored under a different script or spelling than the
     * caller holds — which [findSurveyId] cannot do, since it matches the place names literally.
     */
    suspend fun findSurveyIdIn(propertyId: Long, surveyNo: String): Long? =
        db.surveyDao().observeForProperty(propertyId).first()
            .firstOrNull { it.normalized == tokenOf(surveyNo) }?.id

    /** Outcome of an add: which survey numbers were freshly created vs already present (skipped). */
    data class AddResult(val propertyId: Long, val added: List<String>, val duplicates: List<String>)

    /**
     * Create a property (or reuse an existing same-named one) and add the given survey numbers.
     * The plain district/taluka/village fields hold a stable English/code key (used for the storage
     * path + the seed-link lookup); the *Gu fields hold the Gujarati label the AnyRoR/iRCMS cascades
     * match on. This mirrors seedQueued's convention (English in plain, Gujarati in Gu) so a
     * user-added village and a seeded one never diverge and findByVillageAndNo stays deterministic.
     * When only a typed name is available the same text fills both fields. Returns the property id.
     */
    suspend fun addProperty(
        district: String, districtGu: String,
        taluka: String, talukaGu: String,
        village: String, villageGu: String,
        surveyNos: List<String>,
    ): Long = addPropertyDetailed(district, districtGu, taluka, talukaGu, village, villageGu, surveyNos).propertyId

    /**
     * Like [addProperty] but DE-DUPLICATES the surveys and reports the outcome: a survey number
     * already present in that village is never inserted a second time and never overwritten — the
     * existing survey row (and every fetched record / scan / case / colour-mark hanging off it) is
     * kept intact, and the number is returned under [AddResult.duplicates]. Dedup is by the
     * *normalized* token, so "174/p2", "174/P2" and "174 p 2" collapse to one survey.
     */
    suspend fun addPropertyDetailed(
        district: String, districtGu: String,
        taluka: String, talukaGu: String,
        village: String, villageGu: String,
        surveyNos: List<String>,
    ): AddResult {
        // Match case-INSENSITIVELY: the cascade gives district/village en in inconsistent case
        // (e.g. "ANAND", "KARAMSAD") so an exact-case compare would make a *second* property for a
        // village that already exists → a duplicate village card. Reuse the existing one instead.
        val existing = db.propertyDao().observeAll().first()
            .firstOrNull { placeKey(it.district, it.taluka, it.village) == placeKey(district, taluka, village) }
        val propId = existing?.id ?: db.propertyDao().upsert(
            PropertyEntity(
                state = "Gujarat", district = district, taluka = taluka, village = village,
                districtGu = districtGu.ifBlank { district },
                talukaGu = talukaGu.ifBlank { taluka },
                villageGu = villageGu.ifBlank { village },
            ),
        )
        // Tokens already held in this property — dedup against them AND within the input list.
        val present = db.surveyDao().observeForProperty(propId).first().map { it.normalized }.toMutableSet()
        val added = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        for (no in surveyNos.map { it.trim() }.filter { it.isNotEmpty() }) {
            val tok = tokenOf(no)
            if (!present.add(tok)) { duplicates.add(no); continue } // already exists → keep, don't overwrite
            db.surveyDao().upsert(SurveyEntity(propertyId = propId, surveyNo = no, normalized = tok))
            added.add(no)
        }
        return AddResult(propId, added, duplicates)
    }

    suspend fun recordFor(surveyId: Long, type: RecordType): RecordEntity? =
        db.recordDao().find(surveyId, type.name)

    /** All marked records (with a PDF), grouped by colour — drives the Marked / batch-export screen. */
    fun observeMarked(): Flow<List<com.landrecords.app.data.db.MarkedRecordRow>> = db.recordDao().observeMarked()

    /** Set or clear ([mark] = null) a record's export colour. */
    suspend fun setMark(recordId: Long, mark: String?) = db.recordDao().setMark(recordId, mark)

    /** A one-shot snapshot of a survey plus its owning property, for filing a capture. */
    suspend fun snapshot(surveyId: Long): Pair<SurveyEntity, PropertyEntity>? {
        val survey = db.surveyDao().byIdOnce(surveyId) ?: return null
        val property = db.propertyDao().byId(survey.propertyId) ?: return null
        return survey to property
    }

    /** File a captured record: record the filed PDF + raw-HTML source paths and doc count. */
    suspend fun saveFetchedRecord(
        surveyId: Long,
        type: RecordType,
        docCount: Int,
        pdfPath: String? = null,
        sourcePath: String? = null,
    ) {
        val existing = db.recordDao().find(surveyId, type.name)
        val row = (existing ?: RecordEntity(surveyId = surveyId, type = type)).copy(
            docCount = docCount,
            fetchedAt = System.currentTimeMillis(),
            pdfPath = pdfPath ?: existing?.pdfPath,
            sourcePath = sourcePath ?: existing?.sourcePath,
        )
        db.recordDao().upsert(row)
    }

    suspend fun seedIfEmpty() {
        if (db.propertyDao().count() > 0) return
        seedBharoda()
        seedQueued("Anand", "આણંદ", "Umreth", "ઉમરેઠ", "Sundalpura", "સુંદલપુરા",
            listOf("906", "845/અ", "851", "901/p", "902"))
        seedQueued("Kheda", "ખેડા", "Nadiad Gramya", "નડિયાદ ગ્રામ્ય", "Valetva", "વળેટવા",
            listOf("41"))
    }

    private suspend fun seedBharoda() {
        val propId = db.propertyDao().upsert(
            PropertyEntity(
                state = "Gujarat", district = "Anand", taluka = "Umreth", village = "Bharoda",
                districtGu = "આણંદ", talukaGu = "ઉમરેઠ", villageGu = "ભરોડા",
            ),
        )
        val now = System.currentTimeMillis()
        // surveyNo, area, assessment, tenure, vf712 pages, ircms cases
        data class Seed(
            val no: String, val area: String, val assess: String, val tenure: String,
            val vf: Int, val cases: Int,
        )
        val agri = "ખેતીલાયક"
        val seeds = listOf(
            Seed("221/p", "૩-૩૧-૮૪", "૨૪.૬૯", "બીન ખેતી પ્રિપાત્ર", 10, 14),
            Seed("222/1", "૦-૩૦-૩પ", "૨.૧૨", "જુની શરત (જુ.શ)", 6, 2),
            Seed("222/2/p", "", "", "જુની શરત (જુ.શ)", 8, 9),
            Seed("222/3/p1", "", "", "જુની શરત (જુ.શ)", 5, 1),
            Seed("222/3/p2", "", "", "જુની શરત (જુ.શ)", 5, 0),
            Seed("226/p1", "", "", "જુની શરત (જુ.શ)", 4, 0),
            Seed("228/p1/p", "", "", "જુની શરત (જુ.શ)", 6, 2),
            Seed("229/p", "", "", "જુની શરત (જુ.શ)", 4, 1),
            Seed("230/p1/p3", "૦-૪૭-૦૪", "૩.૪૮", "જુની શરત (જુ.શ)", 12, 0),
        )
        for (s in seeds) {
            val surveyId = db.surveyDao().upsert(
                SurveyEntity(
                    propertyId = propId, surveyNo = s.no, normalized = tokenOf(s.no),
                    area = s.area, assessment = s.assess, tenure = s.tenure, landUse = agri,
                ),
            )
            db.recordDao().upsert(RecordEntity(surveyId = surveyId, type = RecordType.INTEGRATED,
                docCount = 1, fetchedAt = now))
            db.recordDao().upsert(RecordEntity(surveyId = surveyId, type = RecordType.VF712,
                docCount = s.vf, fetchedAt = now))
            if (s.cases > 0) {
                db.recordDao().upsert(RecordEntity(surveyId = surveyId, type = RecordType.IRCMS,
                    docCount = s.cases, fetchedAt = now))
            }
        }
    }

    private suspend fun seedQueued(
        district: String, districtGu: String, taluka: String, talukaGu: String,
        village: String, villageGu: String, surveys: List<String>,
    ) {
        val propId = db.propertyDao().upsert(
            PropertyEntity(
                state = "Gujarat", district = district, taluka = taluka, village = village,
                districtGu = districtGu, talukaGu = talukaGu, villageGu = villageGu,
            ),
        )
        for (no in surveys) {
            db.surveyDao().upsert(
                SurveyEntity(propertyId = propId, surveyNo = no, normalized = tokenOf(no)),
            )
        }
    }

    private fun tokenOf(surveyNo: String): String =
        surveyNo.trim().uppercase().replace('/', '_').replace(" ", "")

    /**
     * Case/space-insensitive identity of a place. When the shipped cascade catalogue can resolve
     * the place confidently (see [com.landrecords.app.data.place.PlaceNames]) the key is built from
     * its canonical ENGLISH triple, so the same village written in Gujarati and in English lands on
     * one key — that is what lets [mergeDuplicateProperties] see a cross-script duplicate at all.
     * Anything the catalogue can't resolve falls back to the literal, script-sensitive key.
     */
    private fun placeKey(district: String, taluka: String, village: String): String {
        val c = com.landrecords.app.data.place.PlaceNames.canonical(district, taluka, village)
        val d = c?.first ?: district
        val t = c?.second ?: taluka
        val v = c?.third ?: village
        return "${d.trim().lowercase()}|${t.trim().lowercase()}|${v.trim().lowercase()}"
    }

    /**
     * Strip the "- <code>" suffix AnyRoR appends to its village dropdown text (e.g. "ઉતરસંડા - 091")
     * from any stored place name — that code breaks the AnyRoR/iRCMS name matchers. Idempotent.
     */
    suspend fun stripPlaceCodeSuffixes() {
        runCatching {
            val rx = Regex("""\s*-\s*\d+\s*$""")
            for (p in db.propertyDao().observeAll().first()) {
                val cleaned = p.copy(
                    district = p.district.replace(rx, "").trim(),
                    districtGu = p.districtGu.replace(rx, "").trim(),
                    taluka = p.taluka.replace(rx, "").trim(),
                    talukaGu = p.talukaGu.replace(rx, "").trim(),
                    village = p.village.replace(rx, "").trim(),
                    villageGu = p.villageGu.replace(rx, "").trim(),
                )
                if (cleaned != p) {
                    db.propertyDao().upsert(cleaned)
                    android.util.Log.i("LR", "stripPlaceCodeSuffixes: cleaned property ${p.id} -> ${cleaned.villageGu}")
                }
            }
        }.onFailure { android.util.Log.w("LR", "stripPlaceCodeSuffixes failed: ${it.message}") }
    }

    /**
     * Collapse duplicate village cards: properties that are the same place (case-insensitively) but
     * got stored as separate rows — e.g. seeded "Anand/…/Bharoda" vs a user-added "ANAND/…/Bharoda".
     * Keeps the lowest-id property and RE-PARENTS every survey of the duplicates onto it (survey ids
     * are preserved, so their records follow untouched — nothing is deleted, so no data can be lost),
     * then removes the now-empty duplicate property. Idempotent; safe to run every launch.
     */
    suspend fun mergeDuplicateProperties() {
        runCatching {
            val props = db.propertyDao().observeAll().first()
            val groups = props.groupBy { placeKey(it.district, it.taluka, it.village) }
            for ((_, group) in groups) {
                if (group.size <= 1) continue
                val canonical = group.minByOrNull { it.id } ?: continue
                for (dup in group) {
                    if (dup.id == canonical.id) continue
                    db.surveyDao().observeForProperty(dup.id).first()
                        .forEach { s -> db.surveyDao().upsert(s.copy(propertyId = canonical.id)) }
                    db.propertyDao().deleteById(dup.id)
                    android.util.Log.i("LR", "mergeDuplicateProperties: merged property ${dup.id} -> ${canonical.id} (${canonical.village})")
                }
            }
        }.onFailure { android.util.Log.w("LR", "mergeDuplicateProperties failed: ${it.message}") }
    }

    // ------------------------------------------------------------ cross-script place migration

    private companion object {
        const val MIGRATION_PREFS = "lr_migrations"
        const val KEY_CROSS_SCRIPT = "crossScriptPlaces_v1"
    }

    /**
     * One-shot repair for villages that got saved twice in two scripts — e.g. the seeded
     * "Anand / Umreth / Bharoda" and an AnyRoR-added "આણંદ / ઉમરેઠ / ભરોડા". Both are the same
     * real village, but until [com.landrecords.app.data.place.PlaceNames] existed nothing could see
     * that, so the user got two library cards and a second set of folders on disk.
     *
     * Per group of properties sharing one canonical place:
     *  1. Pick the survivor — a row whose stored English names already ARE the canonical spelling,
     *     else the lowest id (the oldest, which owns the bulk of the files).
     *  2. Copy every file of the other rows' places into the survivor's place, in both the
     *     app-internal `source/` tree and the visible Documents/LandRecords tree. Existing
     *     destination files are never overwritten.
     *  3. Rewrite the place segments inside RecordEntity.pdfPath / sourcePath.
     *  4. Re-parent the surveys onto the survivor, normalise the survivor's own names, delete the
     *     drained duplicate row — and only THEN delete the copied-from files.
     *
     * Nothing is deleted until every copy and every database write of that group has succeeded, so
     * a failure leaves the group fully usable (at worst with a duplicated file) and the whole thing
     * is safe to run again. Guarded by a preference flag as well, and every step is independently
     * idempotent. Audit trail: `adb logcat -s LR`.
     */
    suspend fun migrateCrossScriptPlaces(context: android.content.Context) {
        val prefs = context.getSharedPreferences(MIGRATION_PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CROSS_SCRIPT, false)) return
        com.landrecords.app.data.place.PlaceNames.load(context)
        if (!com.landrecords.app.data.place.PlaceNames.isLoaded) {
            android.util.Log.w("LR", "migrateCrossScriptPlaces: catalogue unavailable — skipped (will retry next launch)")
            return
        }

        var allOk = true
        runCatching {
            val props = db.propertyDao().observeAll().first()
            val groups = props
                .filter { com.landrecords.app.data.place.PlaceNames.canonical(it.district, it.taluka, it.village) != null }
                .groupBy { placeKey(it.district, it.taluka, it.village) }

            for ((key, group) in groups) {
                if (group.size <= 1) continue
                val target = targetNamesFor(group) ?: continue
                val survivor = group.firstOrNull { sameNames(it, target) } ?: group.minByOrNull { it.id } ?: continue
                android.util.Log.i(
                    "LR",
                    "migrateCrossScriptPlaces: group '$key' -> survivor ${survivor.id} as " +
                        "${target.first}/${target.second}/${target.third} (${group.size} rows)",
                )
                if (!migrateGroup(context, group, survivor, target)) allOk = false
            }
        }.onFailure {
            allOk = false
            android.util.Log.w("LR", "migrateCrossScriptPlaces failed: ${it.message}")
        }

        // Only latch the flag on a clean sweep; a failed group gets another attempt next launch.
        if (allOk) prefs.edit().putBoolean(KEY_CROSS_SCRIPT, true).apply()
    }

    /**
     * The names this group should end up stored under. Canonically the catalogue's English triple —
     * but where a row already spells a level the same way (ignoring case), that existing spelling
     * wins. The catalogue writes districts upper-case ("ANAND"); adopting that would rename a whole
     * on-disk tree ("…/Anand" → "…/ANAND") for no gain, and case-only renames are exactly what the
     * MediaStore layer handles worst.
     */
    private fun targetNamesFor(group: List<PropertyEntity>): Triple<String, String, String>? {
        val first = group.first()
        val canon = com.landrecords.app.data.place.PlaceNames
            .canonical(first.district, first.taluka, first.village) ?: return null
        fun keep(catalogue: String, stored: List<String>): String =
            stored.firstOrNull { com.landrecords.app.data.place.PlaceNames.normalize(it) == com.landrecords.app.data.place.PlaceNames.normalize(catalogue) }
                ?: catalogue
        return Triple(
            keep(canon.first, group.map { it.district }),
            keep(canon.second, group.map { it.taluka }),
            keep(canon.third, group.map { it.village }),
        )
    }

    private fun sameNames(p: PropertyEntity, t: Triple<String, String, String>): Boolean =
        p.district == t.first && p.taluka == t.second && p.village == t.third

    /** Returns true when the whole group completed; false means it was aborted and left as it was. */
    private suspend fun migrateGroup(
        context: android.content.Context,
        group: List<PropertyEntity>,
        survivor: PropertyEntity,
        target: Triple<String, String, String>,
    ): Boolean = runCatching {
        val moved = mutableListOf<Triple<String, String, String>>()

        // ---- phase 1: copy files. Nothing is deleted here, so a throw is harmless.
        for (p in group) {
            val from = Triple(p.district, p.taluka, p.village)
            if (from == target) continue
            val copied = com.landrecords.app.data.storage.PlaceRelocator.copyInto(context, from, target)
            android.util.Log.i(
                "LR",
                "migrateCrossScriptPlaces: copied ${from.first}/${from.second}/${from.third} -> " +
                    "${target.first}/${target.second}/${target.third} " +
                    "(internal ${copied.internal}, visible ${copied.visible})",
            )
            moved.add(from)
        }

        // ---- phase 2: database. Paths first, then surveys, then the survivor's own names.
        for (p in group) {
            val from = Triple(p.district, p.taluka, p.village)
            if (from == target) continue
            for (s in db.surveyDao().observeForProperty(p.id).first()) {
                for (r in db.recordDao().observeForSurvey(s.id).first()) {
                    val fixed = r.copy(
                        pdfPath = rewritePlacePath(r.pdfPath, from, target),
                        sourcePath = rewritePlacePath(r.sourcePath, from, target),
                    )
                    if (fixed != r) {
                        db.recordDao().upsert(fixed)
                        android.util.Log.i("LR", "migrateCrossScriptPlaces: record ${r.id} path -> ${fixed.pdfPath}")
                    }
                }
            }
        }
        for (p in group) {
            if (p.id == survivor.id) continue
            db.surveyDao().observeForProperty(p.id).first()
                .forEach { s -> db.surveyDao().upsert(s.copy(propertyId = survivor.id)) }
        }
        if (!sameNames(survivor, target)) {
            db.propertyDao().upsert(
                survivor.copy(district = target.first, taluka = target.second, village = target.third),
            )
        }
        for (p in group) {
            if (p.id == survivor.id) continue
            db.propertyDao().deleteById(p.id)
            android.util.Log.i("LR", "migrateCrossScriptPlaces: merged property ${p.id} -> ${survivor.id}")
        }
        collapseIdenticalSurveys(survivor.id)

        // ---- phase 3: the copied-from files are now unreferenced — drop them.
        for (from in moved) com.landrecords.app.data.storage.PlaceRelocator.dropSource(context, from, target)
        true
    }.getOrElse {
        android.util.Log.w(
            "LR",
            "migrateCrossScriptPlaces: ABORTED group ${target.first}/${target.second}/${target.third} " +
                "— nothing deleted (${it.message})",
        )
        false
    }

    /**
     * After a cross-script merge the survivor can hold the SAME survey number twice — once from
     * each of the old village cards — which would show as two identical parcel tiles. Collapse them,
     * but only where it is provably lossless:
     *
     *  · a record whose type the keeper doesn't have at all is simply re-parented onto the keeper;
     *  · a record that duplicates one the keeper already has AND now resolves to the exact same
     *    file path is dropped (the file itself is untouched — the keeper still points at it);
     *  · an empty placeholder row (no path, nothing fetched) is dropped;
     *  · anything else — a second record of the same type pointing somewhere ELSE — is left alone,
     *    and its survey survives as a separate tile rather than risk losing a document.
     *
     * A duplicate survey row is deleted only once it holds no records at all.
     */
    private suspend fun collapseIdenticalSurveys(propertyId: Long) {
        val surveys = db.surveyDao().observeForProperty(propertyId).first()
        for ((_, sameNo) in surveys.groupBy { it.normalized }) {
            if (sameNo.size <= 1) continue
            val keeper = sameNo.minByOrNull { it.id } ?: continue
            for (dup in sameNo) {
                if (dup.id == keeper.id) continue
                for (r in db.recordDao().observeForSurvey(dup.id).first()) {
                    val mine = db.recordDao().find(keeper.id, r.type.name)
                    when {
                        mine == null -> db.recordDao().upsert(r.copy(surveyId = keeper.id))
                        r.pdfPath != null && r.pdfPath == mine.pdfPath -> {
                            db.recordDao().deleteById(r.id)
                            android.util.Log.i("LR", "collapseIdenticalSurveys: dropped duplicate record ${r.id} (${r.type}) — same file as ${mine.id}")
                        }
                        r.pdfPath == null && r.sourcePath == null && r.fetchedAt == null ->
                            db.recordDao().deleteById(r.id)
                        else -> android.util.Log.w("LR", "collapseIdenticalSurveys: kept record ${r.id} (${r.type}) — differs from ${mine.id}")
                    }
                }
                if (db.recordDao().countForSurvey(dup.id) == 0) {
                    db.surveyDao().deleteById(dup.id)
                    android.util.Log.i("LR", "collapseIdenticalSurveys: removed drained duplicate survey ${dup.id} (${dup.surveyNo}) — kept ${keeper.id}")
                }
            }
        }
    }

    /**
     * Swap the three place segments inside a stored path, leaving every other segment (and the
     * absolute/relative prefix) alone. Matching is script-exact but case-insensitive, so
     * "Documents/LandRecords/આણંદ/ઉમરેઠ/ભરોડા/Survey 221_p/Integrated Record.pdf" becomes
     * "Documents/LandRecords/Anand/Umreth/Bharoda/Survey 221_p/Integrated Record.pdf".
     * A path that doesn't contain the old triple is returned unchanged — which is what makes a
     * second run a no-op.
     */
    private fun rewritePlacePath(
        path: String?,
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ): String? {
        if (path.isNullOrBlank()) return path
        val n = com.landrecords.app.data.place.PlaceNames::normalize
        val segs = path.split('/').toMutableList()
        for (i in 0..segs.size - 3) {
            if (n(segs[i]) == n(from.first) && n(segs[i + 1]) == n(from.second) && n(segs[i + 2]) == n(from.third)) {
                segs[i] = to.first; segs[i + 1] = to.second; segs[i + 2] = to.third
                return segs.joinToString("/")
            }
        }
        return path
    }
}
