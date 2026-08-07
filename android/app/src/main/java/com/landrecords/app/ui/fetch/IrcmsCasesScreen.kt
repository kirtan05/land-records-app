package com.landrecords.app.ui.fetch

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.landrecords.app.data.storage.CasesStore
import com.landrecords.app.data.storage.LibraryAccess
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.join
import com.landrecords.app.web.PdfMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Loads a survey's captured iRCMS cases (cases.json) + the merged View-all PDF path. */
class IrcmsCasesViewModel(
    private val app: LandRecordsApp,
    private val surveyId: Long,
) : ViewModel() {

    data class CasesUi(
        val loaded: Boolean,
        val surveyNo: String,
        val district: String,
        val taluka: String,
        val village: String,
        val villageGu: String,
        val villageLatin: String,
        val cases: List<CasesStore.CaseEntry>,
        val mergedPdfPath: String?,
    )

    val ui = MutableStateFlow<CasesUi?>(null)

    init {
        viewModelScope.launch {
            val snap = app.repository.snapshot(surveyId)
            if (snap == null) {
                ui.value = CasesUi(true, "", "", "", "", "", "", emptyList(), null)
                return@launch
            }
            val (survey, prop) = snap
            val cases = CasesStore.read(app, prop.district, prop.taluka, prop.village, survey.surveyNo)
            val record = app.repository.recordFor(surveyId, RecordType.IRCMS)
            ui.value = CasesUi(
                loaded = true,
                surveyNo = survey.surveyNo,
                district = prop.district, taluka = prop.taluka, village = prop.village,
                villageGu = prop.villageGu.ifBlank { prop.village },
                villageLatin = prop.village,
                cases = cases,
                mergedPdfPath = record?.pdfPath,
            )
        }
    }
}

/**
 * Per-case iRCMS view. Lists every captured case with a checkbox (all on by default), a
 * PENDING/DISPOSED chip and its parties; expands to office/date/survey. A header "Include orders"
 * toggle folds each disposed case's order PDF in right after its detail. Export merges the chosen
 * cases and shares the result; "View all" opens the pre-merged library PDF.
 */
@Composable
fun IrcmsCasesScreen(
    surveyId: Long,
    onBack: () -> Unit,
) {
    val app = landApp()
    val context = LocalContext.current
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    val vm: IrcmsCasesViewModel = viewModel(
        factory = viewModelFactory { initializer { IrcmsCasesViewModel(app, surveyId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    var includeOrders by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    val selected = remember { mutableStateListOf<Boolean>() }

    val cases = ui?.cases ?: emptyList()
    // (Re)seed the selection — all cases checked by default — once the manifest loads.
    LaunchedEffect(cases.size) {
        selected.clear()
        selected.addAll(List(cases.size) { true })
    }
    val chosenCount = selected.count { it }

    fun exportSelected() {
        val u = ui ?: return
        val chosen = u.cases.filterIndexed { i, _ -> selected.getOrElse(i) { false } }
        if (chosen.isEmpty()) return
        scope.launch {
            val parts = withContext(Dispatchers.IO) {
                val out = ArrayList<ByteArray>()
                for (c in chosen) {
                    CasesStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, c.detailFile)
                        ?.let { runCatching { File(it).readBytes() }.getOrNull() }
                        ?.let { out.add(it) }
                    if (includeOrders && c.hasOrder) {
                        CasesStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, c.orderFile)
                            ?.let { runCatching { File(it).readBytes() }.getOrNull() }
                            ?.let { out.add(it) }
                    }
                }
                out
            }
            val merged = PdfMerge.merge(parts, app.cacheDir)
            if (merged == null || merged.isEmpty()) {
                Toast.makeText(context, "Nothing to export", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val name = "${u.villageLatin.ifBlank { "Land" }}_${u.surveyNo}_iRCMS_${chosen.size}cases.pdf"
            if (!LibraryAccess.shareBytes(context, merged, name)) {
                Toast.makeText(context, "Couldn't export", Toast.LENGTH_SHORT).show()
            }
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
                    Text(L("કેસ", "Cases"), style = LandType.bodyStrong, color = Land.colors.ink)
                    val sub = ui?.let { "${it.surveyNo} · ${it.villageLatin}" } ?: ""
                    Text(sub, style = LandType.label, color = Land.colors.ink3)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            // Include-orders toggle + View all.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(LandShape.pill)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { includeOrders = !includeOrders }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectBox(checked = includeOrders, onToggle = { includeOrders = !includeOrders })
                    Text(L("હુકમ સાથે", "Include orders"), style = LandType.body, color = Land.colors.ink2)
                }
                Spacer(Modifier.weight(1f))
                val hasMerged = ui?.mergedPdfPath != null
                OutlinePill(
                    text = L("બધા જુઓ", "View all"),
                    enabled = hasMerged,
                    onClick = {
                        val ok = LibraryAccess.view(context, ui?.mergedPdfPath)
                        if (!ok) Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
        }

        // ── Body ────────────────────────────────────────────────────────────────────────
        when {
            ui?.loaded != true -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(L("ખૂલી રહ્યું છે…", "Opening…"), style = LandType.metaMono, color = Land.colors.ink3)
            }
            cases.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L("કોઈ કેસ સાચવ્યો નથી", "No cases stored yet"), style = LandType.bodyStrong, color = Land.colors.ink2, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        L("આ સર્વે ફરી મેળવો ત્યારે દરેક કેસ અલગથી સચવાશે.", "Re-fetch this survey to store each case individually."),
                        style = LandType.meta, color = Land.colors.ink3, textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(cases) { i, c ->
                    CaseRow(
                        case = c,
                        checked = selected.getOrElse(i) { false },
                        expanded = expanded == i,
                        onToggleChecked = { if (i < selected.size) selected[i] = !selected[i] },
                        onToggleExpand = { expanded = if (expanded == i) null else i },
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

/** One case tile: 1dp border, radius 12 — checkbox · caseNo/parties · status chip · expand. */
@Composable
private fun CaseRow(
    case: CasesStore.CaseEntry,
    checked: Boolean,
    expanded: Boolean,
    onToggleChecked: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    val lang = LocalLang.current
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
                Text(
                    caseTitle(case.caseNo),
                    style = LandType.bodyStrong, color = Land.colors.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (case.parties.isNotBlank()) {
                    Text(case.parties, style = LandType.meta, color = Land.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            StatusChip(case.status)
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
                KeyVal(L("કચેરી", "Office"), case.office)
                KeyVal(L("તારીખ", "Date"), case.dtv)
                KeyVal(L("સર્વે", "Survey"), case.survno)
                if (case.hasOrder) {
                    Text(L("હુકમ ઉપલબ્ધ", "Order available"), style = LandType.label, color = Land.colors.accent)
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

/** PENDING/DISPOSED chip. Status is source data → shown untranslated; unknown renders "—". */
@Composable
private fun StatusChip(status: String) {
    val disposed = status.equals("DISPOSED", ignoreCase = true)
    val bg = if (disposed) Land.colors.accentSoft else Land.colors.surfaceAlt
    val fg = if (disposed) Land.colors.accent else Land.colors.ink2
    Box(
        Modifier.clip(LandShape.pill).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(status.ifBlank { "—" }.uppercase(), style = LandType.label, color = fg, maxLines = 1)
    }
}

/** A 22dp square checkbox: accent-filled + check when on, 1dp outline when off. */
@Composable
private fun SelectBox(checked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) Land.colors.accent else Land.colors.surface)
            .then(if (checked) Modifier else Modifier.border(1.dp, Land.colors.line, RoundedCornerShape(6.dp)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Outlined.Check, contentDescription = null, tint = Land.colors.onAccent, modifier = Modifier.size(14.dp))
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

/** Strip the "(PENDING)/(DISPOSED)" suffix from case_str for the row title. */
private fun caseTitle(caseNo: String): String {
    val stripped = caseNo.replace(Regex("\\s*\\((PENDING|DISPOSED)\\)\\s*", RegexOption.IGNORE_CASE), " ").trim()
    return stripped.ifBlank { caseNo.ifBlank { "—" } }
}
