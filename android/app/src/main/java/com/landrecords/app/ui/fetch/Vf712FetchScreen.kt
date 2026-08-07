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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
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

    val surveyDropId = AnyRor.Ids.SURVEY_VF712
    val recordValue = AnyRor.recordValue(RecordType.VF712) ?: "11"

    // ── Cascade prefill (one step per postback) + spotlight the CAPTCHA — same machine as FetchScreen.
    LaunchedEffect(pageLoaded, info, awaitingDetail) {
        val wv = webRef ?: return@LaunchedEffect
        val i = info ?: return@LaunchedEffect
        if (captureRunning || awaitingDetail) return@LaunchedEffect
        val step = WebViewCapture.eval(
            wv,
            AnyRorInjection.prefillStepJs(
                recordValue = recordValue,
                districtGu = i.districtGu, talukaGu = i.talukaGu, villageGu = i.villageGu,
                surveyNorm = i.surveyNorm, surveyDropId = surveyDropId,
            ),
        )
        if (step.contains("READY") || step.contains("SUR")) {
            delay(600)
            WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
            working = false // cascade filled + spotlit — the user solves the CAPTCHA now
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
                val kept = ArrayList<Pair<Int, ByteArray>>() // startYear -> scan bytes
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
                    kept.add(startYearOf(r.period) to bytes)
                    android.util.Log.i("LR", "vf712 row ${r.index} ${r.period}: kept ${bytes.size} bytes")
                }

                if (kept.isEmpty()) {
                    // Grid had rows but every doc was a "not scanned" placeholder → genuinely empty.
                    vm.markEmpty(RecordType.VF712); vm.setPhase(FetchPhase.DONE); delay(300); onDone(); return@LaunchedEffect
                }
                // Combine oldest→newest, matching anyror/run-vf712.mjs (sort by start year ascending).
                val ordered = kept.sortedBy { it.first }.map { it.second }
                val merged = PdfMerge.merge(ordered, app.cacheDir)
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
            working && awaitingDetail -> InputBlocker(active = true) { PreparingOverlay(fetching = true) }
            working -> InputBlocker(active = true) { if (pageLoaded >= 1) FillingIndicator() }
        }
    }
}

/** One VF-7/12 grid row we care about. */
internal data class Vf712Row(val index: Int, val period: String, val status: String)

/** Parse [Vf712Injection.readRowsJs] output → the grid rows (in table order). */
internal fun parseVf712Rows(json: String): List<Vf712Row> = try {
    val arr = JSONObject(json).optJSONArray("rows") ?: return emptyList()
    (0 until arr.length()).mapNotNull { k ->
        val o = arr.getJSONObject(k)
        val idx = o.optInt("index", -1)
        if (idx < 0) null else Vf712Row(idx, o.optString("period", ""), o.optString("status", ""))
    }
} catch (e: Exception) {
    android.util.Log.w("LR", "parseVf712Rows failed: ${e.message}")
    emptyList()
}

/** Leading 4-digit year of a period like "1993-2004" → 1993 (9999 if unknown), for chronological sort. */
internal fun startYearOf(period: String): Int =
    Regex("(\\d{4})").find(period)?.groupValues?.get(1)?.toIntOrNull() ?: 9999
