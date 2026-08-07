package com.landrecords.app.ui.fetch

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.delay

/**
 * The one human step: solve the CAPTCHA on the real AnyRoR page. The app prefills and
 * locks the cascade (record type + district/taluka/village/survey), dims the rest, and
 * spotlights the code box + Get Record Detail. After the tap it captures the detail page
 * automatically — cleanup CSS, raw HTML, paginated PDF — and files it into the library.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FetchScreen(
    surveyId: Long,
    recordType: RecordType,
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
    var awaitingDetail by remember { mutableStateOf(false) }
    var submitPageLoaded by remember { mutableIntStateOf(-1) }
    var captureRunning by remember { mutableStateOf(false) }
    // Non-error notice shown after a Deeds fetch (table saved, but the scanned documents are
    // usually dead server-side) — a dialog the user dismisses, then we leave the screen.
    var deedNotice by remember { mutableStateOf<String?>(null) }
    // True while the app is driving the page (loading + auto-filling the cascade, or waiting
    // for the post-submit postback) — a blocking buffer covers the WebView so a stray tap or
    // Back can't derail the machine. Cleared only when the CAPTCHA spotlight is up for the user.
    var working by remember { mutableStateOf(true) }

    val surveyDropId = when (recordType) {
        RecordType.VF712 -> AnyRor.Ids.SURVEY_VF712
        else -> AnyRor.Ids.SURVEY_INTEGRATED
    }
    val recordValue = AnyRor.recordValue(recordType) ?: "8"

    // Drive prefill (one step per page load) and, after the CAPTCHA tap, the capture.
    LaunchedEffect(pageLoaded, info, awaitingDetail) {
        val wv = webRef ?: return@LaunchedEffect
        val i = info ?: return@LaunchedEffect
        if (captureRunning) return@LaunchedEffect

        if (awaitingDetail) {
            // Only look for the result AFTER the submit's postback has loaded a new page —
            // otherwise we'd capture the form the instant the button/Enter fires.
            if (pageLoaded <= submitPageLoaded) {
                android.util.Log.i("LR", "awaitingDetail: waiting for postback (pageLoaded=$pageLoaded submit=$submitPageLoaded)")
                return@LaunchedEffect
            }
            // Poll a few seconds — the detail DOM can render a beat after onPageFinished.
            var ready = WebViewCapture.eval(wv, AnyRorInjection.detailReadyJs())
            var tries = 0
            while (ready.contains("WAIT") && tries < 8) {
                delay(500)
                ready = WebViewCapture.eval(wv, AnyRorInjection.detailReadyJs())
                tries++
            }
            android.util.Log.i("LR", "detailReady='$ready' after $tries tries (pageLoaded=$pageLoaded)")
            when {
                ready.contains("READY") -> {
                    captureRunning = true
                    working = true
                    vm.setPhase(FetchPhase.READING)
                    if (recordType == RecordType.DEEDS) {
                        // Deeds are a SECTION of the type-8 page (Sub registrar Deed Details).
                        // Read the postback FORM first — deedCaptureJs rewrites the DOM, which would
                        // wipe the download links — then TRY the scanned documents (usually dead:
                        // the server answers "Document Record Not Found"), then isolate + capture the
                        // details table either way so the user always keeps the registered-deed info.
                        android.util.Log.i("LR", "deeds probe: " + WebViewCapture.eval(wv, com.landrecords.app.web.DeedsInjection.deedDebugJs()))
                        var form = WebViewCapture.eval(wv, com.landrecords.app.web.DeedsInjection.deedFormJs())
                        var dtries = 0
                        while (form == "NONE" && dtries < 8) {
                            delay(500)
                            form = WebViewCapture.eval(wv, com.landrecords.app.web.DeedsInjection.deedFormJs())
                            dtries++
                        }
                        if (form == "NONE") {
                            android.util.Log.i("LR", "deeds: none after $dtries tries")
                            vm.markEmpty(RecordType.DEEDS)
                            vm.setPhase(FetchPhase.DONE)
                            deedNotice = "આ સર્વે નંબર માટે કોઈ નોંધાયેલ દસ્તાવેજ મળ્યો નથી.\n\n" +
                                "No registered deeds found for this survey number."
                        } else {
                            // Replay each "View Deed" postback with the live cookies. Almost always
                            // returns nothing usable right now, but keeps working if the SRO recovers.
                            val docs = com.landrecords.app.web.DeedsDownloader.fetchAll(form, app.cacheDir)
                            android.util.Log.i("LR", "deeds: pulled ${docs.size} document(s)")
                            var r = WebViewCapture.eval(wv, com.landrecords.app.web.DeedsInjection.deedCaptureJs())
                            var ctries = 0
                            while (!r.startsWith("READY") && ctries < 6) {
                                delay(400); r = WebViewCapture.eval(wv, com.landrecords.app.web.DeedsInjection.deedCaptureJs()); ctries++
                            }
                            android.util.Log.i("LR", "deeds capture: '$r'")
                            val n = r.substringAfter(":", "").toIntOrNull() ?: docs.size.coerceAtLeast(1)
                            val html = WebViewCapture.rawHtml(wv)
                            vm.setPhase(FetchPhase.BUILDING)
                            val tablePdf = WebViewCapture.renderPdf(wv, app.cacheDir)
                            // Details table first, then any scanned documents we actually pulled.
                            val merged = if (docs.isNotEmpty() && tablePdf != null)
                                com.landrecords.app.web.PdfMerge.merge(listOf(tablePdf) + docs.map { it.pdf }, app.cacheDir) ?: tablePdf
                            else tablePdf
                            vm.setPhase(FetchPhase.FILING)
                            if (merged != null && vm.fileCapture(RecordType.DEEDS, merged, html, docCount = n)) {
                                vm.setPhase(FetchPhase.DONE)
                                deedNotice = if (docs.isNotEmpty())
                                    "$n દસ્તાવેજ સાચવ્યા — વિગત અને સ્કેન કરેલ દસ્તાવેજ.\n\n" +
                                        "Saved $n deed(s) with the scanned documents."
                                else
                                    "દસ્તાવેજની વિગત સાચવી. પણ સ્કેન કરેલ દસ્તાવેજ અત્યારે સરકારી (GARVI) સાઇટ પરથી મળતો નથી " +
                                        "(“Document Record Not Found”). થોડા કલાક પછી ફરી “Get record” કરીને પ્રયત્ન કરો.\n\n" +
                                        "Saved the deed details. The scanned deed document isn’t available from the government (GARVI) " +
                                        "server right now (“Document Record Not Found”) — try “Get record” again in a few hours."
                            } else {
                                vm.fail("Couldn't save the deed details.")
                            }
                        }
                    } else {
                        WebViewCapture.eval(wv, AnyRorInjection.cleanupJs(
                            districtEn = i.district, talukaEn = i.taluka, villageEn = i.village, surveyNo = i.surveyNo,
                        ))
                        val html = WebViewCapture.rawHtml(wv)
                        vm.setPhase(FetchPhase.BUILDING)
                        val pdf = WebViewCapture.renderPdf(wv, app.cacheDir)
                        vm.setPhase(FetchPhase.FILING)
                        val ok = vm.fileCapture(recordType, pdf, html)
                        if (ok) {
                            vm.setPhase(FetchPhase.DONE)
                            delay(450)
                            onDone()
                        }
                    }
                    captureRunning = false
                }
                ready.contains("NOTFOUND") -> {
                    captureRunning = true
                    vm.fail("No record found for this survey. Check the survey number, or the code you typed.")
                }
                else -> {
                    // Postback returned the form again (usually a wrong CAPTCHA) — re-arm for retry.
                    awaitingDetail = false
                    submitPageLoaded = -1
                    WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
                    working = false // hand the CAPTCHA back to the user
                }
            }
        } else {
            val step = WebViewCapture.eval(
                wv,
                AnyRorInjection.prefillStepJs(
                    recordValue = recordValue,
                    districtGu = i.districtGu, talukaGu = i.talukaGu, villageGu = i.villageGu,
                    surveyNorm = i.surveyNorm, surveyDropId = surveyDropId,
                ),
            )
            // One line per cascade step so the NEXT on-device run shows exactly where it stalls:
            // the returned code (RT/DIST/TAL/VIL/SUR/READY/WAIT) and, on a miss, the wanted text plus
            // every option the page offered (e.g. "WAIT|tal|want=…|opts=…"). This is the Valetva probe.
            android.util.Log.i("LR", "prefill step (page #$pageLoaded): $step")
            // The step code is the field before the first '|'. District/taluka/village selections post
            // back → a new pageLoaded re-runs this effect for the next step; the survey (last dropdown)
            // does NOT post back, so spotlight as soon as it's picked ('SUR') or the cascade is fully
            // set ('READY'). dimSpotlight is idempotent, so a following postback re-spotlight is harmless.
            val code = step.substringBefore('|').trim()
            if (code == "READY" || code == "SUR") {
                android.util.Log.i("LR", "cascade complete ($code) — spotlighting the CAPTCHA")
                delay(600)
                WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
                working = false // cascade is filled + spotlit — the user solves the CAPTCHA now
            }
        }
    }

    // ── Connection / WAF guards ──────────────────────────────────────────────────────────
    // A WAF-blocked IP makes the WebView hang with no callback: the initial page never finishes
    // loading, and the post-submit postback never arrives. Cap each wait and surface a clear
    // error instead of spinning forever. pageLoaded>=1 means the page DID load, so the load
    // guard never fires while the user is calmly solving the CAPTCHA.
    LaunchedEffect(phase) {
        if (phase != FetchPhase.SOLVING) return@LaunchedEffect
        delay(75_000)
        if (pageLoaded < 1 && phase == FetchPhase.SOLVING)
            vm.fail("Could not reach the site — your connection may be blocked. Try later or a different network.")
    }
    LaunchedEffect(awaitingDetail, submitPageLoaded) {
        if (!awaitingDetail) return@LaunchedEffect
        delay(75_000)
        if (awaitingDetail && !captureRunning && phase == FetchPhase.SOLVING)
            vm.fail("Could not reach the site — your connection may be blocked. Try later or a different network.")
    }
    // Prefill-stall guard: the cascade fills one step per postback (pageLoaded++). If a place name
    // doesn't match the site, pick() fires no change event, no postback arrives, pageLoaded freezes,
    // and the "Filling…" overlay would hang forever (the frozen/non-responsive Valetva symptom).
    // Keyed on pageLoaded, so every real step resets this timer; it only fires on a true stall.
    LaunchedEffect(pageLoaded) {
        delay(30_000)
        if (phase == FetchPhase.SOLVING && working && !awaitingDetail && pageLoaded >= 1) {
            android.util.Log.w("LR", "prefill watchdog: cascade stalled at page #$pageLoaded — failing out")
            vm.fail("Couldn't auto-fill the form — a district/taluka/village name may not match the site. Tap retry, or add the survey through the picker.")
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

        // Slim accent banner — the one instruction.
        Column(Modifier.fillMaxWidth().background(Land.colors.accent).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(Lr(R.string.fetch_banner_gu, R.string.fetch_banner_en), style = LandType.body, color = Land.colors.onAccent)
            Text("Type the code below, then tap Get Record Detail", style = LandType.label, color = Land.colors.onAccent)
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // Deeds live in a desktop-only section of the page — render as desktop for them.
                    if (recordType == RecordType.DEEDS) {
                        settings.userAgentString =
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onSubmit() {
                            post {
                                android.util.Log.i("LR", "onSubmit fired (awaitingDetail=$awaitingDetail pageLoaded=$pageLoaded)")
                                if (!awaitingDetail) {
                                    submitPageLoaded = pageLoaded
                                    awaitingDetail = true
                                    working = true // block the UI the instant they tap — no dead air
                                }
                            }
                        }
                    }, "AndroidCapture")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            pageLoaded += 1
                            android.util.Log.i("LR", "onPageFinished #$pageLoaded url=${url?.take(60)}")
                        }
                    }
                    webRef = this
                    loadUrl(if (recordType == RecordType.DEEDS) AnyRor.URL_DESKTOP else AnyRor.URL)
                }
            },
        )
    }

    // ── Blocking buffers ──────────────────────────────────────────────────────────────
    // While the app drives the page (capture in progress, or auto-filling / waiting) a
    // full-screen layer swallows every touch and Back press so nothing can derail the run.
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
                        submitPageLoaded = -1
                        captureRunning = false
                        working = true
                        webRef?.reload()
                    },
                )
            }
        }
        // Post-tap fetch: full "Fetching the record…" overlay (the one that reads well).
        working && awaitingDetail -> {
            InputBlocker(active = true) { PreparingOverlay(fetching = true) }
        }
        // Auto-fill: keep the form VISIBLE (don't cover it) but swallow stray taps/Back so
        // the cascade can't be derailed. Only show the "Filling…" chip once the form has
        // actually loaded — before that it'd sit over a blank page, which read as premature.
        working -> {
            InputBlocker(active = true) { if (pageLoaded >= 1) FillingIndicator() else OpeningOverlay() }
        }
    }

    // Deeds outcome — a plain dismissable notice (the record is already saved); leaving is the
    // only action, so tapping the button always exits to the record.
    deedNotice?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deedNotice = null; onDone() },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { deedNotice = null; onDone() }) {
                    Text("બરાબર · OK")
                }
            },
            title = { Text("દસ્તાવેજ · Deeds") },
            text = { Text(msg) },
        )
    }
    }
}
