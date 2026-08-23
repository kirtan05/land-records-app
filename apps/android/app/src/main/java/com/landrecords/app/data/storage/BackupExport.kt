package com.landrecords.app.data.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Backs up / exports every PDF in the visible library (under Documents/LandRecords) as a ZIP,
 * preserving the District/Taluka/Village/Survey folder tree, and hands it to a share sheet
 * (Drive, WhatsApp, etc.). The library also auto-backs-up via Android's Documents backup; this
 * is the one-tap "give me everything" export.
 */
object BackupExport {

    /** Returns the number of PDFs exported, or -1 on failure. Shares the ZIP on success. */
    suspend fun exportZip(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val outDir = File(context.cacheDir, "backup").apply { mkdirs() }
            val zipFile = File(outDir, "LandRecords_backup.zip")

            var count = 0
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("%${LandRecordsStorage.ROOT_DIR_NAME}%"),
                    null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val name = c.getString(nameCol) ?: continue
                        if (!name.endsWith(".pdf", ignoreCase = true)) continue
                        val rel = (c.getString(relCol) ?: "").trimEnd('/')
                        // Entry path relative to LandRecords/, e.g. "Anand/Umreth/Bharoda/Survey 221_p/…pdf"
                        val entry = (rel.substringAfter(LandRecordsStorage.ROOT_DIR_NAME + "/", "") + "/" + name)
                            .trimStart('/')
                            .ifBlank { name }
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                        runCatching {
                            zip.putNextEntry(ZipEntry("LandRecords/$entry"))
                            resolver.openInputStream(uri)?.use { it.copyTo(zip) }
                            zip.closeEntry()
                            count++
                        }
                    }
                }
            }
            if (count == 0) { zipFile.delete(); return@withContext 0 }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, "LandRecords_backup.zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Backup / export").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
            count
        } catch (e: Exception) {
            android.util.Log.w("LR", "backup export failed: ${e.message}")
            -1
        }
    }
}
