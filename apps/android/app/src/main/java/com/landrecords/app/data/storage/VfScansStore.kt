package com.landrecords.app.data.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-scan OLD-SCANNED VF-7/12 storage. Sits BESIDE the merged "VF-7-12.pdf" (which stays the quick
 * View-all): keeps every year-wise scan's own PDF + a vf712.json manifest, so the Scans screen can
 * list, re-select and re-export any subset of years without re-fetching.
 *
 * The per-scan analogue of [CasesStore]. Layout (app-internal, sibling of the ircms/ dir):
 *   filesDir/source/<Dist>/<Tal>/<Vil>/<safeSurvey>/vf712/
 *       scan_<i>.pdf          (i = 1-based chronological index, oldest→newest)
 *       vf712.json
 *
 * safeSurvey matches LibraryWriter / CasesStore: surveyNo.trim() with '/' → '_'.
 */
object VfScansStore {

    private const val MANIFEST = "vf712.json"

    /** One kept scan as recorded in vf712.json (the manifest row). */
    data class ScanEntry(
        val index: Int,        // 1-based chronological position (oldest→newest)
        val period: String,    // year span like "1993-2004" (land data — never translated)
        val thok: String,
        val block: String,     // survey / block number
        val oldSurvey: String,
        val status: String,    // grid "PDF status" — typically "Ok"
        val file: String,      // filename inside the vf712/ dir, or ""
        /** Per-scan export colour ([com.landrecords.app.ui.marked.MarkColor] id), null = unmarked. */
        val mark: String? = null,
    )

    /** A freshly captured scan awaiting write: its grid metadata + its raw scan PDF bytes. */
    data class ScanCapture(
        val index: Int,
        val period: String,
        val thok: String,
        val block: String,
        val oldSurvey: String,
        val status: String,
        val pdf: ByteArray?,   // the genuine scan bytes (null/empty when weeded or the fetch failed)
    )

    /** The vf712/ folder that holds this survey's per-scan PDFs + manifest. */
    fun dir(context: Context, district: String, taluka: String, village: String, surveyNo: String): File {
        val safeSurvey = surveyNo.trim().replace('/', '_')
        return File(context.filesDir, "source/$district/$taluka/$village/$safeSurvey/vf712")
    }

    /**
     * Writes every captured scan's PDF and the vf712.json manifest, replacing any prior contents so
     * a re-fetch overwrites cleanly. Purely additive to the merged library PDF.
     */
    suspend fun save(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        captures: List<ScanCapture>,
    ) = withContext(Dispatchers.IO) {
        val dir = dir(context, district, taluka, village, surveyNo).apply { mkdirs() }
        // Drop any prior per-scan files + manifest — a stale year set could otherwise linger.
        // §4: the manifest is rewritten, but the VF-7/12 scans themselves are NOT deleted.
        // They are content-addressed in BlobStore and re-projected below, so a re-fetch
        // tombstones LINKS, never bytes. Deleting here was a live data-loss risk: merging
        // with an older database could lose a document only one machine ever had.
        File(dir, MANIFEST).delete()

        val entries = ArrayList<ScanEntry>(captures.size)
        captures.forEach { c ->
            var fileName = ""
            c.pdf?.takeIf { it.isNotEmpty() }?.let {
                fileName = "scan_${c.index}.pdf"
                BlobStore.ingest(context, it, File(dir, fileName))
            }
            entries.add(
                ScanEntry(
                    index = c.index, period = c.period, thok = c.thok, block = c.block,
                    oldSurvey = c.oldSurvey, status = c.status, file = fileName,
                ),
            )
        }
        File(dir, MANIFEST).writeText(toJson(entries))
        android.util.Log.i("LR", "VfScansStore: wrote ${entries.size} scans -> ${dir.absolutePath}")
    }

    /** Reads vf712.json for a survey (empty list if nothing has been captured yet). */
    suspend fun read(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): List<ScanEntry> = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext emptyList()
        runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
    }

    /** Absolute path to a stored scan file by manifest filename, or null. */
    fun filePath(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        name: String,
    ): String? {
        if (name.isBlank()) return null
        val f = File(dir(context, district, taluka, village, surveyNo), name)
        return if (f.exists()) f.absolutePath else null
    }

    /**
     * Set (or clear, [mark] = null) the export colour on one scan, rewriting just that row in the
     * manifest and leaving every other scan + all per-scan PDFs untouched. [itemId] is the scan's
     * 1-based [ScanEntry.index] as a string.
     */
    suspend fun setMark(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        itemId: String, mark: String?,
    ) = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext
        val all = runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
        val updated = all.map { if (it.index.toString() == itemId) it.copy(mark = mark) else it }
        f.writeText(toJson(updated))
    }

    /**
     * Remove every scan whose old-survey number matches [oldToken] (compared by canonical token,
     * so `174/1` and `174/૧` are the same) from the manifest and delete its file. Returns how many
     * were removed. The blob-store bytes are NOT touched — they are content-addressed, so if the
     * user changes his mind the scan is re-projected for free.
     */
    suspend fun removeByOldSurvey(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        oldToken: String,
    ): Int = withContext(Dispatchers.IO) {
        val d = dir(context, district, taluka, village, surveyNo)
        val f = File(d, MANIFEST)
        if (!f.exists()) return@withContext 0
        val all = runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
        val (drop, keep) = all.partition {
            com.landrecords.app.data.identity.Identity.surveyToken(it.oldSurvey) == oldToken
        }
        if (drop.isEmpty()) return@withContext 0
        for (s in drop) if (s.file.isNotBlank()) runCatching { File(d, s.file).delete() }
        f.writeText(toJson(keep))
        drop.size
    }

    /** Distinct old-survey numbers in this survey's manifest, each with its scan count. */
    suspend fun oldSurveyGroups(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        read(context, district, taluka, village, surveyNo)
            .groupBy { it.oldSurvey.ifBlank { "—" } }
            .map { (old, scans) -> old to scans.size }
            .sortedWith(com.landrecords.app.data.sync.OldSurveyMatcher.byOldSurvey { it.first })
    }

    private fun toJson(entries: List<ScanEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("index", e.index); put("period", e.period); put("thok", e.thok)
                    put("block", e.block); put("oldSurvey", e.oldSurvey); put("status", e.status)
                    put("file", e.file)
                    // Only write the colour when set — a missing key reads back as unmarked (null),
                    // which keeps manifests written before marking existed backward-compatible.
                    e.mark?.let { put("mark", it) }
                },
            )
        }
        return arr.toString()
    }

    private fun fromJson(text: String): List<ScanEntry> {
        val arr = JSONArray(text)
        val out = ArrayList<ScanEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ScanEntry(
                    index = o.optInt("index", i + 1), period = o.optString("period"),
                    thok = o.optString("thok"), block = o.optString("block"),
                    oldSurvey = o.optString("oldSurvey"), status = o.optString("status"),
                    file = o.optString("file"),
                    mark = o.optString("mark").ifBlank { null }, // absent/blank → unmarked
                ),
            )
        }
        return out
    }
}
