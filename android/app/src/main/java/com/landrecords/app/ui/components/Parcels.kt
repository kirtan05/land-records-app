package com.landrecords.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr

/**
 * One survey drawn as a parcel: 1dp solid border + radius 12, a 1dp dashed inset at
 * 5dp (radius 8), surface fill. Content top→bottom: survey number, area (+ Latin
 * helper), tenure, then the stamp strip. Queued surveys show "No records yet" and an
 * all-hollow strip; unknown metadata renders "—" and never invents a tenure line.
 */
@Composable
fun ParcelTile(
    surveyNo: String,
    areaGu: String,
    areaLatin: String,
    tenure: String,
    counts: Map<RecordType, Int>,
    queued: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(LandShape.tile)
            .background(if (pressed) Land.colors.surfaceAlt else Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.tile)
            .dashedInset(Land.colors.hair)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(15.dp),
    ) {
        Column {
            Text(surveyNo, style = LandType.surveyTile, color = Land.colors.ink)
            Spacer(Modifier.height(6.dp))
            when {
                queued -> Text(
                    Lr(R.string.no_records_yet_gu, R.string.no_records_yet_en),
                    style = LandType.meta, color = Land.colors.ink3,
                )
                areaGu.isBlank() -> Text("—", style = LandType.meta, color = Land.colors.ink3)
                else -> {
                    Text(areaGu, style = LandType.meta, color = Land.colors.ink)
                    if (areaLatin.isNotBlank()) {
                        Text(areaLatin, style = LandType.label, color = Land.colors.ink3)
                    }
                }
            }
            if (!queued && tenure.isNotBlank()) {
                Text(
                    tenure, style = LandType.meta, color = Land.colors.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            StampStrip(counts)
        }
    }
}

/**
 * The detail-screen "plate": bordered, dashed inset (colour [dashColor]), [fill]
 * background. Content is supplied by the caller (survey number + village, etc.).
 */
@Composable
fun ParcelPlate(
    modifier: Modifier = Modifier,
    dashColor: androidx.compose.ui.graphics.Color = Land.colors.line,
    fill: androidx.compose.ui.graphics.Color = Land.colors.bg,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(LandShape.tile)
            .background(fill)
            .border(1.dp, Land.colors.line, LandShape.tile)
            .dashedInset(dashColor)
            .padding(16.dp),
        content = content,
    )
}
