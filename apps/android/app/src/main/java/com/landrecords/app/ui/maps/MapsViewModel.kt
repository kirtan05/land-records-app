package com.landrecords.app.ui.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.maps.VillageMapEntry
import com.landrecords.app.data.maps.VillageMaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MapsUiState(
    val loading: Boolean = true,
    val districts: List<String> = emptyList(),
    val talukas: List<String> = emptyList(),
    val villages: List<VillageMapEntry> = emptyList(),
    val selectedDistrict: String? = null,
    val selectedTaluka: String? = null,
    val query: String = "",
    val searchResults: List<Pair<String, VillageMapEntry>> = emptyList(),
)

class MapsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MapsUiState())
    val uiState: StateFlow<MapsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            VillageMaps.load(getApplication())
            _uiState.value = _uiState.value.copy(loading = false, districts = VillageMaps.districts())
        }
    }

    fun selectDistrict(district: String) {
        val talukas = VillageMaps.talukas(district)
        _uiState.value = _uiState.value.copy(
            selectedDistrict = district,
            talukas = talukas,
            selectedTaluka = null,
            villages = emptyList(),
            query = "",
            searchResults = emptyList(),
        )
    }

    fun selectTaluka(taluka: String) {
        val district = _uiState.value.selectedDistrict ?: return
        _uiState.value = _uiState.value.copy(
            selectedTaluka = taluka,
            villages = VillageMaps.villages(district, taluka),
            query = "",
            searchResults = emptyList(),
        )
    }

    fun setQuery(query: String) {
        val district = _uiState.value.selectedDistrict
        val results = if (district != null && query.isNotBlank()) VillageMaps.search(district, query) else emptyList()
        _uiState.value = _uiState.value.copy(query = query, searchResults = results)
    }
}
