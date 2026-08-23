package com.landrecords.app.web

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Concatenates several PDFs into one WITHOUT rasterizing — pdfbox copies page objects and streams
 * to a temp file (MemoryUsageSetting.setupTempFileOnly), so merging big scanned records (iRCMS
 * cases + order scans, VF-7/12 year scans) stays flat in memory instead of OOM-ing. Also keeps the
 * original scan quality (the old bitmap-rasterizing merge was both lossy and memory-hungry).
 */
object PdfMerge {

    suspend fun merge(parts: List<ByteArray>, cacheDir: File): ByteArray? = withContext(Dispatchers.IO) {
        val usable = parts.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext null
        if (usable.size == 1) return@withContext usable[0]

        val out = File(cacheDir, "merge_out_${usable.size}_${usable.first().size}.pdf")
        try {
            if (out.exists()) out.delete()
            val merger = PDFMergerUtility()
            usable.forEach { merger.addSource(ByteArrayInputStream(it)) }
            merger.destinationFileName = out.absolutePath
            // Temp-file scratch only → merging N large scans never inflates the heap.
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            val bytes = if (out.exists() && out.length() > 0) out.readBytes() else null
            android.util.Log.i("LR", "PdfMerge: merged ${usable.size} parts -> ${bytes?.size ?: -1} bytes")
            bytes
        } catch (e: Throwable) {
            android.util.Log.w("LR", "PdfMerge failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { out.delete() }
        }
    }
}
