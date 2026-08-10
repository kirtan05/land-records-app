package com.landrecords.app.ui.fetch

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.data.storage.LibraryAccess
import com.landrecords.app.ui.landApp
import com.landrecords.app.web.AnyRor
import com.landrecords.app.web.AnyRorInjection
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Re-renders a record's PDF from its stored .source HTML — no network, no CAPTCHA
 * (APP_SPEC §4D). Applies the current layout (wide viewport + paginator) so a layout
 * fix reflows every saved record. Reuses [FetchViewModel.fileCapture] to overwrite.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RegenerateScreen(
    surveyId: Long,
    recordType: RecordType,
    onDone: () -> Unit,
) {
    val app = landApp()
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: FetchViewModel = viewModel(
        factory = viewModelFactory {
            initializer { FetchViewModel(app.repository, app.libraryWriter, surveyId) }
        },
    )
    val info by vm.info.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()

    var html by remember { mutableStateOf<String?>(null) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.setPhase(FetchPhase.READING)
        val rec = app.repository.recordFor(surveyId, recordType)
        val bytes = withContext(Dispatchers.IO) { LibraryAccess.readBytes(context, rec?.sourcePath) }
        if (bytes == null || bytes.isEmpty()) {
            vm.fail("No saved copy to re-render. Fetch this record first.")
        } else {
            html = String(bytes, Charsets.UTF_8)
        }
    }

    LaunchedEffect(html, webRef) {
        val wv = webRef ?: return@LaunchedEffect
        val h = html ?: return@LaunchedEffect
        wv.loadDataWithBaseURL("${AnyRor.URL}", h, "text/html", "UTF-8", null)
    }

    LaunchedEffect(loaded) {
        if (loaded == 0 || html == null || done) return@LaunchedEffect
        val wv = webRef ?: return@LaunchedEffect
        done = true
        // Back-fill the survey's header metadata from the saved page. Older records were
        // filed before this was captured, so re-rendering one fills in its area / આકાર
        // without needing a network re-fetch (the values survive in the stored HTML).
        if (recordType == RecordType.INTEGRATED) {
            vm.saveSummary(WebViewCapture.eval(wv, AnyRorInjection.summaryJs()))
        }
        vm.setPhase(FetchPhase.BUILDING)
        val pdf = WebViewCapture.renderPdf(wv, app.cacheDir)
        vm.setPhase(FetchPhase.FILING)
        val ok = vm.fileCapture(recordType, pdf, html!!)
        if (ok) {
            vm.setPhase(FetchPhase.DONE)
            delay(400)
            onDone()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) { loaded += 1 }
                    }
                    webRef = this
                }
            },
        )
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
            onRetry = onDone,
        )
    }
}
