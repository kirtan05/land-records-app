package com.landrecords.app.data.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-case iRCMS storage. Sits BESIDE the merged "iRCMS Cases.pdf" (which stays the quick
 * View-all): keeps every case's own detail PDF + (if disposed) order PDF plus a cases.json
 * manifest, so the Cases screen can list, re-select and re-export any subset without re-fetching.
 *
 * Layout (app-internal, sibling of LibraryWriter's ".../ircms.html" source):
 *   filesDir/source/<Dist>/<Tal>/<Vil>/<safeSurvey>/ircms/
 *       case_<sr>.pdf
 *       case_<sr>_order.pdf   (disposed cases only)
 *       cases.json
 *
 * safeSurvey matches LibraryWriter: surveyNo.trim() with '/' → '_'.
 */
object CasesStore {

    private const val MANIFEST = "cases.json"

    /** One case as recorded in cases.json (the manifest row). */
    data class CaseEntry(
        val sr: String,
        /**
         * iRCMS's own case id (the row button's `data-id`) — the case's identity under
         * docs/specs/2026-08-14-unified-db-and-autofetch-design.md §1.3, and the value the
         * desktop scraper dedupes on. Persisted so the migration can key existing cases
         * without re-fetching them. Empty for manifests written before this shipped.
         */
        val dataId: String = "",
        val caseNo: String,
        val status: String,     // "PENDING" | "DISPOSED" | ""
        val office: String,
        val dtv: String,
        val parties: String,
        val survno: String,
        val hasOrder: Boolean,
        val detailFile: String, // filename inside the ircms/ dir, or ""
        val orderFile: String,  // filename inside the ircms/ dir, or ""
        /** Per-case export colour ([com.landrecords.app.ui.marked.MarkColor] id), null = unmarked. */
        val mark: String? = null,
    )

    /** A freshly captured case awaiting write: its scraped metadata + its raw per-case PDFs. */
    data class CaseCapture(
        val sr: String,
        val dataId: String = "",
        val caseNo: String,
        val status: String,
        val office: String,
        val dtv: String,
        val parties: String,
        val survno: String,
        val detailPdf: ByteArray?, // the case-detail print (null/empty when the print failed)
        val orderPdf: ByteArray?,  // the disposed-case order (null when the case has none)
    )

    /** The ircms/ folder that holds this survey's per-case PDFs + manifest. */
    fun dir(context: Context, district: String, taluka: String, village: String, surveyNo: String): File {
        val safeSurvey = surveyNo.trim().replace('/', '_')
        return File(context.filesDir, "source/$district/$taluka/$village/$safeSurvey/ircms")
    }

    /**
     * Writes every captured case's detail (+ order) PDF and the cases.json manifest, replacing any
     * prior contents so a re-fetch overwrites cleanly. Purely additive to the merged library PDF.
     */
    suspend fun save(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        captures: List<CaseCapture>,
    ) = withContext(Dispatchers.IO) {
        val dir = dir(context, district, taluka, village, surveyNo).apply { mkdirs() }
        // Drop any prior per-case files + manifest — a stale sr set could otherwise linger.
        // §4: the manifest is rewritten, but the iRCMS cases themselves are NOT deleted.
        // They are content-addressed in BlobStore and re-projected below, so a re-fetch
        // tombstones LINKS, never bytes. Deleting here was a live data-loss risk: merging
        // with an older database could lose a document only one machine ever had.
        File(dir, MANIFEST).delete()

        val entries = ArrayList<CaseEntry>(captures.size)
        captures.forEachIndexed { idx, c ->
            val key = safe(c.sr.ifBlank { (idx + 1).toString() })
            var detailName = ""
            var orderName = ""
            c.detailPdf?.takeIf { it.isNotEmpty() }?.let {
                detailName = "case_$key.pdf"
                BlobStore.ingest(context, it, File(dir, detailName))
            }
            c.orderPdf?.takeIf { it.isNotEmpty() }?.let {
                orderName = "case_${key}_order.pdf"
                BlobStore.ingest(context, it, File(dir, orderName))
            }
            entries.add(
                CaseEntry(
                    sr = c.sr, dataId = c.dataId, caseNo = c.caseNo, status = c.status, office = c.office,
                    dtv = c.dtv, parties = c.parties, survno = c.survno,
                    hasOrder = orderName.isNotEmpty(), detailFile = detailName, orderFile = orderName,
                ),
            )
        }
        File(dir, MANIFEST).writeText(toJson(entries))
        android.util.Log.i("LR", "CasesStore: wrote ${entries.size} cases -> ${dir.absolutePath}")
    }

    /**
     * Reads cases.json for a survey (empty list if nothing has been captured yet), collapsing any
     * duplicate rows by [identity] — this cleans up manifests written before the capture-side dedup,
     * where the same case cross-listed under several survey numbers was stored more than once.
     */
    suspend fun read(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): List<CaseEntry> = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext emptyList()
        val all = runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
        val seen = HashSet<String>()
        all.filter { seen.add(identity(it.caseNo, it.parties, it.office, it.dtv)) }
    }

    /**
     * Visible identity of a case — the same case can be cross-listed under multiple survey numbers
     * (so it carries different iRCMS keys but identical case-no/parties/office/date). Dedup by this
     * to avoid capturing/showing it twice.
     */
    fun identity(caseNo: String, parties: String, office: String, dtv: String): String =
        listOf(caseNo, parties, office, dtv).joinToString("|") { it.replace(Regex("\\s+"), " ").trim().lowercase() }

    /** Absolute path to a stored case file (detail or order) by manifest filename, or null. */
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
     * Set (or clear, [mark] = null) the export colour on one case, rewriting just that row in the
     * manifest and leaving every other case + all per-case PDFs untouched. [itemId] is the case's
     * visible [identity] — every manifest row sharing it (a cross-listed case) gets the same mark.
     */
    suspend fun setMark(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        itemId: String, mark: String?,
    ) = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext
        val all = runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
        val updated = all.map {
            if (identity(it.caseNo, it.parties, it.office, it.dtv) == itemId) it.copy(mark = mark) else it
        }
        f.writeText(toJson(updated))
    }

    private fun safe(s: String): String =
        s.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "x" }

    private fun toJson(entries: List<CaseEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("sr", e.sr); put("caseNo", e.caseNo); put("status", e.status)
                    put("office", e.office); put("dtv", e.dtv); put("parties", e.parties)
                    put("dataId", e.dataId)
                    put("survno", e.survno); put("hasOrder", e.hasOrder)
                    put("detailFile", e.detailFile); put("orderFile", e.orderFile)
                    // Only write the colour when set — a missing key reads back as unmarked (null),
                    // which keeps manifests written before marking existed backward-compatible.
                    e.mark?.let { put("mark", it) }
                },
            )
        }
        return arr.toString()
    }

    private fun fromJson(text: String): List<CaseEntry> {
        val arr = JSONArray(text)
        val out = ArrayList<CaseEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CaseEntry(
                    sr = o.optString("sr"), caseNo = o.optString("caseNo"), status = o.optString("status"),
                    office = o.optString("office"), dtv = o.optString("dtv"), parties = o.optString("parties"),
                    dataId = o.optString("dataId"),
                    survno = o.optString("survno"), hasOrder = o.optBoolean("hasOrder"),
                    detailFile = o.optString("detailFile"), orderFile = o.optString("orderFile"),
                    mark = o.optString("mark").ifBlank { null }, // absent/blank → unmarked
                ),
            )
        }
        return out
    }
}
