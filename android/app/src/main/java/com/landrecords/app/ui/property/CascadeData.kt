package com.landrecords.app.ui.property

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/** One district / taluka / village entry from the bundled AnyRoR/iRCMS cascade. */
data class GeoItem(val code: String, val en: String, val gu: String)

/** A taluka plus its villages (null until that taluka's cascade has been scraped). */
data class TalukaItem(val code: String, val en: String, val gu: String, val villages: List<GeoItem>?)

/**
 * Reads the bundled cascade under `assets/cascade/`.
 *
 * - `districts.json` → all 34 Gujarat districts.
 * - `<code>.json` (generic drop-in, e.g. `15.json`) → that district's talukas, each
 *   with a `villages` array once fetched (`null` means not yet scraped). Anand ships
 *   today as `anand_15.json`; new districts can be dropped in as plain `<code>.json`.
 *
 * Results are parsed lazily and cached for the app's lifetime so re-opening a picker
 * doesn't re-read the asset. Every lookup returns `null` (or empty) when no bundled
 * data exists for that district/taluka, so callers can fall back to a typed field.
 */
object CascadeData {

    private var districtsCache: List<GeoItem>? = null
    private val talukaCache = HashMap<String, List<TalukaItem>?>()

    /** All 34 districts; empty only if the asset is missing/unreadable. */
    fun districts(context: Context): List<GeoItem> {
        districtsCache?.let { return it }
        val json = readAsset(context, "cascade/districts.json")
        val list = json?.let { parseGeoArray(JSONObject(it).optJSONArray("districts")) } ?: emptyList()
        districtsCache = list
        return list
    }

    /** The talukas bundled for [districtCode], or `null` when none ship for it. */
    fun talukasFor(context: Context, districtCode: String): List<TalukaItem>? {
        if (talukaCache.containsKey(districtCode)) return talukaCache[districtCode]
        val json = candidateFiles(districtCode).firstNotNullOfOrNull { readAsset(context, it) }
        val result = json?.let { parseTalukas(it) }
        talukaCache[districtCode] = result
        return result
    }

    /** The villages for one taluka, or `null` when that taluka hasn't been scraped. */
    fun villagesFor(context: Context, districtCode: String, talukaCode: String): List<GeoItem>? =
        talukasFor(context, districtCode)?.firstOrNull { it.code == talukaCode }?.villages

    /** Files to try for a district's talukas: the generic `<code>.json` first, then known names. */
    private fun candidateFiles(code: String): List<String> = buildList {
        add("cascade/$code.json")
        if (code == "15") add("cascade/anand_15.json")
    }

    private fun parseTalukas(json: String): List<TalukaItem> {
        val arr = JSONObject(json).optJSONArray("talukas") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // optJSONArray returns null for both a missing key and an explicit JSON null.
            val villages = o.optJSONArray("villages")?.let { parseGeoArray(it) }
            TalukaItem(o.getString("code"), o.getString("en"), o.getString("gu"), villages)
        }
    }

    private fun parseGeoArray(arr: org.json.JSONArray?): List<GeoItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GeoItem(o.getString("code"), o.getString("en"), o.getString("gu"))
        }
    }

    private fun readAsset(context: Context, path: String): String? =
        try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            null
        }
}
