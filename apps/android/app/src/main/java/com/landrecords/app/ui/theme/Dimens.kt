package com.landrecords.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing rhythm is a 4dp base. Values below are the approved "comfy" density. */
object Dp4 {
    val screenPadding = 20.dp
    val cardPadding = 15.dp
    val cardGap = 12.dp
    val chipGap = 7.dp
    val sectionGap = 18.dp
}

object LandShape {
    val tile = RoundedCornerShape(12.dp)
    val tileInset = RoundedCornerShape(8.dp)   // the dashed plot outline, inset 5dp
    val card = RoundedCornerShape(12.dp)
    val field = RoundedCornerShape(10.dp)
    val iconButton = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(20.dp)
    val stamp = RoundedCornerShape(4.dp)
}

object LandSize {
    val primaryButton = 56.dp
    val field = 48.dp
    val iconButton = 36.dp
    val backButton = 34.dp
    val stampW = 23.dp
    val stampH = 21.dp
    val recordRail = 3.dp
    val border = 1.dp
    val dashInset = 5.dp
    val blueprintGrid = 22.dp
    val progressBar = 4.dp
    val minTouchTarget = 48.dp
}

/** Motion. Every duration collapses to 0 when animations are disabled. */
object LandMotion {
    const val screenEnterMs = 220
    val screenEnterSlide = 7.dp
    const val spotlightPulseMs = 2400
    const val caretBlinkMs = 1100
}
