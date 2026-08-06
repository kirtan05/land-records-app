package com.landrecords.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
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
    onView: () -> Unit,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
    onGet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = LocalLang.current
    val held = docCount > 0
    val lineColor = Land.colors.line // hoisted for the non-composable drawBehind below
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card),
    ) {
        // 3dp rail
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (held) Land.colors.accent else Land.colors.line),
        )
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(type.label(), style = LandType.bodyStrong, color = Land.colors.ink)
                        if (justAdded) JustAddedBadge()
                    }
                    if (lang == Lang.BOTH) {
                        Text(type.englishSubLabel(), style = LandType.label, color = Land.colors.ink3)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
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
                        "${Lr(R.string.as_of_gu, R.string.as_of_en)} " +
                            lang.join(asOfGu, asOfLatin.ifBlank { asOfGu }),
                        style = LandType.meta, color = Land.colors.ink2,
                        modifier = Modifier.padding(start = 15.dp, end = 15.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton(Lr(R.string.action_view_gu, R.string.action_view_en), onView, filled = true)
                    PillButton(Lr(R.string.action_regenerate_gu, R.string.action_regenerate_en), onRegenerate)
                    PillButton(Lr(R.string.action_share_gu, R.string.action_share_en), onShare)
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
                        Lr(R.string.not_fetched_gu, R.string.not_fetched_en),
                        style = LandType.metaMono, color = Land.colors.ink3,
                        modifier = Modifier.weight(1f),
                    )
                    PillButton(Lr(R.string.get_record_gu, R.string.get_record_en), onGet)
                }
            }
        }
    }
}

@Composable
private fun JustAddedBadge() {
    Box(
        Modifier
            .clip(LandShape.pill)
            .background(Land.colors.accentSoft)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            Lr(R.string.just_added_gu, R.string.just_added_en),
            style = LandType.label, color = Land.colors.accent, textAlign = TextAlign.Center,
        )
    }
}
