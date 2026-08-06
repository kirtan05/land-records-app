package com.landrecords.app.web

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
        val contentWidthPx = max(webView.width, 1)
        val contentHeightPx = max((webView.contentHeight * webView.resources.displayMetrics.density).toInt(), webView.height)

        val printableW = PAGE_W - 2 * MARGIN
        val printableH = PAGE_H - 2 * MARGIN
        val scale = printableW.toFloat() / contentWidthPx
        val pageContentPx = (printableH / scale).toInt().coerceAtLeast(1)
        val pageCount = ceil(contentHeightPx.toFloat() / pageContentPx).toInt().coerceIn(1, 60)

        // Lay the WebView out at its full content height so draw() paints everything.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(contentHeightPx, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, contentWidthPx, contentHeightPx)

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
