package com.landrecords.app.ui.fetch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.model.RecordType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FetchInfo(
    val surveyNo: String,
    val district: String,
    val taluka: String,
    val village: String,
)

class FetchViewModel(
    private val repo: LandRecordsRepository,
    surveyId: Long,
) : ViewModel() {

    val info: StateFlow<FetchInfo?> = repo.observeSurvey(surveyId).map { survey ->
        survey ?: return@map null
        val prop = repo.propertyById(survey.propertyId)
        FetchInfo(
            surveyNo = survey.surveyNo,
            district = prop?.district ?: "",
            taluka = prop?.taluka ?: "",
            village = prop?.village ?: "",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Record the fetch once the capture completes. Doc counts firm up when the real
     *  on-device capture (print-to-PDF / VF-7/12 byte fetch) is wired in. */
    fun fileResult(surveyId: Long, type: RecordType, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.saveFetchedRecord(surveyId, type, docCount = 1)
            onDone()
        }
    }
}
