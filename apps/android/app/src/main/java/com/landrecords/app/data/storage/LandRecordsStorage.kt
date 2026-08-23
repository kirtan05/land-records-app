package com.landrecords.app.data.storage

import android.os.Environment
import com.landrecords.app.data.model.RecordType
import java.io.File

/**
 * Owns the visible, backup-able library tree:
 *
 *   Documents/LandRecords/<District>/<Taluka>/<Village>/Survey <No>/
 *       Integrated Record.pdf
 *       VF-7-12.pdf
 *       Deeds/…pdf
 *       iRCMS Cases/…pdf
 *       .source/            (raw HTML + JSON + scans for re-render/export)
 *
 * Token convention matches the desktop tooling: survey upper-cased, '/' → '_'  (221/p → 221_P).
 * Actual writes on API 29+ go through MediaStore; this class centralizes the layout so both the
 * legacy-File path and the MediaStore path agree on names.
 */
object LandRecordsStorage {

    const val ROOT_DIR_NAME = "LandRecords"

    fun libraryRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT_DIR_NAME)

    fun surveyDir(district: String, taluka: String, village: String, surveyNo: String): File =
        File(libraryRoot(), "$district/$taluka/$village/Survey ${surveyNo.trim()}")

    /** Hidden per-survey source folder for re-render/export. */
    fun sourceDir(surveyDir: File): File = File(surveyDir, ".source")

    /** Normalized token, e.g. "221/p" → "221_P", "845/અ" → "845_અ". */
    fun token(surveyNo: String): String =
        surveyNo.trim().uppercase().replace('/', '_').replace(" ", "")

    fun pdfName(type: RecordType): String = when (type) {
        RecordType.INTEGRATED -> "Integrated Record.pdf"
        RecordType.VF712 -> "VF-7-12.pdf"
        RecordType.DEEDS -> "Deeds.pdf"
        RecordType.IRCMS -> "iRCMS Cases.pdf"
    }
}
