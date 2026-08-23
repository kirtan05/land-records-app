package com.landrecords.app.ui.fetch

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.LandRecordsApp
import com.landrecords.app.data.identity.Identity
import com.landrecords.app.data.sync.OldSurveyMatcher
import com.landrecords.app.data.sync.SyncDb
import com.landrecords.app.ui.components.PillButton
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Old survey numbers, curated by the user — §2.
 *
 * VF-7/12 uses a different dropdown (`ddlOldScannedSno`) whose numbers do not map 1:1 onto
 * current survey numbers: `174/p1` may correspond to `174`, `174/1`, `174/2`, … Auto-matching
 * *proposes*; this screen is where the user **decides**, having already seen the documents.
 *
 * Three rules are visible in the UI and enforced in [com.landrecords.app.data.sync.MergeEngine]:
 *
 *  1. **A removal is remembered.** Storing only what he keeps would make auto-matching
 *     re-propose the same wrong candidates on every fetch, forever. "Removed" is a stored
 *     state, not an absence — which is why removed rows stay listed here, greyed, and can be
 *     put back.
 *  2. **The decision is keyed on the pair**, so making it on the phone and on the laptop
 *     produces one row, and a removal propagates to both.
 *  3. **A later auto-match can never overrule him** — a `candidate` may not overwrite a
 *     curated state, however new its timestamp.
 *
 * This is the only data in the system that cannot be recovered by re-fetching.
 */
class OldSurveyLinksViewModel(
    private val app: LandRecordsApp,
    private val surveyId: Long,
) : ViewModel() {

    data class LinkUi(val oldToken: String, val state: String, val source: String)

    data class Ui(
        val loaded: Boolean = false,
        val surveyNo: String = "",
        val surveyUid: String = "",
        val links: List<LinkUi> = emptyList(),
    )

    val ui = MutableStateFlow(Ui())

    private val db by lazy { com.landrecords.app.data.db.AppDatabase.get(app).openHelper.writableDatabase }

    init { reload() }

    fun reload() = viewModelScope.launch {
        val snap = app.repository.snapshot(surveyId)
        if (snap == null) { ui.value = Ui(loaded = true); return@launch }
        val (survey, prop) = snap
        val placeId = withContext(Dispatchers.IO) {
            com.landrecords.app.data.sync.LegacyMigration.placeIdOf(app, prop)
        }
        val surveyUid = Identity.surveyUid(placeId, survey.surveyNo)

        val links = withContext(Dispatchers.IO) {
            SyncDb.createTables(db)
            SyncDb.allRows(db, "survey_link")
                .filter { it["current_survey_uid"] == surveyUid && (it["deleted"] as? Long ?: 0L) == 0L }
                .map {
                    LinkUi(
                        oldToken = it["old_token"] as? String ?: "",
                        state = it["state"] as? String ?: "candidate",
                        source = it["source"] as? String ?: "",
                    )
                }
                .sortedWith(compareBy(
                    { it.state != "candidate" },
                    { com.landrecords.app.data.sync.OldSurveyMatcher.leadingNumber(it.oldToken) ?: Int.MAX_VALUE },
                    { it.oldToken },
                ))
        }
        ui.value = Ui(loaded = true, surveyNo = survey.surveyNo, surveyUid = surveyUid, links = links)
    }

    /**
     * Record a decision. Written through the merge engine like any other row, so the same
     * choice made on two machines converges rather than duplicating.
     */
    fun decide(oldToken: String, state: OldSurveyMatcher.State) = viewModelScope.launch {
        if (state == OldSurveyMatcher.State.REJECTED) {
            // Reject also pulls the scans, so the Scans screen reflects it immediately — exactly
            // like the chip strip. One engine does link + scan removal + tombstone; no duplication.
            val snap = app.repository.snapshot(surveyId) ?: return@launch
            val (survey, prop) = snap
            com.landrecords.app.fetch.Vf712Curation.decide(app, survey, prop, oldToken, keep = false)
        } else {
            val surveyUid = ui.value.surveyUid
            if (surveyUid.isEmpty()) return@launch
            withContext(Dispatchers.IO) {
                val row = OldSurveyMatcher.linkRow(surveyUid, oldToken, state, source = "user")
                // NOT fromScraper: this is the user speaking, and only the user may set a
                // curated state.
                SyncDb.merge(db, "survey_link", listOf(SyncDb.stamp(db, "survey_link", row, origin = "app")))
            }
        }
        reload()
    }
}

@Composable
fun OldSurveyLinksScreen(surveyId: Long, onBack: () -> Unit) {
    val app = landApp()
    val vm: OldSurveyLinksViewModel = viewModel(
        factory = viewModelFactory { initializer { OldSurveyLinksViewModel(app, surveyId) } },
    )
    val ui by vm.ui.collectAsState()
    rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Land.colors.surface)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareIconButton(Icons.Outlined.ChevronLeft, onBack, L("પાછળ", "Back"), size = LandSize.backButton)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(L("જૂના સર્વે નંબર", "Old survey numbers"), style = LandType.screenTitle, color = Land.colors.ink)
                if (ui.surveyNo.isNotEmpty()) {
                    Text(ui.surveyNo, style = LandType.metaMono, color = Land.colors.ink3)
                }
            }
        }
        HorizontalDivider(color = Land.colors.line)

        // The explanation matters as much as the list: the user is being asked to judge a
        // government data quirk, not to fix a bug in the app.
        Text(
            L(
                "VF-7/12 જૂના સર્વે નંબર વાપરે છે, જે અત્યારના નંબર સાથે બરાબર મળતા નથી. " +
                    "નીચેના દસ્તાવેજો જોઈને નક્કી કરો કે કયા આ સર્વેના છે. " +
                    "તમે કાઢી નાખેલો નંબર યાદ રહેશે — ફરી નહીં પુછાય.",
                "VF-7/12 stores old survey numbers, which don't line up exactly with the current " +
                    "one. Look at the documents and decide which belong to this survey. " +
                    "Anything you remove is remembered, so you won't be asked about it again.",
            ),
            style = LandType.meta,
            color = Land.colors.ink3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        when {
            !ui.loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(L("લોડ થાય છે…", "Loading…"), style = LandType.body, color = Land.colors.ink3)
            }

            ui.links.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    L(
                        "હજુ કોઈ જૂનો સર્વે નંબર મળ્યો નથી.",
                        "No old survey numbers have been proposed yet.",
                    ),
                    style = LandType.body, color = Land.colors.ink3,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.links, key = { it.oldToken }) { link ->
                    LinkRow(
                        link = link,
                        onKeep = { vm.decide(link.oldToken, OldSurveyMatcher.State.CONFIRMED) },
                        onRemove = { vm.decide(link.oldToken, OldSurveyMatcher.State.REJECTED) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkRow(
    link: OldSurveyLinksViewModel.LinkUi,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
) {
    val removed = link.state == "rejected"
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Land.colors.line, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                link.oldToken,
                style = LandType.metaMono,
                // A removed row stays visible but reads as inactive — it is knowledge we hold,
                // not a candidate awaiting a decision.
                color = if (removed) Land.colors.ink3 else Land.colors.ink,
            )
            Spacer(Modifier.width(10.dp))
            Text(stateLabel(link.state), style = LandType.meta, color = Land.colors.ink3)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(text = L("રાખો", "Keep"), onClick = onKeep, filled = link.state == "confirmed",)
            PillButton(text = if (removed) L("પાછું ઉમેરો", "Put back") else L("કાઢી નાખો", "Remove"), onClick = if (removed) onKeep else onRemove, filled = false,)
        }
    }
}

@Composable
private fun stateLabel(state: String): String = when (state) {
    "confirmed" -> L("રાખેલ", "kept")
    "rejected" -> L("કાઢી નાખેલ", "removed")
    "manual" -> L("જાતે ઉમેરેલ", "added by you")
    else -> L("સૂચવેલ", "proposed")
}
