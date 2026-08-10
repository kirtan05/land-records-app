package com.landrecords.app.data.maps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One village's official map sheet on Drive. */
data class VillageMapEntry(
    val village: String,
    val driveFileId: String,
) {
    val viewUrl: String get() = "https://drive.google.com/file/d/$driveFileId/view"
}

private data class TalukaEntry(
    val name: String,
    val villages: List<VillageMapEntry>,
)

private data class DistrictEntry(
    val name: String,
    val talukas: List<TalukaEntry>,
)

/**
 * Read-only lookup over `assets/maps/villages.json` (33 districts, 261 talukas,
 * 16044 villages). [load] parses the asset once and caches it; every other
 * accessor reads the in-memory cache and is safe to call from composables once
 * [load] has completed. This is a browse-and-open feature — nothing here
 * mutates or persists.
 */
object VillageMaps {
    @Volatile
    private var districts: List<DistrictEntry> = emptyList()

    /** Parses the asset once (subsequent calls are a no-op). Call before using the other accessors. */
    suspend fun load(context: Context) {
        if (districts.isNotEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(this) {
                if (districts.isNotEmpty()) return@synchronized
                val text = context.assets.open("maps/villages.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val districtsArr = root.getJSONArray("districts")
                districts = buildList {
                    for (i in 0 until districtsArr.length()) {
                        val d = districtsArr.getJSONObject(i)
                        val talukasArr = d.getJSONArray("t")
                        val talukas = buildList {
                            for (j in 0 until talukasArr.length()) {
                                val t = talukasArr.getJSONObject(j)
                                val villagesArr = t.getJSONArray("v")
                                val villages = buildList {
                                    for (k in 0 until villagesArr.length()) {
                                        val v = villagesArr.getJSONArray(k)
                                        add(VillageMapEntry(village = v.getString(0), driveFileId = v.getString(1)))
                                    }
                                }
                                add(TalukaEntry(name = t.getString("n"), villages = villages))
                            }
                        }
                        add(DistrictEntry(name = d.getString("n"), talukas = talukas))
                    }
                }
            }
        }
    }

    fun districts(): List<String> = districts.map { it.name }

    fun talukas(district: String): List<String> =
        districts.firstOrNull { it.name == district }?.talukas?.map { it.name } ?: emptyList()

    fun villages(district: String, taluka: String): List<VillageMapEntry> =
        districts.firstOrNull { it.name == district }
            ?.talukas?.firstOrNull { it.name == taluka }
            ?.villages ?: emptyList()

    /** Case-insensitive substring match across every taluka in [district]; returns (taluka, entry). */
    fun search(district: String, query: String): List<Pair<String, VillageMapEntry>> {
        if (query.isBlank()) return emptyList()
        val d = districts.firstOrNull { it.name == district } ?: return emptyList()
        val q = query.trim()
        return buildList {
            d.talukas.forEach { t ->
                t.villages.forEach { v ->
                    if (v.village.contains(q, ignoreCase = true)) add(t.name to v)
                }
            }
        }
    }

    /** trim, lowercase, collapse whitespace runs, and treat '_'/'-' as spaces. */
    private fun normalize(s: String): String =
        s.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ")

    /**
     * Find the official map for a property's (district, taluka, village), used to decide whether
     * a village card gets a map affordance at all. Exact normalised match within the right
     * district+taluka wins; if the taluka itself doesn't match anything, fall back to a unique
     * normalised match anywhere in the district. Returns null (never a guess) if nothing matches
     * or a district-wide fallback is ambiguous — showing no map beats showing the wrong one.
     */
    fun find(district: String, taluka: String, village: String): VillageMapEntry? {
        val d = districts.firstOrNull { normalize(it.name) == normalize(district) } ?: return null
        val nv = normalize(village)
        val t = d.talukas.firstOrNull { normalize(it.name) == normalize(taluka) }
        if (t != null) {
            t.villages.firstOrNull { normalize(it.village) == nv }?.let { return it }
        }
        val candidates = d.talukas.flatMap { it.villages }.filter { normalize(it.village) == nv }.distinct()
        return candidates.singleOrNull()
    }
}
