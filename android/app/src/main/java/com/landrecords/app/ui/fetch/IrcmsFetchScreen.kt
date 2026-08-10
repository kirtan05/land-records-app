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
import com.landrecords.app.data.storage.CasesStore
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr
import com.landrecords.app.web.Ircms
import com.landrecords.app.web.IrcmsInjection
import com.landrecords.app.web.OrderDownloader
import com.landrecords.app.web.PdfMerge
import com.landrecords.app.web.PrintPdf
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * iRCMS land-cases capture. Unlike AnyRoR this is one AJAX page: the app polls the cascade
 * until it's filled, spotlights the CAPTCHA + View, and after the human taps View it reads the
 * case table, then POSTs each case's detail (viewnewcasestatus) into the same WebView (shared
 * session cookies), prints each, and merges them into one "iRCMS Cases.pdf". One CAPTCHA covers
 * every case (iRCMS reuse). Currently proven for Bharoda.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IrcmsFetchScreen(
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
    var working by remember { mutableStateOf(true) }
    var awaitingList by remember { mutableStateOf(false) }
    var captureRunning by remember { mutableStateOf(false) }

    // ── Prefill the cascade once the page has loaded (AJAX, so we poll) ──────────────────
    // Key on info too: the survey details load async, so a fast page-load could otherwise
    // start (and give up) before we know what to select.
    LaunchedEffect(pageLoaded, info) {
        val wv = webRef ?: return@LaunchedEffect
        val i = info ?: return@LaunchedEffect
        if (pageLoaded < 1 || captureRunning || phase != FetchPhase.SOLVING || awaitingList) return@LaunchedEffect
        var tries = 0
        while (phase == FetchPhase.SOLVING && !awaitingList && tries < 40) {
            val step = WebViewCapture.eval(wv, IrcmsInjection.prefillJs(i.district, i.taluka, i.village, i.surveyNorm))
            android.util.Log.i("LR", "iRCMS prefill step='$step' (try $tries)")
            if (step.contains("READY")) {
                delay(400)
                WebViewCapture.eval(wv, IrcmsInjection.spotlightBatchJs())
                working = false
                return@LaunchedEffect
            }
            delay(600)
            tries++
        }
        if (phase == FetchPhase.SOLVING && !awaitingList) {
            // Never resolved — still let the user try (maybe the survey/village isn't in iRCMS).
            WebViewCapture.eval(wv, IrcmsInjection.spotlightBatchJs())
            working = false
        }
    }

    // ── After the View tap: watch for the case list, then capture every case ────────────
    LaunchedEffect(awaitingList) {
        val wv = webRef ?: return@LaunchedEffect
        if (!awaitingList || captureRunning) return@LaunchedEffect

        val i = info ?: return@LaunchedEffect
        // Same proven path as the batch: search directly (the site button is stripped), poll status.
        WebViewCapture.eval(wv, IrcmsInjection.searchSurveyDirectJs(i.surveyNorm))
        var res = "WAIT"
        var tries = 0
        while (tries < 150) { // ~75s no-progress cap (WAF/connection guard)
            res = WebViewCapture.eval(wv, IrcmsInjection.searchStatusJs())
            if (res != "WAIT") break
            delay(500); tries++
        }
        android.util.Log.i("LR", "iRCMS single search='$res' after $tries tries")

        if (res == "WAIT") { // never responded — connection likely blocked
            vm.fail("Could not reach the site — your connection may be blocked. Try later or a different network.")
            return@LaunchedEffect
        }

        val wrongCode = res.startsWith("EMPTY") && Regex("captcha|invalid", RegexOption.IGNORE_CASE).containsMatchIn(res)
        when {
            res == "READY" -> {
                captureRunning = true
                working = true
                vm.setPhase(FetchPhase.READING)
                val listHtml = WebViewCapture.rawHtml(wv)
                val casesJson = WebViewCapture.eval(wv, IrcmsInjection.readCasesJs())
                val (token, cases) = parseCaseList(casesJson)
                android.util.Log.i("LR", "iRCMS cases=${cases.size} token=${token.take(6)}…")
                if (cases.isEmpty()) { vm.markEmpty(RecordType.IRCMS); vm.setPhase(FetchPhase.DONE); delay(300); onDone(); return@LaunchedEffect }

                vm.setPhase(FetchPhase.BUILDING)
                // Desktop UA now (capture only) so the case pages render wide for the PDF.
                wv.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
                val captures = ArrayList<CasesStore.CaseCapture>(cases.size)
                for ((idx, c) in cases.withIndex()) {
                    val sig = CompletableDeferred<Unit>()
                    pageSignal = sig
                    val body = IrcmsInjection.caseBody(token, c.casekey).toByteArray()
                    wv.post { wv.postUrl(Ircms.CASE_DETAIL_URL, body) }
                    withTimeoutOrNull(20_000) { sig.await() }
                    // The detail table paints a beat after the page load — poll so we don't capture a
                    // blank white page (the ~896-byte symptom). Falls through to capture after ~5s.
                    var cr = ""; var ct = 0
                    while (ct < 12) { cr = WebViewCapture.eval(wv, IrcmsInjection.caseReadyJs()); if (cr.startsWith("READY")) break; delay(400); ct++ }
                    android.util.Log.i("LR", "iRCMS case ${idx + 1} ready='$cr' after $ct polls")
                    delay(250)
                    WebViewCapture.eval(wv, IrcmsInjection.caseCleanupJs())
                    val pdf = PrintPdf.toPdfBytes(wv, app.cacheDir) ?: WebViewCapture.toPdfBytes(wv)
                    // Disposed cases carry a "Download Order" — the order PDF follows the case.
                    val orderForm = WebViewCapture.eval(wv, IrcmsInjection.orderFormJs())
                    val orderPdf = if (orderForm.startsWith("{")) OrderDownloader.fetch(orderForm) else null
                    captures.add(
                        CasesStore.CaseCapture(
                            sr = c.sr, caseNo = c.caseNo, status = c.status, office = c.office,
                            dtv = c.dtv, parties = c.parties, survno = c.survno,
                            detailPdf = pdf.takeIf { it.isNotEmpty() }, orderPdf = orderPdf,
                        ),
                    )
                    android.util.Log.i("LR", "iRCMS captured case ${idx + 1}/${cases.size} (${pdf.size} bytes, order=${orderPdf != null})")
                }

                // Persist each case's own PDFs + a cases.json manifest (additive to the merged PDF).
                CasesStore.save(app, i.district, i.taluka, i.village, i.surveyNo, captures)

                // The quick View-all PDF is the ordered concat of every case (detail then order).
                val parts = captures.flatMap { listOfNotNull(it.detailPdf, it.orderPdf) }
                val merged = PdfMerge.merge(parts, app.cacheDir)
                if (merged == null || merged.isEmpty()) { vm.fail("Couldn't build the cases PDF."); return@LaunchedEffect }
                vm.setPhase(FetchPhase.FILING)
                val ok = vm.fileCapture(RecordType.IRCMS, merged, listHtml, docCount = cases.size)
                if (ok) { vm.setPhase(FetchPhase.DONE); delay(450); onDone() }
            }
            wrongCode -> { awaitingList = false; working = false; WebViewCapture.eval(wv, IrcmsInjection.spotlightBatchJs()) } // wrong code → re-solve
            res.startsWith("EMPTY") -> { vm.markEmpty(RecordType.IRCMS); vm.setPhase(FetchPhase.DONE); delay(300); onDone() } // genuine empty
            else -> { awaitingList = false; working = false; WebViewCapture.eval(wv, IrcmsInjection.spotlightBatchJs()) } // error/timeout → re-solve
        }
    }

    // ── Connection / WAF guard ───────────────────────────────────────────────────────────
    // A WAF-blocked IP makes the initial WebView load hang forever (no onPageFinished, so the
    // cascade never fills). Cap the wait and surface a clear error. pageLoaded>=1 means the
    // page loaded, so this never fires while the user is solving the CAPTCHA.
    LaunchedEffect(phase) {
        if (phase != FetchPhase.SOLVING) return@LaunchedEffect
        delay(75_000)
        if (pageLoaded < 1 && phase == FetchPhase.SOLVING)
            vm.fail("Could not reach the site — your connection may be blocked. Try later or a different network.")
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
                        Text("ircms.gujarat.gov.in", style = LandType.label, color = Land.colors.ink3)
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            }

            Column(Modifier.fillMaxWidth().background(Land.colors.accent).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("જમીન કેસ (iRCMS)", style = LandType.body, color = Land.colors.onAccent)
                Text("Solve the code, tap View — one code covers every case", style = LandType.label, color = Land.colors.onAccent)
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // Default (mobile) UA — a desktop UA reverses CAPTCHA typing. We switch to
                        // desktop UA just before capturing case pages (for wider PDFs).
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onSubmit() {
                                post {
                                    android.util.Log.i("LR", "iRCMS onSubmit (awaitingList=$awaitingList)")
                                    if (!awaitingList && !captureRunning) { awaitingList = true; working = true }
                                }
                            }
                        }, "AndroidCapture")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                pageLoaded += 1
                                pageSignal?.takeIf { !it.isCompleted }?.complete(Unit)
                                android.util.Log.i("LR", "iRCMS onPageFinished #$pageLoaded url=${url?.take(60)}")
                            }
                        }
                        webRef = this
                        loadUrl(Ircms.URL)
                    }
                },
            )
        }

        when {
            phase != FetchPhase.SOLVING -> {
                val isError = phase == FetchPhase.ERROR
                InputBlocker(active = !isError, onExit = onBack) {
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
                            awaitingList = false
                            captureRunning = false
                            working = true
                            webRef?.reload()
                        },
                    )
                }
            }
            working && awaitingList -> InputBlocker(active = true, onExit = onBack) { PreparingOverlay(fetching = true) }
            working -> InputBlocker(active = true, onExit = onBack) { if (pageLoaded >= 1) FillingIndicator() else OpeningOverlay() }
        }
    }
}

/** A case row scraped from the iRCMS list — metadata only; each PDF is captured separately. */
internal data class ScrapedCase(
    val casekey: String,
    val sr: String,
    val caseNo: String,
    val status: String,
    val office: String,
    val dtv: String,
    val parties: String,
    val survno: String,
)

/** Parse readCasesJs output → (token, ordered unique cases in table order). */
internal fun parseCaseList(json: String): Pair<String, List<ScrapedCase>> {
    return try {
        val o = JSONObject(json)
        val token = o.optString("token", "")
        val arr = o.optJSONArray("cases") ?: return token to emptyList()
        val out = ArrayList<ScrapedCase>(arr.length())
        val seen = HashSet<String>()       // by iRCMS key (data-id:offcode:survno)
        val seenIdent = HashSet<String>()  // by visible identity — collapses one case cross-listed under many survnos
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val key = c.optString("casekey", "")
            if (key.isBlank() || !seen.add(key)) continue // dedupe exact repeated key rows
            val caseNo = c.optString("caseNo", "")
            val office = c.optString("office", "")
            val dtv = c.optString("dtv", "")
            val parties = c.optString("parties", "")
            // The key includes the survey number, so the SAME case listed under two survey numbers has
            // two different keys; collapse those here so it isn't captured/shown/merged twice.
            if (!seenIdent.add(CasesStore.identity(caseNo, parties, office, dtv))) continue
            out.add(
                ScrapedCase(
                    casekey = key,
                    sr = c.optString("sr", "").ifBlank { (out.size + 1).toString() },
                    caseNo = caseNo,
                    status = c.optString("status", ""),
                    office = office,
                    dtv = dtv,
                    parties = parties,
                    survno = c.optString("survno", ""),
                ),
            )
        }
        token to out
    } catch (e: Exception) {
        android.util.Log.w("LR", "parseCaseList failed: ${e.message}")
        "" to emptyList()
    }
}
