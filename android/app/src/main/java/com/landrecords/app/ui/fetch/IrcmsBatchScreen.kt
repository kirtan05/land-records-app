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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.LibraryWriter
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.web.Ircms
import com.landrecords.app.web.IrcmsInjection
import com.landrecords.app.web.OrderDownloader
import com.landrecords.app.web.PdfMerge
import com.landrecords.app.web.PrintPdf
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A survey to fetch cases for. */
data class BatchTarget(val surveyId: Long, val surveyNo: String)

data class BatchInfo(
    val district: String, val taluka: String, val village: String,
    val targets: List<BatchTarget>,
)

/** Loads the surveys in a property that still need iRCMS cases, and files captures per survey. */
class IrcmsBatchViewModel(
    private val repo: LandRecordsRepository,
    private val writer: LibraryWriter,
    private val propertyId: Long,
) : ViewModel() {

    val info = MutableStateFlow<BatchInfo?>(null)

    init {
        viewModelScope.launch {
            val prop = repo.propertyById(propertyId) ?: return@launch
            val surveys = repo.observeSurveys(propertyId).first()
            val targets = surveys
                .filter { repo.recordFor(it.id, RecordType.IRCMS) == null } // only ones missing cases
                .map { BatchTarget(it.id, it.surveyNo) }
            info.value = BatchInfo(prop.district, prop.taluka, prop.village, targets)
        }
    }

    /** Record that a survey was checked and has NO cases, so batches skip it from now on. */
    suspend fun markEmpty(surveyId: Long) {
        repo.saveFetchedRecord(surveyId, RecordType.IRCMS, docCount = 0, pdfPath = null, sourcePath = null)
    }

    suspend fun fileCases(surveyId: Long, surveyNo: String, pdf: ByteArray, listHtml: String, docCount: Int) {
        val prop = repo.propertyById(propertyId) ?: return
        val filed = writer.writeRecord(
            district = prop.district, taluka = prop.taluka, village = prop.village,
            surveyNo = surveyNo, type = RecordType.IRCMS, pdfBytes = pdf, rawHtml = listHtml,
        )
        repo.saveFetchedRecord(surveyId, RecordType.IRCMS, docCount, filed.pdfPath, filed.sourcePath)
    }
}

private enum class BatchPhase { SOLVING, RUNNING, DONE, ERROR }

/**
 * One-CAPTCHA batch of iRCMS land cases for every survey in a village that still needs them.
 * The human solves ONE code; the app then loops the surveys — re-selecting the survey dropdown
 * and re-running the search with the same code — capturing each survey's cases in a hidden
 * WebView (so the search page never navigates away and the code stays valid).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IrcmsBatchScreen(
    propertyId: Long,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val app = landApp()
    val vm: IrcmsBatchViewModel = viewModel(
        factory = viewModelFactory { initializer { IrcmsBatchViewModel(app.repository, app.libraryWriter, propertyId) } },
    )
    val info by vm.info.collectAsStateWithLifecycle()

    var mainRef by remember { mutableStateOf<WebView?>(null) }
    var captureRef by remember { mutableStateOf<WebView?>(null) }
    var mainLoaded by remember { mutableIntStateOf(0) }
    var captureSignal by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var tapSignal by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }

    var phase by remember { mutableStateOf(BatchPhase.SOLVING) }
    var working by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var doneCount by remember { mutableIntStateOf(0) }

    // Whole batch runs in one coroutine: prefill target[0] → wait for the single human tap → loop.
    LaunchedEffect(mainLoaded, info) {
        val wv = mainRef ?: return@LaunchedEffect
        val i = info ?: return@LaunchedEffect
        if (mainLoaded < 1 || phase != BatchPhase.SOLVING) return@LaunchedEffect
        if (i.targets.isEmpty()) { errorMsg = "Every survey here already has its cases."; phase = BatchPhase.ERROR; return@LaunchedEffect }

        // Fill the cascade for the first survey, then hand the CAPTCHA to the human.
        var tries = 0
        while (tries < 40) {
            val step = WebViewCapture.eval(wv, IrcmsInjection.prefillJs(i.district, i.taluka, i.village, i.targets[0].surveyNo))
            if (step.contains("READY")) break
            delay(600); tries++
        }
        WebViewCapture.eval(wv, IrcmsInjection.spotlightBatchJs())
        android.util.Log.i("LR", "iRCMS captcha diag: " + WebViewCapture.eval(wv, IrcmsInjection.captchaDiagJs()))
        working = false
        val firstTap = CompletableDeferred<Unit>(); tapSignal = firstTap
        firstTap.await() // human typed the code + tapped View (its rotating handler is stripped)
        working = true
        phase = BatchPhase.RUNNING

        for ((idx, t) in i.targets.withIndex()) {
            progress = "${t.surveyNo}  ·  ${idx + 1}/${i.targets.size}"
            var attempts = 0
            while (true) {
                // Search directly (reuse the one code — never touch the button's get_captcha).
                WebViewCapture.eval(wv, IrcmsInjection.searchSurveyDirectJs(t.surveyNo))
                var st = "WAIT"; var w = 0
                while (w < 24) {
                    st = WebViewCapture.eval(wv, IrcmsInjection.searchStatusJs())
                    if (st != "WAIT") break
                    delay(500); w++
                }
                if (st == "READY") {
                    val listHtml = WebViewCapture.rawHtml(wv)
                    val (token, cases) = parseCases(WebViewCapture.eval(wv, IrcmsInjection.readCasesJs()))
                    android.util.Log.i("LR", "batch: ${t.surveyNo} -> ${cases.size} cases")
                    val cwv = captureRef
                    val parts = ArrayList<ByteArray>(cases.size)
                    if (cwv != null) {
                        for (ck in cases) {
                            val sig = CompletableDeferred<Unit>(); captureSignal = sig
                            cwv.post { cwv.postUrl(Ircms.CASE_DETAIL_URL, IrcmsInjection.caseBody(token, ck).toByteArray()) }
                            withTimeoutOrNull(20_000) { sig.await() }
                            delay(650)
                            WebViewCapture.eval(cwv, IrcmsInjection.caseCleanupJs())
                            val pdf = PrintPdf.toPdfBytes(cwv, app.cacheDir) ?: WebViewCapture.toPdfBytes(cwv)
                            if (pdf.isNotEmpty()) parts.add(pdf)
                            val orderForm = WebViewCapture.eval(cwv, IrcmsInjection.orderFormJs())
                            if (orderForm.startsWith("{")) OrderDownloader.fetch(orderForm)?.let { parts.add(it) }
                        }
                    }
                    val merged = PdfMerge.merge(parts, app.cacheDir)
                    if (merged != null && merged.isNotEmpty()) {
                        vm.fileCases(t.surveyId, t.surveyNo, merged, listHtml, cases.size)
                        doneCount++
                    }
                    break
                }
                // Wrong/expired code → the direct search reports a captcha message. Re-solve once more.
                val wrongCode = st.startsWith("EMPTY") && Regex("captcha|invalid", RegexOption.IGNORE_CASE).containsMatchIn(st)
                if (wrongCode && attempts < 4) {
                    attempts++
                    working = false
                    WebViewCapture.eval(wv, IrcmsInjection.spotlightBatchJs())
                    val again = CompletableDeferred<Unit>(); tapSignal = again
                    again.await(); working = true
                    continue
                }
                if (st.startsWith("EMPTY")) vm.markEmpty(t.surveyId) // checked, no cases → skip next time
                android.util.Log.i("LR", "batch: skip ${t.surveyNo} ($st)")
                break
            }
        }

        phase = BatchPhase.DONE
        delay(600)
        onDone()
    }

    Box(Modifier.fillMaxSize()) {
        // Hidden capture WebView (behind the opaque UI) — posts each case detail for printing.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            captureSignal?.takeIf { !it.isCompleted }?.complete(Unit)
                        }
                    }
                    captureRef = this
                }
            },
        )

        Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
            Column(Modifier.background(Land.colors.surface).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("બધા જમીન કેસ · All Land Cases", style = LandType.bodyStrong, color = Land.colors.ink)
                        Text("ircms.gujarat.gov.in · one code", style = LandType.label, color = Land.colors.ink3)
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            }
            Column(Modifier.fillMaxWidth().background(Land.colors.accent).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("એક જ કોડ — બધા સર્વેના કેસ", style = LandType.body, color = Land.colors.onAccent)
                Text("Solve the code once, then leave it — every survey's cases download", style = LandType.label, color = Land.colors.onAccent)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // NOTE: default (mobile) UA on purpose — a desktop UA makes this WebView
                        // type the CAPTCHA in reverse. Only the hidden capture WebView uses desktop UA.
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onSubmit() {
                                post {
                                    tapSignal?.takeIf { !it.isCompleted }?.complete(Unit)
                                    working = true
                                }
                            }
                        }, "AndroidCapture")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                mainLoaded += 1
                            }
                        }
                        mainRef = this
                        loadUrl(Ircms.URL)
                    }
                },
            )
        }

        when {
            phase == BatchPhase.ERROR -> InputBlocker(active = false) {
                BatchStatusCard(title = "Couldn't start", detail = errorMsg ?: "", onBack = onBack)
            }
            phase == BatchPhase.RUNNING || phase == BatchPhase.DONE -> InputBlocker(active = true) {
                BatchStatusCard(
                    title = if (phase == BatchPhase.DONE) "Done" else "Fetching cases…",
                    detail = if (phase == BatchPhase.DONE) "$doneCount survey(s) filed" else progress,
                    onBack = null,
                )
            }
            working -> InputBlocker(active = true) { if (mainLoaded >= 1) FillingIndicator() }
        }
    }
}

@Composable
private fun BatchStatusCard(title: String, detail: String, onBack: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().background(Land.colors.bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = LandType.screenTitle, color = Land.colors.ink, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(detail, style = LandType.metaMono, color = Land.colors.ink2, textAlign = TextAlign.Center)
        }
        if (onBack != null) {
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Land.colors.accent)
                    .clickable(onClick = onBack).padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text("પાછા · Back", style = LandType.bodyStrong, color = Land.colors.onAccent) }
        }
    }
}
