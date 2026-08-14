package com.landrecords.app.fetch

import android.content.Context
import com.landrecords.app.data.db.PropertyEntity
import com.landrecords.app.data.db.SurveyEntity
import com.landrecords.app.data.identity.Identity
import com.landrecords.app.data.storage.VfScansStore
import com.landrecords.app.data.sync.LegacyMigration
import com.landrecords.app.data.sync.OldSurveyMatcher
import com.landrecords.app.data.sync.SyncDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bridge between "which OLD survey numbers belong to this survey" (§2 `survey_link`) and the
 * VF-7/12 scans a fetch actually pulls.
 *
 * The user's decision lives in ONE place — `survey_link` — so it is shared by the scans screen
 * (where he deletes an old number he doesn't want) and the fetcher (which must not drag it back on
 * the next re-fetch). A deleted old number is a `rejected` link; the merge engine guarantees a
 * later auto-fetch can never un-reject it, so the removal is permanent until *he* undoes it.
 *
 * Old numbers are compared by their canonical token, so `174/1`, `174/૧` and `174 / 1` are the
 * same decision.
 */
object Vf712Curation {

    private fun db(context: Context) =
        com.landrecords.app.data.db.AppDatabase.get(context).openHelper.writableDatabase

    /** The tokens the user has REJECTED for this survey — the old numbers a fetch must skip. */
    suspend fun rejectedTokens(context: Context, surveyUid: String): Set<String> =
        withContext(Dispatchers.IO) {
            val d = db(context)
            SyncDb.createTables(d)
            SyncDb.allRows(d, "survey_link")
                .asSequence()
                .filter { it["current_survey_uid"] == surveyUid }
                .filter { it["state"] == "rejected" }
                .filter { (it["deleted"] as? Long ?: 0L) == 0L }
                .mapNotNull { it["old_token"] as? String }
                .toSet()
        }

    /** True if this old-survey value is one the user removed. */
    fun isRejected(oldSurveyRaw: String, rejected: Set<String>): Boolean =
        Identity.surveyToken(oldSurveyRaw) in rejected

    /**
     * Propose the scanned old survey numbers as §2 `candidate` links, so the curation screen can
     * show them survey-no-wise (VF-7/12 review #3 — the mapping recorded positively, not merely
     * implied by which scans survive). Written as a SCRAPER (`fromScraper = true`): the merge
     * engine guarantees a candidate can never overrule a curated state, so a rejected old survey
     * stays rejected and is never re-proposed. Idempotent — safe to call on every fetch.
     */
    suspend fun proposeCandidates(
        context: Context,
        survey: SurveyEntity,
        property: PropertyEntity,
        oldSurveys: Collection<String>,
    ) = withContext(Dispatchers.IO) {
        val placeId = LegacyMigration.placeIdOf(context, property)
        val surveyUid = Identity.surveyUid(placeId, survey.surveyNo)
        val d = db(context)
        SyncDb.createTables(d)
        val rows = oldSurveys
            .map { Identity.surveyToken(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { tok ->
                SyncDb.stamp(
                    d, "survey_link",
                    OldSurveyMatcher.linkRow(surveyUid, tok, OldSurveyMatcher.State.CANDIDATE, source = "scan"),
                    origin = "app",
                )
            }
        if (rows.isNotEmpty()) SyncDb.merge(d, "survey_link", rows, fromScraper = true)
    }

    /**
     * Record the user's decision on one old survey number and, on a delete, remove its scans from
     * the visible tree so the screen reflects it immediately. Written through the merge engine, so
     * the same decision syncs to the laptop as one row.
     *
     * @return the number of scan files removed (0 for keep).
     */
    suspend fun decide(
        context: Context,
        survey: SurveyEntity,
        property: PropertyEntity,
        oldSurveyRaw: String,
        keep: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        val placeId = LegacyMigration.placeIdOf(context, property)
        val surveyUid = Identity.surveyUid(placeId, survey.surveyNo)
        val oldToken = Identity.surveyToken(oldSurveyRaw)
        val state = if (keep) OldSurveyMatcher.State.CONFIRMED else OldSurveyMatcher.State.REJECTED

        val d = db(context)
        SyncDb.createTables(d)
        // The user is speaking — NOT a scraper — so a curated state is allowed to win.
        val row = OldSurveyMatcher.linkRow(surveyUid, oldToken, state, source = "user")
        SyncDb.merge(d, "survey_link", listOf(SyncDb.stamp(d, "survey_link", row, origin = "app")))

        if (keep) return@withContext 0

        // Delete: pull the rejected old number's scans out of the visible manifest + files (the
        // bytes stay content-addressed in the blob store, so a re-keep is free), and tombstone the
        // matching vf_scan rows so a merge cannot resurrect them.
        val removed = VfScansStore.removeByOldSurvey(
            context, property.district, property.taluka, property.village, survey.surveyNo, oldToken,
        )
        val scanRows = SyncDb.allRows(d, "vf_scan")
            .filter { it["survey_uid"] == surveyUid }
            .filter { Identity.surveyToken(it["old_survey"] as? String ?: "") == oldToken }
            .filter { (it["deleted"] as? Long ?: 0L) == 0L }
        if (scanRows.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val localMax = SyncDb.maxUpdatedAt(d)
            val tombstones = scanRows.map { com.landrecords.app.data.sync.MergeEngine.tombstone("vf_scan", it, now, localMax) }
            SyncDb.upsertAll(d, "vf_scan", tombstones)
        }
        removed
    }
}
