package com.landrecords.app.fetch

import android.content.Context
import com.landrecords.app.captcha.CaptchaCnn
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.web.AnyRor
import com.landrecords.app.web.AnyRorInjection
import com.landrecords.app.web.DeedsInjection
import com.landrecords.app.web.EntriesInjection
import com.landrecords.app.web.WebViewCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Headless AnyRoR fetch — the driver half of §6, lifted out of `FetchScreen`.
 *
 * Same page, same injections, same order as the on-screen flow; the only difference is that
 * nothing here touches Compose state, so it can run inside [FetchService] with no UI attached.
 * The injections (`AnyRorInjection`, `EntriesInjection`, `DeedsInjection`) are pure JS and are
 * used unchanged — that was the point of keeping them separate from the screens.
 *
 * The CAPTCHA is auto-solved by [CaptchaCnn] (pure Kotlin, ~200 ms on-device). Confidence is
 * **not** a usable gate — a wrong read at confidence 0.9665 was observed on-device — but a wrong
 * captcha is self-correcting: the site rejects it and we retry, so it costs a round-trip, not
 * data integrity. After [MAX_CAPTCHA_ATTEMPTS] rejections the item is failed and left for the
 * human spotlight in the UI, exactly as the spec requires.
 */
class AnyRorDriver(
    private val context: Context,
    private val pacer: FetchPacer,
) {

    companion object {
        const val MAX_CAPTCHA_ATTEMPTS = 3
        private const val MAX_PREFILL_STEPS = 14
    }

    /** Everything one AnyRoR page yields. One fetch, three record types (§6). */
    data class Capture(
        val pdf: ByteArray,
        val rawHtml: String,
        val summaryJson: String,
        val deedRowsJson: String,
        val entryListJson: String,
        val deedForm: String,
        /** VF-6 નોંધ entries (the red ones with scans), captured after the PDF render. */
        val entries: List<com.landrecords.app.data.storage.EntriesStore.EntryCapture> = emptyList(),
    )

    sealed interface Result {
        data class Ok(val capture: Capture) : Result
        /** VF-7/12: the kept scans, oldest→newest, ready to combine and file. */
        data class Vf712Ok(val scans: List<com.landrecords.app.data.storage.VfScansStore.ScanCapture>, val rawHtml: String) : Result
        /** The page loaded and genuinely holds nothing for this survey — a real answer. */
        data object Empty : Result
        data class Blocked(val detail: String) : Result
        data class Failed(val detail: String) : Result
    }

    /**
     * Fetch one survey's record page.
     *
     * [recordType] picks the AnyRoR record-type value and the survey dropdown: INTEGRATED (and
     * therefore entries + deeds) uses `ddlSurveyNo` on the /1000 desktop page, VF-7/12 uses
     * `ddlOldScannedSno`. The desktop page is used for both because its Sub-registrar deed grid
     * is absent from the mobile page.
     */
    suspend fun fetch(
        recordType: RecordType,
        districtGu: String,
        talukaGu: String,
        villageGu: String,
        surveyNo: String,
        /** The survey's uid — VF-7/12 needs it to skip old numbers the user removed (§2). */
        surveyUid: String = "",
    ): Result {
        val surveyDropId = when (recordType) {
            RecordType.VF712 -> AnyRor.Ids.SURVEY_VF712
            else -> AnyRor.Ids.SURVEY_INTEGRATED
        }
        val recordValue = AnyRor.recordValue(recordType) ?: "8"

        val host = HeadlessWebView.create(context)
        var captchaSolver: CaptchaCnn? = null
        try {
            if (!host.load(AnyRor.URL_DESKTOP)) return Result.Failed("page never loaded")
            if (FetchPacer.looksBlocked(host.lastHttpStatus, null)) {
                return Result.Blocked("http ${host.lastHttpStatus} on load")
            }

            // ---- cascade: one step per postback -----------------------------------------
            var steps = 0
            var lastStep = ""
            while (steps < MAX_PREFILL_STEPS) {
                val before = host.loadCount
                val step = WebViewCapture.eval(
                    host.webView,
                    AnyRorInjection.prefillStepJs(
                        recordValue, districtGu, talukaGu, villageGu, surveyNo, surveyDropId,
                    ),
                ).trim()
                lastStep = step
                android.util.Log.i("LR", "AnyRorDriver[$recordType $surveyNo] step='$step'")

                if (step.startsWith("READY")) break
                if (step.startsWith("WAIT")) {
                    // A WAIT is either "the postback hasn't landed yet" or "this dropdown will
                    // never contain what we want". Both look identical for a beat, so give the
                    // page time before treating it as unresolvable — the diagnostic dump in the
                    // step string says which it was.
                    delay(900)
                    steps++
                    continue
                }
                // RT / DIST / TAL / VIL / SUR each fire a change event -> a full postback.
                host.awaitLoadAfter(before, 30_000)
                delay(400)
                steps++
            }

            if (!lastStep.startsWith("READY")) {
                // The survey dropdown loaded a real option list but our number isn't in it
                // (e.g. "WAIT|sur|want=1257p|...|n=3059|near="). For VF-7/12 that is normal —
                // the old-scanned dropdown lists OLD survey numbers, which don't map 1:1 to the
                // current one — so it means "no VF-7/12 for this survey", a real EMPTY, not a
                // failure to retry 4×. Only treat it as a failure when the dropdown never filled.
                val surveyAbsent = Regex("\\|sur\\|.*\\|n=([1-9]\\d*)").find(lastStep) != null
                return if (surveyAbsent) Result.Empty
                else Result.Failed("cascade unresolved: $lastStep")
            }

            // ---- captcha -----------------------------------------------------------------
            val solver = runCatching { CaptchaCnn(context) }.getOrNull()
                ?: return Result.Failed("captcha model unavailable")
            captchaSolver = solver

            var attempt = 0
            var detailReady = false
            while (attempt < MAX_CAPTCHA_ATTEMPTS && !detailReady) {
                attempt++
                // Read the captcha out of the DOM. NEVER re-fetch its URL — that rotates the
                // code and invalidates the one baked into the page.
                val b64 = WebViewCapture.eval(host.webView, AnyRorInjection.captchaImageJs()).trim()
                if (b64.isBlank() || b64 == "NONE" || b64 == "null") {
                    // The cascade resolved but the form has no captcha — the site served
                    // something other than the real page. That is a host-level signal (very
                    // often a WAF soft-block after a burst), so back OFF rather than retry-
                    // hammer this one survey. When the block lifts, reopening the app resumes.
                    return Result.Blocked("no captcha image — page is not the real form")
                }
                val png = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                }.getOrNull()
                if (png == null || png.isEmpty()) return Result.Failed("captcha image undecodable")

                val (code, conf) = withContext(Dispatchers.Default) { solver.solve(png) }
                val clean = code.filter { it.isDigit() }
                android.util.Log.i("LR", "AnyRorDriver captcha '$clean' conf=$conf attempt $attempt")
                if (clean.length < 4) {
                    refreshCaptcha(host)
                    continue
                }

                val before = host.loadCount
                val submitted = WebViewCapture.eval(host.webView, AnyRorInjection.solveAndSubmitJs(clean))
                if (submitted != "OK") {
                    android.util.Log.w("LR", "AnyRorDriver submit failed: $submitted")
                    refreshCaptcha(host)
                    continue
                }
                host.awaitLoadAfter(before, 45_000)

                if (FetchPacer.looksBlocked(host.lastHttpStatus, null)) {
                    return Result.Blocked("http ${host.lastHttpStatus} after submit")
                }

                // Which "the result rendered" signal to poll depends entirely on the record
                // type. INTEGRATED lands on the type-8 detail page (detailReadyJs); VF-7/12
                // lands on the old-scanned-survey GRID, a completely different DOM, so it needs
                // resultReadyJs. Using the INTEGRATED check for VF-7/12 was the bug that made
                // every VF-7/12 fetch spin on WAIT, fail as a "rejected captcha", and — because
                // AnyRoR is serialized to one lane — starve the INTEGRATED items behind it.
                val readyJs = if (recordType == RecordType.VF712) {
                    com.landrecords.app.web.Vf712Injection.resultReadyJs()
                } else {
                    AnyRorInjection.detailReadyJs()
                }
                var ready = WebViewCapture.eval(host.webView, readyJs)
                var tries = 0
                while (ready.contains("WAIT") && tries < 12) {
                    delay(600)
                    ready = WebViewCapture.eval(host.webView, readyJs)
                    tries++
                }
                android.util.Log.i("LR", "AnyRorDriver[$recordType $surveyNo] resultReady='$ready'")
                when {
                    ready.startsWith("READY") -> detailReady = true
                    // VF-7/12 grid explicitly says "no record" — a real empty, not a bad captcha.
                    ready.contains("EMPTY") || ready.contains("NOTFOUND") -> return Result.Empty
                    else -> {
                        // Almost always a rejected captcha: the page came back to the form.
                        pacer.onBlocked()
                        delay(pacer.penaltyMs().coerceAtMost(15_000))
                    }
                }
            }

            if (!detailReady) {
                // Falling back to the human spotlight is a UI action, so from here the queue
                // records a failure the user can retry from the survey's status line.
                return Result.Failed("captcha rejected $MAX_CAPTCHA_ATTEMPTS times — needs the human spotlight")
            }

            // ---- VF-7/12: a grid of scanned-survey rows, each a separate PDF ----------------
            // Nothing like the INTEGRATED page. Read the grid, fire each row's View-PDF postback,
            // pull the PDF over the session cookies, drop the "not scanned" placeholders, and
            // hand back the genuine scans oldest→newest for the filer to combine. Mirrors the
            // proven on-screen flow in Vf712FetchScreen.
            if (recordType == RecordType.VF712) {
                return captureVf712(host, surveyUid)
            }

            // ---- capture, in the order the on-screen flow uses ---------------------------
            // Read the entry (નોંધ) grid and the deed rows from the PRISTINE DOM first: both
            // cleanupJs and deedCaptureJs rewrite the page, and deedCaptureJs replaces
            // document.body outright, which would destroy the __doPostBack form the deed
            // download needs. Order here is load-bearing, not stylistic.
            val entryListJson = if (recordType == RecordType.INTEGRATED) {
                WebViewCapture.eval(host.webView, EntriesInjection.entryListJs())
            } else ""

            val deedRowsJson = if (recordType == RecordType.INTEGRATED) {
                WebViewCapture.eval(host.webView, DeedsInjection.deedRowsJs())
            } else ""

            val deedForm = if (deedRowsJson.isNotBlank() && deedRowsJson != "[]") {
                WebViewCapture.eval(host.webView, DeedsInjection.deedFormJs())
            } else "NONE"

            val summaryJson = if (recordType == RecordType.INTEGRATED) {
                WebViewCapture.eval(host.webView, AnyRorInjection.summaryJs())
            } else ""

            WebViewCapture.eval(
                host.webView,
                AnyRorInjection.cleanupJs(
                    districtEn = districtGu, talukaEn = talukaGu,
                    villageEn = villageGu, surveyNo = surveyNo,
                ),
            )
            if (recordType == RecordType.INTEGRATED) {
                // Red નોંધ numbers become bold+underlined so they survive a black-&-white print.
                WebViewCapture.eval(host.webView, EntriesInjection.markRedEntriesForPdfJs())
            }

            val rawHtml = WebViewCapture.rawHtml(host.webView)
            val pdf = WebViewCapture.renderPdf(host.webView, context.cacheDir)
            if (pdf.isEmpty()) return Result.Failed("render produced no PDF")

            // VF-6 entries LAST: capturing a red નોંધ fires a Select postback that navigates the
            // page, so it must come after the PDF render and the pristine-DOM reads above. This is
            // the piece the headless driver used to skip entirely — the reason entries were missed.
            val entries = if (recordType == RecordType.INTEGRATED) {
                captureEntries(host, entryListJson)
            } else emptyList()

            return Result.Ok(
                Capture(
                    pdf = pdf,
                    rawHtml = rawHtml,
                    summaryJson = summaryJson,
                    deedRowsJson = deedRowsJson,
                    entryListJson = entryListJson,
                    deedForm = deedForm,
                    entries = entries,
                ),
            )
        } catch (e: Exception) {
            android.util.Log.e("LR", "AnyRorDriver failed", e)
            return Result.Failed(e.message ?: "unexpected error")
        } finally {
            runCatching { captchaSolver?.close() }
            host.destroy()
        }
    }

    /**
     * VF-7/12 capture: walk the results grid, fetch each row's scanned PDF via the WebView's
     * cookies, weed placeholders, and return the genuine scans oldest→newest.
     */
    private suspend fun captureVf712(host: HeadlessWebView, surveyUid: String): Result {
        val listHtml = WebViewCapture.rawHtml(host.webView)
        val rowsJson = WebViewCapture.eval(host.webView, com.landrecords.app.web.Vf712Injection.readRowsJs())
        val rows = parseVf712Rows(rowsJson)
        android.util.Log.i("LR", "AnyRorDriver VF712 grid rows=${rows.size}")
        if (rows.isEmpty()) return Result.Empty

        // Old survey numbers the user has already removed for this survey (§2) — never re-fetch
        // them. A rejection is stored precisely so a re-fetch cannot drag the same scans back.
        val rejected = if (surveyUid.isNotEmpty()) {
            Vf712Curation.rejectedTokens(context, surveyUid)
        } else emptySet()

        data class Kept(val period: String, val thok: String, val block: String, val oldSurvey: String, val status: String, val bytes: ByteArray)
        val kept = ArrayList<Kept>()
        for (r in rows) {
            if (!r.status.contains("ok", ignoreCase = true)) continue
            if (Vf712Curation.isRejected(r.oldSurvey, rejected)) {
                android.util.Log.i("LR", "VF712 row ${r.index} oldSurvey=${r.oldSurvey}: skipped (user removed it)")
                continue
            }
            pacer.awaitTurn()
            val before = host.loadCount
            WebViewCapture.eval(host.webView, com.landrecords.app.web.Vf712Injection.selectRowJs(r.index))
            host.awaitLoadAfter(before, 30_000)
            delay(500) // let the <object> embed parse in
            val url = WebViewCapture.eval(host.webView, com.landrecords.app.web.Vf712Injection.readPdfUrlJs())
            if (url.isBlank()) { android.util.Log.i("LR", "VF712 row ${r.index} ${r.period}: no embed"); continue }
            val bytes = com.landrecords.app.web.Vf712Downloader.fetch(url)
            if (bytes == null || !com.landrecords.app.web.Vf712Downloader.isGenuineScan(bytes)) {
                android.util.Log.i("LR", "VF712 row ${r.index} ${r.period}: weeded (placeholder/empty)")
                continue
            }
            kept.add(Kept(r.period, r.thok, r.block, r.oldSurvey, r.status, bytes))
        }
        // A grid full of "not scanned" placeholders is a genuine empty, not a failure.
        if (kept.isEmpty()) return Result.Empty

        val ordered = kept.sortedBy { startYearOf(it.period) }
        val scans = ordered.mapIndexed { i, k ->
            com.landrecords.app.data.storage.VfScansStore.ScanCapture(
                index = i + 1, period = k.period, thok = k.thok, block = k.block,
                oldSurvey = k.oldSurvey, status = k.status, pdf = k.bytes,
            )
        }
        return Result.Vf712Ok(scans, listHtml)
    }

    private data class Vf712Row(val index: Int, val period: String, val thok: String, val block: String, val oldSurvey: String, val status: String)

    private fun parseVf712Rows(json: String): List<Vf712Row> = runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("rows") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Vf712Row(
                o.optInt("index"), o.optString("period"), o.optString("thok"),
                o.optString("block"), o.optString("oldSurvey"), o.optString("status"),
            )
        }
    }.getOrDefault(emptyList())

    /** Leading 4-digit year of a "1951-1960"-style period, for oldest→newest ordering. */
    private fun startYearOf(period: String): Int =
        Regex("(\\d{4})").find(period)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    /**
     * Capture the RED VF-6 નોંધ entries (the old handwritten ones that carry a scan).
     *
     * Ported from the on-screen flow (FetchScreen): one Select postback on the first red entry
     * teaches us the `Info6oldImage` handler URL (its `dtv` is constant for the whole survey);
     * every entry's scan is then a plain parallel GET, so all N entries cost one page reload, not N.
     */
    private suspend fun captureEntries(
        host: HeadlessWebView,
        entryListJson: String,
    ): List<com.landrecords.app.data.storage.EntriesStore.EntryCapture> {
        val reds = runCatching {
            val arr = org.json.JSONObject(entryListJson).optJSONArray("rows") ?: return emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optBoolean("red") }
                .map { it.optInt("index") to it.optString("number") }
        }.getOrDefault(emptyList())
        if (reds.isEmpty()) return emptyList()

        val before = host.loadCount
        WebViewCapture.eval(host.webView, EntriesInjection.selectEntryJs(reds.first().first))
        host.awaitLoadAfter(before, 30_000)
        delay(400)

        val firstImgs = runCatching {
            val arr = org.json.JSONArray(WebViewCapture.eval(host.webView, EntriesInjection.entryImagesJs()))
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        val template = firstImgs.firstNotNullOfOrNull { com.landrecords.app.web.EntriesDownloader.templateFrom(it) }

        if (template == null) {
            // dtv unreadable → salvage just the first entry from its rendered URLs.
            android.util.Log.w("LR", "AnyRorDriver entries: no dtv template — first-entry fallback")
            val bytes = firstImgs.mapNotNull { com.landrecords.app.web.EntriesDownloader.fetchImage(it) }
            val pdf = com.landrecords.app.web.EntriesDownloader.imagesToPdf(bytes, reds.first().second)
            return listOf(com.landrecords.app.data.storage.EntriesStore.EntryCapture(reds.first().second, true, pdf))
        }

        // At most 4 concurrent image GETs — the one place §6 fan-out is genuinely parallel.
        val sem = Semaphore(4)
        return coroutineScope {
            val scope = this
            reds.map { (_, number) ->
                scope.async(Dispatchers.IO) {
                    sem.withPermit {
                        val pages = com.landrecords.app.web.EntriesDownloader.fetchEntryPages(template, number)
                        val pdf = com.landrecords.app.web.EntriesDownloader.imagesToPdf(pages, number)
                        android.util.Log.i("LR", "AnyRorDriver entry $number: ${pages.size} page(s) -> ${pdf?.size ?: 0} bytes")
                        com.landrecords.app.data.storage.EntriesStore.EntryCapture(number, true, pdf)
                    }
                }
            }.awaitAll()
        }.filter { it.pdf != null && it.pdf!!.isNotEmpty() }
    }

    /** Rotate to a fresh captcha before another attempt. */
    private suspend fun refreshCaptcha(host: HeadlessWebView) {
        runCatching { WebViewCapture.eval(host.webView, AnyRorInjection.refreshCaptchaJs()) }
        delay(1_200)
    }
}
