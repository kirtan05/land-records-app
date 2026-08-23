package com.landrecords.app.web

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.CCITTFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream

/**
 * TIFF -> PDF for the scanned Sub-registrar deeds.
 *
 * Android has NO built-in TIFF decoder (BitmapFactory / ImageDecoder / WebView all refuse it), so
 * this used to be a no-op stub pointing at a native libtiff wrapper. It doesn't need one: the app
 * already bundles pdfbox-android for [PdfMerge], and pdfbox's [CCITTFactory] EMBEDS a CCITT
 * Group 3/4 TIFF's compressed data straight into a PDF image object — no decode, no raster, no new
 * dependency, and the scan keeps its original bilevel quality. Garvi/SRO scans are fax-style
 * bilevel images, which is exactly (and only) what CCITTFactory handles.
 *
 * Limits, deliberately not worked around:
 *  - Non-CCITT TIFFs (LZW, JPEG-in-TIFF, uncompressed) throw inside CCITTFactory; we return null
 *    and the caller logs + skips, exactly as before. No deed TIFF has ever been captured from the
 *    live site ("Document Record Not Found"), so guessing at other encodings would be untestable
 *    speculation — the magic-byte log in [DeedsDownloader] is what will tell us if one shows up.
 *  - Multi-page is handled by asking for page 0, 1, 2 … until the factory refuses.
 */
object TiffToPdf {

    /** Hard stop so a malformed IFD chain can't spin forever. Real deeds are a few pages. */
    private const val MAX_PAGES = 60

    /**
     * Convert a (possibly multi-page) CCITT TIFF into PDF bytes, one PDF page per TIFF page at the
     * image's own aspect, or null when nothing could be embedded.
     */
    fun convert(tiffBytes: ByteArray): ByteArray? {
        val doc = PDDocument()
        var pages = 0
        try {
            for (p in 0 until MAX_PAGES) {
                val img: PDImageXObject = try {
                    CCITTFactory.createFromByteArray(doc, tiffBytes, p)
                } catch (e: Exception) {
                    // Page 0 failing = not a CCITT TIFF at all; a later page failing = end of file.
                    if (p == 0) {
                        android.util.Log.w("LR", "TiffToPdf: not embeddable (${e.javaClass.simpleName}: ${e.message})")
                    }
                    break
                }
                // Page box = the scan's own pixel size at 72dpi, so nothing is cropped or stretched.
                val page = PDPage(PDRectangle(img.width.toFloat(), img.height.toFloat()))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, 0f, 0f, img.width.toFloat(), img.height.toFloat())
                }
                pages++
            }
            if (pages == 0) return null
            val out = ByteArrayOutputStream()
            doc.save(out)
            android.util.Log.i("LR", "TiffToPdf: embedded $pages TIFF page(s) -> ${out.size()} bytes")
            return out.toByteArray()
        } catch (e: Throwable) {
            android.util.Log.w("LR", "TiffToPdf failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            runCatching { doc.close() }
        }
    }
}
