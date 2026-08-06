package com.landrecords.app.ui.fetch

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
    var captureRunning by remember { mutableStateOf(false) }

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
            val ready = WebViewCapture.eval(wv, AnyRorInjection.detailReadyJs())
            if (ready.contains("READY")) {
                captureRunning = true
                vm.setPhase(FetchPhase.READING)
                WebViewCapture.eval(wv, AnyRorInjection.cleanupJs())
                delay(700)
                val html = WebViewCapture.rawHtml(wv)
                vm.setPhase(FetchPhase.BUILDING)
                val pdf = WebViewCapture.toPdfBytes(wv)
                vm.setPhase(FetchPhase.FILING)
                val ok = vm.fileCapture(recordType, pdf, html)
                if (ok) {
                    vm.setPhase(FetchPhase.DONE)
                    delay(450)
                    onDone()
                }
                captureRunning = false
            } else {
                // Wrong code or page not ready yet — re-spotlight so the user can retry.
                WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
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
            // Any real selection triggers an ASP.NET postback → a new pageLoaded fires this
            // effect again for the next step. When the cascade is set, spotlight the CAPTCHA.
            if (step.contains("READY")) {
                WebViewCapture.eval(wv, AnyRorInjection.dimSpotlightJs())
            }
        }
    }

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
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onSubmit() {
                            post { awaitingDetail = true }
                        }
                    }, "AndroidCapture")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            pageLoaded += 1
                        }
                    }
                    webRef = this
                    loadUrl(AnyRor.URL)
                }
            },
        )
    }

    if (phase != FetchPhase.SOLVING) {
        SavingOverlay(
            surveyNo = info?.surveyNo ?: "",
            village = info?.village ?: "",
            destinationPath = "Documents/LandRecords/${info?.district}/${info?.taluka}/${info?.village}/Survey ${info?.surveyNo}",
            step = when (phase) {
                FetchPhase.READING -> 0
                FetchPhase.BUILDING -> 1
                else -> 2
            },
            error = if (phase == FetchPhase.ERROR) vm.errorMessage else null,
            onRetry = {
                vm.setPhase(FetchPhase.SOLVING)
                awaitingDetail = false
                captureRunning = false
                webRef?.reload()
            },
        )
    }
}
