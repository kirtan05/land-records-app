package com.landrecords.app.data.storage

import android.content.Context
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.model.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * One-time import of the records already generated on the desktop tool. The canonical PDFs +
 * a manifest.json are staged into the app's INTERNAL files dir (filesDir/seed) via
 * `adb ... run-as`; on the next launch this writes each one into the visible library through
 * [LibraryWriter] (identical to a real fetch) and links it into Room so View/Share work.
 * (Internal storage is used because adb-pushed files in Android/data are shell-owned and the
 * app can't read them through the FUSE layer.)
 *
 * If the seed isn't present it does nothing AND leaves no marker, so a later launch — after the
 * files are pushed — still imports. After a successful pass it drops the ~60MB of staged copies.
 */
object SeedImporter {

    private const val MARKER = "seed_imported_v1"

    suspend fun run(
        context: Context,
        repo: LandRecordsRepository,
        writer: LibraryWriter,
    ) = withContext(Dispatchers.IO) {
        val marker = File(context.filesDir, MARKER)
        if (marker.exists()) return@withContext

        val seedDir = File(context.filesDir, "seed")
        val manifest = File(seedDir, "manifest.json")
        if (!manifest.exists()) {
            android.util.Log.i("LR", "SeedImporter: no seed manifest at ${manifest.absolutePath} — skipping")
            return@withContext
        }

        val arr = try {
            JSONArray(manifest.readText())
        } catch (e: Exception) {
            android.util.Log.w("LR", "SeedImporter: bad manifest: ${e.message}")
            return@withContext
        }

        var ok = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val district = o.getString("district")
            val taluka = o.getString("taluka")
            val village = o.getString("village")
            val surveyNo = o.getString("surveyNo")
            val typeName = o.getString("type")
            val docCount = o.optInt("docCount", 1)
            val file = File(seedDir, o.getString("file"))

            val type = runCatching { RecordType.valueOf(typeName) }.getOrNull()
            if (type == null || !file.exists()) {
                skipped++
                android.util.Log.w("LR", "SeedImporter: skip $surveyNo/$typeName (type=$type exists=${file.exists()})")
                continue
            }
            val surveyId = repo.findSurveyId(village, surveyNo)
            if (surveyId == null) {
                skipped++
                android.util.Log.w("LR", "SeedImporter: no survey row for $village '$surveyNo'")
                continue
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) { skipped++; continue }
            val filed = runCatching {
                writer.writeRecord(district, taluka, village, surveyNo, type, bytes, rawHtml = "")
            }.getOrElse {
                skipped++
                android.util.Log.w("LR", "SeedImporter: writeRecord failed for $surveyNo/$typeName: ${it.message}")
                null
            } ?: continue
            repo.saveFetchedRecord(surveyId, type, docCount, filed.pdfPath, filed.sourcePath)
            ok++
        }

        android.util.Log.i("LR", "SeedImporter: imported=$ok skipped=$skipped")
        marker.writeText("done")
        // Reclaim the staged copies — the records now live in the library.
        runCatching {
            seedDir.listFiles()?.forEach { it.delete() }
            seedDir.delete()
        }
        Unit
    }
}
