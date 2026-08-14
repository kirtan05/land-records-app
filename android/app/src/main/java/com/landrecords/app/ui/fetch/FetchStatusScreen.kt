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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.LandRecordsApp
import com.landrecords.app.data.sync.LegacyMigration
import com.landrecords.app.fetch.FetchQueue
import com.landrecords.app.fetch.FetchService
import com.landrecords.app.ui.components.PillButton
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.data.db.PropertyEntity
import com.landrecords.app.data.db.SurveyEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What the background fetch is doing — the visible answer to "what's running / pending / failed?"
 * (spec §6: "files appear in the library as they land, plus a per-survey status line").
 *
 * A plain functional surface, deliberately not part of the designed survey cards: it reads the
 * durable `fetch_queue` and polls it a few times a second while open, so progress is live without
 * needing adb. Each row is labelled by the survey's readable name rather than its uid.
 */
class FetchStatusViewModel(private val app: LandRecordsApp) : ViewModel() {

    data class Row(
        val label: String,
        val recordType: String,
        val state: String,
        val attempts: Int,
        val error: String?,
    )

    data class Ui(
        val counts: Map<String, Int> = emptyMap(),
        val rows: List<Row> = emptyList(),
        val loaded: Boolean = false,
        /** Wall-clock ms the current (or last) run has taken; 0 if it never ran. */
        val elapsedMs: Long = 0,
        val running: Boolean = false,
    ) {
        val active: Int get() = (counts["pending"] ?: 0) + (counts["running"] ?: 0)
        val done: Int get() = counts["done"] ?: 0
        val failed: Int get() = counts["failed"] ?: 0
    }

    val ui = MutableStateFlow(Ui())

    init {
        // Poll while the screen is subscribed — the queue is written by a separate service
        // process, so a Room Flow wouldn't see those writes anyway; a short poll is simplest.
        viewModelScope.launch {
            // survey_uid -> readable "Village · surveyNo", built once from the library.
            val names = HashMap<String, String>()
            runCatching {
                val propList: List<PropertyEntity> = app.repository.observeProperties().first()
                for (p in propList) {
                    val placeId = LegacyMigration.placeIdOf(app, p)
                    val surveys: List<SurveyEntity> = app.repository.observeSurveys(p.id).first()
                    for (s in surveys) {
                        names["$placeId/${s.normalized}"] =
                            "${p.villageGu.ifBlank { p.village }} · ${s.surveyNo}"
                    }
                }
            }
            while (true) {
                val counts = FetchQueue.counts(app)
                val rows = FetchQueue.all(app).map {
                    Row(
                        label = names[it.surveyUid] ?: it.surveyUid.substringAfterLast('/'),
                        recordType = it.recordType,
                        state = it.state,
                        attempts = it.attempts,
                        error = it.lastError,
                    )
                }
                val (startedAt, endedAt) = FetchService.runTimes(app)
                val running = startedAt > 0 && endedAt < startedAt
                val elapsed = when {
                    startedAt <= 0 -> 0L
                    running -> System.currentTimeMillis() - startedAt
                    else -> endedAt - startedAt
                }
                ui.value = Ui(counts, rows, loaded = true, elapsedMs = elapsed, running = running)
                // Tick once a second while running (so the timer moves), slower when idle.
                delay(if (running) 1_000 else 2_500)
            }
        }
    }

    fun retryFailed() = viewModelScope.launch {
        FetchQueue.retryFailed(app, null)
        FetchService.start(app)
    }

    /** Queue only the record types never fetched (fills gaps without redoing work). */
    fun fetchMissing() = viewModelScope.launch {
        com.landrecords.app.fetch.FetchBackfill.queueMissing(app, app.repository)
    }

    /** Force a full re-fetch of every survey — the backfill for the missed VF-6 entries + deeds. */
    fun refetchAll() = viewModelScope.launch {
        com.landrecords.app.fetch.FetchBackfill.requeueEverything(app, app.repository)
    }
}

@Composable
fun FetchStatusScreen(onBack: () -> Unit) {
    val app = landApp()
    val vm: FetchStatusViewModel = viewModel(
        factory = viewModelFactory { initializer { FetchStatusViewModel(app) } },
    )
    val ui by vm.ui.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Land.colors.surface)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareIconButton(Icons.Outlined.ChevronLeft, onBack, L("પાછળ", "Back"), size = LandSize.backButton)
            Spacer(Modifier.width(12.dp))
            Text(L("મેળવવાની સ્થિતિ", "Fetch status"), style = LandType.screenTitle, color = Land.colors.ink)
        }
        HorizontalDivider(color = Land.colors.line)

        // One-line summary — the answer at a glance.
        val summary = when {
            !ui.loaded -> L("લોડ થાય છે…", "Loading…")
            ui.active == 0 && ui.failed == 0 ->
                L("બધું પૂરું · ${ui.done} મળ્યાં", "All done · ${ui.done} fetched")
            else -> buildString {
                append(L("${ui.active} બાકી", "${ui.active} to go"))
                append(" · ${ui.done} ")
                append(L("પૂરાં", "done"))
                if (ui.failed > 0) append(" · ${ui.failed} " + L("નિષ્ફળ", "failed"))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(summary, style = LandType.body, color = Land.colors.ink)
                if (ui.elapsedMs > 0) {
                    val verb = if (ui.running) L("ચાલુ", "running") else L("સમય", "took")
                    Text("$verb ${formatElapsed(ui.elapsedMs)}", style = LandType.metaMono, color = Land.colors.ink3)
                }
            }
            if (ui.failed > 0) {
                PillButton(text = L("ફરી પ્રયત્ન", "Retry failed"), onClick = { vm.retryFailed() }, filled = true)
            }
        }

        // Actions: fill gaps, or force a full re-fetch (backfills the VF-6 entries + deeds that
        // an earlier build missed). "Re-fetch all" asks first — it re-pulls everything.
        var confirmAll by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(text = L("બાકી મેળવો", "Fetch missing"), onClick = { vm.fetchMissing() }, filled = false)
            PillButton(
                text = if (confirmAll) L("ખાતરી? બધું ફરી", "Sure? re-fetch all") else L("બધું ફરી મેળવો", "Re-fetch all"),
                onClick = { if (confirmAll) { vm.refetchAll(); confirmAll = false } else confirmAll = true },
                filled = false,
            )
        }

        when {
            !ui.loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(L("લોડ થાય છે…", "Loading…"), style = LandType.body, color = Land.colors.ink3)
            }
            ui.rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    L("કતારમાં કંઈ નથી.", "Nothing in the queue."),
                    style = LandType.body, color = Land.colors.ink3,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.rows, key = { it.label + it.recordType }) { StatusRow(it) }
            }
        }
    }
}

@Composable
private fun StatusRow(row: FetchStatusViewModel.Row) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Land.colors.line, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(row.label, style = LandType.metaMono, color = Land.colors.ink)
                Text(row.recordType, style = LandType.meta, color = Land.colors.ink3)
            }
            StatusPill(row.state)
        }
        if (row.state == "failed" && !row.error.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(row.error, style = LandType.meta, color = Land.colors.ink3)
        }
    }
}

/** "4m 12s" / "48s" — a compact human duration for the run timer. */
private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

@Composable
private fun StatusPill(state: String) {
    // Traffic-light: green done, ochre running, muted pending, red failed. No new palette —
    // reuse the theme's accent + a red only for failure so it can't be missed.
    val (label, color) = when (state) {
        "done" -> L("પૂરું", "done") to Color(0xFF2E7D32)
        "running" -> L("ચાલુ", "running") to Land.colors.accent
        "failed" -> L("નિષ્ફળ", "failed") to Color(0xFFC62828)
        else -> L("બાકી", "waiting") to Land.colors.ink3
    }
    Text(label, style = LandType.label.copy(fontWeight = FontWeight.SemiBold), color = color)
}
