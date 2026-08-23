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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.landrecords.app.data.storage.EntriesStore
import com.landrecords.app.data.storage.LibraryAccess
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

/** Loads a survey's captured entry (નોંધ) scans (entries.json). */
class EntriesViewModel(
    private val app: LandRecordsApp,
    private val surveyId: Long,
) : ViewModel() {

    data class EntriesUi(
        val loaded: Boolean,
        val surveyNo: String,
        val district: String,
        val taluka: String,
        val village: String,
        val villageLatin: String,
        val entries: List<EntriesStore.EntryItem>,
    )

    val ui = MutableStateFlow<EntriesUi?>(null)

    init {
        viewModelScope.launch {
            val snap = app.repository.snapshot(surveyId)
            if (snap == null) {
                ui.value = EntriesUi(true, "", "", "", "", "", emptyList())
                return@launch
            }
            val (survey, prop) = snap
            val entries = EntriesStore.read(app, prop.district, prop.taluka, prop.village, survey.surveyNo)
            ui.value = EntriesUi(
                loaded = true,
                surveyNo = survey.surveyNo,
                district = prop.district, taluka = prop.taluka, village = prop.village,
                villageLatin = prop.village,
                entries = entries,
            )
        }
    }

    /** Set/clear one entry's export colour, persisting it and updating the list in place. */
    fun setMark(entry: EntriesStore.EntryItem, mark: String?) {
        val u = ui.value ?: return
        viewModelScope.launch {
            EntriesStore.setMark(app, u.district, u.taluka, u.village, u.surveyNo, entry.number, mark)
            // B5: mirror the mark into the synced `mark` table.
            app.repository.surveyUidOf(surveyId)?.let { su ->
                com.landrecords.app.data.sync.MarkSync.set(
                    app, com.landrecords.app.data.identity.Identity.entryUid(su, entry.number), mark,
                )
            }
            ui.value = u.copy(entries = u.entries.map { if (it.number == entry.number) it.copy(mark = mark) else it })
        }
    }
}

/**
 * Per-entry (નોંધ) view for the INTEGRATED record: lists every captured red-entry scan with a
 * checkbox (all on by default), its number, and a per-entry "View". Export merges the chosen
 * entries by number and shares the result. The per-item analogue of the iRCMS Cases screen.
 */
@Composable
fun EntriesScreen(
    surveyId: Long,
    onBack: () -> Unit,
) {
    val app = landApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: EntriesViewModel = viewModel(
        factory = viewModelFactory { initializer { EntriesViewModel(app, surveyId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    val entries = ui?.entries ?: emptyList()
    val selected = remember { mutableStateListOf<Boolean>() }
    LaunchedEffect(entries.size) {
        selected.clear()
        selected.addAll(List(entries.size) { true })
    }
    val chosenCount = selected.count { it }

    fun exportSelected() {
        val u = ui ?: return
        val chosen = u.entries.filterIndexed { i, _ -> selected.getOrElse(i) { false } && u.entries[i].file.isNotBlank() }
        if (chosen.isEmpty()) return
        scope.launch {
            val parts = withContext(Dispatchers.IO) {
                val out = ArrayList<ByteArray>()
                for (e in chosen) {
                    EntriesStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, e.file)
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
            val name = "${u.villageLatin.ifBlank { "Land" }}_${u.surveyNo}_Entries_${chosen.size}.pdf"
            if (!LibraryAccess.shareBytes(context, merged, name)) {
                Toast.makeText(context, "Couldn't export", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun viewAll() {
        val u = ui ?: return
        val withScan = u.entries.filter { it.file.isNotBlank() }
        if (withScan.isEmpty()) {
            Toast.makeText(context, "Nothing to view", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val merged = withContext(Dispatchers.IO) {
                val parts = withScan.mapNotNull { e ->
                    EntriesStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, e.file)
                        ?.let { runCatching { File(it).readBytes() }.getOrNull() }
                }
                val bytes = PdfMerge.merge(parts, app.cacheDir) ?: return@withContext null
                File(app.cacheDir, "AllEntries_${u.surveyNo.replace('/', '_')}.pdf").apply { writeBytes(bytes) }
            }
            if (merged == null) {
                Toast.makeText(context, "Couldn't open", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!LibraryAccess.view(context, merged.absolutePath, "${u.villageLatin.ifBlank { "Land" }} ${u.surveyNo} Entries.pdf")) {
                Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun viewEntryFile(entry: EntriesStore.EntryItem) {
        val u = ui ?: return
        val path = EntriesStore.filePath(app, u.district, u.taluka, u.village, u.surveyNo, entry.file)
        if (!LibraryAccess.view(context, path, "${u.villageLatin.ifBlank { "Land" }} ${u.surveyNo} Entry ${entry.number}.pdf")) {
            Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
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
                    Text(L("નોંધ", "Entries"), style = LandType.bodyStrong, color = Land.colors.ink)
                    val sub = ui?.let { "${it.surveyNo} · ${it.villageLatin}" } ?: ""
                    Text(sub, style = LandType.label, color = Land.colors.ink3)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val total = entries.size
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
                    EntrySelectBox(checked = allSelected, onToggle = toggleAll, partial = someSelected)
                    Text(
                        if (allSelected) L("બધા રદ કરો", "Deselect all") else L("બધા પસંદ કરો", "Select all"),
                        style = LandType.body, color = Land.colors.ink2,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (entries.any { it.file.isNotBlank() }) {
                    PillButton(L("બધા જુઓ", "View all"), onClick = { viewAll() })
                    Spacer(Modifier.width(10.dp))
                }
                Text("$chosenCount / $total", style = LandType.metaMono, color = Land.colors.ink3)
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
        }

        when {
            ui?.loaded != true -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(L("ખૂલી રહ્યું છે…", "Opening…"), style = LandType.metaMono, color = Land.colors.ink3)
            }
            entries.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L("કોઈ નોંધ સાચવી નથી", "No entries stored yet"), style = LandType.bodyStrong, color = Land.colors.ink2, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        L("સંકલિત રેકોર્ડ ફરી મેળવો — લાલ નોંધના સ્કેન સચવાશે.", "Re-fetch the Integrated record — the red entry scans will be stored."),
                        style = LandType.meta, color = Land.colors.ink3, textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(entries) { i, e ->
                    EntryRowTile(
                        index = i + 1,
                        entry = e,
                        checked = selected.getOrElse(i) { false },
                        onSetMark = { vm.setMark(e, it) },
                        onToggleChecked = { if (i < selected.size) selected[i] = !selected[i] },
                        onView = { viewEntryFile(e) },
                    )
                }
            }
        }

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

/** One entry tile: checkbox · n. નોંધ number · red "scan" chip · mark dot · View. */
@Composable
private fun EntryRowTile(
    index: Int,
    entry: EntriesStore.EntryItem,
    checked: Boolean,
    onSetMark: (String?) -> Unit,
    onToggleChecked: () -> Unit,
    onView: () -> Unit,
) {
    val hasScan = entry.file.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntrySelectBox(checked = checked, onToggle = onToggleChecked)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$index.", style = LandType.metaMono, color = Land.colors.ink3)
            // The entry number is source land data → shown untranslated.
            Text(
                "${L("નોંધ", "Entry")} ${entry.number}",
                style = LandType.bodyStrong, color = Land.colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // A red "scan" chip marks the old handwritten entries — the site draws these numbers red.
        Box(
            Modifier.clip(LandShape.pill)
                .background(Land.colors.accentSoft)
                .padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
            Text(L("સ્કેન", "SCAN"), style = LandType.label, color = Land.colors.accent, maxLines = 1)
        }
        MarkDot(mark = entry.mark, onSet = onSetMark)
        if (hasScan) {
            PillButton(L("જુઓ", "View"), onClick = onView)
        } else {
            Text(L("સ્કેન નથી", "no scan"), style = LandType.label, color = Land.colors.ink3)
        }
    }
}

/** A 22dp square checkbox: accent-filled + check when on, 1dp outline when off. */
@Composable
private fun EntrySelectBox(checked: Boolean, onToggle: () -> Unit, partial: Boolean = false) {
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
