package com.landrecords.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandType

/**
 * A small metadata card in the Survey-detail strip: label over a Gujarati-numeral
 * value with a Latin helper line. Unknown → "—".
 */
@Composable
fun MetaChip(
    label: String,
    valueGu: String,
    valueLatin: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, style = LandType.label, color = Land.colors.ink3)
        Text(valueGu.ifBlank { "—" }, style = LandType.meta, color = Land.colors.ink)
        if (valueLatin.isNotBlank()) {
            Text(valueLatin, style = LandType.label, color = Land.colors.ink3)
        }
    }
}

/**
 * Path pills joined by short 1dp rules; the last pill is filled accent, the rest are
 * surface with a line border.
 */
@Composable
fun PathBreadcrumb(items: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, label ->
            val last = i == items.lastIndex
            Box(
                modifier = Modifier
                    .clip(LandShape.pill)
                    .background(if (last) Land.colors.accent else Land.colors.surface)
                    .then(if (last) Modifier else Modifier.border(1.dp, Land.colors.line, LandShape.pill))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(label, style = LandType.meta, color = if (last) Land.colors.onAccent else Land.colors.ink2)
            }
            if (!last) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = 10.dp, height = 1.dp)
                        .background(Land.colors.line),
                )
            }
        }
    }
}

/**
 * A header block: surface fill with the faint blueprint grid and a 1dp bottom line.
 * The caller supplies padding and status-bar insets on [modifier].
 */
@Composable
fun BlueprintHeader(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .background(Land.colors.surface)
            .blueprintGrid(Land.colors.hair),
    ) {
        content()
        HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
    }
}
