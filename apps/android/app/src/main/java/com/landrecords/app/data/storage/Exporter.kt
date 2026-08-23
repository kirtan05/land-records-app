package com.landrecords.app.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.landrecords.app.web.PdfMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Batch export of the records dad has marked with a colour. Three ways out — chosen at the
 * "Send all" menu — plus print:
 *   • [sendMerged]   one combined PDF (easiest to print in one job / send as one WhatsApp file)
 *   • [sendSeparate] each record its own PDF, attached together (ACTION_SEND_MULTIPLE)
 *   • [sendZip]      everything bundled into one .zip
 *   • [printMerged]  the combined PDF handed to Android's print service
 *
 * Files are always written under cache/export with descriptive, Gujarati-preserving names (see
 * [LibraryAccess.safeName]) so whatever the receiving app shows is meaningful.
 */
object Exporter {

    /** One record to export: where its PDF lives + the descriptive filename to give the copy. */
    data class Item(val pdfPath: String, val name: String)

    private fun authority(context: Context) = "${context.packageName}.fileprovider"
    private fun exportDir(context: Context) = File(context.cacheDir, "export").apply { mkdirs() }

    /** Combine all [items] into one PDF and open the share sheet under [groupName]. */
    suspend fun sendMerged(context: Context, items: List<Item>, groupName: String): Boolean {
        val merged = mergedBytes(context, items) ?: return false
        return LibraryAccess.shareBytes(context, merged, groupName)
    }

    /** Attach every item as its own PDF (multi-file share). */
    suspend fun sendSeparate(context: Context, items: List<Item>): Boolean {
        val uris = withContext(Dispatchers.IO) {
            val out = ArrayList<Uri>()
            val used = HashSet<String>()
            for (it in items) {
                val bytes = LibraryAccess.readBytes(context, it.pdfPath) ?: continue
                val f = File(exportDir(context), uniqueName(it.name, used))
                runCatching { f.writeBytes(bytes) }.onSuccess {
                    out.add(FileProvider.getUriForFile(context, authority(context), f))
                }
            }
            out
        }
        if (uris.isEmpty()) return false
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooser(context, intent, "Send records")
    }

    /** Bundle every item into one .zip and share it. */
    suspend fun sendZip(context: Context, items: List<Item>, zipName: String): Boolean {
        val zipFile = withContext(Dispatchers.IO) {
            val base = LibraryAccess.safeName(zipName).removeSuffix(".pdf")
            val safeZip = if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
            val out = File(exportDir(context), safeZip)
            val used = HashSet<String>()
            runCatching {
                ZipOutputStream(out.outputStream().buffered()).use { zip ->
                    for (it in items) {
                        val bytes = LibraryAccess.readBytes(context, it.pdfPath) ?: continue
                        zip.putNextEntry(ZipEntry(uniqueName(it.name, used)))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                out.takeIf { it.length() > 0 }
            }.getOrNull()
        } ?: return false
        val uri = FileProvider.getUriForFile(context, authority(context), zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, zipFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooser(context, intent, "Send zip")
    }

    /** Merge [items] to one PDF and hand it to the Android print service. */
    suspend fun printMerged(context: Context, items: List<Item>, jobName: String): Boolean {
        val merged = mergedBytes(context, items) ?: return false
        val file = withContext(Dispatchers.IO) {
            val f = File(exportDir(context), "print_${System.nanoTime()}.pdf")
            runCatching { f.writeBytes(merged); f }.getOrNull()
        } ?: return false
        return withContext(Dispatchers.Main) {
            runCatching {
                val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(jobName, PdfFileAdapter(file), null)
                true
            }.getOrDefault(false)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────
    private suspend fun mergedBytes(context: Context, items: List<Item>): ByteArray? {
        val parts = withContext(Dispatchers.IO) { items.mapNotNull { LibraryAccess.readBytes(context, it.pdfPath) } }
        if (parts.isEmpty()) return null
        return PdfMerge.merge(parts, context.cacheDir)
    }

    /** A [LibraryAccess.safeName] that stays unique within one export (adds " (2)", " (3)"…). */
    private fun uniqueName(raw: String, used: MutableSet<String>): String {
        var name = LibraryAccess.safeName(raw)
        var n = 2
        val stem = name.removeSuffix(".pdf")
        while (!used.add(name)) { name = LibraryAccess.safeName("$stem ($n)"); n++ }
        return name
    }

    private suspend fun startChooser(context: Context, intent: Intent, title: String): Boolean =
        withContext(Dispatchers.Main) {
            val chooser = Intent.createChooser(intent, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(chooser); true }.getOrDefault(false)
        }

    /** Streams a pre-rendered PDF file straight to the print spooler (no re-rasterization). */
    private class PdfFileAdapter(private val file: File) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(file.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?, callback: WriteResultCallback?,
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination!!.fileDescriptor).use { out -> input.copyTo(out) }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            }
        }
    }
}
