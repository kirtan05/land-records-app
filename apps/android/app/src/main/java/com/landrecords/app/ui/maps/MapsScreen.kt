package com.landrecords.app.ui.maps

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.data.maps.VillageMapEntry
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.components.dashedInset
import com.landrecords.app.ui.theme.Dp4
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.Lr
import com.landrecords.app.ui.theme.join

@Composable
fun MapsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lang = LocalLang.current
    val vm: MapsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { MapsViewModel(context.applicationContext as android.app.Application) }
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    fun openVillage(entry: VillageMapEntry) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.viewUrl)))
        }.onFailure {
            Toast.makeText(
                context,
                lang.join("કોઈ એપ નકશો ખોલી શકતી નથી", "No app available to open the map"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        BlueprintHeader(modifier = Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(Lr(R.string.maps_title_gu, R.string.maps_title_en), style = LandType.screenTitle, color = Land.colors.ink)
                    val sub = when {
                        state.loading -> lang.join("લોડ થાય છે…", "Loading…")
                        state.selectedTaluka != null -> "${state.villages.size}"
                        else -> Lr(R.string.maps_pick_district_gu, R.string.maps_pick_district_en)
                    }
                    Text(sub, style = LandType.label, color = Land.colors.ink3)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(Dp4.cardGap),
        ) {
            item {
                Text(
                    Lr(R.string.maps_district_label_gu, R.string.maps_district_label_en).uppercase(),
                    style = LandType.label, color = Land.colors.ink3,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.districts, key = { it }) { d ->
                        MapChip(text = d, selected = d == state.selectedDistrict, onClick = { vm.selectDistrict(d) })
                    }
                }
            }

            if (state.selectedDistrict != null) {
                item { Spacer(Modifier.height(2.dp)) }
                item {
                    Text(
                        Lr(R.string.maps_taluka_label_gu, R.string.maps_taluka_label_en).uppercase(),
                        style = LandType.label, color = Land.colors.ink3,
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.talukas, key = { it }) { t ->
                            MapChip(text = t, selected = t == state.selectedTaluka, onClick = { vm.selectTaluka(t) })
                        }
                    }
                }
            }

            if (state.selectedDistrict != null) {
                item { Spacer(Modifier.height(2.dp)) }
                item {
                    MapsSearchField(
                        value = state.query,
                        onChange = vm::setQuery,
                        placeholder = Lr(R.string.maps_search_hint_gu, R.string.maps_search_hint_en),
                    )
                }
            }

            val showingSearch = state.query.isNotBlank()
            val rows: List<Pair<String?, VillageMapEntry>> = when {
                showingSearch -> state.searchResults.map { (taluka, entry) -> taluka to entry }
                state.selectedTaluka != null -> state.villages.map { null to it }
                else -> emptyList()
            }

            if (state.selectedDistrict != null && state.selectedTaluka == null && !showingSearch) {
                item {
                    Text(
                        Lr(R.string.maps_pick_taluka_gu, R.string.maps_pick_taluka_en),
                        style = LandType.body, color = Land.colors.ink3,
                    )
                }
            } else if (rows.isEmpty() && (state.selectedTaluka != null || showingSearch) && !state.loading) {
                item {
                    Text(
                        Lr(R.string.maps_no_villages_gu, R.string.maps_no_villages_en),
                        style = LandType.body, color = Land.colors.ink3,
                    )
                }
            }

            items(rows, key = { (taluka, entry) -> "${taluka ?: ""}/${entry.village}/${entry.driveFileId}" }) { (taluka, entry) ->
                VillageMapRow(name = entry.village, subtitle = taluka, onClick = { openVillage(entry) })
            }
            item { Spacer(Modifier.height(2.dp)) }
        }
    }
}

@Composable
private fun MapChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(LandShape.pill)
            .background(if (selected) Land.colors.accentSoft else Land.colors.surface)
            .border(1.dp, if (selected) Land.colors.accent else Land.colors.line, LandShape.pill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text, style = LandType.body,
            color = if (selected) Land.colors.accent else Land.colors.ink2,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A village row drawn as a parcel: matches [com.landrecords.app.ui.components.ParcelTile]'s
 *  construction (tile clip, surface fill, 1dp line border, dashed hair inset). */
@Composable
private fun VillageMapRow(name: String, subtitle: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LandShape.tile)
            .background(if (pressed) Land.colors.surfaceAlt else Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.tile)
            .dashedInset(Land.colors.hair)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = LandType.bodyStrong, color = Land.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = LandType.label, color = Land.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Outlined.Place, contentDescription = null, tint = Land.colors.ink3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MapsSearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(LandShape.field)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.field)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(11.dp).clip(CircleShape).border(1.dp, Land.colors.ink3, CircleShape))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = LandType.body, color = Land.colors.ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LandType.body.copy(color = Land.colors.ink),
                cursorBrush = SolidColor(Land.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
