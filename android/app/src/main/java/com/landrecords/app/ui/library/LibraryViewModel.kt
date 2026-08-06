package com.landrecords.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.SurveyWithCounts
import com.landrecords.app.data.db.PropertyEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class VillageChip(
    val propertyId: Long,
    val name: String,
    val helper: String,
    val selected: Boolean,
)

data class LibraryUiState(
    val breadcrumb: List<String> = listOf("ગુજરાત"),
    val villages: List<VillageChip> = emptyList(),
    val surveys: List<SurveyWithCounts> = emptyList(),
    val query: String = "",
    val totalCount: Int = 0,
    val searching: Boolean = false,
    val villageForSurvey: Map<Long, String> = emptyMap(),
    val queuedProperties: Set<Long> = emptySet(),
)

class LibraryViewModel(private val repo: LandRecordsRepository) : ViewModel() {
    private val selectedId = MutableStateFlow<Long?>(null)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<LibraryUiState> = combine(
        repo.observeProperties(),
        repo.observeAllSurveyCards(),
        selectedId,
        query,
    ) { props, cards, sel, q ->
        val selId = sel ?: props.firstOrNull()?.id
        val prop = props.firstOrNull { it.id == selId }
        val countFor = { pid: Long -> cards.count { it.survey.propertyId == pid } }
        val queued = props.filter { p -> cards.filter { it.survey.propertyId == p.id }.all { it.counts.isEmpty() } && countFor(p.id) > 0 }
            .map { it.id }.toSet()

        val villages = props.map { p ->
            VillageChip(
                propertyId = p.id,
                name = p.villageGu.ifBlank { p.village },
                helper = "${p.village} · ${countFor(p.id)}",
                selected = p.id == selId,
            )
        }

        val list = if (q.isBlank()) {
            cards.filter { it.survey.propertyId == selId }
        } else {
            cards.filter { c ->
                val cp = props.firstOrNull { it.id == c.survey.propertyId }
                c.survey.surveyNo.contains(q, ignoreCase = true) ||
                    c.survey.normalized.contains(q.uppercase()) ||
                    cp?.village?.contains(q, ignoreCase = true) == true ||
                    cp?.villageGu?.contains(q) == true
            }
        }

        LibraryUiState(
            breadcrumb = prop.breadcrumb(),
            villages = villages,
            surveys = list,
            query = q,
            totalCount = cards.size,
            searching = q.isNotBlank(),
            villageForSurvey = props.associate { it.id to (it.villageGu.ifBlank { it.village }) },
            queuedProperties = queued,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun selectVillage(propertyId: Long) {
        selectedId.value = propertyId
        query.value = ""
    }

    fun setQuery(value: String) {
        query.value = value
    }
}

private fun PropertyEntity?.breadcrumb(): List<String> =
    if (this == null) listOf("ગુજરાત")
    else listOf(
        "ગુજરાત",
        districtGu.ifBlank { district },
        talukaGu.ifBlank { taluka },
        villageGu.ifBlank { village },
    )
