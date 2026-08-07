package com.landrecords.app.web

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches one OLD-SCANNED VF-7/12 document and decides whether it is a genuine scan.
 * The faithful port of anyror/run-vf712.mjs fetchRowPdf + isPlaceholder — except that on
 * the phone the WebView has already run the postback, so the PDFView1.aspx?detail=<token>
 * URL is read straight from the embed's `data` attribute ([Vf712Injection.readPdfUrlJs])
 * and we replay a GET here with the live session cookies (same trick as [OrderDownloader]),
 * so the PDF bytes never cross the JS→Kotlin Binder.
 *
 * WEEDING RULE (the desktop's "weed any PDF that has a text layer"): a genuine old VF-7/12
 * scan is a raster image (JPEG/CCITT) with NO text layer; the "not scanned / dilapidated /
 * ઉપલબ્ધ નથી / does not exist" placeholder AnyRoR returns is a small generated TEXT pdf.
 * Desktop used pdftotext (unavailable on-device); the on-device equivalent is: keep the file
 * only if it is a real PDF, is not trivially small, and embeds a raster image
 * (`/DCTDecode` | `/CCITTFax` | `/JPXDecode` | `/…/Image`). A placeholder text pdf has none.
 * (Desktop placeholder phrases, kept for reference:
 *  શકેલ નથી · શકલ નથી · જર્જરીત · ઉપલબ્ધ નથી · does not exist · not available · record not found.)
 */
object Vf712Downloader {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    private const val REFERER = "https://anyror.gujarat.gov.in/InfoOld712Detail.aspx"

    /** GETs [pdfUrl] (the raw object[data] URL) with the WebView cookies. Returns PDF bytes or null. */
    suspend fun fetch(pdfUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // The token contains '/', '+', '=' — send the URL verbatim (do NOT re-encode), exactly
            // as the browser's <object> would; HttpURLConnection preserves the query as-is.
            val cookie = CookieManager.getInstance().getCookie(pdfUrl) ?: ""
            val conn = (URL(pdfUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", DESKTOP_UA)
                setRequestProperty("Accept", "application/pdf,*/*")
                setRequestProperty("Referer", REFERER)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            conn.disconnect()
            // The server sometimes answers a stale/blocked token with an HTML error body
            // (see anyror/vf712-capture/captured-1.pdf: 536 bytes of "<!doctype html>").
            if (isPdf(bytes)) {
                android.util.Log.i("LR", "vf712 doc fetched (${bytes.size} bytes) status=$code")
                bytes
            } else {
                android.util.Log.i("LR", "vf712 doc not a PDF (${bytes.size} bytes) status=$code")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("LR", "vf712 download failed: ${e.message}")
            null
        }
    }

    /** True if [bytes] is a genuine image scan we should keep (not a text placeholder). */
    fun isGenuineScan(bytes: ByteArray): Boolean {
        if (bytes.size < 2_000 || !isPdf(bytes)) return false
        // ISO-8859-1 is a byte-preserving 1:1 decode — safe for scanning raw PDF markers.
        val raw = String(bytes, Charsets.ISO_8859_1)
        return raw.contains("/DCTDecode") || raw.contains("/CCITTFax") ||
            raw.contains("/JPXDecode") || raw.contains("/Image")
    }

    private fun isPdf(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x25.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x44.toByte() && b[3] == 0x46.toByte() // %PDF
}
