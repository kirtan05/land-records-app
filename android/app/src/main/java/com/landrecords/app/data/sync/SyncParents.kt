package com.landrecords.app.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.landrecords.app.data.db.PropertyEntity
import com.landrecords.app.data.db.SurveyEntity
import com.landrecords.app.data.identity.Identity

/**
 * Writes the PLACE and SURVEY *parent* sync rows.
 *
 * DB review B1: the only writer of `place`/`place_name`/`survey`/`survey_alias` used to be
 * [LegacyMigration], which runs once at upgrade. A property added — or a survey fetched — after
 * that wrote only Room, so a later fetch emitted synced *children* (`record_set`, `vf_scan`,
 * `entry`, `ircms_case`, `deed_link`) keyed to a `survey_uid` whose `survey`/`place` rows did not
 * exist. Exported, those are orphans the other device cannot reconstruct.
 *
 * The row BUILDERS here are the single definition of each parent row's shape, called by the
 * migration, the add path, and the filer alike, so the three can never drift. Everything is
 * idempotent: the rows are identical to the migration's, so a re-write merges as a no-op.
 */
object SyncParents {

    /** "gu" for any string containing a Gujarati codepoint, else "en". */
    fun scriptOf(s: String): String =
        if (s.any { it.code in 0x0A80..0x0AFF }) "gu" else "en"

    fun placeRow(placeId: String, codes: Triple<String, String, String>?): Map<String, Any?> =
        mapOf(
            "uid" to placeId,
            "district_code" to codes?.first,
            "taluka_code" to codes?.second,
            "village_code" to codes?.third,
        )

    fun placeNameRows(
        placeId: String,
        district: String, taluka: String, village: String,
        districtGu: String, talukaGu: String, villageGu: String,
    ): List<Map<String, Any?>> {
        // Names become attributes, never keys — the Nadiad duplicate dies here.
        val names = buildList {
            if (district.isNotBlank()) add(Triple("district", scriptOf(district), district))
            if (taluka.isNotBlank()) add(Triple("taluka", scriptOf(taluka), taluka))
            if (village.isNotBlank()) add(Triple("village", scriptOf(village), village))
            if (districtGu.isNotBlank()) add(Triple("district", "gu", districtGu))
            if (talukaGu.isNotBlank()) add(Triple("taluka", "gu", talukaGu))
            if (villageGu.isNotBlank()) add(Triple("village", "gu", villageGu))
        }
        return names.map { (level, script, name) ->
            mapOf(
                "uid" to Identity.uid("pn", placeId, level, script, name),
                "place_id" to placeId, "script" to script,
                "source" to "legacy:$level", "name" to name,
            )
        }
    }

    fun surveyRow(
        placeId: String, surveyUid: String, token: String, surveyNo: String,
        area: String?, assessment: String?, tenure: String?, landUse: String?, asOf: String?,
    ): Map<String, Any?> =
        mapOf(
            "uid" to surveyUid, "place_id" to placeId, "token" to token, "survey_no" to surveyNo,
            "area" to area, "assessment" to assessment,
            "tenure" to tenure, "land_use" to landUse, "as_of" to asOf,
        )

    /** One row per original raw form, deduped and blank-filtered (survey_no first). */
    fun aliasRows(surveyUid: String, vararg raws: String): List<Map<String, Any?>> =
        linkedSetOf(*raws).filter { it.isNotBlank() }.map { raw ->
            mapOf(
                "uid" to Identity.uid("sa", surveyUid, raw),
                "survey_uid" to surveyUid, "raw" to raw, "source" to "legacy",
            )
        }

    /**
     * Upsert place + survey parents for a property and its surveys, keyed off the SAME placeId
     * every other resolver uses (see [LegacyMigration.placeIdOf]). Call from the add path and
     * before filing a fetched child. Runs on the caller's dispatcher; failures are the caller's
     * to swallow (a missing parent is a sync-completeness issue, never a reason to fail an add or
     * a fetch).
     */
    suspend fun upsert(
        context: Context,
        db: SupportSQLiteDatabase,
        property: PropertyEntity,
        surveys: List<SurveyEntity>,
    ) {
        SyncDb.createTables(db)
        val codes = LegacyMigration.resolveCodesBoth(
            context, property.district, property.taluka, property.village,
            property.districtGu, property.talukaGu, property.villageGu,
        )
        val placeId = if (codes != null) Identity.placeId(codes.first, codes.second, codes.third)
        else Identity.provisionalPlaceId(property.district, property.taluka, property.village)

        merge(db, "place", listOf(placeRow(placeId, codes)))
        merge(db, "place_name", placeNameRows(
            placeId, property.district, property.taluka, property.village,
            property.districtGu, property.talukaGu, property.villageGu,
        ))
        for (s in surveys) {
            val token = Identity.surveyToken(s.surveyNo)
            if (token.isEmpty()) continue
            val surveyUid = "$placeId/$token"
            merge(db, "survey", listOf(surveyRow(
                placeId, surveyUid, token, s.surveyNo,
                s.area, s.assessment, s.tenure, s.landUse, s.asOf,
            )))
            merge(db, "survey_alias", aliasRows(surveyUid, s.surveyNo, s.normalized))
        }
    }

    private const val ORIGIN = "app"
    private fun merge(db: SupportSQLiteDatabase, table: String, rows: List<Map<String, Any?>>) {
        if (rows.isEmpty()) return
        SyncDb.merge(db, table, rows.map { SyncDb.stamp(db, table, it, ORIGIN) })
    }
}
