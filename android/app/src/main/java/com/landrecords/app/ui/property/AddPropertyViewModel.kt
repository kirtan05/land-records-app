package com.landrecords.app.ui.property

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import kotlinx.coroutines.launch

/** Creates a new property (village) + its surveys so they can be browsed and fetched. */
class AddPropertyViewModel(private val repo: LandRecordsRepository) : ViewModel() {

    /** Save and hand the new property id back (so the caller can open it). */
    fun save(district: String, taluka: String, village: String, surveyNos: List<String>, onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.addProperty(district.trim(), taluka.trim(), village.trim(), surveyNos)
            onSaved(id)
        }
    }
}
