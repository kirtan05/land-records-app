package com.landrecords.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.LanguagePill
import com.landrecords.app.ui.components.ParcelTile
import com.landrecords.app.ui.components.PathBreadcrumb
import com.landrecords.app.ui.components.PrimaryButton
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.components.VillageCard
import com.landrecords.app.ui.components.areaLatinHelper
import com.landrecords.app.ui.components.numerals
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Dp4
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lang
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.Lr
import com.landrecords.app.ui.theme.badge

@Composable
fun LibraryScreen(
    onOpenSurvey: (Long) -> Unit,
    onFetch: (Long, RecordType) -> Unit,
    onAddProperty: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = landApp()
    val appState = app.appState
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { LibraryViewModel(app.repository) } },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val lang = LocalLang.current

    val subline = when (lang) {
        Lang.GU -> "${state.totalCount.numerals(Lang.GU)} સર્વે"
        Lang.EN -> "${state.totalCount} surveys"
        Lang.BOTH -> "Your land records · ${state.totalCount} surveys"
    }.uppercase()

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        BlueprintHeader(modifier = Modifier.statusBarsPadding()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Lr(R.string.library_title_gu, R.string.library_title_en), style = LandType.screenTitle, color = Land.colors.ink)
                        Text(subline, style = LandType.label, color = Land.colors.ink3)
                    }
                    LanguagePill(badge = lang.badge(), onClick = { appState.cycleLang() })
                    Spacer(Modifier.width(8.dp))
                    SquareIconButton(Icons.Outlined.Settings, onSettings, contentDescription = "Settings")
                }
                Spacer(Modifier.height(12.dp))
                SearchField(
                    value = state.query,
                    onChange = vm::setQuery,
                    placeholder = Lr(R.string.search_hint_gu, R.string.search_hint_en),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(Dp4.cardGap),
        ) {
            item { PathBreadcrumb(state.breadcrumb) }
            if (!state.searching) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.villages.forEach { v ->
                            VillageCard(
                                name = v.name, helper = v.helper, selected = v.selected,
                                onClick = { vm.selectVillage(v.propertyId) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Lr(R.string.surveys_gu, R.string.surveys_en).uppercase(), style = LandType.label, color = Land.colors.ink3)
                    Spacer(Modifier.weight(1f))
                    Text(state.surveys.size.numerals(lang), style = LandType.label, color = Land.colors.ink3)
                }
            }
            items(state.surveys, key = { it.survey.id }) { card ->
                val queued = card.survey.propertyId in state.queuedProperties
                ParcelTile(
                    surveyNo = card.survey.surveyNo,
                    areaGu = card.survey.area,
                    areaLatin = areaLatinHelper(card.survey.area),
                    tenure = card.survey.tenure,
                    counts = card.counts,
                    queued = queued,
                    onClick = {
                        if (queued) onFetch(card.survey.id, RecordType.INTEGRATED)
                        else onOpenSurvey(card.survey.id)
                    },
                )
            }
            item {
                PrimaryButton(
                    text = Lr(R.string.add_property_gu, R.string.add_property_en),
                    onClick = onAddProperty,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(LandShape.field)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.field)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(11.dp).clip(CircleShape).border(1.dp, Land.colors.ink3, CircleShape))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = LandType.body, color = Land.colors.ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LandType.body.copy(color = Land.colors.ink),
                cursorBrush = SolidColor(Land.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
