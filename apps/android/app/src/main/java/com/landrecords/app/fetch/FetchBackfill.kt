package com.landrecords.app.fetch

import android.content.Context
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.sync.LegacyMigration
import com.landrecords.app.data.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Queue everything that is missing — the way §6's auto-fetch reaches records that already exist.
 *
 * Auto-fetch fires when a property is *added*, which covers everything from here on but nothing
 * already in the library. Dad's phone has 15 surveys that predate the queue entirely, and some of
 * them are missing a record type or two. This walks what is actually held and queues only the
 * gaps.
 *
 * Deliberately conservative about what counts as a gap: a record row with `docCount = 0` means
 * "checked, and there is genuinely nothing there" (no iRCMS cases for this survey, no deeds), and
 * re-queueing those would burn a captcha per survey to re-learn the same nothing. Only a record
 * type with **no row at all** is treated as never fetched.
 */
object FetchBackfill {

    /** What [queueMissing] would do / did. */
    data class Plan(val surveys: Int, val queued: Int, val alreadyHeld: Int, val unresolved: Int)

    /**
     * Queue every (survey × record type) that has never been fetched.
     *
     * Enqueueing is idempotent — a row already `pending`, `running` or `done` is left alone — so
     * this is safe to run repeatedly and will not restart work in flight or redo work finished.
     */
    suspend fun queueMissing(
        context: Context,
        repo: LandRecordsRepository,
        dryRun: Boolean = false,
    ): Plan = withContext(Dispatchers.IO) {
        val cards = repo.observeAllSurveyCards().first()
        var queued = 0
        var alreadyHeld = 0
        var unresolved = 0

        // Property lookup once, rather than per survey.
        val properties = repo.observeProperties().first().associateBy { it.id }

        for (card in cards) {
            val property = properties[card.survey.propertyId]
            if (property == null) { unresolved++; continue }

            val placeId = LegacyMigration.placeIdOf(context, property)
            val surveyUid = Identity.surveyUid(placeId, card.survey.surveyNo)

            val missing = FetchService.ALL_TYPES.filter { name ->
                val type = runCatching { RecordType.valueOf(name) }.getOrNull()
                // No row at all = never fetched. A row with docCount 0 is a real "none".
                type != null && !card.counts.containsKey(type)
            }
            alreadyHeld += FetchService.ALL_TYPES.size - missing.size
            if (missing.isEmpty()) continue

            queued += missing.size
            if (!dryRun) FetchQueue.enqueue(context, surveyUid, missing)
        }

        val plan = Plan(cards.size, queued, alreadyHeld, unresolved)
        android.util.Log.i("LR", "FetchBackfill${if (dryRun) " (dry run)" else ""}: $plan")
        if (!dryRun && queued > 0) FetchService.start(context)
        plan
    }

    /**
     * Re-fetch EVERYTHING — force every survey's every record type back to pending, even ones
     * already fetched. This is the backfill for data an older build captured incompletely: VF-6
     * entries and deeds were missed before the headless entry capture existed, so the only way to
     * get them for surveys already marked done is to fetch the INTEGRATED page again (it now also
     * grabs entries + deeds). Returns how many (survey × type) rows were requeued.
     */
    suspend fun requeueEverything(
        context: Context,
        repo: LandRecordsRepository,
    ): Int = withContext(Dispatchers.IO) {
        val cards = repo.observeAllSurveyCards().first()
        val properties = repo.observeProperties().first().associateBy { it.id }
        var n = 0
        for (card in cards) {
            val property = properties[card.survey.propertyId] ?: continue
            val placeId = LegacyMigration.placeIdOf(context, property)
            val surveyUid = Identity.surveyUid(placeId, card.survey.surveyNo)
            n += FetchQueue.requeue(context, surveyUid, FetchService.ALL_TYPES)
        }
        android.util.Log.i("LR", "FetchBackfill.requeueEverything: $n rows across ${cards.size} surveys")
        if (n > 0) FetchService.start(context)
        n
    }
}
