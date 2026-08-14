package com.landrecords.app.fetch

import android.content.Context
import com.landrecords.app.data.storage.CasesStore
import com.landrecords.app.ui.fetch.parseCaseList
import com.landrecords.app.web.Ircms
import com.landrecords.app.web.IrcmsInjection
import com.landrecords.app.web.OrderDownloader
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Headless iRCMS fetch — the driver half of §6, lifted out of `IrcmsFetchScreen`.
 *
 * Runs **concurrently with AnyRoR**: separate site, separate session, separate `__VIEWSTATE`
 * problem (iRCMS has none — it is one AJAX page). That concurrency was already proven in
 * `IrcmsBatchScreen`, which drives two WebViews at once.
 *
 * The CAPTCHA here is not a CNN problem: iRCMS renders it as SVG `<text>` nodes, so
 * [IrcmsInjection.autoSolveCaptchaJs] reads the digits straight out of the DOM. One solve
 * covers every case in the list (iRCMS reuses it), which is why the per-case loop below does
 * not re-solve.
 */
class IrcmsDriver(
    private val context: Context,
    private val pacer: FetchPacer,
) {

    sealed interface Result {
        data class Ok(val captures: List<CasesStore.CaseCapture>) : Result
        /** Searched successfully and this survey genuinely has no cases — a real answer. */
        data object Empty : Result
        data class Blocked(val detail: String) : Result
        data class Failed(val detail: String) : Result
    }

    suspend fun fetch(
        district: String,
        taluka: String,
        village: String,
        surveyNo: String,
    ): Result {
        val host = HeadlessWebView.create(context)
        try {
            if (!host.load(Ircms.URL)) return Result.Failed("page never loaded")
            if (FetchPacer.looksBlocked(host.lastHttpStatus, null)) {
                return Result.Blocked("http ${host.lastHttpStatus} on load")
            }

            // ---- cascade (AJAX, so poll rather than count postbacks) ---------------------
            var filled = false
            var tries = 0
            while (!filled && tries < 40) {
                val step = WebViewCapture.eval(
                    host.webView, IrcmsInjection.prefillJs(district, taluka, village, surveyNo),
                )
                if (step.contains("READY")) { filled = true; break }
                delay(600)
                tries++
            }
            if (!filled) return Result.Failed("iRCMS cascade never resolved")

            // ---- captcha: a deterministic SVG <text> parse, not a model ------------------
            delay(400)
            var code = ""
            for (a in 1..10) {
                code = WebViewCapture.eval(host.webView, IrcmsInjection.autoSolveCaptchaJs()).trim()
                if (code.length == 6) break
                delay(700)
            }
            // Snapshot how the code was parsed, so a later rejection can be explained rather
            // than guessed at. See IrcmsInjection.captchaParseDiagJs for what the fields mean.
            val diagAtSolve = WebViewCapture.eval(host.webView, IrcmsInjection.captchaParseDiagJs())
            android.util.Log.i("LR", "IrcmsDriver captcha solve code='$code' diag=$diagAtSolve")

            if (code.length != 6) return Result.Failed("iRCMS captcha unreadable — needs the human spotlight")

            // ---- search ------------------------------------------------------------------
            WebViewCapture.eval(host.webView, IrcmsInjection.searchSurveyDirectJs(surveyNo))
            var res = "WAIT"
            var polls = 0
            while (polls < 150) { // ~75s no-progress cap, same as the screen's WAF guard
                res = WebViewCapture.eval(host.webView, IrcmsInjection.searchStatusJs())
                if (res != "WAIT") break
                delay(500); polls++
            }
            if (res == "WAIT") return Result.Blocked("iRCMS never responded to the search")
            if (FetchPacer.looksBlocked(host.lastHttpStatus, res)) {
                return Result.Blocked("iRCMS refused the search: ${res.take(80)}")
            }
            if (res.startsWith("EMPTY")) {
                // A rejected captcha also lands here, so distinguish before calling it empty —
                // marking a survey "no cases" when it was really a bad captcha is a data error
                // the user has no way to notice.
                val wrongCode = Regex("captcha|invalid", RegexOption.IGNORE_CASE).containsMatchIn(res)
                if (wrongCode) {
                    // Re-read the SAME page now. The two diagnostics side by side answer the
                    // question: if srcHash CHANGED the server rotated the captcha under us (we
                    // sent a stale but correctly-read code); if it is UNCHANGED then the code we
                    // sent really was what the image showed, and the parse/order is the suspect
                    // — check `missingX`/`transforms`/`orderMatters` in the solve diagnostic.
                    val diagAtReject = WebViewCapture.eval(host.webView, IrcmsInjection.captchaParseDiagJs())
                    android.util.Log.w(
                        "LR",
                        "IrcmsDriver CAPTCHA REJECTED sent='$code'\n" +
                            "  at solve : $diagAtSolve\n" +
                            "  at reject: $diagAtReject\n" +
                            "  status   : ${res.take(200)}",
                    )
                    return Result.Failed("iRCMS rejected the captcha")
                }
                return Result.Empty
            }
            if (res != "READY") return Result.Failed("unexpected search status '$res'")

            // ---- read + capture each case ------------------------------------------------
            val casesJson = WebViewCapture.eval(host.webView, IrcmsInjection.readCasesJs())
            val (token, cases) = parseCaseList(casesJson)
            if (cases.isEmpty()) return Result.Empty

            val captures = ArrayList<CasesStore.CaseCapture>(cases.size)
            for (c in cases) {
                pacer.awaitTurn()
                val before = host.loadCount
                withContext(Dispatchers.Main) {
                    host.webView.postUrl(
                        Ircms.CASE_DETAIL_URL,
                        IrcmsInjection.caseBody(token, c.casekey).toByteArray(),
                    )
                }
                host.awaitLoadAfter(before, 45_000)

                var ready = WebViewCapture.eval(host.webView, IrcmsInjection.caseReadyJs())
                var t = 0
                while (!ready.startsWith("READY") && t < 12) {
                    delay(500)
                    ready = WebViewCapture.eval(host.webView, IrcmsInjection.caseReadyJs())
                    t++
                }
                if (!ready.startsWith("READY")) {
                    android.util.Log.w("LR", "IrcmsDriver: case ${c.dataId} never rendered — skipped")
                    continue
                }

                // A disposed case carries a downloadable order; read its form BEFORE the
                // cleanup rewrites the DOM.
                val orderForm = runCatching {
                    WebViewCapture.eval(host.webView, IrcmsInjection.orderFormJs())
                }.getOrNull() ?: "NONE"

                WebViewCapture.eval(host.webView, IrcmsInjection.caseCleanupJs())
                val pdf = WebViewCapture.renderPdf(host.webView, context.cacheDir)
                val orderPdf = if (orderForm != "NONE") {
                    runCatching { OrderDownloader.fetch(orderForm) }.getOrNull()
                } else null

                captures.add(
                    CasesStore.CaseCapture(
                        sr = c.sr, dataId = c.dataId, caseNo = c.caseNo, status = c.status,
                        office = c.office, dtv = c.dtv, parties = c.parties, survno = c.survno,
                        detailPdf = pdf.takeIf { it.isNotEmpty() }, orderPdf = orderPdf,
                    ),
                )
            }

            return if (captures.isEmpty()) Result.Empty else Result.Ok(captures)
        } catch (e: Exception) {
            android.util.Log.e("LR", "IrcmsDriver failed", e)
            return Result.Failed(e.message ?: "unexpected error")
        } finally {
            host.destroy()
        }
    }
}
