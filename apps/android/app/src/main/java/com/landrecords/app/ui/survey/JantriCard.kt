package com.landrecords.app.ui.survey

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.landrecords.app.R
import com.landrecords.app.data.jantri.JantriRate
import com.landrecords.app.data.jantri.LandArea
import com.landrecords.app.data.jantri.JantriResult
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr

/**
 * The jantri (ASR-2011) government rate for this survey number.
 *
 * Rates are the ASR-2011 figures with the 15/04/2023 doubling already applied; the
 * "ASR-2011 x 2" basis sits beside the title rather than in a separate note. When the
 * survey's area is known the card also gives area x rate; with no area it shows the rate
 * alone and never estimates one. Renders nothing when the number is not in the jantri.
 */
@Composable
fun JantriCard(
    result: JantriResult,
    areaSqm: Double? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Lr(R.string.jantri_title_gu, R.string.jantri_title_en).uppercase(),
                style = LandType.label,
                color = Land.colors.ink3,
                modifier = Modifier.weight(1f),
            )
            Text(
                Lr(R.string.jantri_basis_gu, R.string.jantri_basis_en),
                style = LandType.label, color = Land.colors.ink3,
            )
        }

        result.rates.forEach { rate -> RateRow(rate, areaSqm) }

        if (result.ambiguous) {
            Text(
                Lr(R.string.jantri_multiple_gu, R.string.jantri_multiple_en),
                style = LandType.meta,
                color = Land.colors.accent,
            )
        }
    }
}

@Composable
private fun RateRow(rate: JantriRate, areaSqm: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(landTypeLabel(rate.landType), style = LandType.bodyStrong, color = Land.colors.ink)
            Spacer(Modifier.width(8.dp))
            roadClassLabel(rate.roadClass)?.let {
                Text(it, style = LandType.label, color = Land.colors.ink3)
            }
        }
        // Current rate only. The doubling that applies from 15/04/2023 is already
        // included here and stated once on the card, rather than shown as a second line.
        Row {
            Text(
                "₹${rate.sqm2023.grouped()} ${Lr(R.string.jantri_per_sqm_gu, R.string.jantri_per_sqm_en)}",
                style = LandType.metaMono, color = Land.colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                "₹${rate.acre2023.grouped()} ${Lr(R.string.jantri_per_acre_gu, R.string.jantri_per_acre_en)}",
                style = LandType.metaMono, color = Land.colors.ink,
            )
        }
        // Area x rate. Shown only when this survey's area is known — never estimated.
        if (areaSqm != null) {
            val value = (areaSqm * rate.sqm2023).roundToLong()
            Text(
                "= ₹${LandArea.grouped(value)}  ${inLakh(value)}",
                style = LandType.bodyStrong, color = Land.colors.accent,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "₹70.3 lakh" / "₹1.24 crore" — how the figure is actually spoken. */
private fun inLakh(v: Long): String = when {
    v >= 10_000_000 -> "(₹%.2f crore)".format(v / 10_000_000.0)
    v >= 100_000 -> "(₹%.1f lakh)".format(v / 100_000.0)
    else -> ""
}

@Composable
private fun landTypeLabel(t: Int): String = when (t) {
    0 -> Lr(R.string.jantri_land_dry_gu, R.string.jantri_land_dry_en)
    1 -> Lr(R.string.jantri_land_irrigated_gu, R.string.jantri_land_irrigated_en)
    2 -> Lr(R.string.jantri_land_waste_gu, R.string.jantri_land_waste_en)
    else -> Lr(R.string.jantri_land_mineral_gu, R.string.jantri_land_mineral_en)
}

@Composable
private fun roadClassLabel(c: Int): String? = when (c) {
    1 -> Lr(R.string.jantri_road_general_gu, R.string.jantri_road_general_en)
    2 -> Lr(R.string.jantri_road_district_gu, R.string.jantri_road_district_en)
    3 -> Lr(R.string.jantri_road_highway_gu, R.string.jantri_road_highway_en)
    else -> null           // unknown classification renders nothing, never a guess
}

private fun Long.grouped(): String = LandArea.grouped(this)

private fun Int.grouped(): String = LandArea.grouped(toLong())
