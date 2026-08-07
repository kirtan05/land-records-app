package com.landrecords.app.data.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens and shares the saved library files. Paths stored in Room are either an absolute
 * File path (pre-API 29) or a MediaStore relative path like
 * "Documents/LandRecords/…/Integrated Record.pdf" (API 29+) — resolve both to a content Uri.
 */
object LibraryAccess {

    fun contentUriFor(context: Context, path: String?): Uri? {
        if (path.isNullOrBlank()) { android.util.Log.w("LR", "contentUriFor: path is null/blank"); return null }
        if (path.startsWith("/")) {
            val file = File(path)
            if (!file.exists()) { android.util.Log.w("LR", "contentUriFor: file missing $path"); return null }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return null
        val relDir = path.substring(0, slash + 1) // MediaStore RELATIVE_PATH keeps the trailing slash
        val name = path.substring(slash + 1)
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relDir, name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        android.util.Log.w("LR", "contentUriFor: no MediaStore match for relDir='$relDir' name='$name'")
        return null
    }

    /** Read a saved file's bytes back (used to re-render a PDF from stored .source HTML). */
    fun readBytes(context: Context, path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("/")) {
            return runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
                .also { if (it == null) android.util.Log.w("LR", "readBytes: file missing $path") }
        }
        val uri = contentUriFor(context, path) ?: return null
        return runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }

    fun view(context: Context, pdfPath: String?): Boolean {
        val uri = contentUriFor(context, pdfPath) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /**
     * Share the PDF. When [shareName] is given, the file is copied to the cache under that
     * descriptive name (e.g. "Bharoda_221_p_Integrated_Survey_Record.pdf") so the receiving
     * app shows a meaningful filename instead of the generic library name.
     */
    fun share(context: Context, pdfPath: String?, shareName: String? = null): Boolean {
        val uri: Uri = if (!shareName.isNullOrBlank()) {
            val bytes = readBytes(context, pdfPath) ?: return false
            val safe = shareName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "record.pdf" }
            val out = File(context.cacheDir, "share").apply { mkdirs() }.let { File(it, safe) }
            runCatching { out.writeBytes(bytes) }.getOrElse { return false }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        } else {
            contentUriFor(context, pdfPath) ?: return false
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!shareName.isNullOrBlank()) putExtra(Intent.EXTRA_TITLE, shareName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share record").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    /**
     * Share freshly-built PDF [bytes] (e.g. an on-the-fly export of selected iRCMS cases) under a
     * descriptive [shareName]. Same cache/FileProvider pattern as [share], but the source is bytes
     * in memory rather than a stored library file.
     */
    fun shareBytes(context: Context, bytes: ByteArray, shareName: String): Boolean {
        if (bytes.isEmpty()) return false
        val safe = shareName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "cases.pdf" }
        val out = File(context.cacheDir, "share").apply { mkdirs() }.let { File(it, safe) }
        runCatching { out.writeBytes(bytes) }.getOrElse { return false }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, shareName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share cases").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }
}
