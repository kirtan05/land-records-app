package com.landrecords.app.ui.property

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.PillButton
import com.landrecords.app.ui.components.PrimaryButton
import com.landrecords.app.ui.components.RemovableChip
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Dp4
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr

/** Which picker sheet is currently open, if any. */
private enum class Picker { DISTRICT, TALUKA, VILLAGE, SURVEY }

@Composable
fun AddPropertyScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val app = landApp()
    val context = LocalContext.current
    val vm: AddPropertyViewModel = viewModel(
        factory = viewModelFactory { initializer { AddPropertyViewModel(app.repository) } },
    )

    // The whole cascade is bundled; names are still saved as they appear on AnyRoR
    // (Gujarati) so the iRCMS/AnyRoR cascade can match on the visible label.
    val districts = remember { CascadeData.districts(context) }
    val hasDistricts = districts.isNotEmpty()

    // District is always chosen from the bundled list (default: Anand, code 15).
    var district by remember { mutableStateOf(districts.firstOrNull { it.code == "15" }) }
    var districtTyped by remember { mutableStateOf("") } // only used if the asset is missing

    // Taluka may be a dropdown (bundled) or a typed fallback; keep both, use one.
    var talukaItem by remember { mutableStateOf<TalukaItem?>(null) }
    var talukaTyped by remember { mutableStateOf("") }

    // Village likewise.
    var villageItem by remember { mutableStateOf<GeoItem?>(null) }
    var villageTyped by remember { mutableStateOf("") }

    val surveyNumbers = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var openPicker by remember { mutableStateOf<Picker?>(null) }

    val districtCode = if (hasDistricts) district?.code else null
    val talukas = remember(districtCode) { districtCode?.let { CascadeData.talukasFor(context, it) } }
    val villageOptions = remember(districtCode, talukaItem?.code) {
        val d = districtCode; val t = talukaItem?.code
        if (d != null && t != null) CascadeData.villagesFor(context, d, t) else null
    }
    // The bundled survey-token list for the chosen village (null → fall back to free-text entry).
    val villageCode = if (villageOptions != null) villageItem?.code else null
    val surveyOptions = remember(districtCode, talukaItem?.code, villageCode) {
        val d = districtCode; val t = talukaItem?.code; val v = villageCode
        if (d != null && t != null && v != null) CascadeData.surveyTokensFor(context, d, t, v) else null
    }

    // Effective saved names: the picked Gujarati label, or the typed text.
    val districtName = if (hasDistricts) district?.gu.orEmpty() else districtTyped.trim()
    val talukaName = if (talukas != null) talukaItem?.gu.orEmpty() else talukaTyped.trim()
    val villageName = if (villageOptions != null) villageItem?.gu.orEmpty() else villageTyped.trim()

    val canSave = districtName.isNotBlank() && talukaName.isNotBlank() &&
        villageName.isNotBlank() && surveyNumbers.isNotEmpty()

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        BlueprintHeader(modifier = Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                Spacer(Modifier.width(12.dp))
                Text(Lr(R.string.add_property_gu, R.string.add_property_en), style = LandType.screenTitle, color = Land.colors.ink)
            }
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Dp4.cardGap),
        ) {
            ReadOnlyRow(Lr(R.string.state_gu, R.string.state_en), "ગુજરાત · Gujarat")

            // District — always a picker when the bundled list is present.
            if (hasDistricts) {
                PickerRow(
                    label = Lr(R.string.district_gu, R.string.district_en),
                    valueGu = district?.gu.orEmpty(),
                    valueLatin = district?.en.orEmpty(),
                    placeholder = L("જિલ્લો પસંદ કરો", "Choose district"),
                    onClick = { openPicker = Picker.DISTRICT },
                )
            } else {
                EditableRow(Lr(R.string.district_gu, R.string.district_en), districtTyped, { districtTyped = it }, "દા.ત. આણંદ")
            }

            // Taluka — dropdown when bundled for the district, else a typed field.
            if (talukas != null) {
                PickerRow(
                    label = Lr(R.string.taluka_gu, R.string.taluka_en),
                    valueGu = talukaItem?.gu.orEmpty(),
                    valueLatin = talukaItem?.en.orEmpty(),
                    placeholder = L("તાલુકો પસંદ કરો", "Choose taluka"),
                    onClick = { openPicker = Picker.TALUKA },
                )
            } else {
                EditableRow(Lr(R.string.taluka_gu, R.string.taluka_en), talukaTyped, { talukaTyped = it }, "દા.ત. બોરસદ")
            }

            // Village — dropdown when this taluka's villages are bundled, else a typed field.
            if (villageOptions != null) {
                PickerRow(
                    label = Lr(R.string.village_gu, R.string.village_en),
                    valueGu = villageItem?.gu.orEmpty(),
                    valueLatin = villageItem?.en.orEmpty(),
                    placeholder = L("ગામ પસંદ કરો", "Choose village"),
                    onClick = { openPicker = Picker.VILLAGE },
                )
            } else {
                EditableRow(Lr(R.string.village_gu, R.string.village_en), villageTyped, { villageTyped = it }, "ગામનું નામ")
            }

            Text(
                L("યાદીમાંથી પસંદ કરો, અથવા AnyRoR પ્રમાણે ગુજરાતીમાં લખો", "Pick from the list, or type in Gujarati as on AnyRoR"),
                style = LandType.stamp, color = Land.colors.ink3,
            )

            Spacer(Modifier.height(4.dp))
            Text(Lr(R.string.survey_numbers_gu, R.string.survey_numbers_en).uppercase(), style = LandType.label, color = Land.colors.ink3)

            if (surveyNumbers.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dp4.chipGap), verticalArrangement = Arrangement.spacedBy(Dp4.chipGap)) {
                    surveyNumbers.forEach { no ->
                        RemovableChip(no, onRemove = { surveyNumbers.remove(no) })
                    }
                }
            }

            if (surveyOptions != null) {
                // Bundled list for this village → pick exact tokens (no typos, guaranteed match).
                SurveyPickTrigger(count = surveyOptions.size, onClick = { openPicker = Picker.SURVEY })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineField(
                        value = input, onChange = { input = it },
                        placeholder = Lr(R.string.add_survey_number_gu, R.string.add_survey_number_en),
                        modifier = Modifier.weight(1f),
                    )
                    PillButton(
                        Lr(R.string.action_add_gu, R.string.action_add_en),
                        onClick = {
                            val t = input.trim()
                            if (t.isNotEmpty() && t !in surveyNumbers) surveyNumbers.add(t)
                            input = ""
                        },
                        filled = true,
                    )
                }
            }
        }

        Column(Modifier.padding(20.dp)) {
            PrimaryButton(
                Lr(R.string.action_save_gu, R.string.action_save_en),
                onClick = {
                    if (canSave) vm.save(districtName, talukaName, villageName, surveyNumbers.toList()) { onSaved() }
                },
            )
        }
    }

    when (openPicker) {
        Picker.DISTRICT -> PickerSheet(
            titleGu = "જિલ્લો", titleEn = "District",
            hintGu = "જિલ્લો શોધો", hintEn = "Search district",
            items = districts,
            onDismiss = { openPicker = null },
            onPick = { picked ->
                if (picked.code != district?.code) {
                    district = picked
                    talukaItem = null; talukaTyped = ""
                    villageItem = null; villageTyped = ""
                    surveyNumbers.clear()
                }
                openPicker = null
            },
        )
        Picker.TALUKA -> PickerSheet(
            titleGu = "તાલુકો", titleEn = "Taluka",
            hintGu = "તાલુકો શોધો", hintEn = "Search taluka",
            items = talukas.orEmpty().map { GeoItem(it.code, it.en, it.gu) },
            onDismiss = { openPicker = null },
            onPick = { picked ->
                if (picked.code != talukaItem?.code) {
                    talukaItem = talukas?.firstOrNull { it.code == picked.code }
                    villageItem = null; villageTyped = ""
                    surveyNumbers.clear()
                }
                openPicker = null
            },
        )
        Picker.VILLAGE -> PickerSheet(
            titleGu = "ગામ", titleEn = "Village",
            hintGu = "ગામ શોધો", hintEn = "Search village",
            items = villageOptions.orEmpty(),
            onDismiss = { openPicker = null },
            onPick = { picked ->
                if (picked.code != villageItem?.code) {
                    villageItem = picked
                    surveyNumbers.clear()
                }
                openPicker = null
            },
        )
        Picker.SURVEY -> SurveyPickerSheet(
            tokens = surveyOptions.orEmpty(),
            selected = surveyNumbers,
            onToggle = { tok -> if (tok in surveyNumbers) surveyNumbers.remove(tok) else surveyNumbers.add(tok) },
            onDismiss = { openPicker = null },
        )
        null -> Unit
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LandSize.minTouchTarget)
            .clip(LandShape.field).background(Land.colors.surfaceAlt)
            .border(1.dp, Land.colors.line, LandShape.field)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = LandType.label, color = Land.colors.ink3, modifier = Modifier.width(84.dp))
        Text(value, style = LandType.bodyStrong, color = Land.colors.ink2)
    }
}

/** A tap-to-open row that mirrors [EditableRow] but shows a chosen "gu / latin" value + a chevron. */
@Composable
private fun PickerRow(
    label: String,
    valueGu: String,
    valueLatin: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LandSize.minTouchTarget)
            .clip(LandShape.field).background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.field)
            .noRippleClick(onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = LandType.label, color = Land.colors.ink3, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f)) {
            if (valueGu.isEmpty()) {
                Text(placeholder, style = LandType.body, color = Land.colors.ink3)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(valueGu, style = LandType.bodyStrong, color = Land.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (valueLatin.isNotBlank()) {
                        Text(valueLatin, style = LandType.label, color = Land.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = Land.colors.ink3, modifier = Modifier.size(18.dp))
    }
}

/** A searchable bottom-sheet list of "gu / latin" items — used for district / taluka / village. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    titleGu: String,
    titleEn: String,
    hintGu: String,
    hintEn: String,
    items: List<GeoItem>,
    onDismiss: () -> Unit,
    onPick: (GeoItem) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        val q = query.trim()
        if (q.isEmpty()) items
        else items.filter { it.gu.contains(q, ignoreCase = true) || it.en.contains(q, ignoreCase = true) || it.code.contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Land.colors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(L(titleGu, titleEn), style = LandType.screenTitle, color = Land.colors.ink)
            SheetSearchField(value = query, onChange = { query = it }, placeholder = L(hintGu, hintEn))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(Dp4.chipGap),
            ) {
                items(filtered, key = { it.code }) { item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = LandSize.minTouchTarget)
                            .clip(LandShape.field).background(Land.colors.surface)
                            .border(1.dp, Land.colors.line, LandShape.field)
                            .noRippleClick { onPick(item) }
                            .padding(horizontal = 15.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(item.gu, style = LandType.bodyStrong, color = Land.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.en, style = LandType.label, color = Land.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Full-width tap target (styled like [InlineField]) that opens the survey-token picker. */
@Composable
private fun SurveyPickTrigger(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(LandSize.field)
            .clip(LandShape.field).background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.field)
            .noRippleClick(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            L("સર્વે નંબર પસંદ કરો", "Pick survey number"),
            style = LandType.body, color = Land.colors.ink3, modifier = Modifier.weight(1f),
        )
        Text("$count", style = LandType.label, color = Land.colors.ink3)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = Land.colors.ink3, modifier = Modifier.size(18.dp))
    }
}

/**
 * Type-to-filter picker over a village's bundled survey tokens (up to ~2k). Tapping a token
 * toggles it into the record; the sheet stays open so several surveys can be added in one pass.
 * Tokens render in Mono (they are land data — never translated).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurveyPickerSheet(
    tokens: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, tokens) {
        val q = query.trim()
        if (q.isEmpty()) tokens else tokens.filter { it.contains(q, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Land.colors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(L("સર્વે નંબર", "Survey number"), style = LandType.screenTitle, color = Land.colors.ink)
            SheetSearchField(value = query, onChange = { query = it }, placeholder = L("સર્વે નંબર શોધો", "Search survey number"))
            Text(
                L("${selected.size} પસંદ · ${filtered.size} બતાવ્યા", "${selected.size} chosen · ${filtered.size} shown"),
                style = LandType.stamp, color = Land.colors.ink3,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(Dp4.chipGap),
            ) {
                items(filtered, key = { it }) { tok ->
                    val isSel = tok in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = LandSize.minTouchTarget)
                            .clip(LandShape.field)
                            .background(if (isSel) Land.colors.accentSoft else Land.colors.surface)
                            .border(1.dp, if (isSel) Land.colors.accent else Land.colors.line, LandShape.field)
                            .noRippleClick { onToggle(tok) }
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tok, style = LandType.metaMono,
                            color = if (isSel) Land.colors.accent else Land.colors.ink,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (isSel) Icon(Icons.Outlined.Check, contentDescription = null, tint = Land.colors.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(LandShape.field)
            .background(Land.colors.surfaceAlt)
            .border(1.dp, Land.colors.line, LandShape.field)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(11.dp).clip(CircleShape).border(1.dp, Land.colors.ink3, CircleShape))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = LandType.body, color = Land.colors.ink3)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = LandType.body.copy(color = Land.colors.ink),
                cursorBrush = SolidColor(Land.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EditableRow(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LandSize.minTouchTarget)
            .clip(LandShape.field).background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.field)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = LandType.label, color = Land.colors.ink3, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = LandType.body, color = Land.colors.ink3)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = LandType.bodyStrong.copy(color = Land.colors.ink),
                cursorBrush = SolidColor(Land.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InlineField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(LandSize.field)
            .clip(LandShape.field)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.field)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = LandType.body, color = Land.colors.ink3)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = LandType.body.copy(color = Land.colors.ink),
            cursorBrush = SolidColor(Land.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Press feedback in this app is a background swap, never a ripple. */
@Composable
private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
