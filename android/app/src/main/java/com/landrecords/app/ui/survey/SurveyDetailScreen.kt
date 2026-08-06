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
import androidx.compose.foundation.background
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
    onView: (RecordEntity) -> Unit,
    onShare: (RecordEntity) -> Unit,
    onRegenerate: (RecordEntity) -> Unit,
) {
    val app = landApp()
    val vm: SurveyDetailViewModel = viewModel(
        factory = viewModelFactory { initializer { SurveyDetailViewModel(app.repository, surveyId) } },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val survey = state.survey

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
                    MetaChip(Lr(R.string.meta_area_gu, R.string.meta_area_en), survey.area, areaLatinHelper(survey.area))
                    MetaChip(Lr(R.string.meta_assessment_gu, R.string.meta_assessment_en), survey.assessment, survey.assessment.guToLatinDigits())
                    MetaChip(Lr(R.string.meta_tenure_gu, R.string.meta_tenure_en), survey.tenure, tenureLatin(survey.tenure))
                    MetaChip(Lr(R.string.meta_landuse_gu, R.string.meta_landuse_en), survey.landUse, landUseLatin(survey.landUse))
                    MetaChip(Lr(R.string.as_of_gu, R.string.as_of_en), survey.asOf, survey.asOf.guToLatinDigits())
                }
            }
            items(RecordType.entries.toList()) { type ->
                val record = state.records[type]
                RecordCard(
                    type = type,
                    docCount = record?.docCount ?: 0,
                    asOfGu = record?.asOf ?: "",
                    asOfLatin = (record?.asOf ?: "").guToLatinDigits(),
                    justAdded = type == justAddedType,
                    onView = { record?.let(onView) },
                    onRegenerate = { record?.let(onRegenerate) },
                    onShare = { record?.let(onShare) },
                    onGet = { onFetch(surveyId, type) },
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
