package com.landrecords.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.landrecords.app.R
import com.landrecords.app.data.storage.BackupExport
import com.landrecords.app.ui.components.BlueprintHeader
import com.landrecords.app.ui.components.SegmentedPills
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Dp4
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lang
import com.landrecords.app.ui.theme.Lr
import com.landrecords.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = landApp()
    val appState = app.appState
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    var exporting by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showPdfInfo by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<com.landrecords.app.web.Updater.Update?>(null) }
    var installing by remember { mutableStateOf(false) }

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

            SettingCard {
                Text(Lr(R.string.storage_gu, R.string.storage_en), style = LandType.bodyStrong, color = Land.colors.ink)
                Spacer(Modifier.height(4.dp))
                Text("Documents/LandRecords", style = LandType.metaMono, color = Land.colors.ink2)
                Text(stringResource(R.string.storage_note), style = LandType.meta, color = Land.colors.ink3)
            }

            NavRow(
                label = if (exporting) L("નિકાસ થઈ રહ્યું છે…", "Exporting…") else Lr(R.string.backup_gu, R.string.backup_en),
                enabled = !exporting,
                onClick = {
                    exporting = true
                    scope.launch {
                        val n = BackupExport.exportZip(context)
                        exporting = false
                        val msg = when {
                            n > 0 -> "Exported $n PDFs"
                            n == 0 -> "No records to export yet"
                            else -> "Export failed"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            NavRow(label = Lr(R.string.pdf_layout_gu, R.string.pdf_layout_en), onClick = { showPdfInfo = true })

            NavRow(
                label = if (checking) L("તપાસ થઈ રહી છે…", "Checking…") else L("અપડેટ તપાસો", "Check for updates"),
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val u = com.landrecords.app.web.Updater.check(context)
                        checking = false
                        if (u != null) update = u
                        else Toast.makeText(context, "તમે લેટેસ્ટ વર્ઝન પર છો · You're on the latest version", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            NavRow(
                label = if (reporting) L("તૈયાર થઈ રહ્યું છે…", "Preparing…") else L("ભૂલ / સમસ્યા જણાવો", "Report a problem"),
                enabled = !reporting,
                onClick = {
                    reporting = true
                    scope.launch {
                        val ok = com.landrecords.app.data.storage.DiagnosticsReport.share(context)
                        reporting = false
                        if (!ok) Toast.makeText(context, "Couldn't build the report", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            // Migration now runs automatically on first launch (MainActivity.resumeFetchQueue),
            // and fetch controls live on the Fetch-status screen reached from the top bar — so
            // Settings no longer carries those rows.
            NavRow(label = Lr(R.string.about_gu, R.string.about_en), onClick = { showAbout = true })

            Spacer(Modifier.height(4.dp))
            Text(
                "${stringResource(R.string.settings_footer)} · v$version",
                style = LandType.stamp, color = Land.colors.ink3,
            )
        }
    }

    if (showPdfInfo) {
        InfoDialog(
            title = Lr(R.string.pdf_layout_gu, R.string.pdf_layout_en),
            body = L(
                "રેકોર્ડ A4 લેન્ડસ્કેપ PDF તરીકે સાચવવામાં આવે છે — AnyRoR ડેસ્કટૉપ જેવું આખું-પહોળું લેઆઉટ, પેજ-બ્રેક સાચવીને.",
                "Records are saved as A4-landscape PDFs matching the AnyRoR desktop layout — full-width tables, page-break aware.",
            ),
            onDismiss = { showPdfInfo = false },
        )
    }
    if (showAbout) {
        InfoDialog(
            title = Lr(R.string.about_gu, R.string.about_en),
            body = L(
                "તમારા જમીન રેકોર્ડ — સંકલિત ૭/૧૨, જૂનું સ્કેન થયેલ ૭/૧૨, અને iRCMS જમીન કેસ — AnyRoR અને iRCMS પરથી મેળવીને PDF તરીકે સાચવેલ.\n\nસંસ્કરણ $version",
                "Your land records — Integrated 7/12, old scanned VF-7/12, and iRCMS land cases — fetched from AnyRoR & iRCMS and saved as PDFs.\n\nVersion $version",
            ),
            onDismiss = { showAbout = false },
        )
    }
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { if (!installing) update = null },
            confirmButton = {
                TextButton(
                    enabled = !installing,
                    onClick = {
                        installing = true
                        scope.launch {
                            val apk = com.landrecords.app.web.Updater.download(context, u)
                            installing = false
                            if (apk != null) {
                                com.landrecords.app.web.Updater.install(context, apk); update = null
                            } else {
                                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text(
                        if (installing) L("ડાઉનલોડ થઈ રહ્યું છે…", "Downloading…") else L("હમણાં અપડેટ કરો", "Update now"),
                        color = Land.colors.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !installing, onClick = { update = null }) {
                    Text(L("પછી", "Later"), color = Land.colors.ink3)
                }
            },
            title = { Text(L("નવું અપડેટ", "Update available") + " · v${u.versionName}", style = LandType.bodyStrong, color = Land.colors.ink) },
            text = { Text(u.notes.ifBlank { L("નવું વર્ઝન ઉપલબ્ધ છે.", "A newer version is available.") }, style = LandType.body, color = Land.colors.ink2) },
            containerColor = Land.colors.surface,
        )
    }
}

@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("બંધ કરો", "Close"), color = Land.colors.accent) } },
        title = { Text(title, style = LandType.bodyStrong, color = Land.colors.ink) },
        text = { Text(body, style = LandType.body, color = Land.colors.ink2) },
        containerColor = Land.colors.surface,
    )
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
private fun NavRow(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Dp4.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LandType.body, color = Land.colors.ink, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Land.colors.ink3)
    }
}
