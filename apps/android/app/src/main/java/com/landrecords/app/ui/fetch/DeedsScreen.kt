package com.landrecords.app.ui.fetch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.LandRecordsApp
import com.landrecords.app.data.storage.DeedsStore
import com.landrecords.app.data.storage.LibraryAccess
import com.landrecords.app.ui.components.PillButton
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Loads a survey's registered Sub-registrar deeds (deeds.json, written by the integrated pass). */
class DeedsViewModel(app: LandRecordsApp, surveyId: Long) : ViewModel() {

    data class DeedsUi(
        val loaded: Boolean,
        val surveyNo: String,
        val district: String,
        val taluka: String,
        val village: String,
        val villageLatin: String,
        val deeds: List<DeedsStore.Deed>,
    )

    val ui = MutableStateFlow<DeedsUi?>(null)

    init {
        viewModelScope.launch {
            val snap = app.repository.snapshot(surveyId)
            if (snap == null) {
                ui.value = DeedsUi(true, "", "", "", "", "", emptyList())
                return@launch
            }
            val (survey, prop) = snap
            ui.value = DeedsUi(
                loaded = true,
                surveyNo = survey.surveyNo,
                district = prop.district, taluka = prop.taluka, village = prop.village,
                villageLatin = prop.village,
                deeds = DeedsStore.read(app, prop.district, prop.taluka, prop.village, survey.surveyNo),
            )
        }
    }
}

/**
 * Registered deeds (સબ-રજીસ્ટ્રાર દસ્તાવેજ) for one survey — one tile per registered document with
 * its SRO office, document number/year, date, consideration amount and every party.
 *
 * These are captured with the INTEGRATED record (same AnyRoR page), not fetched separately, and the
 * deed table is also printed inside the Integrated PDF. A tile only offers "View" when the GARVI
 * server actually served that document's scan — normally it answers "Document Record Not Found".
 */
@Composable
fun DeedsScreen(
    surveyId: Long,
    onBack: () -> Unit,
) {
    val app = landApp()
    val vm: DeedsViewModel = viewModel(
        factory = viewModelFactory { initializer { DeedsViewModel(app, surveyId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val deeds = ui?.deeds ?: emptyList()
    val context = LocalContext.current

    // Only reachable when a scan actually came down (deed.file non-blank) — normally it doesn't.
    fun viewScan(deed: DeedsStore.Deed) {
        val u = ui ?: return
        val path = DeedsStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, deed.file)
        val name = "${u.villageLatin.ifBlank { "Land" }} ${u.surveyNo} Deed ${deed.docNo}-${deed.docYear}.pdf"
        if (!LibraryAccess.view(context, path, name)) {
            android.widget.Toast.makeText(context, "Can't open this file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        Column(Modifier.background(Land.colors.surface).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("નોંધાયેલ દસ્તાવેજ", "Registered deeds"), style = LandType.bodyStrong, color = Land.colors.ink)
                    val sub = ui?.let { "${it.surveyNo} · ${it.villageLatin}" } ?: ""
                    Text(sub, style = LandType.label, color = Land.colors.ink3)
                }
                Text("${deeds.size}", style = LandType.count, color = Land.colors.ink)
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
        }

        when {
            ui?.loaded != true -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(L("ખૂલી રહ્યું છે…", "Opening…"), style = LandType.metaMono, color = Land.colors.ink3)
            }
            deeds.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        L("કોઈ નોંધાયેલ દસ્તાવેજ નથી", "No registered deeds"),
                        style = LandType.bodyStrong, color = Land.colors.ink2, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        L(
                            "સંકલિત રેકોર્ડ સાથે જ દસ્તાવેજ તપાસાય છે — આ સર્વે માટે સબ-રજીસ્ટ્રારમાં કંઈ મળ્યું નથી.",
                            "Deeds are checked together with the Integrated record — the Sub-registrar has nothing for this survey.",
                        ),
                        style = LandType.meta, color = Land.colors.ink3, textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(deeds) { d ->
                    DeedTile(d, onView = { viewScan(d) }.takeIf { d.file.isNotBlank() })
                }
            }
        }
    }
}

/** One registered document: doc no/year + date, office, amount, then every party line. */
@Composable
private fun DeedTile(deed: DeedsStore.Deed, onView: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L("દસ્તાવેજ", "Document").uppercase(), style = LandType.label, color = Land.colors.ink3)
                // Document number/year and date are source land data — printed as the site shows them.
                Text(
                    listOf(deed.docNo, deed.docYear).filter { it.isNotBlank() }.joinToString(" / "),
                    style = LandType.bodyStrong, color = Land.colors.ink,
                )
            }
            if (deed.docDate.isNotBlank()) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(L("તારીખ", "Date").uppercase(), style = LandType.label, color = Land.colors.ink3)
                    Text(deed.docDate, style = LandType.metaMono, color = Land.colors.ink)
                }
            }
            if (onView != null) {
                Spacer(Modifier.width(10.dp))
                PillButton(L("જુઓ", "View"), onClick = onView)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (deed.office.isNotBlank()) DeedChip(L("કચેરી", "Office"), deed.office)
            if (deed.survey.isNotBlank()) DeedChip(L("સર્વે", "Survey"), deed.survey)
            if (deed.amount.isNotBlank()) DeedChip(L("રકમ", "Amount"), deed.amount)
        }
        if (deed.parties.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            deed.parties.forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Party type (આપનાર / લેનાર) and names are land data — never translated.
                    Text(
                        p.type.ifBlank { "—" },
                        style = LandType.label, color = Land.colors.accent,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(p.name.ifBlank { "—" }, style = LandType.body, color = Land.colors.ink, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DeedChip(label: String, value: String) {
    Row(
        Modifier
            .clip(LandShape.pill)
            .background(Land.colors.surfaceAlt)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = LandType.label, color = Land.colors.ink3)
        Text(value, style = LandType.metaMono, color = Land.colors.ink)
    }
}
