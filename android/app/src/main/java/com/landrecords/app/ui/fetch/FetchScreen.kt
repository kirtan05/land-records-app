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
import androidx.compose.runtime.getValue
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

/**
 * The one human step: solve the CAPTCHA on the real AnyRoR page. The cascade is
 * pre-filled + locked, the rest of the page dimmed, and the code box + Get Record
 * Detail button spotlighted. On submit the app captures the result (see [SavingScreen]).
 * The full cascade/postback + print-to-PDF capture ports from the anyror .mjs scripts on-device.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FetchScreen(
    surveyId: Long,
    recordType: RecordType,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val app = landApp()
    val vm: FetchViewModel = viewModel(
        factory = viewModelFactory { initializer { FetchViewModel(app.repository, surveyId) } },
    )
    val info by vm.info.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        // Compact app bar — shows the real government host for trust.
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
            Text(
                "Type the code below, then tap Get Record Detail",
                style = LandType.label, color = Land.colors.onAccent.copy(alpha = 0.85f),
            )
        }

        // The live WebView.
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onSubmit() {
                            post { onSubmit() }
                        }
                    }, "AndroidCapture")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(dimAndSpotlightJs(), null)
                        }
                    }
                    loadUrl(AnyRor.URL)
                }
            },
        )
    }
}

/**
 * Dims the whole AnyRoR page, spotlights the CAPTCHA image/input and the Get Record
 * Detail button, and routes that button's click back to the app. The sequential
 * cascade prefill (district→taluka→village→survey postbacks) ports from
 * anyror/run-anyror.mjs; it needs the live page to validate.
 */
private fun dimAndSpotlightJs(): String = """
(function(){
  try {
    var btn = document.getElementById('${AnyRor.Ids.GET_DETAIL_BUTTON}');
    var style = document.createElement('style');
    style.innerHTML = 'body *{opacity:.36 !important;} .lr-spot,.lr-spot *{opacity:1 !important;}';
    document.head.appendChild(style);
    // Spotlight the captcha area + submit button (best-effort selectors).
    [btn, document.querySelector('img[src*="captcha" i]'), document.querySelector('input[id*="captcha" i]')]
      .forEach(function(el){ if(el){ var p = el.closest('div,td,tr,form')||el; p.classList.add('lr-spot'); }});
    if (btn) {
      btn.addEventListener('click', function(){ try{ AndroidCapture.onSubmit(); }catch(e){} });
    }
  } catch(e) {}
})();
""".trimIndent()
