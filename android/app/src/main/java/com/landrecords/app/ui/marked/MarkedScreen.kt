package com.landrecords.app.ui.marked

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.AppState
import com.landrecords.app.data.db.MarkedRecordRow
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.Exporter
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.PillButton
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Dp4
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.join
import kotlinx.coroutines.launch

@Composable
fun MarkedScreen(onBack: () -> Unit) {
    val app = landApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = LocalLang.current
    val vm: MarkedViewModel = viewModel(
        factory = viewModelFactory { initializer { MarkedViewModel(app.repository) } },
    )
    val rows by vm.marked.collectAsStateWithLifecycle()

    // Colour → its rows, only the colours that actually have marks (in palette order).
    val groups = MarkColor.ordered.mapNotNull { c ->
        rows.filter { it.mark == c.id }.takeIf { it.isNotEmpty() }?.let { c to it }
    }

    var busy by remember { mutableStateOf(false) }
    var sendMenuFor by remember { mutableStateOf<String?>(null) }
    var showRename by remember { mutableStateOf(false) }

    fun itemsFor(list: List<MarkedRecordRow>): List<Exporter.Item> =
        list.map { Exporter.Item(it.pdfPath, "${it.villageEn} ${it.surveyNo} ${exportLabel(it.type)}.pdf") }

    fun launchExport(action: suspend () -> Boolean) {
        if (busy) return
        busy = true
        sendMenuFor = null
        scope.launch {
            val ok = action()
            busy = false
            if (!ok) Toast.makeText(context, "Nothing to send", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        BlueprintHeader(modifier = Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("ચિહ્નિત", "Marked"), style = LandType.screenTitle, color = Land.colors.ink)
                    val sub = when {
                        busy -> L("તૈયાર થાય છે…", "Preparing…")
                        rows.isEmpty() -> L("કંઈ ચિહ્નિત નથી", "Nothing marked")
                        else -> lang.join("${rows.size} રેકોર્ડ", "${rows.size} records")
                    }
                    Text(sub, style = LandType.label, color = Land.colors.ink3)
                }
                SquareIconButton(Icons.Outlined.Edit, { showRename = true }, "Rename colours")
            }
        }

        if (groups.isEmpty()) {
            EmptyMarked()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(Dp4.cardGap),
        ) {
            item {
                Text(
                    L(
                        "રંગ પ્રમાણે એકસાથે મોકલો કે પ્રિન્ટ કરો. ચિહ્ન લગાવવા રેકોર્ડ પરના ટપકાં દબાવો.",
                        "Send or print each colour together. Tap the dot on a record to mark it.",
                    ),
                    style = LandType.stamp, color = Land.colors.ink3,
                )
            }
            groups.forEach { (color, list) ->
                item(key = color.id) {
                    val cname = color.label()
                    GroupCard(
                        color = color,
                        name = cname,
                        rows = list,
                        sendMenuOpen = sendMenuFor == color.id,
                        onToggleSendMenu = { sendMenuFor = if (sendMenuFor == color.id) null else color.id },
                        onSendMerged = {
                            launchExport { Exporter.sendMerged(context, itemsFor(list), "Land records - $cname (${list.size}).pdf") }
                        },
                        onSendSeparate = { launchExport { Exporter.sendSeparate(context, itemsFor(list)) } },
                        onSendZip = {
                            launchExport { Exporter.sendZip(context, itemsFor(list), "Land records - $cname (${list.size})") }
                        },
                        onPrint = {
                            launchExport { Exporter.printMerged(context, itemsFor(list), "Land records $cname") }
                        },
                        onUnmark = { vm.unmark(it) },
                    )
                }
            }
            item { Spacer(Modifier.height(2.dp)) }
        }
    }

    if (showRename) RenameColoursSheet(onDismiss = { showRename = false })
}

@Composable
private fun GroupCard(
    color: MarkColor,
    name: String,
    rows: List<MarkedRecordRow>,
    sendMenuOpen: Boolean,
    onToggleSendMenu: () -> Unit,
    onSendMerged: () -> Unit,
    onSendSeparate: () -> Unit,
    onSendZip: () -> Unit,
    onPrint: () -> Unit,
    onUnmark: (Long) -> Unit,
) {
    val lang = LocalLang.current
    Column(
        Modifier.fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Colour + count.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color.swatch))
            Text(name, style = LandType.bodyStrong, color = Land.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${rows.size}", style = LandType.count, color = Land.colors.ink3)
        }

        // Records in this colour.
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${r.villageGu.ifBlank { r.villageEn }} · ${r.surveyNo}",
                        style = LandType.metaMono, color = Land.colors.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(lang.join(r.type.gujaratiLabel, r.type.englishLabel), style = LandType.label, color = Land.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onUnmark(r.recordId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove mark", tint = Land.colors.ink3, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Actions: Send ▾ (One PDF / Separate / Zip) + Print.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                PillButton(lang.join("બધા મોકલો", "Send all"), onToggleSendMenu, filled = true)
                DropdownMenu(expanded = sendMenuOpen, onDismissRequest = onToggleSendMenu) {
                    DropdownMenuItem(
                        text = { Text(lang.join("એક PDF", "One PDF"), style = LandType.body, color = Land.colors.ink) },
                        onClick = onSendMerged,
                    )
                    DropdownMenuItem(
                        text = { Text(lang.join("અલગ ફાઈલો", "Separate files"), style = LandType.body, color = Land.colors.ink) },
                        onClick = onSendSeparate,
                    )
                    DropdownMenuItem(
                        text = { Text(lang.join("ઝિપ (Zip)", "Zip"), style = LandType.body, color = Land.colors.ink) },
                        onClick = onSendZip,
                    )
                }
            }
            PillButton(lang.join("પ્રિન્ટ", "Print"), onPrint)
        }
    }
}

@Composable
private fun EmptyMarked() {
    val lang = LocalLang.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(L("કંઈ ચિહ્નિત નથી", "Nothing marked yet"), style = LandType.bodyStrong, color = Land.colors.ink2)
        Text(
            L(
                "કોઈ પણ રેકોર્ડ ખોલો અને તેના નામ પાસેના ટપકાં પર રંગ પસંદ કરો. પછી અહીંથી એ રંગના બધા રેકોર્ડ એકસાથે મોકલો કે પ્રિન્ટ કરો.",
                "Open any record and pick a colour on the dot next to its name. Then come here to send or print all of that colour together.",
            ),
            style = LandType.body, color = Land.colors.ink3,
        )
    }
}

/** Bottom sheet to give each of the 6 mark colours a custom name (persisted, ≤20 chars). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameColoursSheet(onDismiss: () -> Unit) {
    val appState = landApp().appState
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Land.colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(L("રંગનાં નામ", "Name the colours"), style = LandType.screenTitle, color = Land.colors.ink)
            Text(
                L(
                    "દરેક રંગને તમારું નામ આપો — દા.ત. “પ્રિન્ટ કરવાના”, “રમેશ”. ખાલી રાખો તો મૂળ નામ રહેશે.",
                    "Give each colour your own name — e.g. \"To print\", \"Ramesh\". Leave blank to keep the default.",
                ),
                style = LandType.stamp, color = Land.colors.ink3,
            )
            Spacer(Modifier.height(2.dp))
            MarkColor.ordered.forEach { c -> RenameRow(c, appState) }
        }
    }
}

@Composable
private fun RenameRow(c: MarkColor, appState: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(c.swatch))
        val current = appState.markName(c.id) ?: ""
        Box(
            Modifier.weight(1f).height(46.dp).clip(LandShape.field)
                .background(Land.colors.surfaceAlt).border(1.dp, Land.colors.line, LandShape.field)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (current.isEmpty()) Text(c.defaultLabel(), style = LandType.body, color = Land.colors.ink3)
            BasicTextField(
                value = current,
                onValueChange = { appState.setMarkName(c.id, it.take(MarkColor.NAME_MAX)) },
                singleLine = true,
                textStyle = LandType.body.copy(color = Land.colors.ink),
                cursorBrush = SolidColor(Land.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Short, filename-friendly English label per record type (used for the exported PDF names). */
private fun exportLabel(type: RecordType): String = when (type) {
    RecordType.INTEGRATED -> "Integrated Record"
    RecordType.VF712 -> "VF 7-12"
    RecordType.DEEDS -> "Deeds"
    RecordType.IRCMS -> "iRCMS Cases"
}
