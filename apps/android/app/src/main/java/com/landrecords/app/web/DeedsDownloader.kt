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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls each registered deed off AnyRoR and turns it into PDF bytes.
 *
 * "View Deed" is an ASP.NET LinkButton — clicking it does a full-form __doPostBack to
 * InfoSurveyNoDetail.aspx and the server streams the scanned deed file back as an attachment.
 * We replay that POST here with the WebView's live session cookies (exactly like
 * [OrderDownloader]), so the file bytes never have to cross the JS→Kotlin bridge.
 *
 * The response format is sniffed by magic bytes and converted:
 *   %PDF   -> used as-is (best case; merge straight away)
 *   JPEG   -> BitmapFactory -> one PDF page
 *   PNG    -> BitmapFactory -> one PDF page
 *   TIFF   -> [TiffToPdf.convert] (Android has NO built-in TIFF decoder — see that file)
 *
 * The AnyRorFetch.kt contract says these are Sub-registrar TIFFs; sniffing keeps us correct even
 * if a given SRO returns PDF/JPEG (several garvi/iORA document downloads do).
 */
object DeedsDownloader {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

    /**
     * One captured deed: the PDF bytes + the row metadata.
     * [id] matches [com.landrecords.app.data.storage.DeedsStore.Deed.id] (office|year|no) so a
     * downloaded scan can be attached to the right row of the deeds manifest.
     */
    data class Deed(val index: Int, val id: String, val label: String, val pdf: ByteArray)

    /**
     * Fetch every deed described by [deedFormJson] (the output of [DeedsInjection.deedFormJs]),
     * convert each to PDF, and return them in table order. Deeds that fail to fetch/convert are
     * skipped (logged), so a single bad scan never sinks the batch.
     */
    suspend fun fetchAll(deedFormJson: String, cacheDir: File): List<Deed> = withContext(Dispatchers.IO) {
        val out = ArrayList<Deed>()
        try {
            val o = JSONObject(deedFormJson)
            val action = o.getString("action")
            val baseFields = o.getJSONObject("fields")
            val deeds = o.getJSONArray("deeds")
            val cookie = CookieManager.getInstance().getCookie(action) ?: ""
            // The grid prints one ROW PER PARTY, so the same document carries several "View Deed"
            // links. Fetch each document once — three POSTs for one scan is pure waste on a site
            // we're deliberately gentle with.
            val fetched = HashSet<String>()
            for (i in 0 until deeds.length()) {
                val d = deeds.getJSONObject(i)
                val target = d.optString("eventTarget")
                if (target.isBlank()) continue
                val docKey = listOf(d.optString("office").trim(), d.optString("docYear").trim(), d.optString("docNo").trim())
                    .joinToString("|")
                if (!fetched.add(docKey)) continue
                val arg = d.optString("eventArgument", "")
                val label = listOf(d.optString("docNo"), d.optString("docYear"))
                    .filter { it.isNotBlank() }.joinToString("/").ifBlank { "deed_${i + 1}" }

                val bytes = postDeed(action, baseFields, target, arg, cookie)
                if (bytes == null || bytes.size < 8) {
                    android.util.Log.w("LR", "deed[$i] '$label' empty response"); continue
                }
                android.util.Log.i("LR", "deed[$i] '$label' ${bytes.size}B magic=${magic(bytes)}")
                val pdf = toPdf(bytes)
                if (pdf == null) {
                    android.util.Log.w("LR", "deed[$i] '$label' could not convert (${magic(bytes)}) — skipped"); continue
                }
                out.add(Deed(i, docKey, label, pdf))
            }
        } catch (e: Exception) {
            android.util.Log.w("LR", "deed fetchAll failed: ${e.message}")
        }
        out
    }

    /** Replay the ASP.NET postback for one deed (override __EVENTTARGET/__EVENTARGUMENT). */
    private fun postDeed(
        action: String, baseFields: JSONObject, eventTarget: String, eventArgument: String, cookie: String,
    ): ByteArray? = try {
        val body = StringBuilder()
        val keys = baseFields.keys()
        val seen = HashSet<String>()
        fun add(k: String, v: String) { if (body.isNotEmpty()) body.append('&'); body.append(enc(k)).append('=').append(enc(v)); seen.add(k) }
        // Force the two postback controls to THIS deed first, then copy the rest of the form as-is.
        add("__EVENTTARGET", eventTarget)
        add("__EVENTARGUMENT", eventArgument)
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "__EVENTTARGET" || k == "__EVENTARGUMENT") continue
            add(k, baseFields.getString(k))
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
            setRequestProperty("Referer", action)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        bytes
    } catch (e: Exception) {
        android.util.Log.w("LR", "deed POST failed: ${e.message}"); null
    }

    /** Sniff format and convert to PDF bytes, or null if we can't decode it. */
    private fun toPdf(bytes: ByteArray): ByteArray? = when {
        isPdf(bytes) -> bytes
        isJpeg(bytes) || isPng(bytes) -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { imageToPdf(listOf(it)) }
        isTiff(bytes) -> TiffToPdf.convert(bytes)   // decodes each TIFF page -> Bitmap, then imageToPdf
        else -> null
    }

    /** Draw one bitmap per page onto an A4-portrait PDF, fit-to-width, preserving aspect. */
    fun imageToPdf(pages: List<Bitmap>): ByteArray? {
        val usable = pages.filter { it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return null
        // A4 portrait @72dpi points (deeds are portrait document scans).
        val pw = 595; val ph = 842; val margin = 18
        val doc = PdfDocument()
        usable.forEachIndexed { i, bmp ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)
            val maxW = (pw - 2 * margin).toFloat(); val maxH = (ph - 2 * margin).toFloat()
            val scale = minOf(maxW / bmp.width, maxH / bmp.height)
            val dw = bmp.width * scale; val dh = bmp.height * scale
            val left = (pw - dw) / 2f; val top = (ph - dh) / 2f
            val dst = android.graphics.RectF(left, top, left + dw, top + dh)
            c.drawBitmap(bmp, null, dst, null)
            doc.finishPage(page)
            bmp.recycle()
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }

    // ── magic-byte sniffing ────────────────────────────────────────────────────────────────
    private fun isPdf(b: ByteArray) = b.size >= 4 && b[0] == 0x25.toByte() && b[1] == 0x50.toByte() && b[2] == 0x44.toByte() && b[3] == 0x46.toByte()
    private fun isJpeg(b: ByteArray) = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
    private fun isPng(b: ByteArray) = b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
    private fun isTiff(b: ByteArray) = b.size >= 4 &&
        ((b[0] == 0x49.toByte() && b[1] == 0x49.toByte() && b[2] == 0x2A.toByte() && b[3] == 0x00.toByte()) ||   // II* little-endian
         (b[0] == 0x4D.toByte() && b[1] == 0x4D.toByte() && b[2] == 0x00.toByte() && b[3] == 0x2A.toByte()))      // MM* big-endian
    private fun magic(b: ByteArray): String = when {
        isPdf(b) -> "PDF"; isJpeg(b) -> "JPEG"; isPng(b) -> "PNG"; isTiff(b) -> "TIFF"
        b.size >= 2 && b[0] == '<'.code.toByte() -> "HTML?(${String(b, 0, minOf(b.size, 16))})"
        else -> b.take(4).joinToString(" ") { "%02X".format(it) }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
