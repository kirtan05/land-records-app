package com.landrecords.app.ui.marked

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.join

/**
 * The export-mark control: a tappable dot — hollow ring when unmarked, filled with the colour when
 * marked. Tapping opens a colour menu (built-in + user-added colours, an "Add colour" option, and
 * Remove). Whatever is picked here groups the item on the Marked screen for one-tap Send / Print.
 *
 * Shared by the record cards and the per-item Cases / Scans / Entries rows, so a survey, an iRCMS
 * case, a VF-7/12 scan and a નોંધ entry all carry the identical control. Its own [clickable]
 * consumes the tap, so placing it inside a clickable row never triggers that row.
 */
@Composable
fun MarkDot(mark: String?, onSet: (String?) -> Unit) {
    val lang = LocalLang.current
    val app = landApp()
    var open by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val current = Marks.from(mark)
    Box {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .then(
                    if (current != null) Modifier.background(current.color)
                    else Modifier.border(1.5.dp, Land.colors.ink3, CircleShape),
                )
                .clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Marks.all().forEach { c ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(c.color))
                            Text(c.label(), style = LandType.body, color = Land.colors.ink)
                        }
                    },
                    onClick = { onSet(c.id); open = false },
                )
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, Land.colors.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("+", style = LandType.label, color = Land.colors.accent) }
                        Text(lang.join("રંગ ઉમેરો", "Add colour"), style = LandType.body, color = Land.colors.accent)
                    }
                },
                onClick = { open = false; showAdd = true },
            )
            if (current != null) {
                DropdownMenuItem(
                    text = { Text(lang.join("ચિહ્ન કાઢો", "Remove mark"), style = LandType.body, color = Land.colors.ink2) },
                    onClick = { onSet(null); open = false },
                )
            }
        }
    }
    if (showAdd) {
        AddColourDialog(
            onPick = { color -> val id = app.appState.addCustomMark(color.toArgb()); onSet(id); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
}

/**
 * A small dialog to add a new export colour: tap a swatch from the extra palette and it's saved as a
 * custom colour (name it later on the Marked screen's rename sheet). Reused by [MarkDot] and the
 * Marked screen.
 */
@Composable
fun AddColourDialog(onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val lang = LocalLang.current
    val existing = Marks.all().map { it.color.toArgb() }.toSet()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(LandShape.card)
                .background(Land.colors.surface)
                .border(1.dp, Land.colors.line, LandShape.card)
                .padding(20.dp),
        ) {
            Text(lang.join("રંગ ઉમેરો", "Add a colour"), style = LandType.screenTitle, color = Land.colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                lang.join("એક રંગ પસંદ કરો. નામ પછી “ચિહ્નિત” સ્ક્રીન પરથી આપી શકાય.", "Pick a colour. You can name it later on the Marked screen."),
                style = LandType.stamp, color = Land.colors.ink3,
            )
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Marks.extraPalette.forEach { c ->
                    val used = c.toArgb() in existing
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(c)
                            .then(if (used) Modifier.border(2.dp, Land.colors.ink, CircleShape) else Modifier)
                            .clickable(enabled = !used) { onPick(c) },
                    )
                }
            }
        }
    }
}
