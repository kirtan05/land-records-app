package com.landrecords.app.data.jantri

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * One jantri (ASR-2011) rate row for a survey number.
 *
 * Only the per-acre 2011 figure is stored; everything else is derived, exactly as
 * the source document does it. Rates are a stamp-duty floor, NOT a market valuation.
 */
data class JantriRate(
    val landType: Int,          // 0 dry · 1 irrigated · 2 waste · 3 mineral
    val roadClass: Int,         // 0 unknown · 1 general · 2 district road · 3 highway
    val acre2011: Int,
) {
    val sqm2011: Int get() = (acre2011 / SQM_PER_ACRE).roundToInt()
    val acre2023: Long get() = acre2011.toLong() * 2
    val sqm2023: Int get() = sqm2011 * 2

    companion object { const val SQM_PER_ACRE = 4046.856422 }
}

/** The matched village, plus every rate row that mentions the survey number. */
data class JantriResult(
    val villageJantri: String,
    val talukaJantri: String,
    val villageGu: String,
    val matchQuality: String,
    val rates: List<JantriRate>,
) {
    /** True when the survey number sits in more than one priced row (~1% of cases). */
    val ambiguous: Boolean get() = rates.map { it.roadClass to it.acre2011 }.distinct().size > 1
}

/**
 * Read-only lookup over `assets/jantri/jantri.sqlite` (Anand + Kheda, ASR-2011).
 *
 * SQLite cannot read an asset in place, so the DB is copied to files/ on first use
 * and reused thereafter. Nothing here writes to the database.
 */
object JantriRates {
    private const val ASSET = "jantri/jantri.sqlite"
    private const val FILE = "jantri.sqlite"

    @Volatile private var db: SQLiteDatabase? = null

    private suspend fun database(context: Context): SQLiteDatabase? = withContext(Dispatchers.IO) {
        db ?: synchronized(this) {
            db ?: runCatching {
                val out = File(context.filesDir, FILE)
                if (!out.exists() || out.length() == 0L) {
                    context.assets.open(ASSET).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
                SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY)
                    .also { db = it }
            }.getOrNull()
        }
    }

    /** Normalised join key; Gujarati names are kept verbatim minus whitespace. */
    private fun key(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        return if (t.any { it in '઀'..'૿' }) t.replace(Regex("\\s+"), "")
        else t.uppercase().replace(Regex("[^A-Z0-9]"), "")
    }

    /** "221/p" -> 221 to "P"; "845" -> 845 to "". Returns null if there is no number. */
    private fun parseSurvey(surveyNo: String): Pair<Int, String>? {
        val m = Regex("(\\d+)\\s*(?:/\\s*([\\p{L}0-9]+))?").find(surveyNo) ?: return null
        val base = m.groupValues[1].toIntOrNull() ?: return null
        var suffix = m.groupValues[2].uppercase()
        // AnyRoR writes "paiki" as પૈકી / p / paiki; the jantri index stores PAIKI.
        if (suffix.startsWith("P") || suffix.startsWith("પ")) suffix = "PAIKI"
        return base to suffix
    }

    /**
     * Rates for one survey number, or null when the village or the number is not in
     * the jantri (an unknown value must render "—", never a guessed rate).
     *
     * [village] may be the Gujarati or the English name; both are indexed.
     */
    suspend fun lookup(
        context: Context,
        village: String,
        villageGu: String,
        taluka: String,
        surveyNo: String,
    ): JantriResult? = withContext(Dispatchers.IO) {
        val d = database(context) ?: return@withContext null
        val (base, suffix) = parseSurvey(surveyNo) ?: return@withContext null

        val keys = listOf(village, villageGu).map(::key).filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return@withContext null
        val ids = mutableListOf<Long>()
        d.rawQuery(
            "SELECT village_id FROM village_lookup WHERE key IN (${keys.joinToString(",") { "?" }})",
            keys.toTypedArray(),
        ).use { while (it.moveToNext()) ids += it.getLong(0) }
        if (ids.isEmpty()) return@withContext null

        // A name can repeat across talukas -- prefer the row whose taluka matches.
        val tk = key(taluka)
        var chosen = ids.first()
        var vJ = ""; var tJ = ""; var vGu = ""; var quality = ""
        for (id in ids) {
            d.rawQuery(
                "SELECT village, taluka, ircms_village_gu, match_quality, ircms_taluka " +
                    "FROM villages WHERE village_id=?", arrayOf(id.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    val matches = tk.isNotEmpty() &&
                        (key(c.getString(1)) == tk || key(c.getString(4) ?: "") == tk)
                    if (matches || vJ.isEmpty()) {
                        chosen = id
                        vJ = c.getString(0); tJ = c.getString(1)
                        vGu = c.getString(2) ?: ""; quality = c.getString(3) ?: ""
                    }
                }
            }
            if (key(tJ) == tk && tk.isNotEmpty()) break
        }

        val rates = mutableListOf<JantriRate>()
        val sql = if (suffix.isEmpty())
            "SELECT r.land_type, r.road_class, r.acre_2011 FROM sranges s " +
                "JOIN rates r ON r.row_id = s.row_id " +
                "WHERE s.village_id = ? AND ? BETWEEN s.lo AND s.hi"
        else
            "SELECT r.land_type, r.road_class, r.acre_2011 FROM ssub s " +
                "JOIN rates r ON r.row_id = s.row_id " +
                "WHERE s.village_id = ? AND s.base = ? AND s.suffix = ?"
        val args = if (suffix.isEmpty()) arrayOf(chosen.toString(), base.toString())
        else arrayOf(chosen.toString(), base.toString(), suffix)
        d.rawQuery(sql, args).use {
            while (it.moveToNext()) rates += JantriRate(it.getInt(0), it.getInt(1), it.getInt(2))
        }
        // A suffixed number with no entry of its own falls back to its base number,
        // which is how the source document treats subdivisions it does not list.
        if (rates.isEmpty() && suffix.isNotEmpty()) {
            d.rawQuery(
                "SELECT r.land_type, r.road_class, r.acre_2011 FROM sranges s " +
                    "JOIN rates r ON r.row_id = s.row_id " +
                    "WHERE s.village_id = ? AND ? BETWEEN s.lo AND s.hi",
                arrayOf(chosen.toString(), base.toString()),
            ).use {
                while (it.moveToNext()) rates += JantriRate(it.getInt(0), it.getInt(1), it.getInt(2))
            }
        }
        if (rates.isEmpty()) null
        else JantriResult(vJ, tJ, vGu, quality, rates.distinct().sortedBy { it.landType })
    }
}
