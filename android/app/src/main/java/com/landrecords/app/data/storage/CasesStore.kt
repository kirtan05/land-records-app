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
        val caseNo: String,
        val status: String,     // "PENDING" | "DISPOSED" | ""
        val office: String,
        val dtv: String,
        val parties: String,
        val survno: String,
        val hasOrder: Boolean,
        val detailFile: String, // filename inside the ircms/ dir, or ""
        val orderFile: String,  // filename inside the ircms/ dir, or ""
    )

    /** A freshly captured case awaiting write: its scraped metadata + its raw per-case PDFs. */
    data class CaseCapture(
        val sr: String,
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
        dir.listFiles()?.forEach { if (it.name.startsWith("case_") || it.name == MANIFEST) it.delete() }

        val entries = ArrayList<CaseEntry>(captures.size)
        captures.forEachIndexed { idx, c ->
            val key = safe(c.sr.ifBlank { (idx + 1).toString() })
            var detailName = ""
            var orderName = ""
            c.detailPdf?.takeIf { it.isNotEmpty() }?.let {
                detailName = "case_$key.pdf"
                File(dir, detailName).writeBytes(it)
            }
            c.orderPdf?.takeIf { it.isNotEmpty() }?.let {
                orderName = "case_${key}_order.pdf"
                File(dir, orderName).writeBytes(it)
            }
            entries.add(
                CaseEntry(
                    sr = c.sr, caseNo = c.caseNo, status = c.status, office = c.office,
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

    private fun safe(s: String): String =
        s.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "x" }

    private fun toJson(entries: List<CaseEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("sr", e.sr); put("caseNo", e.caseNo); put("status", e.status)
                    put("office", e.office); put("dtv", e.dtv); put("parties", e.parties)
                    put("survno", e.survno); put("hasOrder", e.hasOrder)
                    put("detailFile", e.detailFile); put("orderFile", e.orderFile)
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
                    survno = o.optString("survno"), hasOrder = o.optBoolean("hasOrder"),
                    detailFile = o.optString("detailFile"), orderFile = o.optString("orderFile"),
                ),
            )
        }
        return out
    }
}
