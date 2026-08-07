package com.landrecords.app.ui.property

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.Text
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
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr

@Composable
fun AddPropertyScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val app = landApp()
    val vm: AddPropertyViewModel = viewModel(
        factory = viewModelFactory { initializer { AddPropertyViewModel(app.repository) } },
    )

    // Names are entered as they appear on AnyRoR (Gujarati) so the cascade can match them.
    var district by remember { mutableStateOf("આણંદ") }
    var taluka by remember { mutableStateOf("") }
    var village by remember { mutableStateOf("") }
    val surveyNumbers = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }

    val canSave = district.isNotBlank() && taluka.isNotBlank() && village.isNotBlank() && surveyNumbers.isNotEmpty()

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
            EditableRow(Lr(R.string.district_gu, R.string.district_en), district, { district = it }, "દા.ત. આણંદ")
            EditableRow(Lr(R.string.taluka_gu, R.string.taluka_en), taluka, { taluka = it }, "દા.ત. બોરસદ")
            EditableRow(Lr(R.string.village_gu, R.string.village_en), village, { village = it }, "ગામનું નામ")

            Text(
                "AnyRoR માં જેમ છે તેમ ગુજરાતીમાં લખો · Type the names in Gujarati, as on AnyRoR",
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

        Column(Modifier.padding(20.dp)) {
            PrimaryButton(
                Lr(R.string.action_save_gu, R.string.action_save_en),
                onClick = {
                    if (canSave) vm.save(district, taluka, village, surveyNumbers.toList()) { onSaved() }
                },
            )
        }
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
