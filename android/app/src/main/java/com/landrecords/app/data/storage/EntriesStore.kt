package com.landrecords.app.data.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-entry (નોંધ / mutation-entry) storage for the INTEGRATED survey record. On the AnyRoR
 * detail page each entry number in the "Entry Details" grid is a link; the ones drawn in RED are
 * the old handwritten entries that have a scanned image behind them (blue ones are plain text,
 * already inside the integrated PDF). We capture every red entry's scan as its own little PDF so
 * the Entries screen can list, view, share and colour-mark any of them without re-fetching.
 *
 * The per-item analogue of [VfScansStore] / [CasesStore]. Layout (app-internal, sibling of ircms/
 * and vf712/):
 *   filesDir/source/<Dist>/<Tal>/<Vil>/<safeSurvey>/entries/
 *       entry_<safeNumber>.pdf
 *       entries.json
 *
 * safeSurvey matches LibraryWriter / CasesStore (surveyNo.trim(), '/' → '_'); safeNumber is the
 * entry number with any non-alphanumeric char → '_' (entry numbers can carry a trailing '*').
 */
object EntriesStore {

    private const val MANIFEST = "entries.json"

    /** One kept entry as recorded in entries.json (the manifest row). */
    data class EntryItem(
        val number: String,    // the નોંધ number as shown on the site (land data — never translated)
        val red: Boolean,      // true = old handwritten entry with a scan (the ones we download)
        val file: String,      // filename inside the entries/ dir, or "" when no scan was saved
        /** Per-entry export colour ([com.landrecords.app.ui.marked.MarkColor] id), null = unmarked. */
        val mark: String? = null,
    )

    /** A freshly captured entry awaiting write: its number + its assembled scan PDF bytes. */
    data class EntryCapture(
        val number: String,
        val red: Boolean,
        val pdf: ByteArray?,   // image-scan PDF bytes (null/empty when the fetch produced nothing)
    )

    /** The entries/ folder that holds this survey's per-entry PDFs + manifest. */
    fun dir(context: Context, district: String, taluka: String, village: String, surveyNo: String): File {
        val safeSurvey = surveyNo.trim().replace('/', '_')
        return File(context.filesDir, "source/$district/$taluka/$village/$safeSurvey/entries")
    }

    private fun safeNumber(number: String): String = number.trim().replace(Regex("[^A-Za-z0-9]"), "_")

    /**
     * Writes every captured entry's PDF and the entries.json manifest, replacing any prior contents
     * so a re-fetch overwrites cleanly. Purely additive to the integrated record PDF.
     */
    suspend fun save(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        captures: List<EntryCapture>,
    ) = withContext(Dispatchers.IO) {
        val dir = dir(context, district, taluka, village, surveyNo).apply { mkdirs() }
        // Drop any prior per-entry files + manifest — a stale entry set could otherwise linger.
        dir.listFiles()?.forEach { if (it.name.startsWith("entry_") || it.name == MANIFEST) it.delete() }

        val items = ArrayList<EntryItem>(captures.size)
        captures.forEach { c ->
            var fileName = ""
            c.pdf?.takeIf { it.isNotEmpty() }?.let {
                fileName = "entry_${safeNumber(c.number)}.pdf"
                File(dir, fileName).writeBytes(it)
            }
            items.add(EntryItem(number = c.number, red = c.red, file = fileName))
        }
        File(dir, MANIFEST).writeText(toJson(items))
        android.util.Log.i("LR", "EntriesStore: wrote ${items.size} entries -> ${dir.absolutePath}")
    }

    /** Reads entries.json for a survey (empty list if nothing has been captured yet). */
    suspend fun read(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): List<EntryItem> = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext emptyList()
        runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
    }

    /** How many entries are on record for this survey (0 if none) — drives the pill/count. */
    suspend fun count(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): Int = read(context, district, taluka, village, surveyNo).size

    /** Absolute path to a stored entry file by manifest filename, or null. */
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
     * Set (or clear, [mark] = null) the export colour on one entry, rewriting just that row in the
     * manifest and leaving every other entry + all per-entry PDFs untouched. [itemId] is the entry
     * [EntryItem.number].
     */
    suspend fun setMark(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        itemId: String, mark: String?,
    ) = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext
        val all = runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
        val updated = all.map { if (it.number == itemId) it.copy(mark = mark) else it }
        f.writeText(toJson(updated))
    }

    private fun toJson(items: List<EntryItem>): String {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject().apply {
                    put("number", e.number); put("red", e.red); put("file", e.file)
                    // Only write the colour when set — a missing key reads back as unmarked (null).
                    e.mark?.let { put("mark", it) }
                },
            )
        }
        return arr.toString()
    }

    private fun fromJson(text: String): List<EntryItem> {
        val arr = JSONArray(text)
        val out = ArrayList<EntryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                EntryItem(
                    number = o.optString("number"),
                    red = o.optBoolean("red", true),
                    file = o.optString("file"),
                    mark = o.optString("mark").ifBlank { null }, // absent/blank → unmarked
                ),
            )
        }
        return out
    }
}
