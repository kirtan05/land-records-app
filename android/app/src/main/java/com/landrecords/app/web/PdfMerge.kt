package com.landrecords.app.web

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Merges several PDFs into one. Android has no native vector-PDF concatenation, so each source
 * page is rendered to a bitmap (PdfRenderer) and re-emitted into a new PdfDocument. Lossy vs. a
 * true merge, but the iRCMS case pages are already scans/print output, so it's fine — and it
 * needs no third-party dependency (the build toolchain is pinned).
 */
object PdfMerge {

    /** ~144 dpi (2× the 72dpi PDF point grid) — legible without ballooning the file. */
    private const val SCALE = 2

    suspend fun merge(parts: List<ByteArray>, cacheDir: File): ByteArray? = withContext(Dispatchers.IO) {
        val usable = parts.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext null
        val doc = PdfDocument()
        var pageNo = 0
        for ((idx, bytes) in usable.withIndex()) {
            val tmp = File(cacheDir, "merge_src_$idx.pdf").apply { writeBytes(bytes) }
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    val src = renderer.openPage(i)
                    val w = (src.width * SCALE).coerceAtLeast(1)
                    val h = (src.height * SCALE).coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    src.close()
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, ++pageNo).create())
                    page.canvas.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(page)
                    bmp.recycle()
                }
            } catch (e: Exception) {
                android.util.Log.w("LR", "PdfMerge: skipped a part (${e.javaClass.simpleName}: ${e.message})")
            } finally {
                runCatching { renderer?.close() }
                runCatching { pfd?.close() }
                tmp.delete()
            }
        }
        if (pageNo == 0) { doc.close(); return@withContext null }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        android.util.Log.i("LR", "PdfMerge: merged ${usable.size} parts -> $pageNo pages")
        out.toByteArray()
    }
}
