package com.landrecords.app.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a disposed case's order document and normalizes it to PDF bytes — the port of
 * desktop test-order.mjs (`ctx.request.post(action, {form})`). Replays the POST with the
 * WebView's live session cookies. The endpoint may return a PDF (sometimes with a small
 * prefix) or a scanned image; both are handled. Logs content-type + magic bytes for anything
 * it can't turn into a PDF (e.g. multi-page TIFF, which Android can't decode natively).
 */
object OrderDownloader {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

    /** [formJson] = orderFormJs output {action, fields}. Returns PDF bytes or null. */
    suspend fun fetch(formJson: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(formJson)
            val action = o.getString("action")
            val fields = o.getJSONObject("fields")
            val bytes = post(action, fields) ?: return@withContext null
            asPdf(bytes)
        } catch (e: Exception) {
            android.util.Log.w("LR", "order download failed: ${e.message}")
            null
        }
    }

    private fun post(action: String, fields: JSONObject): ByteArray? {
        val cookie = CookieManager.getInstance().getCookie(action) ?: ""
        val body = StringBuilder()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (body.isNotEmpty()) body.append('&')
            body.append(enc(k)).append('=').append(enc(fields.getString(k)))
        }
        val conn = (URL(action).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/pdf,image/*,*/*")
            setRequestProperty("Accept-Encoding", "identity") // no gzip — we want the raw bytes
            setRequestProperty("Referer", Ircms.CASE_DETAIL_URL)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val ct = conn.contentType ?: ""
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        android.util.Log.i("LR", "order resp ct='$ct' ${bytes.size}b magic=${hex(bytes)}")
        return bytes
    }

    /** PDF (even with a leading prefix) → trimmed PDF; JPEG/PNG/WEBP → single-page PDF; else null. */
    fun asPdf(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) return null
        val at = indexOfPdf(bytes)
        if (at >= 0) return if (at == 0) bytes else bytes.copyOfRange(at, bytes.size)
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        if (bmp != null) {
            android.util.Log.i("LR", "order image→PDF (${bmp.width}x${bmp.height})")
            return imageToPdf(bmp)
        }
        android.util.Log.i("LR", "order unsupported format magic=${hex(bytes)} (${bytes.size}b)")
        return null
    }

    /** Find "%PDF" within the first 4KB (some servers prepend a BOM/whitespace/headers). */
    private fun indexOfPdf(b: ByteArray): Int {
        val lim = minOf(b.size - 4, 4096)
        var i = 0
        while (i <= lim) {
            if (b[i] == 0x25.toByte() && b[i + 1] == 0x50.toByte() &&
                b[i + 2] == 0x44.toByte() && b[i + 3] == 0x46.toByte()
            ) return i
            i++
        }
        return -1
    }

    private fun imageToPdf(bmp: Bitmap): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(
            PdfDocument.PageInfo.Builder(bmp.width.coerceAtLeast(1), bmp.height.coerceAtLeast(1), 1).create(),
        )
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawBitmap(bmp, 0f, 0f, null)
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        bmp.recycle()
        return out.toByteArray()
    }

    private fun hex(b: ByteArray): String {
        val n = minOf(8, b.size)
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(String.format("%02x", b[i]))
        return sb.toString()
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
