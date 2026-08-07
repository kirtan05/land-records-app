package com.landrecords.app.data.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.model.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Pre-loads a fresh install with every record already fetched on the reference device, so the app
 * opens fully populated — View/Share, the iRCMS Cases screen, the VF-7/12 year viewer and export
 * all work, not just the cards.
 *
 * The bundle is baked into APK **assets** ("seed/…"), staged by scratchpad/stage_seed.py:
 *   seed/manifest.json      — one row per record {district,taluka,village,surveyNo,safeSurvey,type,docCount,pdfName,pdf}
 *   seed/lib/<i>.pdf        — that record's library PDF (iRCMS/VF-7-12 losslessly re-merged small;
 *                             others verbatim). ASCII-indexed so no space/Gujarati asset names.
 *   seed/source_index.json  — [{rel,asset}] mapping every .source file to its ASCII asset
 *   seed/src/<j>            — the raw per-item source (cases.json + case_*.pdf, vf712.json + scan_*.pdf, html)
 *
 * ADDITIVE + idempotent: a marker stops re-runs; per file/record it only fills a GAP — it never
 * overwrites a PDF the user has already fetched or newer source already on disk. So it fully
 * populates dad's clean install, and on an existing phone only fills what's missing (e.g. the
 * 222/2/p iRCMS merge the OOM crash never wrote).
 */
object SeedImporter {

    private const val MARKER = "seed_imported_v2"

    suspend fun run(
        context: Context,
        repo: LandRecordsRepository,
        writer: LibraryWriter, // kept for signature parity with the caller; restore writes MediaStore directly
    ) = withContext(Dispatchers.IO) {
        val marker = File(context.filesDir, MARKER)
        if (marker.exists()) return@withContext
        val assets = context.assets

        val manifest = try {
            JSONArray(assets.open("seed/manifest.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            android.util.Log.i("LR", "SeedImporter: no seed bundle in assets — skipping (${e.message})")
            return@withContext
        }

        // 1) Restore the raw .source tree (only files not already present) so the per-item screens work.
        var srcOk = 0
        runCatching {
            val idx = JSONArray(assets.open("seed/source_index.json").bufferedReader().use { it.readText() })
            for (i in 0 until idx.length()) {
                val o = idx.getJSONObject(i)
                val out = File(context.filesDir, "source/${o.getString("rel")}")
                if (out.exists() && out.length() > 0) continue
                out.parentFile?.mkdirs()
                assets.open("seed/${o.getString("asset")}").use { inp ->
                    out.outputStream().use { inp.copyTo(it) }
                }
                srcOk++
            }
        }.onFailure { android.util.Log.w("LR", "SeedImporter: source restore issue: ${it.message}") }

        // 2) File each record's library PDF (gap-fill only) and link it into Room.
        var ok = 0
        var skipped = 0
        for (i in 0 until manifest.length()) {
            val o = manifest.getJSONObject(i)
            val district = o.getString("district")
            val taluka = o.getString("taluka")
            val village = o.getString("village")
            val surveyNo = o.getString("surveyNo")
            val safeSurvey = o.getString("safeSurvey")
            val pdfName = o.getString("pdfName")
            val docCount = o.optInt("docCount", 1)
            val type = runCatching { RecordType.valueOf(o.getString("type")) }.getOrNull() ?: run { skipped++; continue }

            val surveyId = repo.findSurveyId(village, surveyNo)
            if (surveyId == null) { skipped++; continue }

            // Don't clobber a record the user has already fetched for real.
            val existing = repo.recordFor(surveyId, type)
            if (existing?.pdfPath != null) { skipped++; continue }

            val bytes = runCatching { assets.open("seed/${o.getString("pdf")}").use { it.readBytes() } }.getOrNull()
            if (bytes == null || bytes.isEmpty()) { skipped++; continue }

            val pdfPath = runCatching {
                writeLibraryPdf(context, district, taluka, village, safeSurvey, pdfName, bytes)
            }.getOrElse {
                android.util.Log.w("LR", "SeedImporter: MediaStore write failed for $surveyNo/$type: ${it.message}")
                skipped++; continue
            }
            val sourcePath = File(
                context.filesDir,
                "source/$district/$taluka/$village/$safeSurvey/${type.name.lowercase()}.html",
            ).absolutePath
            repo.saveFetchedRecord(surveyId, type, docCount, pdfPath, sourcePath)
            ok++
        }

        android.util.Log.i("LR", "SeedImporter: sourceFiles=$srcOk records filed=$ok skipped=$skipped")
        marker.writeText("done")
    }

    /**
     * Writes [bytes] into the visible library at
     * Documents/LandRecords/<D>/<T>/<V>/Survey <safe>/<pdfName>, replacing any stale file at that
     * logical path. Mirrors [LibraryWriter]'s MediaStore path so View/Share resolve identically.
     * Returns the stored relative path (what Room persists as pdfPath).
     */
    private fun writeLibraryPdf(
        context: Context,
        district: String, taluka: String, village: String, safeSurvey: String, pdfName: String,
        bytes: ByteArray,
    ): String {
        val relDir = "$district/$taluka/$village/Survey $safeSurvey"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val base = File(LandRecordsStorage.libraryRoot(), relDir).apply { mkdirs() }
            return File(base, pdfName).apply { writeBytes(bytes) }.absolutePath
        }
        val resolver = context.contentResolver
        val fullRel = "${Environment.DIRECTORY_DOCUMENTS}/${LandRecordsStorage.ROOT_DIR_NAME}/$relDir"
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$fullRel/", pdfName),
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    resolver.delete(ContentUris.withAppendedId(collection, c.getLong(idCol)), null, null)
                }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, pdfName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, fullRel)
        }
        val uri = resolver.insert(collection, values) ?: error("insert failed for $pdfName in $fullRel")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("openOutputStream failed for $pdfName")
        return "$fullRel/$pdfName"
    }
}
