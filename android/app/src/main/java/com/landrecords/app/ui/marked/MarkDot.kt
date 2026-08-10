package com.landrecords.app.ui.marked

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.join

/**
 * The export-mark control: a tappable dot — hollow ring when unmarked, filled with the colour when
 * marked. Tapping opens a tiny colour menu (+ Remove). Whatever dad picks here groups the item on
 * the Marked screen for one-tap "Send all" / "Print".
 *
 * Shared by the record cards ([com.landrecords.app.ui.components.RecordCard]) and the per-item
 * Cases / Scans rows, so a survey, a single iRCMS case and a single VF-7/12 scan all carry the
 * identical control. Its own [clickable] consumes the tap, so placing it inside a clickable row
 * (e.g. one that expands) never triggers that row.
 */
@Composable
fun MarkDot(mark: String?, onSet: (String?) -> Unit) {
    val lang = LocalLang.current
    var open by remember { mutableStateOf(false) }
    val current = MarkColor.from(mark)
    Box {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .then(
                    if (current != null) Modifier.background(current.swatch)
                    else Modifier.border(1.5.dp, Land.colors.ink3, CircleShape),
                )
                .clickable { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            MarkColor.ordered.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(c.swatch))
                            Text(c.label(), style = LandType.body, color = Land.colors.ink)
                        }
                    },
                    onClick = { onSet(c.id); open = false },
                )
            }
            if (current != null) {
                DropdownMenuItem(
                    text = { Text(lang.join("ચિહ્ન કાઢો", "Remove mark"), style = LandType.body, color = Land.colors.ink2) },
                    onClick = { onSet(null); open = false },
                )
            }
        }
    }
}
