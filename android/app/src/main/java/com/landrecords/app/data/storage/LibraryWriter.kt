package com.landrecords.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.landrecords.app.data.model.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes captured records into the visible, backup-able library tree:
 *   Documents/LandRecords/<District>/<Taluka>/<Village>/Survey <No>/
 * plus a hidden .source/ holding the raw HTML for offline re-render/export (§4D).
 *
 * API 29+ writes via MediaStore into the shared Documents collection (no storage
 * permission needed); older devices fall back to direct File writes.
 */
class LibraryWriter(private val context: Context) {

    data class Filed(val pdfPath: String, val sourcePath: String)

    suspend fun writeRecord(
        district: String,
        taluka: String,
        village: String,
        surveyNo: String,
        type: RecordType,
        pdfBytes: ByteArray,
        rawHtml: String,
    ): Filed = withContext(Dispatchers.IO) {
        // Sanitize the survey number for a folder name — a '/' would nest folders (845/અ).
        val safeSurvey = surveyNo.trim().replace('/', '_')
        val relDir = "$district/$taluka/$village/Survey $safeSurvey"
        val pdfName = LandRecordsStorage.pdfName(type)
        val htmlName = ".source/${type.name.lowercase()}.html"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pdfUri = writeViaMediaStore("$relDir", pdfName, "application/pdf", pdfBytes)
            val htmlUri = writeViaMediaStore("$relDir/.source", "${type.name.lowercase()}.html", "text/html", rawHtml.toByteArray())
            Filed(pdfUri, htmlUri)
        } else {
            val base = File(LandRecordsStorage.libraryRoot(), relDir)
            base.mkdirs()
            val pdf = File(base, pdfName).apply { writeBytes(pdfBytes) }
            val src = File(base, ".source").apply { mkdirs() }
            File(src, "${type.name.lowercase()}.html").writeText(rawHtml)
            Filed(pdf.absolutePath, File(src, htmlName).absolutePath)
        }
    }

    private fun writeViaMediaStore(relSubDir: String, name: String, mime: String, bytes: ByteArray): String {
        val resolver = context.contentResolver
        val fullRel = "${Environment.DIRECTORY_DOCUMENTS}/${LandRecordsStorage.ROOT_DIR_NAME}/$relSubDir"
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Replace any existing file at this logical path so re-fetch overwrites cleanly.
        deleteExisting(fullRel, name)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, fullRel)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create $name in $fullRel")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open $name for writing")
        return "$fullRel/$name"
    }

    private fun deleteExisting(fullRel: String, name: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$fullRel/", name),
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    resolver.delete(android.content.ContentUris.withAppendedId(collection, id), null, null)
                }
            }
        }
    }
}
