package com.landrecords.app.fetch

import android.content.Context
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.db.PropertyEntity
import com.landrecords.app.data.db.SurveyEntity
import com.landrecords.app.data.identity.Identity
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.CasesStore
import com.landrecords.app.data.storage.DeedsStore
import com.landrecords.app.data.storage.LibraryWriter
import com.landrecords.app.data.sync.SyncDb
import com.landrecords.app.web.DeedsDownloader
import kotlinx.coroutines.flow.first

/**
 * Files what a driver captured — the half of §6 that turns bytes into rows.
 *
 * Deliberately separate from the drivers: a driver knows about a website, this knows about the
 * library. It writes BOTH representations, and that is the point of the whole spec —
 *
 *  - the **visible tree** (`Documents/LandRecords/…`) and the legacy Room rows, so the UI dad
 *    already uses keeps working exactly as before, and
 *  - the **synced tables**, keyed by uid, so the same record fetched on the laptop merges
 *    instead of duplicating.
 *
 * Writing only the first is what the app did before; writing only the second would break the UI.
 */
class FetchFiler(
    private val context: Context,
    private val repo: LandRecordsRepository,
    private val writer: LibraryWriter,
) {

    private val db by lazy {
        com.landrecords.app.data.db.AppDatabase.get(context).openHelper.writableDatabase
    }

    /** Resolve a queue item's survey_uid back to the Room rows the visible tree is keyed on. */
    suspend fun resolve(surveyUid: String): Pair<SurveyEntity, PropertyEntity>? {
        val token = surveyUid.substringAfterLast('/')
        val properties: List<PropertyEntity> = repo.observeProperties().first()
        for (property in properties) {
            val placeId = placeIdFor(property)
            if (!surveyUid.startsWith("$placeId/")) continue
            val surveys: List<SurveyEntity> = repo.observeSurveys(property.id).first()
            // Compare on the CANONICAL token computed from surveyNo, not on the stored
            // `normalized` column: the migration rewrites that column, but a database that has
            // not been migrated yet still holds old-tokenizer values, and a queued fetch must
            // not be silently dropped just because the row is stale.
            val survey = surveys.firstOrNull {
                Identity.surveyToken(it.surveyNo) == token || it.normalized == token
            }
            if (survey != null) return survey to property
        }
        return null
    }

    /**
     * Ensure the place + survey PARENT sync rows exist before any child row is written (DB review
     * B1). Idempotent and best-effort: a missing parent is a sync-completeness issue, never a
     * reason to fail a fetch.
     */
    suspend fun ensureParents(survey: SurveyEntity, property: PropertyEntity) {
        runCatching {
            com.landrecords.app.data.sync.SyncParents.upsert(context, db, property, listOf(survey))
        }.onFailure { android.util.Log.w("LR", "sync parents (fetch) failed: ${it.message}") }
    }

    /** Delegates to the ONE resolver — see [LegacyMigration.placeIdOf]. */
    fun placeIdFor(property: PropertyEntity): String =
        com.landrecords.app.data.sync.LegacyMigration.placeIdOf(context, property)

    /**
     * File an AnyRoR capture. One page yields the integrated record, the VF-6 entry list and the
     * deed rows, so all three are recorded from a single fetch (§6).
     */
    suspend fun fileAnyRor(
        surveyUid: String,
        recordType: RecordType,
        survey: SurveyEntity,
        property: PropertyEntity,
        capture: AnyRorDriver.Capture,
    ): Boolean = runCatching {
        val filed = writer.writeRecord(
            district = property.district, taluka = property.taluka, village = property.village,
            surveyNo = survey.surveyNo, type = recordType,
            pdfBytes = capture.pdf, rawHtml = capture.rawHtml,
        )
        repo.saveFetchedRecord(
            survey.id, recordType, docCount = 1,
            pdfPath = filed.pdfPath, sourcePath = filed.sourcePath,
        )

        // Header metadata (area / આકાર / tenure / land use) for the survey card.
        if (capture.summaryJson.isNotBlank()) {
            runCatching {
                val o = org.json.JSONObject(capture.summaryJson.ifBlank { "{}" })
                repo.updateSurveyMeta(
                    surveyId = survey.id,
                    area = o.optString("area"), assessment = o.optString("assessment"),
                    tenure = o.optString("tenure"), landUse = o.optString("landUse"),
                    asOf = o.optString("asOf"),
                )
            }
        }

        // The synced row. `doc_count`/`as_of` are content; the PDF path and fetched_at are NOT
        // — they are local facts and would make two machines' identical records differ.
        SyncDb.merge(
            db, "record_set",
            listOf(
                SyncDb.stamp(
                    db, "record_set",
                    mapOf(
                        "uid" to Identity.recordSetUid(surveyUid, recordType.name),
                        "survey_uid" to surveyUid,
                        "record_type" to recordType.name,
                        "doc_count" to 1L,
                        "as_of" to null,
                    ),
                    origin = "app",
                ),
            ),
        )

        // Deeds ride the same type-8 page — no separate fetch (§6, and the memory note).
        if (recordType == RecordType.INTEGRATED) {
            runCatching { fileDeeds(surveyUid, survey, property, capture) }
                .onFailure { android.util.Log.w("LR", "deeds side-save failed: ${it.message}") }
            runCatching { fileEntries(surveyUid, survey, property, capture) }
                .onFailure { android.util.Log.w("LR", "entries side-save failed: ${it.message}") }
        }
        true
    }.onFailure { android.util.Log.e("LR", "fileAnyRor failed", it) }.getOrDefault(false)

    private suspend fun fileDeeds(
        surveyUid: String,
        survey: SurveyEntity,
        property: PropertyEntity,
        capture: AnyRorDriver.Capture,
    ) {
        val deeds = DeedsStore.parse(capture.deedRowsJson)
        // Try the scanned files too. The GARVI server usually answers "Document Record Not
        // Found", so an empty result is the NORMAL case and must never cost us the details.
        val scans = if (deeds.isNotEmpty() && capture.deedForm != "NONE") {
            runCatching {
                DeedsDownloader.fetchAll(capture.deedForm, context.cacheDir).associate { it.id to it.pdf }
            }.getOrDefault(emptyMap())
        } else emptyMap()

        DeedsStore.save(
            context, property.district, property.taluka, property.village, survey.surveyNo, deeds, scans,
        )
        // 0 records "checked, none found" rather than "not fetched", so the card reads "No deeds".
        repo.saveFetchedRecord(survey.id, RecordType.DEEDS, docCount = deeds.size, pdfPath = null)

        val placeId = surveyUid.substringBeforeLast('/')
        val rows = deeds.mapNotNull { d ->
            val office = deedField(d, "office")
            val year = deedField(d, "year")
            val no = deedField(d, "no")
            if (office.isBlank() && year.isBlank() && no.isBlank()) return@mapNotNull null
            // A deed's identity is place+office+year+no, NEVER the survey: one document can
            // touch several surveys and several parties (confirmed at Bhalej 174/પૈકી1).
            mapOf<String, Any?>(
                "uid" to Identity.deedUid(placeId, office, year, no),
                "place_id" to placeId, "office" to office, "doc_year" to year, "doc_no" to no,
                "doc_date" to deedField(d, "date"), "doc_type" to deedField(d, "type"),
                "detail" to d.toString(),
            )
        }
        if (rows.isNotEmpty()) {
            SyncDb.merge(db, "deed", rows.map { SyncDb.stamp(db, "deed", it, "app") })
            // …and the many-to-many link back to this survey.
            SyncDb.merge(
                db, "deed_link",
                rows.map { r ->
                    SyncDb.stamp(
                        db, "deed_link",
                        mapOf(
                            "uid" to Identity.deedLinkUid(r["uid"] as String, surveyUid),
                            "deed_uid" to r["uid"], "survey_uid" to surveyUid,
                        ),
                        origin = "app",
                    )
                },
            )
        }
    }

    /**
     * File the VF-6 નોંધ entries captured on the INTEGRATED page: each entry's scan PDF + the
     * entries.json manifest (via EntriesStore), the entry blobs, and the synced `entry` rows.
     */
    private suspend fun fileEntries(
        surveyUid: String,
        survey: SurveyEntity,
        property: PropertyEntity,
        capture: AnyRorDriver.Capture,
    ) {
        val entries = capture.entries
        if (entries.isEmpty()) return
        // Entries are part of the INTEGRATED record (the stamp strip is I·V·D·C — no separate
        // entries slot), so they get no record row of their own; they live in EntriesStore and
        // the synced `entry` table, and the survey's Entries screen reads them.
        com.landrecords.app.data.storage.EntriesStore.save(
            context, property.district, property.taluka, property.village, survey.surveyNo, entries,
        )

        val rows = entries.mapNotNull { e ->
            val bytes = e.pdf ?: return@mapNotNull null
            mapOf<String, Any?>(
                "uid" to Identity.entryUid(surveyUid, e.number),
                "survey_uid" to surveyUid, "entry_number" to e.number,
                "entry_date" to null, "entry_type" to null, "detail" to null,
                "blob_sha" to Identity.blobUid(bytes),
            )
        }
        if (rows.isNotEmpty()) SyncDb.merge(db, "entry", rows.map { SyncDb.stamp(db, "entry", it, "app") })
    }

    /** Best-effort field read off a DeedsStore row, whatever its concrete shape. */
    private fun deedField(row: Any, name: String): String = runCatching {
        val f = row.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.get(row)?.toString().orEmpty()
    }.getOrDefault("")

    /**
     * File a VF-7/12 capture: each kept scan (period + bytes), the merged View-all PDF, and the
     * synced `vf_scan` rows keyed on (survey, period, thok, block, oldSurvey).
     */
    suspend fun fileVf712(
        surveyUid: String,
        survey: SurveyEntity,
        property: PropertyEntity,
        scans: List<com.landrecords.app.data.storage.VfScansStore.ScanCapture>,
        rawHtml: String,
    ): Boolean = runCatching {
        com.landrecords.app.data.storage.VfScansStore.save(
            context, property.district, property.taluka, property.village, survey.surveyNo, scans,
        )
        val merged = com.landrecords.app.web.PdfMerge.merge(scans.mapNotNull { it.pdf }, context.cacheDir)
        if (merged != null && merged.isNotEmpty()) {
            val filed = writer.writeRecord(
                district = property.district, taluka = property.taluka, village = property.village,
                surveyNo = survey.surveyNo, type = RecordType.VF712, pdfBytes = merged, rawHtml = rawHtml,
            )
            repo.saveFetchedRecord(
                survey.id, RecordType.VF712, docCount = scans.size,
                pdfPath = filed.pdfPath, sourcePath = filed.sourcePath,
            )
        }

        val rows = scans.mapNotNull { s ->
            val bytes = s.pdf ?: return@mapNotNull null
            mapOf<String, Any?>(
                "uid" to Identity.vfScanUid(surveyUid, s.period, s.thok, s.block, s.oldSurvey),
                "survey_uid" to surveyUid, "period" to s.period, "thok" to s.thok,
                "block" to s.block, "old_survey" to s.oldSurvey,
                "blob_sha" to Identity.blobUid(bytes),
            )
        }
        if (rows.isNotEmpty()) SyncDb.merge(db, "vf_scan", rows.map { SyncDb.stamp(db, "vf_scan", it, "app") })

        SyncDb.merge(
            db, "record_set",
            listOf(
                SyncDb.stamp(
                    db, "record_set",
                    mapOf(
                        "uid" to Identity.recordSetUid(surveyUid, RecordType.VF712.name),
                        "survey_uid" to surveyUid, "record_type" to RecordType.VF712.name,
                        "doc_count" to scans.size.toLong(), "as_of" to null,
                    ),
                    origin = "app",
                ),
            ),
        )

        // Record the scanned old surveys as curation candidates (VF-7/12 review #3), so they show
        // survey-no-wise on the old-survey screen. Never overrules a rejection (fromScraper).
        Vf712Curation.proposeCandidates(context, survey, property, scans.map { it.oldSurvey })
        true
    }.onFailure { android.util.Log.e("LR", "fileVf712 failed", it) }.getOrDefault(false)

    /** File an iRCMS capture: the per-case PDFs, the manifest, and the synced case rows. */
    suspend fun fileIrcms(
        surveyUid: String,
        survey: SurveyEntity,
        property: PropertyEntity,
        captures: List<CasesStore.CaseCapture>,
    ): Boolean = runCatching {
        CasesStore.save(
            context, property.district, property.taluka, property.village, survey.surveyNo, captures,
        )

        val merged = com.landrecords.app.web.PdfMerge.merge(
            captures.flatMap { listOfNotNull(it.detailPdf, it.orderPdf) }, context.cacheDir,
        )
        if (merged != null && merged.isNotEmpty()) {
            val filed = writer.writeRecord(
                district = property.district, taluka = property.taluka, village = property.village,
                surveyNo = survey.surveyNo, type = RecordType.IRCMS,
                pdfBytes = merged, rawHtml = "",
            )
            repo.saveFetchedRecord(
                survey.id, RecordType.IRCMS, docCount = captures.size,
                pdfPath = filed.pdfPath, sourcePath = filed.sourcePath,
            )
        }

        // Keyed on data_id — iRCMS's own case id — so a case scraped on the laptop and on the
        // phone is ONE row. Cases whose data_id is missing are skipped rather than keyed on
        // display text, which drifts with spacing and spelling.
        val rows = captures.filter { it.dataId.isNotBlank() }.map { c ->
            mapOf<String, Any?>(
                "uid" to Identity.caseUid(surveyUid, c.dataId),
                "survey_uid" to surveyUid, "data_id" to c.dataId,
                "case_no" to c.caseNo, "case_status" to c.status, "office" to c.office,
                "dtv" to c.dtv, "parties" to c.parties, "survno" to c.survno,
                "disposal_date" to null, "disposal_type" to null,
                "no_appellant" to null, "court_no" to null,
            )
        }
        if (rows.isNotEmpty()) SyncDb.merge(db, "ircms_case", rows.map { SyncDb.stamp(db, "ircms_case", it, "app") })

        SyncDb.merge(
            db, "record_set",
            listOf(
                SyncDb.stamp(
                    db, "record_set",
                    mapOf(
                        "uid" to Identity.recordSetUid(surveyUid, RecordType.IRCMS.name),
                        "survey_uid" to surveyUid, "record_type" to RecordType.IRCMS.name,
                        "doc_count" to captures.size.toLong(), "as_of" to null,
                    ),
                    origin = "app",
                ),
            ),
        )
        true
    }.onFailure { android.util.Log.e("LR", "fileIrcms failed", it) }.getOrDefault(false)

    /** Record "checked, and there is nothing" so the card reads correctly and we don't re-queue. */
    suspend fun markEmpty(surveyUid: String, survey: SurveyEntity, type: RecordType) {
        // Never let a "checked, nothing there" result erase a record we already hold: a transient
        // empty/NOTFOUND from the site must not downgrade a held record to "Not available", and must
        // not propagate doc_count=0 over the sync layer either. If we already have docs, keep them.
        if (repo.heldDocCount(survey.id, type) > 0) {
            android.util.Log.i("LR", "markEmpty skipped: ${type.name} already held for $surveyUid")
            return
        }
        repo.saveFetchedRecord(survey.id, type, docCount = 0, pdfPath = null, sourcePath = null)
        SyncDb.merge(
            db, "record_set",
            listOf(
                SyncDb.stamp(
                    db, "record_set",
                    mapOf(
                        "uid" to Identity.recordSetUid(surveyUid, type.name),
                        "survey_uid" to surveyUid, "record_type" to type.name,
                        "doc_count" to 0L, "as_of" to null,
                    ),
                    origin = "app",
                ),
            ),
        )
    }
}
