package com.landrecords.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang

/**
 * The signature four-slot stamp strip: I · V · D · C. A slot is *held* (accent border,
 * accentSoft fill, accent text) when its record type has documents, else *missing*
 * (line border, transparent, ink3). A count >1 is appended in the current numeral
 * system (e.g. `V૧૦`, `C૧૪`).
 *
 * [counts] maps each present record type to its document count; absent/0 = missing.
 */
@Composable
fun StampStrip(
    counts: Map<RecordType, Int>,
    modifier: Modifier = Modifier,
) {
    val lang = LocalLang.current
    val shape = RoundedCornerShape(4.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        RecordType.entries.forEach { type ->
            val count = counts[type] ?: 0
            val held = count > 0
            val text = type.stampLetter() + if (count > 1) count.numerals(lang) else ""
            Box(
                modifier = Modifier
                    .size(LandSize.stampW, LandSize.stampH)
                    .clip(shape)
                    .background(if (held) Land.colors.accentSoft else Color.Transparent)
                    .border(1.dp, if (held) Land.colors.accent else Land.colors.line, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = LandType.stamp,
                    color = if (held) Land.colors.accent else Land.colors.ink3,
                )
            }
        }
    }
}
