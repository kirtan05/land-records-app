package com.landrecords.app.ui.survey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.data.db.RecordEntity
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.EntriesStore
import com.landrecords.app.data.storage.LibraryAccess
import com.landrecords.app.web.PdfMerge
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.DashedButton
import com.landrecords.app.ui.components.MetaChip
import com.landrecords.app.ui.components.ParcelPlate
import com.landrecords.app.ui.components.PathBreadcrumb
import com.landrecords.app.ui.components.RecordCard
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.components.areaLatinHelper
import com.landrecords.app.ui.components.guToLatinDigits
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Dp4
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr

@Composable
fun SurveyDetailScreen(
    surveyId: Long,
    justAddedType: RecordType?,
    onBack: () -> Unit,
    onFetch: (Long, RecordType) -> Unit,
    onRegenerate: (Long, RecordType) -> Unit,
    onCases: (Long) -> Unit,
    onScans: (Long) -> Unit,
    onEntries: (Long) -> Unit,
) {
    val app = landApp()
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: SurveyDetailViewModel = viewModel(
        factory = viewModelFactory { initializer { SurveyDetailViewModel(app.repository, surveyId) } },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val survey = state.survey
    val scope = rememberCoroutineScope()

    // Merge every captured entry scan into one PDF and open it — the "View all entries" action.
    fun viewAllEntries() {
        val sn = survey?.surveyNo ?: return
        scope.launch {
            val merged = withContext(Dispatchers.IO) {
                val items = EntriesStore.read(app, state.district, state.taluka, state.village, sn)
                    .filter { it.file.isNotBlank() }
                val parts = items.mapNotNull { e ->
                    EntriesStore.filePath(app, state.district, state.taluka, state.village, sn, e.file)
                        ?.let { runCatching { File(it).readBytes() }.getOrNull() }
                }
                val bytes = PdfMerge.merge(parts, app.cacheDir) ?: return@withContext null
                File(app.cacheDir, "AllEntries_${sn.replace('/', '_')}.pdf").apply { writeBytes(bytes) }
            }
            if (merged == null) {
                android.widget.Toast.makeText(context, "Nothing to view", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!LibraryAccess.view(context, merged.absolutePath, "${state.villageLatin.ifBlank { "Land" }} $sn Entries.pdf")) {
                android.widget.Toast.makeText(context, "Can't open this file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // How many entry (નોંધ) scans are on record — gates the "Entries" pill on the Integrated card.
    // Re-reads when the integrated record's doc count changes (i.e. after a re-fetch).
    var entriesCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val integratedDocs = state.records[RecordType.INTEGRATED]?.docCount ?: 0
    androidx.compose.runtime.LaunchedEffect(state.village, survey?.surveyNo, integratedDocs) {
        val sn = survey?.surveyNo
        entriesCount = if (sn != null && state.village.isNotBlank())
            com.landrecords.app.data.storage.EntriesStore.count(app, state.district, state.taluka, state.village, sn)
        else 0
    }

    // Unit shown on the area chip; tapping it cycles through the four.
    var areaUnit by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.landrecords.app.data.jantri.LandArea.Unit.HA_A_M)
    }

    // Jantri (ASR-2011) rate for this survey number, if the village is covered.
    var jantri by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.landrecords.app.data.jantri.JantriResult?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(state.village, state.villageGu, survey?.surveyNo) {
        val sn = survey?.surveyNo
        jantri = if (sn != null) com.landrecords.app.data.jantri.JantriRates.lookup(
            app, state.village, state.villageGu, state.taluka, sn,
        ) else null
    }

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        BlueprintHeader(modifier = Modifier.statusBarsPadding()) {
            Column(Modifier.padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                    Spacer(Modifier.width(12.dp))
                    if (state.breadcrumb.isNotEmpty()) PathBreadcrumb(state.breadcrumb)
                }
                if (survey != null) {
                    Spacer(Modifier.height(14.dp))
                    ParcelPlate(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Lr(R.string.survey_gu, R.string.survey_en).uppercase(), style = LandType.label, color = Land.colors.ink3)
                                Text(survey.surveyNo, style = LandType.surveyHero, color = Land.colors.ink)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(state.villageGu, style = LandType.bodyStrong, color = Land.colors.ink)
                                Text(state.villageLatin, style = LandType.label, color = Land.colors.ink3)
                            }
                        }
                    }
                }
            }
        }

        if (survey == null) return@Column

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(Dp4.cardGap),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dp4.chipGap), verticalArrangement = Arrangement.spacedBy(Dp4.chipGap)) {
                    // Tap the area chip to cycle ha-a-m² → acre → guntha → m².
                    MetaChip(
                        Lr(R.string.meta_area_gu, R.string.meta_area_en),
                        survey.area,
                        com.landrecords.app.data.jantri.LandArea.format(survey.area, areaUnit)
                            .ifBlank { areaLatinHelper(survey.area) },
                        modifier = if (survey.area.isBlank()) Modifier
                        else Modifier.clickable { areaUnit = areaUnit.next() },
                    )
                    MetaChip(Lr(R.string.meta_assessment_gu, R.string.meta_assessment_en), survey.assessment, survey.assessment.guToLatinDigits())
                    MetaChip(Lr(R.string.meta_tenure_gu, R.string.meta_tenure_en), survey.tenure, tenureLatin(survey.tenure))
                    MetaChip(Lr(R.string.meta_landuse_gu, R.string.meta_landuse_en), survey.landUse, landUseLatin(survey.landUse))
                }
            }
            jantri?.let { j ->
                item { JantriCard(j, com.landrecords.app.data.jantri.LandArea.toSqm(survey.area)) }
            }
            items(RecordType.entries.toList()) { type ->
                val record = state.records[type]
                RecordCard(
                    type = type,
                    docCount = record?.docCount ?: 0,
                    asOfGu = record?.asOf ?: "",
                    asOfLatin = (record?.asOf ?: "").guToLatinDigits(),
                    // "Just added" only when something was actually added; a record row with 0
                    // docs means "checked, none found" (e.g. no iRCMS cases / no deeds).
                    justAdded = type == justAddedType && (record?.docCount ?: 0) > 0,
                    checked = record != null,
                    mark = record?.mark,
                    onSetMark = { newMark -> record?.let { vm.setMark(it.id, newMark) } },
                    onView = {
                        // Name the opened copy so a "share from the PDF viewer" carries a good filename.
                        val vil = state.villageLatin.ifBlank { "Land" }
                        val typeName = when (type) {
                            RecordType.INTEGRATED -> "Integrated Record"
                            RecordType.VF712 -> "VF 7-12"
                            RecordType.DEEDS -> "Deeds"
                            RecordType.IRCMS -> "iRCMS Cases"
                        }
                        val ok = LibraryAccess.view(context, record?.pdfPath, "$vil ${survey.surveyNo} $typeName.pdf")
                        if (!ok) android.widget.Toast.makeText(context, "Can't open this file", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onRegenerate = { onRegenerate(surveyId, type) },
                    onShare = {
                        val typeTag = when (type) {
                            RecordType.INTEGRATED -> "Integrated_Survey_Record"
                            RecordType.VF712 -> "VF_7_12"
                            RecordType.DEEDS -> "Registered_Deeds"
                            RecordType.IRCMS -> "iRCMS_Cases"
                        }
                        val vil = state.villageLatin.ifBlank { "Land" }
                        val shareName = "${vil}_${survey.surveyNo}_$typeTag.pdf"
                        val ok = LibraryAccess.share(context, record?.pdfPath, shareName)
                        if (!ok) android.widget.Toast.makeText(context, "Nothing to share yet", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onGet = { onFetch(surveyId, type) },
                    onCases = if (type == RecordType.IRCMS) ({ onCases(surveyId) }) else null,
                    onScans = if (type == RecordType.VF712) ({ onScans(surveyId) }) else null,
                    onEntries = if (type == RecordType.INTEGRATED && entriesCount > 0) ({ onEntries(surveyId) }) else null,
                    onViewAllEntries = if (type == RecordType.INTEGRATED && entriesCount > 0) ({ viewAllEntries() }) else null,
                )
            }
            item {
                DashedButton(
                    text = Lr(R.string.get_more_records_gu, R.string.get_more_records_en),
                    onClick = { onFetch(surveyId, RecordType.INTEGRATED) },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun tenureLatin(gu: String): String = when {
    gu.contains("બીન ખેતી") -> "Non-agri eligible"
    gu.contains("જુની શરત") -> "Old tenure"
    else -> ""
}

private fun landUseLatin(gu: String): String = if (gu.contains("ખેતીલાયક")) "Agricultural" else ""
