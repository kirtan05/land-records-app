package com.landrecords.app.data.place

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Crosswalk between the Gujarati and English spellings of a place, built from the shipped
 * `assets/cascade JSON files` catalogue (one file per district):
 *
 *   { "district": {"code","en","gu"},
 *     "talukas": [ {"code","en","gu","villages":[{"code","en","gu"}]} ] }
 *
 * The app stores a place three times over — the seeded rows hold English in
 * district/taluka/village and Gujarati in the *Gu fields, but a village added straight off the
 * AnyRoR cascade can end up with Gujarati in BOTH. Those two rows are the same real village and
 * must collapse to one library card, so every comparison has to be able to cross scripts.
 *
 * [canonical] is deliberately strict: it resolves a village only WITHIN a matched district and
 * taluka, and returns null the moment anything is unmatched or ambiguous. Merging two genuinely
 * different villages would corrupt real land records, so "don't know" always beats a guess.
 */
object PlaceNames {

    /** One catalogue village. [en] keeps the catalogue's own casing (sometimes "ANAND (CITY)"). */
    private data class Village(val en: String, val gu: String)

    private data class Taluka(val en: String, val gu: String, val villages: List<Village>)

    private data class District(val en: String, val gu: String, val talukas: List<Taluka>)

    @Volatile
    private var districts: List<District> = emptyList()

    /** True once the catalogue is in memory; [canonical] can only resolve after this. */
    val isLoaded: Boolean get() = districts.isNotEmpty()

    /** Parses every `assets/cascade JSON files` district file once; later calls are a no-op. */
    suspend fun load(context: Context) {
        if (districts.isNotEmpty()) return
        withContext(Dispatchers.IO) { loadBlocking(context) }
    }

    @Synchronized
    private fun loadBlocking(context: Context) {
        if (districts.isNotEmpty()) return
        val parsed = mutableListOf<District>()
        runCatching {
            val names = context.assets.list("cascade").orEmpty().filter { it.endsWith(".json") }
            for (name in names) {
                runCatching {
                    val text = context.assets.open("cascade/$name").bufferedReader().use { it.readText() }
                    val root = JSONObject(text)
                    // districts.json is a flat index with no talukas — not a crosswalk source.
                    val d = root.optJSONObject("district") ?: return@runCatching
                    val talukasArr = root.optJSONArray("talukas") ?: return@runCatching
                    val talukas = buildList {
                        for (i in 0 until talukasArr.length()) {
                            val t = talukasArr.getJSONObject(i)
                            val villagesArr = t.optJSONArray("villages")
                            val villages = buildList {
                                for (j in 0 until (villagesArr?.length() ?: 0)) {
                                    val v = villagesArr!!.getJSONObject(j)
                                    add(Village(v.optString("en"), v.optString("gu")))
                                }
                            }
                            add(Taluka(t.optString("en"), t.optString("gu"), villages))
                        }
                    }
                    parsed.add(District(d.optString("en"), d.optString("gu"), talukas))
                }.onFailure { android.util.Log.w("LR", "PlaceNames: skipped asset $name: ${it.message}") }
            }
        }.onFailure { android.util.Log.w("LR", "PlaceNames: catalogue load failed: ${it.message}") }
        if (parsed.isNotEmpty()) {
            districts = parsed
            android.util.Log.i("LR", "PlaceNames: loaded ${parsed.size} district(s) from assets/cascade")
        }
    }

    /**
     * trim, lower-case, collapse whitespace runs, and treat '_' and '-' as spaces — so "ANAND",
     * "anand" and "Anand" compare equal, and a stray separator doesn't defeat a match. The AnyRoR
     * "- <code>" suffix is stripped elsewhere (LandRecordsRepository.stripPlaceCodeSuffixes); this
     * only has to not be defeated by the hyphen it leaves behind mid-name.
     */
    fun normalize(s: String): String =
        s.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()

    /**
     * Resolve a (district, taluka, village) written in EITHER script to the catalogue's canonical
     * English triple, preserving the catalogue's own casing. Returns null when the catalogue isn't
     * loaded, when any level fails to match, or when the village is ambiguous inside its taluka.
     * Never guesses across talukas or districts.
     */
    fun canonical(district: String, taluka: String, village: String): Triple<String, String, String>? {
        val ds = districts
        if (ds.isEmpty()) return null
        val nd = normalize(district)
        val nt = normalize(taluka)
        val nv = normalize(village)
        if (nd.isEmpty() || nt.isEmpty() || nv.isEmpty()) return null

        val dMatches = ds.filter { normalize(it.en) == nd || normalize(it.gu) == nd }
        val d = dMatches.distinctBy { normalize(it.en) }.singleOrNull() ?: return null

        val tMatches = d.talukas.filter { normalize(it.en) == nt || normalize(it.gu) == nt }
        val t = tMatches.distinctBy { normalize(it.en) }.singleOrNull() ?: return null

        val vMatches = t.villages.filter { normalize(it.en) == nv || normalize(it.gu) == nv }
        val v = vMatches.distinctBy { normalize(it.en) }.singleOrNull() ?: return null

        return Triple(d.en, t.en, v.en)
    }
}
