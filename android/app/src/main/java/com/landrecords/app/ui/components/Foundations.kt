package com.landrecords.app.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.landrecords.app.ui.theme.Lang

/**
 * The blueprint grid used behind Library and Survey-detail headers: faint 1dp lines
 * on a [cell] pitch. Drawn, not tiled, so it scales with the container.
 */
fun Modifier.blueprintGrid(color: Color, cell: Dp = 22.dp): Modifier = drawBehind {
    val step = cell.toPx()
    val w = 1.dp.toPx()
    var x = step
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), w)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), w)
        y += step
    }
}

/** A 1dp dashed rounded border, inset by half the stroke so it isn't clipped. */
fun Modifier.dashedRoundBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.dp,
    on: Dp = 3.dp,
    off: Dp = 3.dp,
): Modifier = drawBehind {
    val sw = strokeWidth.toPx()
    val r = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(sw / 2, sw / 2),
        size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()))),
    )
}

/**
 * The dashed "plot" inset that makes every survey read as a parcel: a dashed rounded
 * border drawn [inset] inside the tile's own border.
 */
fun Modifier.dashedInset(color: Color, inset: Dp = 5.dp, cornerRadius: Dp = 8.dp): Modifier = drawBehind {
    val i = inset.toPx()
    val sw = 1.dp.toPx()
    val r = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(i, i),
        size = Size(size.width - 2 * i, size.height - 2 * i),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f.dp.toPx(), 3f.dp.toPx()))),
    )
}

/** Interaction indication that does nothing — press feedback is a background swap, no ripple. */
val NoIndication: Indication? = null

private val guDigits = charArrayOf('૦', '૧', '૨', '૩', '૪', '૫', '૬', '૭', '૮', '૯')

fun Int.guNumerals(): String = buildString {
    this@guNumerals.toString().forEach { c -> append(if (c in '0'..'9') guDigits[c - '0'] else c) }
}

/** Counts follow the app language: Latin digits in `en`, Gujarati numerals otherwise. */
fun Int.numerals(lang: Lang): String = if (lang == Lang.EN) toString() else guNumerals()

/** Map Gujarati numerals in a land-data string back to Latin digits for the helper line. */
fun String.guToLatinDigits(): String = buildString {
    this@guToLatinDigits.forEach { c ->
        val idx = guDigits.indexOf(c)
        append(if (idx >= 0) ('0' + idx) else c)
    }
}

/** The Latin helper under an area value, e.g. "૩-૩૧-૮૪" → "3-31-84 ha-a-m²". */
fun areaLatinHelper(areaGu: String): String =
    if (areaGu.isBlank()) "" else "${areaGu.guToLatinDigits()} ha-a-m²"
