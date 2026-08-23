package com.landrecords.app.ui.library

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.data.maps.VillageMaps
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.DashedButton
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
import com.landrecords.app.ui.theme.join

@Composable
fun LibraryScreen(
    onOpenSurvey: (Long) -> Unit,
    onFetch: (Long, RecordType) -> Unit,
    onBatchIrcms: (Long, Boolean) -> Unit,
    onAddProperty: () -> Unit,
    onSettings: () -> Unit,
    onMarked: () -> Unit,
    onMaps: () -> Unit,
    onFetchStatus: () -> Unit,
) {
    val app = landApp()
    val appState = app.appState
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { LibraryViewModel(app.repository) } },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val lang = LocalLang.current
    val context = LocalContext.current
    // Village-map lookup is a separate, read-only asset (villages.json); load it once so the
    // per-card map affordance can appear as soon as it's ready.
    var mapsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        VillageMaps.load(context)
        mapsReady = true
    }
    fun openVillageMap(entry: com.landrecords.app.data.maps.VillageMapEntry) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.viewUrl)))
        }
    }
    // Property whose "All cases · 1 code" tap is awaiting a re-fetch confirmation (cases already exist).
    var confirmBatchProp by remember { mutableStateOf<Long?>(null) }
    // Village (id → display name) whose long-press is awaiting a delete confirmation.
    var confirmDeleteProp by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // Survey (id → survey number) whose long-press is awaiting a delete confirmation.
    var confirmDeleteSurvey by remember { mutableStateOf<Pair<Long, String>?>(null) }

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
                    // Language moved into Settings; Fetch status lives here in the top bar so
                    // background-fetch progress is one tap away from the library.
                    SquareIconButton(Icons.Outlined.Sync, onFetchStatus, contentDescription = "Fetch status")
                    Spacer(Modifier.width(8.dp))
                    SquareIconButton(Icons.AutoMirrored.Outlined.Label, onMarked, contentDescription = "Marked records")
                    Spacer(Modifier.width(8.dp))
                    SquareIconButton(Icons.Outlined.Map, onMaps, contentDescription = "Maps")
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
                    val selProp = state.villages.firstOrNull { it.selected }?.propertyId
                    // The batch stays available even once everything's fetched — because a
                    // checked-empty survey (0 cases) is indistinguishable from a never-checked one,
                    // we can't reliably show "all fetched". So keep the button, but if any survey
                    // has already been checked, a tap is a RE-fetch → confirm first (it re-runs ALL
                    // surveys and overwrites; a plain run only fetches surveys never checked).
                    val anyChecked = state.surveys.any { it.counts.containsKey(RecordType.IRCMS) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DashedButton(
                            text = Lr(R.string.add_property_gu, R.string.add_property_en),
                            onClick = onAddProperty,
                            modifier = Modifier.weight(1f),
                        )
                        if (selProp != null && state.surveys.isNotEmpty()) {
                            DashedButton(
                                text = lang.join("બધા કેસ · ૧ કોડ", "All cases · 1 code"),
                                onClick = { if (anyChecked) confirmBatchProp = selProp else onBatchIrcms(selProp, false) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (!state.searching) {
                item {
                    // Villages scroll sideways: fixed-width cards in a LazyRow so a 4th, 5th…
                    // village never squeezes the others into slivers (they used to share one row
                    // via weight). Content-padding lets the last card clear the page gutter.
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.villages, key = { it.propertyId }) { v ->
                            val mapEntry = if (mapsReady) {
                                remember(v.district, v.taluka, v.village, mapsReady) {
                                    VillageMaps.find(v.district, v.taluka, v.village)
                                }
                            } else null
                            VillageCard(
                                name = v.name, helper = v.helper, selected = v.selected,
                                onClick = { vm.selectVillage(v.propertyId) },
                                onLongClick = { confirmDeleteProp = v.propertyId to v.name },
                                onOpenMap = mapEntry?.let { entry -> { openVillageMap(entry) } },
                                modifier = Modifier.width(150.dp),
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
                    onLongClick = { confirmDeleteSurvey = card.survey.id to card.survey.surveyNo },
                )
            }
            item { Spacer(Modifier.height(2.dp)) }
        }
    }

    confirmBatchProp?.let { pid ->
        AlertDialog(
            onDismissRequest = { confirmBatchProp = null },
            title = { Text(lang.join("બધા કેસ ફરી લાવવા?", "Re-fetch all cases?"), style = LandType.bodyStrong, color = Land.colors.ink) },
            text = {
                Text(
                    lang.join(
                        "આ ગામના દરેક સર્વે માટે iRCMS કેસ ફરીથી ડાઉનલોડ થશે અને હાલ સાચવેલા બદલાઈ જશે.",
                        "This re-downloads iRCMS cases for every survey in this village and replaces what's saved.",
                    ),
                    style = LandType.body, color = Land.colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmBatchProp = null; onBatchIrcms(pid, true) }) {
                    Text(lang.join("ફરી લાવો", "Re-fetch"), color = Land.colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchProp = null }) {
                    Text(lang.join("રદ કરો", "Cancel"), color = Land.colors.ink3)
                }
            },
            containerColor = Land.colors.surface,
        )
    }

    confirmDeleteProp?.let { (pid, vname) ->
        AlertDialog(
            onDismissRequest = { confirmDeleteProp = null },
            title = { Text(lang.join("ગામ કાઢી નાખવું?", "Remove village?"), style = LandType.bodyStrong, color = Land.colors.ink) },
            text = {
                Text(
                    lang.join(
                        "“$vname” અને તેના બધા સર્વે એપમાંથી કાઢી નાખશે. Documents/LandRecords માંની PDF ફાઈલો રહેશે.",
                        "Removes \"$vname\" and all its surveys from the app. The PDF files in Documents/LandRecords are kept.",
                    ),
                    style = LandType.body, color = Land.colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteProp = null; vm.deleteProperty(pid) }) {
                    Text(lang.join("કાઢી નાખો", "Remove"), color = Land.colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteProp = null }) {
                    Text(lang.join("રદ કરો", "Cancel"), color = Land.colors.ink3)
                }
            },
            containerColor = Land.colors.surface,
        )
    }

    confirmDeleteSurvey?.let { (sid, no) ->
        AlertDialog(
            onDismissRequest = { confirmDeleteSurvey = null },
            title = { Text(lang.join("સર્વે કાઢી નાખવો?", "Remove survey?"), style = LandType.bodyStrong, color = Land.colors.ink) },
            text = {
                Text(
                    lang.join(
                        "સર્વે નંબર “$no” અને તેના રેકોર્ડ એપમાંથી કાઢી નાખશે. Documents/LandRecords માંની PDF ફાઈલો રહેશે.",
                        "Removes survey \"$no\" and its records from the app. The PDF files in Documents/LandRecords are kept.",
                    ),
                    style = LandType.body, color = Land.colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteSurvey = null; vm.deleteSurvey(sid) }) {
                    Text(lang.join("કાઢી નાખો", "Remove"), color = Land.colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSurvey = null }) {
                    Text(lang.join("રદ કરો", "Cancel"), color = Land.colors.ink3)
                }
            },
            containerColor = Land.colors.surface,
        )
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
