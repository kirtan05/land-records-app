package com.landrecords.app.ui.survey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.db.RecordEntity
import com.landrecords.app.data.db.SurveyEntity
import com.landrecords.app.data.model.RecordType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SurveyDetailUiState(
    val survey: SurveyEntity? = null,
    val breadcrumb: List<String> = emptyList(),
    val villageGu: String = "",
    val villageLatin: String = "",
    val records: Map<RecordType, RecordEntity?> = emptyMap(),
    val loaded: Boolean = false,
)

class SurveyDetailViewModel(
    repo: LandRecordsRepository,
    surveyId: Long,
) : ViewModel() {

    val uiState: StateFlow<SurveyDetailUiState> = combine(
        repo.observeSurvey(surveyId),
        repo.observeRecords(surveyId),
        repo.observeProperties(),
    ) { survey, records, props ->
        val prop = props.firstOrNull { it.id == survey?.propertyId }
        SurveyDetailUiState(
            survey = survey,
            breadcrumb = prop?.let {
                listOf(it.districtGu.ifBlank { it.district }, it.talukaGu.ifBlank { it.taluka }, it.villageGu.ifBlank { it.village })
            } ?: emptyList(),
            villageGu = prop?.let { it.villageGu.ifBlank { it.village } } ?: "",
            villageLatin = prop?.let { "${it.village} · ${it.taluka}" } ?: "",
            records = RecordType.entries.associateWith { t -> records.firstOrNull { it.type == t } },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SurveyDetailUiState())
}
