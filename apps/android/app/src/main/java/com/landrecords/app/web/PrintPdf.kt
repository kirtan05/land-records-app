package com.landrecords.app.web

import android.os.ParcelFileDescriptor
import android.print.LrPrintBridge
import android.print.PrintAttributes
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Renders a WebView to a page-break-aware PDF via Chrome's own print engine (through
 * [LrPrintBridge]) — the same engine the desktop tool uses. Returns null if the platform
 * blocks the bridge, so callers can fall back to the canvas paginator.
 */
object PrintPdf {

    suspend fun toPdfBytes(webView: WebView, cacheDir: File): ByteArray? = withContext(Dispatchers.Main) {
        val out = File(cacheDir, "lr_print_${System.identityHashCode(webView)}.pdf")
        if (out.exists()) out.delete()
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            .setMinMargins(PrintAttributes.Margins(160, 160, 160, 160)) // ~4mm, in mils
            .build()

        val pfd = try {
            ParcelFileDescriptor.open(
                out,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
        } catch (e: Exception) {
            return@withContext null
        }

        val ok = try {
            suspendCancellableCoroutine { cont ->
                LrPrintBridge.print(webView, attrs, pfd) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        } catch (e: Throwable) {
            // IllegalAccessError etc. if the platform rejects the package-private bridge.
            android.util.Log.w("LR", "PrintPdf bridge threw: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        runCatching { pfd.close() }
        val bytes = if (ok && out.exists() && out.length() > 0) out.readBytes() else null
        android.util.Log.i("LR", "PrintPdf: ok=$ok fileLen=${out.length()} -> ${bytes?.size ?: -1} bytes")
        out.delete()
        bytes
    }
}
