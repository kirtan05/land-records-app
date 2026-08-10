package com.landrecords.app.web

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a red entry's scanned page images (WebHandler/Info6oldImage.ashx) with the WebView's
 * session cookies — same replay trick as [Vf712Downloader] / [OrderDownloader], so the image bytes
 * never cross the JS→Kotlin Binder — and assembles them into one small PDF per entry, one scanned
 * page per PDF page, stamped with a RED "Entry No. N" header so a printed entry is clearly labelled.
 */
object EntriesDownloader {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    private const val REFERER = "https://anyror.gujarat.gov.in/Information_pages/InfoSurveyNoDetail.aspx"

    // A4 portrait in PostScript points (1/72").
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 24
    private const val HEADER_H = 26

    /** GETs one image URL with the WebView cookies. Returns raw image bytes, or null on failure. */
    suspend fun fetchImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val cookie = CookieManager.getInstance().getCookie(url) ?: ""
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", DESKTOP_UA)
                setRequestProperty("Accept", "image/jpeg,image/png,image/*,*/*")
                setRequestProperty("Referer", REFERER)
            }
            val code = conn.responseCode
            val type = conn.contentType ?: ""
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            conn.disconnect()
            // A blocked/stale handler answers with an HTML error body, not an image — weed those out.
            if (bytes.size > 500 && (type.startsWith("image/") || looksLikeImage(bytes))) {
                android.util.Log.i("LR", "entry image fetched (${bytes.size} bytes, $type) status=$code")
                bytes
            } else {
                android.util.Log.i("LR", "entry image not an image (${bytes.size} bytes, $type) status=$code")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("LR", "entry image download failed: ${e.message}")
            null
        }
    }

    /**
     * Assembles [images] (raw JPEG/PNG bytes, one per scanned page) into a single A4-portrait PDF,
     * each page fit-to-width with a small red "Entry No. [number]" header. Returns null if no image
     * decoded.
     */
    suspend fun imagesToPdf(images: List<ByteArray>, number: String): ByteArray? = withContext(Dispatchers.Default) {
        val usable = images.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext null
        val doc = PdfDocument()
        var drew = 0
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xC4, 0x1E, 0x1E); textSize = 13f; isFakeBoldText = true
        }
        val rulePaint = Paint().apply { color = Color.rgb(0xC4, 0x1E, 0x1E); strokeWidth = 2f }
        try {
            usable.forEachIndexed { i, bytes ->
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    ?: return@forEachIndexed
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, drew + 1).create())
                val canvas: Canvas = page.canvas
                // Red header: a rule + label, so a printed entry scan is unmistakably marked.
                canvas.drawText("Entry No. $number", MARGIN.toFloat(), (MARGIN + 12).toFloat(), headerPaint)
                canvas.drawLine(MARGIN.toFloat(), (MARGIN + HEADER_H - 6).toFloat(), (PAGE_W - MARGIN).toFloat(), (MARGIN + HEADER_H - 6).toFloat(), rulePaint)
                // Fit the scan into the area below the header, preserving aspect, centered.
                val availW = PAGE_W - 2 * MARGIN
                val top = MARGIN + HEADER_H
                val availH = PAGE_H - top - MARGIN
                val scale = minOf(availW.toFloat() / bmp.width, availH.toFloat() / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = MARGIN + (availW - w) / 2f
                val dst = RectF(left, top.toFloat(), left + w, top + h)
                canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
                drew++
            }
            if (drew == 0) return@withContext null
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            out.toByteArray()
        } catch (e: Throwable) {
            android.util.Log.w("LR", "entry imagesToPdf failed: ${e.message}")
            null
        } finally {
            doc.close()
        }
    }

    /** JPEG (FFD8FF) or PNG (89504E47) magic bytes. */
    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
        return jpeg || png
    }
}
