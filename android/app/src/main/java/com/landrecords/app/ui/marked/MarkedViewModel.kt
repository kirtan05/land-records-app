package com.landrecords.app.ui.marked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.db.MarkedRecordRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MarkedViewModel(private val repo: LandRecordsRepository) : ViewModel() {

    /** All marked records with a PDF, ordered by colour then village — grouped on screen. */
    val marked: StateFlow<List<MarkedRecordRow>> =
        repo.observeMarked().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unmark(recordId: Long) {
        viewModelScope.launch { repo.setMark(recordId, null) }
    }
}
