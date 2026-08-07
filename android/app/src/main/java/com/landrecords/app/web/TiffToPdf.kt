package com.landrecords.app.web

/**
 * TIFF -> PDF, the one hard part of the deed capture.
 *
 * Android has NO built-in TIFF decoder: BitmapFactory / ImageDecoder / WebView (Chromium) all
 * refuse TIFF, so a raw Sub-registrar TIFF can neither be BitmapFactory-decoded nor print-to-PDF'd
 * through a WebView. Something has to decode it.
 *
 * IMPORTANT: no captured *.tif exists in the repo (the desktop Valetva/41 deed run was cut off by
 * the AnyRoR WAF before any deed downloaded — output/Valetva_41/ is empty). So the *format* is only
 * asserted by the AnyRorFetch.kt contract ("Sub-registrar 'View Deed' TIFF"). [DeedsDownloader]
 * therefore sniffs magic bytes first and only lands here when the response really is TIFF
 * (II*.../MM*...). Garvi/SRO scans are bilevel fax images -> almost always multi-page CCITT Group 4.
 *
 * Pick ONE decoder (default is a no-op so the module compiles clean on the pinned toolchain):
 *
 *  OPTION A — pragmatic, native (recommended IF the response is genuinely TIFF):
 *    Gradle:  repositories { maven { url = uri("https://jitpack.io") } }
 *             implementation("com.github.beyka:Android-TiffBitmapFactory:0.9.9.0")
 *    A prebuilt-.so JNI wrapper around libtiff (handles CCITT G3/G4, multi-page). Returns
 *    android.graphics.Bitmap, so it plugs straight into DeedsDownloader.imageToPdf.
 *      val opt = TiffBitmapFactory.Options().apply { inJustDecodeBounds = false }
 *      val dir = TiffBitmapFactory.getDirectoryCount(bytes)           // page count
 *      val pages = (0 until dir).mapNotNull { p ->
 *          opt.inDirectoryNumber = p
 *          TiffBitmapFactory.decodeByteArray(bytes, opt)
 *      }
 *      return DeedsDownloader.imageToPdf(pages)
 *    RISK (flag to user): native .so shipped in the AAR must be repackaged by AGP 9's jniLibs
 *    step (untested against this pinned Gradle 9.5 / AGP 9.1.1 chain); the lib last shipped ~2017;
 *    adds ~1-2 MB per ABI. It touches neither kapt nor Room, so it won't break the Kotlin-2.3.21
 *    metadata constraint — the only risk is packaging/ABI, verifiable with one assembleDebug.
 *
 *  OPTION B — zero dependency, more work: a focused pure-Kotlin TIFF + CCITT-G4 (ITU T.6) decoder
 *    (parse IFD tags: ImageWidth/Length, StripOffsets/ByteCounts, Compression==4, Photometric,
 *    Fill/T4/T6 options -> decode each strip to a 1-bpp raster -> Bitmap). ~400-600 lines, but no
 *    toolchain risk at all. Best if deeds turn out to be strictly bilevel G4 (they usually are).
 *
 * Whichever is chosen, the output feeds [DeedsDownloader.imageToPdf] (one PDF page per TIFF page),
 * and the per-deed PDFs are merged by [PdfMerge].
 */
object TiffToPdf {

    /**
     * Decode a TIFF (possibly multi-page) to a single PDF's bytes, or null if no decoder is wired.
     * Default: no-op (returns null) so the app builds without any new dependency. Wire OPTION A or
     * B above once a real deed TIFF has been captured on-device and its exact encoding confirmed.
     */
    fun convert(@Suppress("UNUSED_PARAMETER") tiffBytes: ByteArray): ByteArray? {
        android.util.Log.w(
            "LR",
            "TiffToPdf: TIFF deed received but no decoder wired — see TiffToPdf.kt OPTION A/B",
        )
        return null
    }
}
