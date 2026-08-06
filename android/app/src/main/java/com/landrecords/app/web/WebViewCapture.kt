package com.landrecords.app.web

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max

/**
 * Captures a loaded [WebView] as (a) its raw HTML (for offline re-render / export per
 * APP_SPEC §4D) and (b) a paginated A4 PDF rendered from the live DOM after the
 * cleanup CSS has been injected. Android exposes no clean WebView→vector-PDF-to-file
 * path, so we slice the WebView's own canvas into A4 pages — automated, no print dialog.
 */
object WebViewCapture {

    /** A4 landscape in PostScript points (1/72"). */
    private const val PAGE_W = 842
    private const val PAGE_H = 595
    private const val MARGIN = 18 // ~6mm

    /**
     * Lay the record out at a desktop-width CSS viewport (like Chrome printing landscape
     * A4) so the wide 7/12 tables don't reflow into dozens of tall phone-width pages.
     * Matches the offline render that produces ~4 pages.
     */
    private const val LAYOUT_CSS_WIDTH = 1123

    /**
     * Render the loaded WebView to a PDF. Prefers Chrome's real print engine (page-break
     * aware, matches the desktop output); falls back to the canvas paginator only if the
     * platform blocks the print bridge.
     */
    suspend fun renderPdf(webView: WebView, cacheDir: File): ByteArray {
        PrintPdf.toPdfBytes(webView, cacheDir)?.let {
            if (it.isNotEmpty()) {
                android.util.Log.i("LR", "renderPdf: using PRINT ENGINE (${it.size} bytes)")
                return it
            }
        }
        android.util.Log.w("LR", "renderPdf: print engine unavailable — using CANVAS fallback")
        eval(webView, AnyRorInjection.wideViewportJs())
        delay(900)
        return toPdfBytes(webView)
    }

    /** Evaluate [js] on the main thread and return its (unquoted) string result. */
    suspend fun eval(webView: WebView, js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(js) { value -> cont.resume(unquote(value)) }
        }
    }

    /** Read the current page's full HTML for the .source/ re-render store. */
    suspend fun rawHtml(webView: WebView): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(
                "(function(){return '<!DOCTYPE html>'+document.documentElement.outerHTML;})();",
            ) { value ->
                // evaluateJavascript returns a JSON-quoted string; unescape it.
                cont.resume(unquote(value))
            }
        }
    }

    /**
     * Render the WebView into A4-landscape PDF bytes by drawing its full content height
     * page-by-page. Must run on the main thread (WebView.draw). Applies no scaling beyond
     * fitting the laid-out width to the printable page width.
     */
    suspend fun toPdfBytes(webView: WebView): ByteArray = withContext(Dispatchers.Main) {
        val density = webView.resources.displayMetrics.density
        // With useWideViewPort=false the CSS viewport = viewWidthPx / density, so laying the
        // view out at LAYOUT_CSS_WIDTH*density forces a 1123px CSS layout that fills the page.
        webView.settings.useWideViewPort = false
        webView.settings.loadWithOverviewMode = false
        val layoutWidthPx = (LAYOUT_CSS_WIDTH * density).toInt().coerceAtLeast(1)

        // Measure at the desktop width so the content reflows to it, then learn its height.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(layoutWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        webView.layout(0, 0, layoutWidthPx, webView.measuredHeight)
        // Give the reflow a beat, then re-measure the true content height.
        delay(250)
        val contentHeightPx = max((webView.contentHeight * density).toInt(), webView.measuredHeight)
        webView.layout(0, 0, layoutWidthPx, contentHeightPx)

        // Full-bleed width map: the desktop-width layout scales to the whole page width.
        val scale = (PAGE_W - 2 * MARGIN).toFloat() / layoutWidthPx
        val pageContentPx = ((PAGE_H - 2 * MARGIN) / scale).toInt().coerceAtLeast(1)
        val pageCount = ceil(contentHeightPx.toFloat() / pageContentPx).toInt().coerceIn(1, 40)

        val doc = PdfDocument()
        for (i in 0 until pageCount) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, i + 1).create())
            val canvas: Canvas = page.canvas
            canvas.save()
            canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
            canvas.scale(scale, scale)
            canvas.translate(0f, (-i * pageContentPx).toFloat())
            webView.draw(canvas)
            canvas.restore()
            doc.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        out.toByteArray()
    }

    private fun unquote(jsonString: String): String {
        if (jsonString == "null") return ""
        var s = jsonString
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
        return s.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\u0026", "&")
            .replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
            .replace("\\/", "/").replace("\\\\", "\\")
    }
}
