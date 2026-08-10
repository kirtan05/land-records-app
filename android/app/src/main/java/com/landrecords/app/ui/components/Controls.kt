package com.landrecords.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType

/** Bordered square icon button (back / settings / add), radius 10, no ripple. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = LandSize.iconButton,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(size)
            .clip(LandShape.iconButton)
            .background(if (pressed) Land.colors.surfaceAlt else Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.iconButton)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Land.colors.ink2, modifier = Modifier.size(20.dp))
    }
}

/** Header pill that shows the language badge (ગુ / ગુ/EN / EN) and cycles on tap. */
@Composable
fun LanguagePill(badge: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(width = 56.dp, height = LandSize.iconButton)
            .clip(LandShape.iconButton)
            .background(if (pressed) Land.colors.surfaceAlt else Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.iconButton)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(badge, style = LandType.label, color = Land.colors.ink2)
    }
}

/** A removable survey-number chip: mono 13, radius 20, with an 18dp × on surfaceAlt. */
@Composable
fun RemovableChip(text: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(LandShape.pill)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.pill)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = LandType.metaMono, color = Land.colors.ink)
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Land.colors.surfaceAlt)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = Land.colors.ink2, modifier = Modifier.size(12.dp))
        }
    }
}

/** A village switcher card: name + "Latin · count" helper; selected = accentSoft/accent. Long-press
 *  fires [onLongClick] (used to offer "remove this village"). When [onOpenMap] is non-null (the
 *  village has a known official map), a small map glyph appears next to the name; it carries its
 *  own [clickable] — exactly [MarkDot]'s pattern — so tapping it opens the map without also
 *  selecting the card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VillageCard(
    name: String,
    helper: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(LandShape.field)
            .background(if (selected) Land.colors.accentSoft else Land.colors.surface)
            .border(1.dp, if (selected) Land.colors.accent else Land.colors.line, LandShape.field)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onClick, onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val nameColor = if (selected) Land.colors.accent else Land.colors.ink
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                name, style = LandType.bodyStrong, color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onOpenMap != null) {
                Icon(
                    Icons.Outlined.Map,
                    contentDescription = "Village map",
                    tint = nameColor,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpenMap),
                )
            }
        }
        // Split "Latin · count" so the count stays visible when the card is narrow (portrait);
        // only the name ellipsizes.
        val helperColor = if (selected) Land.colors.accent else Land.colors.ink3
        val hasCount = helper.contains(" · ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (hasCount) helper.substringBeforeLast(" · ") else helper,
                style = LandType.label, color = helperColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hasCount) Text("· ${helper.substringAfterLast(" · ")}", style = LandType.label, color = helperColor, maxLines = 1)
        }
    }
}

/** A segmented row of selectable pills (language / theme in Settings). */
@Composable
fun SegmentedPills(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Equal-width segments: each pill takes 1/3 of the row, so a longer label (e.g. "System", or a
    // wider Gujarati word) can't elongate one pill and blow the card out past the screen width.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(LandShape.pill)
                    .background(if (selected) Land.colors.accentSoft else Color.Transparent)
                    .border(1.dp, if (selected) Land.colors.accent else Land.colors.line, LandShape.pill)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                    .padding(vertical = 9.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = LandType.body, color = if (selected) Land.colors.accent else Land.colors.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
