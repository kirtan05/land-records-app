package com.landrecords.app.data.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-deed storage for the registered Sub-registrar documents (સબ-રજીસ્ટ્રાર દસ્તાવેજ).
 *
 * Deeds are NOT a separate AnyRoR fetch: they are a section of the same type-8
 * InfoSurveyNoDetail page as the INTEGRATED record (table ContentPlaceHolder1_gvgarviProDet),
 * so the integrated capture reads them in the same pass (see
 * [com.landrecords.app.web.DeedsInjection.deedRowsJs]) and writes them here. The deed TABLE also
 * stays inside the Integrated PDF; this manifest is what the Deeds list screen reads.
 *
 * Only metadata is stored — the scanned deed FILES ("View Deed") are dead server-side ("Document
 * Record Not Found"), which is why there is no per-deed PDF here (unlike [EntriesStore]).
 *
 * Layout (app-internal, sibling of entries/):
 *   filesDir/source/<Dist>/<Tal>/<Vil>/<safeSurvey>/deeds/deeds.json
 */
object DeedsStore {

    private const val MANIFEST = "deeds.json"

    /** One registered document, with every party the grid listed for it. */
    data class Deed(
        val office: String,      // S.R.O - UMRETH
        val survey: String,      // as printed on the site (land data — never translated)
        val docYear: String,
        val docNo: String,
        val docDate: String,
        val amount: String,      // consideration amount, as printed
        val parties: List<Party>,
        /**
         * Filename of the scanned deed inside deeds/ ("" when none). Usually "" — the GARVI server
         * answers "Document Record Not Found" for most scans; when it does serve one, the TIFF is
         * converted to PDF ([com.landrecords.app.web.DeedsDownloader]) and stored here.
         */
        val file: String = "",
    ) {
        /** Stable identity of a document within a survey: office + year + number. */
        val id: String get() = "$office|$docYear|$docNo"
    }

    /** One party line: આપનાર (giver) / લેનાર (taker) + name. */
    data class Party(val type: String, val name: String)

    fun dir(context: Context, district: String, taluka: String, village: String, surveyNo: String): File {
        val safeSurvey = surveyNo.trim().replace('/', '_')
        return File(context.filesDir, "source/$district/$taluka/$village/$safeSurvey/deeds")
    }

    /**
     * Groups the raw per-party grid rows from [com.landrecords.app.web.DeedsInjection.deedRowsJs]
     * into one [Deed] per registered document. The site prints one ROW per party, so a single
     * document (e.g. 438/2022 with one આપનાર and two લેનાર) arrives as three rows.
     * Returns an empty list for '[]', blank input, or anything unparseable.
     */
    fun parse(json: String): List<Deed> {
        val arr = runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrNull() ?: return emptyList()
        val order = ArrayList<String>()
        val byId = HashMap<String, Deed>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val deed = Deed(
                office = o.optString("office").trim(),
                survey = o.optString("survey").trim(),
                docYear = o.optString("docYear").trim(),
                docNo = o.optString("docNo").trim(),
                docDate = o.optString("docDate").trim(),
                amount = o.optString("amount").trim(),
                parties = emptyList(),
                file = o.optString("file").trim(),
            )
            if (deed.docNo.isBlank() && deed.docYear.isBlank()) continue
            // Manifest form: parties already grouped under "parties". (Same parser reads both the
            // scraped per-party rows and deeds.json, so a re-read can never disagree with a write.)
            val grouped = o.optJSONArray("parties")
            if (grouped != null) {
                val list = (0 until grouped.length()).mapNotNull { k ->
                    grouped.optJSONObject(k)?.let { Party(it.optString("type").trim(), it.optString("name").trim()) }
                }
                if (byId[deed.id] == null) { order.add(deed.id); byId[deed.id] = deed.copy(parties = list) }
                continue
            }
            val party = Party(o.optString("partyType").trim(), o.optString("partyName").trim())
            val prev = byId[deed.id]
            if (prev == null) {
                order.add(deed.id)
                byId[deed.id] = deed.copy(parties = if (party.name.isBlank()) emptyList() else listOf(party))
            } else if (party.name.isNotBlank() && prev.parties.none { it.name == party.name && it.type == party.type }) {
                byId[deed.id] = prev.copy(parties = prev.parties + party)
            }
        }
        return order.mapNotNull { byId[it] }
    }

    /**
     * Replaces this survey's manifest and per-deed scans (a re-fetch overwrites cleanly).
     * [scans] maps a [Deed.id] to that document's converted PDF bytes — usually empty, since the
     * scans are normally unavailable server-side. Returns the deeds as written (with filenames).
     */
    suspend fun save(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        deeds: List<Deed>,
        scans: Map<String, ByteArray> = emptyMap(),
    ): List<Deed> = withContext(Dispatchers.IO) {
        val dir = dir(context, district, taluka, village, surveyNo).apply { mkdirs() }
        // §4: the manifest is rewritten, but the deeds themselves are NOT deleted.
        // They are content-addressed in BlobStore and re-projected below, so a re-fetch
        // tombstones LINKS, never bytes. Deleting here was a live data-loss risk: merging
        // with an older database could lose a document only one machine ever had.
        File(dir, MANIFEST).delete()
        val written = deeds.map { d ->
            val bytes = scans[d.id]?.takeIf { it.isNotEmpty() } ?: return@map d.copy(file = "")
            val name = "deed_${safeName(d)}.pdf"
            BlobStore.ingest(context, bytes, File(dir, name))
            d.copy(file = name)
        }
        File(dir, MANIFEST).writeText(toJson(written))
        android.util.Log.i(
            "LR",
            "DeedsStore: wrote ${written.size} deed(s), ${written.count { it.file.isNotBlank() }} scan(s) -> ${dir.absolutePath}",
        )
        written
    }

    private fun safeName(d: Deed): String =
        "${d.docNo}_${d.docYear}".replace(Regex("[^A-Za-z0-9]"), "_").ifBlank { "doc" }

    /** Absolute path to a stored deed scan by manifest filename, or null when there is none. */
    fun filePath(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        name: String,
    ): String? {
        if (name.isBlank()) return null
        val f = File(dir(context, district, taluka, village, surveyNo), name)
        return if (f.exists()) f.absolutePath else null
    }

    /** Reads deeds.json (empty when the survey has never been fetched, or holds no deeds). */
    suspend fun read(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): List<Deed> = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext emptyList()
        runCatching { parse(f.readText()) }.getOrDefault(emptyList())
    }

    /** How many registered documents are on record (0 = none found, or never fetched). */
    suspend fun count(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): Int = read(context, district, taluka, village, surveyNo).size

    private fun toJson(deeds: List<Deed>): String {
        val arr = JSONArray()
        for (d in deeds) {
            val parties = JSONArray()
            d.parties.forEach { parties.put(JSONObject().apply { put("type", it.type); put("name", it.name) }) }
            arr.put(
                JSONObject().apply {
                    put("office", d.office); put("survey", d.survey)
                    put("docYear", d.docYear); put("docNo", d.docNo); put("docDate", d.docDate)
                    put("amount", d.amount); put("parties", parties)
                    if (d.file.isNotBlank()) put("file", d.file)
                },
            )
        }
        return arr.toString()
    }
}
