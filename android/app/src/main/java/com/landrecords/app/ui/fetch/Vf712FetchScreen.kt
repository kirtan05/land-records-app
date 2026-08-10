package com.landrecords.app.ui.fetch

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LocalLang
import com.landrecords.app.ui.theme.join
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.VfScansStore
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr
import com.landrecords.app.web.AnyRor
import com.landrecords.app.web.AnyRorInjection
import com.landrecords.app.web.PdfMerge
import com.landrecords.app.web.Vf712Downloader
import com.landrecords.app.web.Vf712Injection
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * OLD SCANNED VF-7/12 capture. The cascade + the single human CAPTCHA are identical to the
 * Integrated record (record type "11", survey dropdown [AnyRor.Ids.SURVEY_VF712]) so they are
 * driven by [AnyRorInjection.prefillStepJs] + [AnyRorInjection.dimSpotlightJs] exactly like
 * [FetchScreen]. What differs is the RESULT: a grid of year-wise scanned entries. After the
 * CAPTCHA we read the grid, and for each "Ok" row fire its View-PDF postback in place, read the
 * server-minted PDFView1.aspx?detail=<token> from the embed, fetch those bytes with the session
 * cookies ([Vf712Downloader]), weed the "not scanned" text placeholders, and merge the genuine
 * scans oldest→newest into one VF-7/12 PDF. One CAPTCHA covers every year of the survey.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Vf712FetchScreen(
    surveyId: Long,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val app = landApp()
    val vm: FetchViewModel = viewModel(
        factory = viewModelFactory {
            initializer { FetchViewModel(app.repository, app.libraryWriter, surveyId) }
        },
    )
    val info by vm.info.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()

    var webRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableIntStateOf(0) }
    var pageSignal by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var awaitingDetail by remember { mutableStateOf(false) }
    var captureRunning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(true) }
    // True only after a genuine stall (no progress ~15s, not mid-capture) — gates the Back escape.
    var stuck by remember { mutableStateOf(false) }
    // When the survey has no exact match in the (OLD-numbered) VF-7/12 dropdown, offer these to pick.
    var surveyChoices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var surveyChosen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val surveyDropId = AnyRor.Ids.SURVEY_VF712
    val recordValue = AnyRor.recordValue(RecordType.VF712) ?: "11"

    // ── Cascade prefill (one step per postback) + spotlight the CAPTCHA — same machine as FetchScreen.
    LaunchedEffect(pageLoaded, info, awaitingDetail, surveyChosen) {
        val wv = webRef ?: return@LaunchedEffect
        val i = info ?: return@LaunchedEffect
        if (captureRunning || awaitingDetail) return@LaunchedEffect
        if (surveyChosen) {
            // The user hand-picked a survey (no exact match existed) — cascade complete, spotlight.
            delay(400)
            WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
            working = false
            return@LaunchedEffect
        }
        if (surveyChoices != null) return@LaunchedEffect // chooser open — wait for the user
        val step = WebViewCapture.eval(
            wv,
            AnyRorInjection.prefillStepJs(
                recordValue = recordValue,
                districtGu = i.districtGu, talukaGu = i.talukaGu, villageGu = i.villageGu,
                surveyNorm = i.surveyNorm, surveyDropId = surveyDropId,
            ),
        )
        android.util.Log.i("LR", "vf712 prefill step (page #$pageLoaded): $step")
        val code = step.substringBefore('|').trim()
        if (code == "READY" || code == "SUR") {
            delay(600)
            WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
            working = false // cascade filled + spotlit — the user solves the CAPTCHA now
        } else if (step.startsWith("WAIT|sur|want=")) {
            // Survey dropdown is populated but the exact survey isn't in it — VF-7/12 uses the OLD
            // numbering (e.g. no "1257/p", but "1257", "1257/1"…). Let the user choose which to fetch.
            val n = Regex("""\|n=(\d+)""").find(step)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (n > 0) {
                val opts = parseSurveyOptions(WebViewCapture.eval(wv, AnyRorInjection.surveyOptionsJs(surveyDropId)))
                if (opts.isNotEmpty()) { surveyChoices = opts; working = false }
            }
        }
    }

    // ── After the CAPTCHA tap: wait for the grid, then fetch + weed + merge every scanned doc.
    //    Keyed on awaitingDetail ONLY (not pageLoaded): the per-row postbacks bump pageLoaded, and
    //    keying on it would cancel this coroutine mid-loop. Page nav is awaited via pageSignal.
    LaunchedEffect(awaitingDetail) {
        val wv = webRef ?: return@LaunchedEffect
        if (!awaitingDetail || captureRunning) return@LaunchedEffect

        // Poll until the submit postback lands us on the results grid (resultReadyJs returns WAIT
        // while still on the form — Get Record Detail present — so this also covers a wrong CAPTCHA).
        var ready = ""
        var tries = 0
        while (awaitingDetail && tries < 30) {
            ready = WebViewCapture.eval(wv, Vf712Injection.resultReadyJs())
            if (ready.startsWith("READY") || ready.contains("NOTFOUND")) break
            delay(500); tries++
        }
        android.util.Log.i("LR", "vf712 resultReady='$ready' after $tries tries")

        when {
            ready.startsWith("READY") -> {
                captureRunning = true
                working = true
                vm.setPhase(FetchPhase.READING)
                val listHtml = WebViewCapture.rawHtml(wv)
                val rows = parseVf712Rows(WebViewCapture.eval(wv, Vf712Injection.readRowsJs()))
                android.util.Log.i("LR", "vf712 grid rows=${rows.size}")
                if (rows.isEmpty()) { vm.fail("Couldn't read the VF-7/12 list. Please try again."); return@LaunchedEffect }

                vm.setPhase(FetchPhase.BUILDING)
                val kept = ArrayList<Pair<Vf712Row, ByteArray>>() // grid row -> genuine scan bytes
                for (r in rows) {
                    if (!r.status.contains("ok", ignoreCase = true)) continue
                    val sig = CompletableDeferred<Unit>()
                    pageSignal = sig
                    WebViewCapture.eval(wv, Vf712Injection.selectRowJs(r.index)) // full-page postback
                    withTimeoutOrNull(30_000) { sig.await() }
                    delay(500) // let the <object> embed parse in
                    val url = WebViewCapture.eval(wv, Vf712Injection.readPdfUrlJs())
                    if (url.isBlank()) { android.util.Log.i("LR", "vf712 row ${r.index} ${r.period}: no embed"); continue }
                    val bytes = Vf712Downloader.fetch(url)
                    if (bytes == null || !Vf712Downloader.isGenuineScan(bytes)) {
                        android.util.Log.i("LR", "vf712 row ${r.index} ${r.period}: weeded (placeholder/empty)")
                        continue
                    }
                    kept.add(r to bytes)
                    android.util.Log.i("LR", "vf712 row ${r.index} ${r.period}: kept ${bytes.size} bytes")
                }

                if (kept.isEmpty()) {
                    // Grid had rows but every doc was a "not scanned" placeholder → genuinely empty.
                    vm.markEmpty(RecordType.VF712); vm.setPhase(FetchPhase.DONE); delay(300); onDone(); return@LaunchedEffect
                }
                // Order oldest→newest, matching anyror/run-vf712.mjs (sort by start year ascending).
                val ordered = kept.sortedBy { startYearOf(it.first.period) }

                // Persist each kept scan individually (period + bytes) BEFORE the merge, so the Scans
                // screen can re-select/re-export any subset of years. Purely additive to VF-7-12.pdf.
                info?.let { i ->
                    val scans = ordered.mapIndexed { k, pair ->
                        val row = pair.first
                        VfScansStore.ScanCapture(
                            index = k + 1, period = row.period, thok = row.thok, block = row.block,
                            oldSurvey = row.oldSurvey, status = row.status, pdf = pair.second,
                        )
                    }
                    VfScansStore.save(app, i.district, i.taluka, i.village, i.surveyNo, scans)
                }

                val merged = PdfMerge.merge(ordered.map { it.second }, app.cacheDir)
                if (merged == null || merged.isEmpty()) { vm.fail("Couldn't build the VF-7/12 PDF."); return@LaunchedEffect }
                vm.setPhase(FetchPhase.FILING)
                if (vm.fileCapture(RecordType.VF712, merged, listHtml, docCount = ordered.size)) {
                    vm.setPhase(FetchPhase.DONE); delay(450); onDone()
                }
            }
            ready.contains("NOTFOUND") -> {
                captureRunning = true
                vm.fail("No scanned VF-7/12 records were found for this survey.")
            }
            else -> {
                // Still on the form after the tap (usually a wrong CAPTCHA) — re-arm for a retry.
                awaitingDetail = false
                WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
                working = false
            }
        }
    }

    // Stall detector for the Back escape hatch (see FetchScreen): reset on progress, arm after ~15s.
    LaunchedEffect(pageLoaded, working, awaitingDetail, captureRunning) {
        stuck = false
        if (!working || captureRunning) return@LaunchedEffect
        delay(15_000)
        stuck = true
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
                        Text(
                            "${Lr(R.string.get_record_gu, R.string.get_record_en)} · ${info?.surveyNo ?: ""}",
                            style = LandType.bodyStrong, color = Land.colors.ink,
                        )
                        Text("anyror.gujarat.gov.in", style = LandType.label, color = Land.colors.ink3)
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            }

            Column(Modifier.fillMaxWidth().background(Land.colors.accent).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("જૂનું સ્કેન થયેલ ૭/૧૨", style = LandType.body, color = Land.colors.onAccent)
                Text("Type the code, tap Get Record Detail — one code covers every year", style = LandType.label, color = Land.colors.onAccent)
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onSubmit() {
                                post {
                                    android.util.Log.i("LR", "vf712 onSubmit (awaitingDetail=$awaitingDetail)")
                                    if (!awaitingDetail && !captureRunning) { awaitingDetail = true; working = true }
                                }
                            }
                        }, "AndroidCapture")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                pageLoaded += 1
                                pageSignal?.takeIf { !it.isCompleted }?.complete(Unit)
                                android.util.Log.i("LR", "vf712 onPageFinished #$pageLoaded url=${url?.take(60)}")
                            }
                        }
                        webRef = this
                        loadUrl(AnyRor.URL)
                    }
                },
            )
        }

        when {
            phase != FetchPhase.SOLVING -> {
                val isError = phase == FetchPhase.ERROR
                InputBlocker(active = !isError) {
                    SavingOverlay(
                        surveyNo = info?.surveyNo ?: "",
                        village = info?.village ?: "",
                        destinationPath = "Documents/LandRecords/${info?.district}/${info?.taluka}/${info?.village}/Survey ${info?.surveyNo}",
                        step = when (phase) {
                            FetchPhase.READING -> 0
                            FetchPhase.BUILDING -> 1
                            else -> 2
                        },
                        error = if (isError) vm.errorMessage else null,
                        onRetry = {
                            vm.setPhase(FetchPhase.SOLVING)
                            awaitingDetail = false
                            captureRunning = false
                            working = true
                            webRef?.reload()
                        },
                    )
                }
            }
            working && awaitingDetail -> InputBlocker(active = true, onBack = if (stuck) onBack else null) { PreparingOverlay(fetching = true) }
            working -> InputBlocker(active = true, onBack = if (stuck) onBack else null) { if (pageLoaded >= 1) FillingIndicator() }
        }

        surveyChoices?.let { choices ->
            Vf712SurveyChooser(
                options = choices,
                wantedSurvey = info?.surveyNo ?: "",
                initialQuery = baseOf(info?.surveyNorm ?: ""),
                onDismiss = { surveyChoices = null },
                onPick = { value ->
                    surveyChoices = null
                    working = true
                    webRef?.let { wv ->
                        scope.launch {
                            WebViewCapture.eval(wv, AnyRorInjection.selectSurveyValueJs(surveyDropId, value))
                            delay(250)
                            surveyChosen = true // triggers the prefill effect → spotlight the CAPTCHA
                        }
                    }
                },
            )
        }
    }
}

/** Parse [AnyRorInjection.surveyOptionsJs] output → list of (value, visibleText). */
private fun parseSurveyOptions(json: String): List<Pair<String, String>> = try {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).mapNotNull { k ->
        val o = arr.getJSONObject(k)
        val v = o.optString("v")
        if (v.isBlank()) null else v to o.optString("t").ifBlank { v }
    }
} catch (e: Exception) {
    android.util.Log.w("LR", "parseSurveyOptions failed: ${e.message}"); emptyList()
}

/** Leading number of a survey token ("1257/p" → "1257") — pre-filters the chooser to the family. */
private fun baseOf(surveyNorm: String): String = Regex("^\\d+").find(surveyNorm.trim())?.value ?: ""

/** Searchable "choose the old survey" sheet, shown when VF-7/12 has no exact match for the survey. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Vf712SurveyChooser(
    options: List<Pair<String, String>>,
    wantedSurvey: String,
    initialQuery: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf(initialQuery) }
    val filtered = remember(query, options) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter { it.second.contains(q, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Land.colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(L("સર્વે પસંદ કરો", "Choose the survey"), style = LandType.screenTitle, color = Land.colors.ink)
            Text(
                L(
                    "જૂનું ૭/૧૨ જૂના સર્વે નંબર વાપરે છે — “$wantedSurvey” સીધું નથી, તેથી નીચેમાંથી પસંદ કરો.",
                    "Old VF-7/12 uses the old survey numbers — \"$wantedSurvey\" isn't listed directly, so pick one below.",
                ),
                style = LandType.stamp, color = Land.colors.ink3,
            )
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(LandShape.field)
                    .background(Land.colors.surfaceAlt).border(1.dp, Land.colors.line, LandShape.field)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(11.dp).clip(CircleShape).border(1.dp, Land.colors.ink3, CircleShape))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(L("સર્વે શોધો", "Search survey"), style = LandType.body, color = Land.colors.ink3)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = LandType.body.copy(color = Land.colors.ink),
                        cursorBrush = SolidColor(Land.colors.accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(L("${filtered.size} બતાવ્યા", "${filtered.size} shown"), style = LandType.stamp, color = Land.colors.ink3)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.first }) { opt ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(LandShape.field)
                            .background(Land.colors.surface).border(1.dp, Land.colors.line, LandShape.field)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(opt.first) }
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(opt.second, style = LandType.metaMono, color = Land.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** One VF-7/12 grid row we care about (mirrors anyror/run-vf712.mjs parseRows). */
internal data class Vf712Row(
    val index: Int,
    val period: String,
    val thok: String,
    val block: String,
    val oldSurvey: String,
    val status: String,
)

/** Parse [Vf712Injection.readRowsJs] output → the grid rows (in table order). */
internal fun parseVf712Rows(json: String): List<Vf712Row> = try {
    val arr = JSONObject(json).optJSONArray("rows") ?: return emptyList()
    (0 until arr.length()).mapNotNull { k ->
        val o = arr.getJSONObject(k)
        val idx = o.optInt("index", -1)
        if (idx < 0) null else Vf712Row(
            index = idx,
            period = o.optString("period", ""),
            thok = o.optString("thok", ""),
            block = o.optString("block", ""),
            oldSurvey = o.optString("oldSurvey", ""),
            status = o.optString("status", ""),
        )
    }
} catch (e: Exception) {
    android.util.Log.w("LR", "parseVf712Rows failed: ${e.message}")
    emptyList()
}

/** Leading 4-digit year of a period like "1993-2004" → 1993 (9999 if unknown), for chronological sort. */
internal fun startYearOf(period: String): Int =
    Regex("(\\d{4})").find(period)?.groupValues?.get(1)?.toIntOrNull() ?: 9999
