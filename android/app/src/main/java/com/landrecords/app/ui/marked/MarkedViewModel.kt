package com.landrecords.app.ui.marked

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.CasesStore
import com.landrecords.app.data.storage.MarkedItems
import com.landrecords.app.data.storage.VfScansStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Marked / batch-export screen. Folds together the two sources of colour marks:
 *   • whole records — reactive, from Room ([LandRecordsRepository.observeMarked])
 *   • individual iRCMS cases + VF-7/12 scans — from JSON manifests, walked by [MarkedItems]
 * into one flat [Row] list the screen groups by colour, so each colour's "Send all"/"Print"
 * exports records, cases and scans together.
 */
class MarkedViewModel(
    private val repo: LandRecordsRepository,
    private val context: Context,
) : ViewModel() {

    /** Where an unmark applies — a Room record, or a case/scan row inside a JSON manifest. */
    sealed interface Handle {
        data class Record(val recordId: Long) : Handle
        data class Case(
            val district: String, val taluka: String, val village: String,
            val surveyNo: String, val itemId: String,
        ) : Handle
        data class Scan(
            val district: String, val taluka: String, val village: String,
            val surveyNo: String, val itemId: String,
        ) : Handle
    }

    /**
     * One export row on the Marked screen. [line2Gu]/[line2En] is the record-type label (localised)
     * or, for a case/scan, its own iRCMS/VF descriptor (land data — same in both languages).
     */
    data class Row(
        val mark: String,
        val villageGu: String,
        val villageEn: String,
        val surveyNo: String,
        val line2Gu: String,
        val line2En: String,
        val pdfPath: String,
        val exportName: String,
        val handle: Handle,
    )

    // Marked cases/scans — re-walked on demand (no reactive source); records stay live via Room.
    private val extras = MutableStateFlow<List<Row>>(emptyList())

    val rows: StateFlow<List<Row>> =
        combine(repo.observeMarked(), extras) { records, exs ->
            records.map { r ->
                Row(
                    mark = r.mark,
                    villageGu = r.villageGu, villageEn = r.villageEn, surveyNo = r.surveyNo,
                    line2Gu = r.type.gujaratiLabel, line2En = r.type.englishLabel,
                    pdfPath = r.pdfPath,
                    exportName = "${r.villageEn} ${r.surveyNo} ${exportLabel(r.type)}.pdf",
                    handle = Handle.Record(r.recordId),
                )
            } + exs
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { reload() }

    /** Re-walk the JSON manifests for marked cases/scans. Call when the screen (re)appears. */
    fun reload() {
        viewModelScope.launch {
            // Map the English place key → the village's Gujarati name, so a case/scan reads the same
            // as a marked record of the same village (the manifests only hold the English folder name).
            val guByPlace = repo.observeProperties().first().associate {
                placeKey(it.district, it.taluka, it.village) to it.villageGu
            }
            extras.value = MarkedItems.collect(context).map { e ->
                val villageGu = guByPlace[placeKey(e.district, e.taluka, e.village)]?.ifBlank { e.village } ?: e.village
                when (e.kind) {
                    MarkedItems.Kind.CASE -> Row(
                        mark = e.mark, villageGu = villageGu, villageEn = e.village, surveyNo = e.surveyNo,
                        line2Gu = "iRCMS · ${e.detail}", line2En = "iRCMS · ${e.detail}",
                        pdfPath = e.pdfPath,
                        exportName = "${e.village} ${e.surveyNo} case ${e.detail}.pdf",
                        handle = Handle.Case(e.district, e.taluka, e.village, e.surveyNo, e.itemId),
                    )
                    MarkedItems.Kind.SCAN -> Row(
                        mark = e.mark, villageGu = villageGu, villageEn = e.village, surveyNo = e.surveyNo,
                        line2Gu = "VF 7-12 · ${e.detail}", line2En = "VF 7-12 · ${e.detail}",
                        pdfPath = e.pdfPath,
                        exportName = "${e.village} ${e.surveyNo} scan ${e.detail}.pdf",
                        handle = Handle.Scan(e.district, e.taluka, e.village, e.surveyNo, e.itemId),
                    )
                }
            }
        }
    }

    /** Clear a row's colour — Room record (auto re-emits) or a manifest case/scan (then reload). */
    fun unmark(row: Row) {
        viewModelScope.launch {
            when (val h = row.handle) {
                is Handle.Record -> repo.setMark(h.recordId, null)
                is Handle.Case -> {
                    CasesStore.setMark(context, h.district, h.taluka, h.village, h.surveyNo, h.itemId, null)
                    reload()
                }
                is Handle.Scan -> {
                    VfScansStore.setMark(context, h.district, h.taluka, h.village, h.surveyNo, h.itemId, null)
                    reload()
                }
            }
        }
    }

    private fun placeKey(district: String, taluka: String, village: String): String =
        "${district.trim().lowercase()}|${taluka.trim().lowercase()}|${village.trim().lowercase()}"

    /** Short, filename-friendly English label per record type (used for the exported PDF names). */
    private fun exportLabel(type: RecordType): String = when (type) {
        RecordType.INTEGRATED -> "Integrated Record"
        RecordType.VF712 -> "VF 7-12"
        RecordType.DEEDS -> "Deeds"
        RecordType.IRCMS -> "iRCMS Cases"
    }
}
