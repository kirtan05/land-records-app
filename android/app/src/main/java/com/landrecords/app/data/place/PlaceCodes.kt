package com.landrecords.app.data.place

import android.content.Context
import androidx.core.content.edit
import com.landrecords.app.data.identity.Identity

/**
 * Cascade codes learned from AnyRoR itself, kept for next time.
 *
 * When a village is added through the AnyRoR flow the site has already told us its exact codes —
 * they are the `<option value>`s of the district/taluka/village dropdowns it just filled in. That
 * is strictly better information than any name match: it is the government's own identifier for
 * the place, and it is what `place_id` is defined as (spec §1.1).
 *
 * Without this, every future resolve re-derives the codes by comparing names against the bundled
 * catalogue — which works, but only for the two districts that ship (Anand, Kheda) and only when
 * the spellings are close enough. Remember the codes once and neither limit applies: a village in
 * an unbundled district resolves to a real `gj:` id instead of a provisional `gj?:` one, forever.
 *
 * Stored in SharedPreferences rather than the synced schema on purpose. It is a **cache of a
 * lookup**, not a fact about the land: the authoritative record is the `place` row the codes
 * produce, and losing this file costs a re-derivation, not data.
 */
object PlaceCodes {

    private const val PREFS = "place_codes"

    /** Name-independent enough to survive a spelling drift, but never across different places. */
    private fun key(district: String, taluka: String, village: String): String =
        listOf(district, taluka, village).joinToString("|") { CascadeMatch.nn(it) }

    /**
     * Remember the codes AnyRoR used for this place. Idempotent, and stored under BOTH the
     * English and Gujarati spellings the caller knows, so a later lookup in either script hits.
     */
    fun remember(
        context: Context,
        districtCode: String, talukaCode: String, villageCode: String,
        names: List<Triple<String, String, String>>,
    ) {
        if (districtCode.isBlank() || talukaCode.isBlank() || villageCode.isBlank()) return
        val value = "$districtCode/$talukaCode/$villageCode"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            for ((d, t, v) in names) {
                val k = key(d, t, v)
                if (k.length > 2) putString(k, value)
            }
        }
        android.util.Log.i("LR", "PlaceCodes: remembered $value for ${names.size} spelling(s)")
    }

    /** The codes previously learned for this place, or null. */
    fun lookup(context: Context, district: String, taluka: String, village: String): Triple<String, String, String>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(district, taluka, village), null) ?: return null
        val parts = raw.split("/")
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    /** The `place_id` these codes produce — the same value [Identity.placeId] would build. */
    fun placeIdFor(context: Context, district: String, taluka: String, village: String): String? =
        lookup(context, district, taluka, village)?.let { Identity.placeId(it.first, it.second, it.third) }

    /** Everything learned so far — shown in the migration report so coverage is never a mystery. */
    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .toMap()
}
