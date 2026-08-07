package com.landrecords.app.ui.property

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import kotlinx.coroutines.launch

/** Creates a new property (village) + its surveys so they can be browsed and fetched. */
class AddPropertyViewModel(private val repo: LandRecordsRepository) : ViewModel() {

    /**
     * Save and hand the new property id back (so the caller can open it). Each geo level is passed
     * as an (English key, Gujarati label) pair so the repository can store the stable English key in
     * the plain field and the cascade-matching Gujarati label in the *Gu field.
     */
    fun save(
        district: String, districtGu: String,
        taluka: String, talukaGu: String,
        village: String, villageGu: String,
        surveyNos: List<String>,
        onSaved: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = repo.addProperty(
                district.trim(), districtGu.trim(),
                taluka.trim(), talukaGu.trim(),
                village.trim(), villageGu.trim(),
                surveyNos,
            )
            onSaved(id)
        }
    }
}
