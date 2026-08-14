package com.landrecords.app.ui.fetch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.LibraryWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class FetchInfo(
    val surveyNo: String,
    val surveyNorm: String,
    val district: String,
    val taluka: String,
    val village: String,
    val districtGu: String,
    val talukaGu: String,
    val villageGu: String,
)

/** Where a capture is in its lifecycle; the last three map to the Saving screen's steps. */
enum class FetchPhase { SOLVING, READING, BUILDING, FILING, DONE, ERROR }

class FetchViewModel(
    private val repo: LandRecordsRepository,
    private val writer: LibraryWriter,
    private val surveyId: Long,
) : ViewModel() {

    val info: StateFlow<FetchInfo?> = repo.observeSurvey(surveyId).map { survey ->
        survey ?: return@map null
        val prop = repo.propertyById(survey.propertyId)
        FetchInfo(
            surveyNo = survey.surveyNo,
            // Match against the human survey number ("845/અ", "901/p") — AnyRoR's option text
            // uses a slash, not the stored "_" token. norm() in the injector handles digits/પ→p.
            surveyNorm = survey.surveyNo,
            district = prop?.district ?: "",
            taluka = prop?.taluka ?: "",
            village = prop?.village ?: "",
            districtGu = prop?.let { it.districtGu.ifBlank { it.district } } ?: "",
            talukaGu = prop?.let { it.talukaGu.ifBlank { it.taluka } } ?: "",
            villageGu = prop?.let { it.villageGu.ifBlank { it.village } } ?: "",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val phase = MutableStateFlow(FetchPhase.SOLVING)
    var errorMessage: String? = null
        private set

    fun setPhase(p: FetchPhase) { phase.value = p }

    /** Record that a type was checked and holds nothing (e.g. no iRCMS cases) so it won't re-queue. */
    /**
     * Persist the AnyRoR header metadata read off the record page (see
     * [com.landrecords.app.web.AnyRorInjection.summaryJs]). Silently does nothing if the
     * page carried none of it — a missing value must leave the existing one alone.
     */
    suspend fun saveSummary(json: String) {
        val o = runCatching { org.json.JSONObject(json.ifBlank { "{}" }) }.getOrNull() ?: return
        repo.updateSurveyMeta(
            surveyId = surveyId,
            area = o.optString("area"),
            assessment = o.optString("assessment"),
            tenure = o.optString("tenure"),
            landUse = o.optString("landUse"),
            asOf = o.optString("asOf"),
        )
    }

    /** Path of the PDF the last successful [fileCapture] wrote — reused for the deeds row. */
    var lastFiledPdfPath: String? = null
        private set

    /**
     * Record the deeds found during the INTEGRATED pass. Deeds are not fetched separately: they are
     * a section of the same type-8 page, and the deed table is inside the integrated PDF — so the
     * DEEDS row points at that same PDF (keeping the D stamp meaningful) while the per-deed detail
     * lives in DeedsStore. [count] = 0 records "checked, none found" rather than "not fetched".
     */
    suspend fun recordDeeds(count: Int, pdfPath: String?) {
        repo.saveFetchedRecord(
            surveyId, RecordType.DEEDS,
            docCount = count,
            pdfPath = if (count > 0) pdfPath else null,
            sourcePath = null,
        )
    }

    suspend fun markEmpty(type: RecordType) {
        repo.saveFetchedRecord(surveyId, type, docCount = 0, pdfPath = null, sourcePath = null)
    }

    fun fail(message: String) {
        errorMessage = message
        phase.value = FetchPhase.ERROR
    }

    /**
     * File a captured record into the visible library tree and record it in Room.
     * Returns true on success. Doc count firms up per type once the multi-doc capture
     * (VF-7/12 combine, deeds) is wired; a single Integrated PDF counts as 1.
     */
    suspend fun fileCapture(type: RecordType, pdfBytes: ByteArray, rawHtml: String, docCount: Int = 1): Boolean {
        return try {
            val snap = repo.snapshot(surveyId) ?: error("Survey not found")
            val (survey, prop) = snap
            val filed = writer.writeRecord(
                district = prop.district, taluka = prop.taluka, village = prop.village,
                surveyNo = survey.surveyNo, type = type, pdfBytes = pdfBytes, rawHtml = rawHtml,
            )
            repo.saveFetchedRecord(surveyId, type, docCount = docCount, pdfPath = filed.pdfPath, sourcePath = filed.sourcePath)
            lastFiledPdfPath = filed.pdfPath
            android.util.Log.i("LR", "fileCapture OK: pdf='${filed.pdfPath}' source='${filed.sourcePath}' (${pdfBytes.size} bytes)")
            true
        } catch (e: Exception) {
            android.util.Log.e("LR", "fileCapture FAILED", e)
            errorMessage = e.message ?: "Could not save the record"
            phase.value = FetchPhase.ERROR
            false
        }
    }
}
