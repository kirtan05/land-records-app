package com.landrecords.app.ui.fetch

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.LandRecordsApp
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.LibraryAccess
import com.landrecords.app.data.storage.VfScansStore
import com.landrecords.app.ui.components.PillButton
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.marked.MarkDot
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.web.PdfMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Loads a survey's captured VF-7/12 scans (vf712.json) + the merged View-all PDF path. */
class VfScansViewModel(
    private val app: LandRecordsApp,
    private val surveyId: Long,
) : ViewModel() {

    data class ScansUi(
        val loaded: Boolean,
        val surveyNo: String,
        val district: String,
        val taluka: String,
        val village: String,
        val villageGu: String,
        val villageLatin: String,
        val scans: List<VfScansStore.ScanEntry>,
        val mergedPdfPath: String?,
    )

    val ui = MutableStateFlow<ScansUi?>(null)

    /** Kept so a delete can record the §2 decision (survey_link) and skip on re-fetch. */
    private var survey: com.landrecords.app.data.db.SurveyEntity? = null
    private var property: com.landrecords.app.data.db.PropertyEntity? = null

    /** Distinct old survey numbers in this survey's scans, each with its scan count. */
    val oldSurveyGroups: List<Pair<String, Int>>
        get() = ui.value?.scans.orEmpty()
            .groupBy { it.oldSurvey.ifBlank { "—" } }
            .map { (old, s) -> old to s.size }
            .sortedBy { it.first }

    /**
     * Remove an old survey number's scans and REMEMBER it (§2 rejected), so a re-fetch never drags
     * it back. Reloads the list in place.
     */
    fun deleteOldSurvey(oldSurvey: String) {
        val s = survey ?: return
        val p = property ?: return
        viewModelScope.launch {
            val removed = com.landrecords.app.fetch.Vf712Curation.decide(app, s, p, oldSurvey, keep = false)
            android.util.Log.i("LR", "deleteOldSurvey $oldSurvey removed $removed scan(s)")
            reload()
        }
    }

    private fun reload() {
        viewModelScope.launch {
            val u = ui.value ?: return@launch
            val scans = withContext(Dispatchers.IO) {
                VfScansStore.read(app, u.district, u.taluka, u.village, u.surveyNo)
            }
            ui.value = u.copy(scans = scans)
        }
    }

    init {
        viewModelScope.launch {
            val snap = app.repository.snapshot(surveyId)
            if (snap == null) {
                ui.value = ScansUi(true, "", "", "", "", "", "", emptyList(), null)
                return@launch
            }
            val (survey, prop) = snap
            this@VfScansViewModel.survey = survey
            this@VfScansViewModel.property = prop
            val scans = VfScansStore.read(app, prop.district, prop.taluka, prop.village, survey.surveyNo)
            val record = app.repository.recordFor(surveyId, RecordType.VF712)
            ui.value = ScansUi(
                loaded = true,
                surveyNo = survey.surveyNo,
                district = prop.district, taluka = prop.taluka, village = prop.village,
                villageGu = prop.villageGu.ifBlank { prop.village },
                villageLatin = prop.village,
                scans = scans,
                mergedPdfPath = record?.pdfPath,
            )
        }
    }

    /** Set/clear one scan's export colour, persisting it and updating the list in place (snappy). */
    fun setMark(scan: VfScansStore.ScanEntry, mark: String?) {
        val u = ui.value ?: return
        viewModelScope.launch {
            VfScansStore.setMark(app, u.district, u.taluka, u.village, u.surveyNo, scan.index.toString(), mark)
            ui.value = u.copy(
                scans = u.scans.map { if (it.index == scan.index) it.copy(mark = mark) else it },
            )
        }
    }
}

/**
 * Per-scan OLD-SCANNED VF-7/12 view. Lists every captured year-wise scan with a checkbox (all on by
 * default) and its period ("1993-2004"); expands to thok/block/old-survey + a per-scan "View scan".
 * Export merges the chosen scans oldest→newest and shares the result; "View all" opens the
 * pre-merged library PDF. The per-scan analogue of the iRCMS Cases screen (no orders concept).
 */
@Composable
fun VfScansScreen(
    surveyId: Long,
    onBack: () -> Unit,
) {
    val app = landApp()
    val context = LocalContext.current
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    val vm: VfScansViewModel = viewModel(
        factory = viewModelFactory { initializer { VfScansViewModel(app, surveyId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    var expanded by remember { mutableStateOf<Int?>(null) }
    val selected = remember { mutableStateListOf<Boolean>() }

    val scans = ui?.scans ?: emptyList()
    // (Re)seed the selection — all scans checked by default — once the manifest loads.
    LaunchedEffect(scans.size) {
        selected.clear()
        selected.addAll(List(scans.size) { true })
    }
    val chosenCount = selected.count { it }

    fun exportSelected() {
        val u = ui ?: return
        val chosen = u.scans.filterIndexed { i, _ -> selected.getOrElse(i) { false } }
        if (chosen.isEmpty()) return
        scope.launch {
            val parts = withContext(Dispatchers.IO) {
                val out = ArrayList<ByteArray>()
                for (s in chosen) {
                    VfScansStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, s.file)
                        ?.let { runCatching { File(it).readBytes() }.getOrNull() }
                        ?.let { out.add(it) }
                }
                out
            }
            val merged = PdfMerge.merge(parts, app.cacheDir)
            if (merged == null || merged.isEmpty()) {
                Toast.makeText(context, "Nothing to export", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val name = "${u.villageLatin.ifBlank { "Land" }}_${u.surveyNo}_VF712_${chosen.size}scans.pdf"
            if (!LibraryAccess.shareBytes(context, merged, name)) {
                Toast.makeText(context, "Couldn't export", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Open a single stored scan file straight from its saved path.
    fun viewScanFile(name: String) {
        val u = ui ?: return
        val path = VfScansStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, name)
        if (!LibraryAccess.view(context, path, "${u.villageLatin.ifBlank { "Land" }} ${u.surveyNo} VF 7-12 scan.pdf")) {
            Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        // ── Header ──────────────────────────────────────────────────────────────────────
        Column(Modifier.background(Land.colors.surface).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("સ્કેન", "Scans"), style = LandType.bodyStrong, color = Land.colors.ink)
                    val sub = ui?.let { "${it.surveyNo} · ${it.villageLatin}" } ?: ""
                    Text(sub, style = LandType.label, color = Land.colors.ink3)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            // Select-all / deselect-all + a live "chosen / total".
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val total = scans.size
                val allSelected = total > 0 && chosenCount == total
                val someSelected = chosenCount in 1 until total
                val toggleAll = {
                    val target = !allSelected
                    for (idx in selected.indices) selected[idx] = target
                }
                Row(
                    Modifier
                        .clip(LandShape.pill)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = total > 0,
                        ) { toggleAll() }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectBox(checked = allSelected, onToggle = toggleAll, partial = someSelected)
                    Text(
                        if (allSelected) L("બધા રદ કરો", "Deselect all") else L("બધા પસંદ કરો", "Select all"),
                        style = LandType.body, color = Land.colors.ink2,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("$chosenCount / $total", style = LandType.metaMono, color = Land.colors.ink3)
            }
            // View all.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                val hasMerged = ui?.mergedPdfPath != null
                OutlinePill(
                    text = L("બધા જુઓ", "View all"),
                    enabled = hasMerged,
                    onClick = {
                        val vn = ui?.let { "${it.villageLatin.ifBlank { "Land" }} ${it.surveyNo} VF 7-12.pdf" }
                        val ok = LibraryAccess.view(context, ui?.mergedPdfPath, vn)
                        if (!ok) Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            // Old survey numbers present in these scans — tap ✕ to remove one you don't want.
            // The removal is remembered (§2), so a re-fetch never drags it back.
            val groups = vm.oldSurveyGroups
            if (groups.size > 1) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((old, count) in groups) {
                        var confirm by remember(old) { mutableStateOf(false) }
                        Row(
                            Modifier
                                .clip(LandShape.pill)
                                .border(1.dp, if (confirm) Land.colors.accent else Land.colors.line, LandShape.pill)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (confirm) { vm.deleteOldSurvey(old); confirm = false } else confirm = true
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("$old · $count", style = LandType.metaMono, color = Land.colors.ink2)
                            Text(
                                if (confirm) L("કાઢી નાખું?", "remove?") else "✕",
                                style = LandType.meta,
                                color = if (confirm) Land.colors.accent else Land.colors.ink3,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
        }

        // ── Body ────────────────────────────────────────────────────────────────────────
        when {
            ui?.loaded != true -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(L("ખૂલી રહ્યું છે…", "Opening…"), style = LandType.metaMono, color = Land.colors.ink3)
            }
            scans.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L("કોઈ સ્કેન સાચવ્યું નથી", "No scans stored yet"), style = LandType.bodyStrong, color = Land.colors.ink2, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        L("આ સર્વે ફરી મેળવો ત્યારે દરેક વર્ષ અલગથી સચવાશે.", "Re-fetch this survey to store each year individually."),
                        style = LandType.meta, color = Land.colors.ink3, textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(scans) { i, s ->
                    ScanRow(
                        index = i + 1,
                        scan = s,
                        checked = selected.getOrElse(i) { false },
                        expanded = expanded == i,
                        mark = s.mark,
                        onSetMark = { vm.setMark(s, it) },
                        onToggleChecked = { if (i < selected.size) selected[i] = !selected[i] },
                        onToggleExpand = { expanded = if (expanded == i) null else i },
                        onViewScan = { viewScanFile(s.file) },
                    )
                }
            }
        }

        // ── Sticky Export ─────────────────────────────────────────────────────────────────
        Column(Modifier.background(Land.colors.surface)) {
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                val enabled = chosenCount > 0
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(LandSize.primaryButton)
                        .clip(LandShape.card)
                        .background(if (enabled) Land.colors.accent else Land.colors.surfaceAlt)
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { exportSelected() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${L("નિકાસ", "Export")} ($chosenCount)",
                        style = LandType.bodyStrong,
                        color = if (enabled) Land.colors.onAccent else Land.colors.ink3,
                    )
                }
            }
        }
    }
}

/** One scan tile: 1dp border, radius 12 — checkbox · n. period · block · status chip · expand. */
@Composable
private fun ScanRow(
    index: Int,
    scan: VfScansStore.ScanEntry,
    checked: Boolean,
    expanded: Boolean,
    mark: String?,
    onSetMark: (String?) -> Unit,
    onToggleChecked: () -> Unit,
    onToggleExpand: () -> Unit,
    onViewScan: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleExpand,
                )
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectBox(checked = checked, onToggle = onToggleChecked)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "$index.",
                        style = LandType.metaMono, color = Land.colors.ink3,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                    // Period is source land data (year span) → shown untranslated; unknown renders "—".
                    Text(
                        scan.period.ifBlank { "—" },
                        style = LandType.bodyStrong, color = Land.colors.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (scan.block.isNotBlank()) {
                    Text(
                        "${L("સર્વે/બ્લોક", "Survey/Block")} ${scan.block}",
                        style = LandType.meta, color = Land.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Per-scan export mark — its own tap opens the colour menu (won't expand the row).
            MarkDot(mark = mark, onSet = onSetMark)
            StatusChip(scan.status)
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = Land.colors.ink3,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            HorizontalDivider(thickness = 1.dp, color = Land.colors.hair)
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyVal(L("થોક વર્ષ", "Period"), scan.period)
                KeyVal(L("થોક નંબર", "Thok no."), scan.thok)
                KeyVal(L("સર્વે/બ્લોક", "Survey/Block"), scan.block)
                KeyVal(L("જૂનો સર્વે", "Old survey"), scan.oldSurvey)

                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton(L("સ્કેન જુઓ", "View scan"), onClick = onViewScan)
                }
            }
        }
    }
}

@Composable
private fun KeyVal(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(key.uppercase(), style = LandType.label, color = Land.colors.ink3, modifier = Modifier.width(96.dp))
        Text(value.ifBlank { "—" }, style = LandType.metaMono, color = Land.colors.ink, modifier = Modifier.weight(1f))
    }
}

/** PDF-status chip. Status is source data → shown untranslated; "Ok" reads as held (accent). */
@Composable
private fun StatusChip(status: String) {
    val ok = status.equals("Ok", ignoreCase = true)
    val bg = if (ok) Land.colors.accentSoft else Land.colors.surfaceAlt
    val fg = if (ok) Land.colors.accent else Land.colors.ink2
    Box(
        Modifier.clip(LandShape.pill).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(status.ifBlank { "—" }.uppercase(), style = LandType.label, color = fg, maxLines = 1)
    }
}

/**
 * A 22dp square checkbox: accent-filled + check when on, 1dp outline when off.
 * [partial] renders an accent-filled box with a dash (the "some selected" select-all state).
 */
@Composable
private fun SelectBox(checked: Boolean, onToggle: () -> Unit, partial: Boolean = false) {
    val on = checked || partial
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) Land.colors.accent else Land.colors.surface)
            .then(if (on) Modifier else Modifier.border(1.dp, Land.colors.line, RoundedCornerShape(6.dp)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        when {
            checked -> Icon(Icons.Outlined.Check, contentDescription = null, tint = Land.colors.onAccent, modifier = Modifier.size(14.dp))
            partial -> Box(Modifier.size(width = 10.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(Land.colors.onAccent))
        }
    }
}

/** Outlined secondary pill (1dp border), dimmed when disabled. */
@Composable
private fun OutlinePill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(LandShape.pill)
            .border(1.dp, if (enabled) Land.colors.line else Land.colors.hair, LandShape.pill)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LandType.body, color = if (enabled) Land.colors.ink else Land.colors.ink3)
    }
}
