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

    /** Survey row id for a full-place (district/taluka/village) + survey number, or null — links seeded records. */
    suspend fun findSurveyId(district: String, taluka: String, village: String, surveyNo: String): Long? =
        db.surveyDao().findByVillageAndNo(district, taluka, village, surveyNo)?.id

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
    ): Long {
        val existing = db.propertyDao().observeAll().first()
            .firstOrNull { it.district == district && it.taluka == taluka && it.village == village }
        val propId = existing?.id ?: db.propertyDao().upsert(
            PropertyEntity(
                state = "Gujarat", district = district, taluka = taluka, village = village,
                districtGu = districtGu.ifBlank { district },
                talukaGu = talukaGu.ifBlank { taluka },
                villageGu = villageGu.ifBlank { village },
            ),
        )
        for (no in surveyNos.map { it.trim() }.filter { it.isNotEmpty() }) {
            db.surveyDao().upsert(SurveyEntity(propertyId = propId, surveyNo = no, normalized = tokenOf(no)))
        }
        return propId
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
}
