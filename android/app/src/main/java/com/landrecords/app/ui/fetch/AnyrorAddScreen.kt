package com.landrecords.app.ui.fetch

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.join
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.web.AnyRor
import com.landrecords.app.web.AnyRorInjection
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Add a village via AnyRoR" — the AnyRoR-agnostic add flow (no pre-cascaded data needed). The app
 * drives the LIVE cascade and, at each still-empty level, shows the site's own options for the user
 * to pick (district → taluka → village → survey). Once all four are chosen it reads the selected
 * TEXTS, creates the property + survey (Gujarati names straight off AnyRoR, so future prefills match
 * exactly), and hands off to the normal integrated [FetchScreen] to solve the CAPTCHA + capture.
 */
private data class GuidedChoice(val level: String, val options: List<Pair<String, String>>)
private data class Sel(val district: String, val taluka: String, val village: String, val survey: String)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AnyrorAddScreen(onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val app = landApp()
    val lang = LocalLang.current // captured so the (non-composable) create coroutine can localise a toast
    val scope = rememberCoroutineScope()
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableIntStateOf(0) }
    var chooser by remember { mutableStateOf<GuidedChoice?>(null) }
    var busy by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val surveyDropId = AnyRor.Ids.SURVEY_INTEGRATED
    val recordValue = "8"

    LaunchedEffect(pageLoaded, chooser, creating) {
        val wv = webRef ?: return@LaunchedEffect
        if (pageLoaded < 1 || chooser != null || creating || error != null) return@LaunchedEffect
        val raw = WebViewCapture.eval(wv, AnyRorInjection.guidedStepJs(recordValue, surveyDropId))
        android.util.Log.i("LR", "anyrorAdd step (page #$pageLoaded): ${raw.take(60)}")
        when {
            raw == "READY" -> if (!creating) {
                creating = true
                // Create + hand off in the EXTERNAL scope, not this LaunchedEffect: `creating` is an
                // effect key, so setting it cancels this coroutine — which previously killed the work
                // mid-way (before addProperty/onCreated) and left it stuck on "Fetching the record".
                scope.launch {
                    val sel = parseSelectedTexts(WebViewCapture.eval(wv, AnyRorInjection.selectedTextsJs(surveyDropId)))
                    // AnyRoR appends a "- <code>" to its VILLAGE option text (e.g. "ઉતરસંડા - 091");
                    // strip it so the saved name is clean and the AnyRoR/iRCMS matchers work.
                    val codeRx = Regex("""\s*-\s*\d+\s*$""")
                    val d = sel.district.replace(codeRx, "").trim()
                    val t = sel.taluka.replace(codeRx, "").trim()
                    val v = sel.village.replace(codeRx, "").trim()
                    val s = sel.survey.trim()
                    if (d.isBlank() || t.isBlank() || v.isBlank() || s.isBlank()) {
                        error = "Couldn't read the selection — please try again."; creating = false; return@launch
                    }
                    // AnyRoR's cascade is Gujarati-only. Writing that text into BOTH the English key
                    // columns and the Gujarati label columns is what produced a second "ભરોડા" card
                    // beside the iRCMS-added "Bharoda" — the two never compared equal. Resolve the
                    // catalogue's English spelling for the key and keep the Gujarati as the label;
                    // when the village isn't in the catalogue we fall back to the Gujarati text,
                    // i.e. exactly the old behaviour.
                    com.landrecords.app.data.place.PlaceNames.load(app)
                    val canon = com.landrecords.app.data.place.PlaceNames.canonical(d, t, v)
                    // Dedup: if this survey is already saved it is kept (not duplicated/overwritten);
                    // we still hand off to the fetch so the user gets a fresh record.
                    val addRes = app.repository.addPropertyDetailed(
                        canon?.first ?: d, d,
                        canon?.second ?: t, t,
                        canon?.third ?: v, v,
                        listOf(s),
                    )
                    // Look the survey up by the property we just got back, not by name: an existing
                    // row may be stored under either script, and a name lookup would miss it.
                    val sid = app.repository.findSurveyIdIn(addRes.propertyId, s)
                    if (sid == null) { error = "Couldn't save the village — please try again."; creating = false; return@launch }
                    if (addRes.duplicates.isNotEmpty()) {
                        android.widget.Toast.makeText(
                            app,
                            lang.join("આ સર્વે નંબર પહેલેથી યાદીમાં છે — ફરી મેળવી રહ્યા છીએ", "Already in your list — re-fetching"),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    android.util.Log.i("LR", "anyrorAdd survey $sid ($d / $t / $v / $s) dup=${addRes.duplicates.isNotEmpty()} — handing to integrated fetch")
                    onCreated(sid)
                }
            }
            raw.startsWith("{") -> {
                val gc = parseGuidedChoice(raw)
                if (gc != null && gc.options.isNotEmpty()) { chooser = gc; busy = false }
            }
            else -> busy = true // 'WAIT' / 'RT' — the page is still cascading; keep driving
        }
    }

    // Reachability guard — a WAF-blocked IP hangs the WebView with no callback.
    LaunchedEffect(Unit) {
        delay(75_000)
        if (pageLoaded < 1 && error == null && !creating) {
            error = "Couldn't reach AnyRoR — your connection may be blocked. Try later or another network."
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
            Column(Modifier.background(Land.colors.surface).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L("AnyRoR થી ગામ ઉમેરો", "Add village via AnyRoR"), style = LandType.bodyStrong, color = Land.colors.ink)
                        Text("anyror.gujarat.gov.in", style = LandType.label, color = Land.colors.ink3)
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            }
            Column(Modifier.fillMaxWidth().background(Land.colors.accent).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(L("જિલ્લો → તાલુકો → ગામ → સર્વે પસંદ કરો", "Pick district → taluka → village → survey"), style = LandType.body, color = Land.colors.onAccent)
                Text(L("પછી કોડ ભરીને રેકોર્ડ મેળવો — ગામ સચવાઈ જશે", "then solve the code to fetch — the village is saved"), style = LandType.label, color = Land.colors.onAccent)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                pageLoaded += 1
                                android.util.Log.i("LR", "anyrorAdd onPageFinished #$pageLoaded url=${url?.take(60)}")
                            }
                        }
                        webRef = this
                        loadUrl(AnyRor.URL)
                    }
                },
            )
        }

        when {
            error != null -> InputBlocker(active = false) {
                SavingOverlay(
                    surveyNo = "", village = "", destinationPath = "", step = 2, error = error,
                    onRetry = { error = null; creating = false; chooser = null; busy = true; webRef?.reload() },
                )
            }
            creating -> InputBlocker(active = true, onExit = onBack) { PreparingOverlay(fetching = true) }
            busy && chooser == null -> InputBlocker(active = true, onExit = onBack) { if (pageLoaded >= 1) FillingIndicator() else OpeningOverlay() }
        }

        chooser?.let { gc ->
            GuidedChooserSheet(
                level = gc.level,
                options = gc.options,
                onDismiss = { chooser = null; busy = true },
                onPick = { value ->
                    busy = true
                    val wv = webRef
                    if (wv == null) {
                        chooser = null
                    } else {
                        scope.launch {
                            // Select the value FIRST, then clear the chooser — clearing re-runs the
                            // step effect, which must see the level already set (else it re-shows this
                            // same chooser). dist/tal/vil also post back → the next level loads.
                            WebViewCapture.eval(wv, AnyRorInjection.selectSurveyValueJs(AnyRorInjection.levelDropId(gc.level, surveyDropId), value))
                            chooser = null
                        }
                    }
                },
            )
        }
    }
}

private fun parseGuidedChoice(json: String): GuidedChoice? = try {
    val o = org.json.JSONObject(json)
    val level = o.optString("level")
    val arr = o.optJSONArray("opts") ?: return null
    val opts = (0 until arr.length()).mapNotNull { k ->
        val io = arr.getJSONObject(k)
        val v = io.optString("v"); if (v.isBlank()) null else v to io.optString("t").ifBlank { v }
    }
    if (level.isBlank()) null else GuidedChoice(level, opts)
} catch (e: Exception) { null }

private fun parseSelectedTexts(json: String): Sel = try {
    val o = org.json.JSONObject(json)
    Sel(o.optString("district"), o.optString("taluka"), o.optString("village"), o.optString("survey"))
} catch (e: Exception) { Sel("", "", "", "") }

/** Searchable sheet listing a cascade level's live AnyRoR options for the user to pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidedChooserSheet(
    level: String,
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val titleGu = when (level) { "dist" -> "જિલ્લો પસંદ કરો"; "tal" -> "તાલુકો પસંદ કરો"; "vil" -> "ગામ પસંદ કરો"; else -> "સર્વે પસંદ કરો" }
    val titleEn = when (level) { "dist" -> "Choose district"; "tal" -> "Choose taluka"; "vil" -> "Choose village"; else -> "Choose survey" }
    val mono = level == "sur"
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter { it.second.contains(q, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Land.colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(L(titleGu, titleEn), style = LandType.screenTitle, color = Land.colors.ink)
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(LandShape.field)
                    .background(Land.colors.surfaceAlt).border(1.dp, Land.colors.line, LandShape.field)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(11.dp).clip(CircleShape).border(1.dp, Land.colors.ink3, CircleShape))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(L("શોધો", "Search"), style = LandType.body, color = Land.colors.ink3)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = LandType.body.copy(color = Land.colors.ink),
                        cursorBrush = SolidColor(Land.colors.accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(L("${filtered.size} બતાવ્યા", "${filtered.size} shown"), style = LandType.stamp, color = Land.colors.ink3)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.first }) { opt ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(LandShape.field)
                            .background(Land.colors.surface).border(1.dp, Land.colors.line, LandShape.field)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(opt.first) }
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(opt.second, style = if (mono) LandType.metaMono else LandType.bodyStrong, color = Land.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
