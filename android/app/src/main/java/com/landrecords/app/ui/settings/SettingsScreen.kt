package com.landrecords.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.landrecords.app.R
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.SegmentedPills
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Dp4
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lang
import com.landrecords.app.ui.theme.Lr
import com.landrecords.app.ui.theme.ThemeMode

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = landApp()
    val appState = app.appState

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        BlueprintHeader(modifier = Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                Spacer(Modifier.width(12.dp))
                Text(Lr(R.string.settings_gu, R.string.settings_en), style = LandType.screenTitle, color = Land.colors.ink)
            }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Language
            SettingCard {
                Text(Lr(R.string.language_gu, R.string.language_en), style = LandType.bodyStrong, color = Land.colors.ink)
                Spacer(Modifier.height(10.dp))
                SegmentedPills(
                    options = listOf(
                        Lr(R.string.lang_gu_gu, R.string.lang_gu_en),
                        Lr(R.string.lang_both_gu, R.string.lang_both_en),
                        Lr(R.string.lang_en_gu, R.string.lang_en_en),
                    ),
                    selectedIndex = when (appState.lang) { Lang.GU -> 0; Lang.BOTH -> 1; Lang.EN -> 2 },
                    onSelect = { appState.setLang(listOf(Lang.GU, Lang.BOTH, Lang.EN)[it]) },
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.language_note), style = LandType.meta, color = Land.colors.ink3)
            }

            // Theme
            SettingCard {
                Text(Lr(R.string.theme_gu, R.string.theme_en), style = LandType.bodyStrong, color = Land.colors.ink)
                Spacer(Modifier.height(10.dp))
                SegmentedPills(
                    options = listOf(
                        Lr(R.string.theme_system_gu, R.string.theme_system_en),
                        Lr(R.string.theme_light_gu, R.string.theme_light_en),
                        Lr(R.string.theme_dark_gu, R.string.theme_dark_en),
                    ),
                    selectedIndex = when (appState.themeMode) { ThemeMode.SYSTEM -> 0; ThemeMode.LIGHT -> 1; ThemeMode.DARK -> 2 },
                    onSelect = { appState.setTheme(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)[it]) },
                )
            }

            // Storage
            SettingCard {
                Text(Lr(R.string.storage_gu, R.string.storage_en), style = LandType.bodyStrong, color = Land.colors.ink)
                Spacer(Modifier.height(4.dp))
                Text("Documents/LandRecords", style = LandType.metaMono, color = Land.colors.ink2)
                Text(stringResource(R.string.storage_note), style = LandType.meta, color = Land.colors.ink3)
            }

            NavRow(Lr(R.string.backup_gu, R.string.backup_en))
            NavRow(Lr(R.string.pdf_layout_gu, R.string.pdf_layout_en))
            NavRow(Lr(R.string.about_gu, R.string.about_en))

            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_footer), style = LandType.stamp, color = Land.colors.ink3)
        }
    }
}

@Composable
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .padding(Dp4.cardPadding),
        content = content,
    )
}

@Composable
private fun NavRow(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .clickable { }
            .padding(Dp4.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LandType.body, color = Land.colors.ink, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Land.colors.ink3)
    }
}
