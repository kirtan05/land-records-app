package com.landrecords.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.marked.MarkColor
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.Lang
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.Lr
import com.landrecords.app.ui.theme.join

/**
 * One record type's card. A 3dp left rail is accent when held, line when missing.
 * Held → count + as-of + View/Re-generate/Share pills. Missing → a dashed-top,
 * bg-filled footer with "Not fetched yet" and a Get record pill.
 */
@Composable
fun RecordCard(
    type: RecordType,
    docCount: Int,
    asOfGu: String,
    asOfLatin: String,
    justAdded: Boolean,
    checked: Boolean = false,
    /** Current export colour id (see MarkColor), null = unmarked. Only shown on held records. */
    mark: String? = null,
    onSetMark: (String?) -> Unit = {},
    onView: () -> Unit,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
    onGet: () -> Unit,
    /** iRCMS only: opens the per-case Cases view. Shown as a pill when held (docCount>0). */
    onCases: (() -> Unit)? = null,
    /** VF-7/12 only: opens the per-scan Scans view. Shown as a pill when held (docCount>0). */
    onScans: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lang = LocalLang.current
    val held = docCount > 0
    val lineColor = Land.colors.line
    val railColor = if (held) Land.colors.accent else Land.colors.line

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .drawBehind {
                drawRect(color = railColor, size = Size(3.dp.toPx(), size.height))
            },
    ) {
        // Header: name + doc count.
        Row(
            Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Held records carry a tappable colour dot (the export mark). It sits where the
                    // old "Just added" pill used to — a fixed-size dot never reflows, so it can't
                    // squeeze the title or stretch the card the way that text badge did.
                    if (held) MarkControl(mark = mark, onSet = onSetMark)
                    Text(type.label(), style = LandType.bodyStrong, color = Land.colors.ink, modifier = Modifier.weight(1f))
                }
                if (lang == Lang.BOTH) {
                    Text(type.englishSubLabel(), style = LandType.label, color = Land.colors.ink3)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    if (held) docCount.numerals(lang) else "—",
                    style = LandType.count,
                    color = if (held) Land.colors.ink else Land.colors.ink3,
                )
                Text(
                    if (held) type.unit(lang, docCount) else lang.join("કંઈ નહીં", "none"),
                    style = LandType.label,
                    color = Land.colors.ink3,
                )
            }
        }

        if (held) {
            if (asOfGu.isNotBlank()) {
                Text(
                    "${Lr(R.string.as_of_gu, R.string.as_of_en)} " + lang.join(asOfGu, asOfLatin.ifBlank { asOfGu }),
                    style = LandType.meta, color = Land.colors.ink2,
                    modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 8.dp),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(Lr(R.string.action_view_gu, R.string.action_view_en), onView, filled = true)
                if (type == RecordType.IRCMS && onCases != null) {
                    PillButton(lang.join("કેસ", "Cases"), onCases)
                }
                if (type == RecordType.VF712 && onScans != null) {
                    PillButton(lang.join("સ્કેન", "Scans"), onScans)
                }
                PillButton(Lr(R.string.action_regenerate_gu, R.string.action_regenerate_en), onRegenerate)
                PillButton(Lr(R.string.action_share_gu, R.string.action_share_en), onShare)
                // Re-fetch stays available on a HELD record so a merged-only seed row (e.g.
                // Sundalpura iRCMS with no per-case detail) can be refreshed. onGet -> onFetch ->
                // the fetch pipeline, which overwrites the record (PDF, cases.json/vf712.json, Room).
                PillButton(lang.join("ફરી લાવો", "Re-fetch"), onGet)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val sw = 1.dp.toPx()
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, sw / 2),
                            end = Offset(size.width, sw / 2),
                            strokeWidth = sw,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                        )
                    }
                    .background(Land.colors.bg)
                    .padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (checked) lang.join("ઉપલબ્ધ નથી", "Not available")
                    else Lr(R.string.not_fetched_gu, R.string.not_fetched_en),
                    style = LandType.metaMono, color = Land.colors.ink3,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    if (checked) lang.join("ફરી તપાસો", "Recheck")
                    else Lr(R.string.get_record_gu, R.string.get_record_en),
                    onGet,
                )
            }
        }
    }
}

/**
 * The export-mark control: a tappable dot — hollow ring when unmarked, filled with the colour when
 * marked. Tapping opens a tiny colour menu (+ Remove). Whatever dad picks here groups the record on
 * the Marked screen for one-tap "Send all" / "Print".
 */
@Composable
private fun MarkControl(mark: String?, onSet: (String?) -> Unit) {
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
