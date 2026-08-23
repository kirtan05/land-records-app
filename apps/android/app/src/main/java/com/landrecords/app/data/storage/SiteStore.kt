package com.landrecords.app.data.storage

import android.content.Context
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Per-survey ON-SITE CAPTURE storage. When dad is standing on a plot he can save the site's GPS
 * fix and take a geo-tagged photo; both are filed under that survey as a site.json manifest plus the
 * captured JPEGs. LOCAL only — nothing here rides the sync/identity layer (site visits are notes he
 * takes in the field, not scraped land records).
 *
 * Layout (app-internal, sibling of vf712/, ircms/ under the same survey dir):
 *   filesDir/source/<Dist>/<Tal>/<Vil>/<safeSurvey>/site/
 *       photo_<ts>.jpg
 *       site.json
 *
 * safeSurvey matches VfScansStore / CasesStore: surveyNo.trim() with '/' → '_'.
 */
object SiteStore {

    private const val MANIFEST = "site.json"

    /**
     * One recorded field visit. [lat]/[lng]/[accuracy] are null when no fix was available (a photo
     * can still be captured with location off) — the UI renders those as "—". [photo] is a filename
     * inside the site/ dir, or "" for a location-only visit.
     */
    data class SiteVisit(
        val ts: Long,            // capture time, epoch millis (passed in by the caller)
        val lat: Double?,        // degrees, null = no fix
        val lng: Double?,        // degrees, null = no fix
        val accuracy: Double?,   // horizontal accuracy in metres, null = unknown
        val note: String = "",
        val photo: String = "",  // filename inside the site/ dir, or ""
    )

    /** The site/ folder that holds this survey's photos + manifest. */
    fun dir(context: Context, district: String, taluka: String, village: String, surveyNo: String): File {
        val safeSurvey = surveyNo.trim().replace('/', '_')
        return File(context.filesDir, "source/$district/$taluka/$village/$safeSurvey/site")
    }

    /**
     * The File a fresh photo should be captured into (NOT created — the camera writes it). Kept
     * stable via [ts] so the manifest row and the file agree even if the capture is cancelled.
     */
    fun newPhotoFile(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        ts: Long,
    ): File {
        val d = dir(context, district, taluka, village, surveyNo).apply { mkdirs() }
        return File(d, "photo_$ts.jpg")
    }

    /** Absolute path to a stored photo by manifest filename, or null if it isn't there. */
    fun filePath(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        name: String,
    ): String? {
        if (name.isBlank()) return null
        val f = File(dir(context, district, taluka, village, surveyNo), name)
        return if (f.exists()) f.absolutePath else null
    }

    /** Append one visit to the manifest, creating it on first write. Purely additive. */
    suspend fun append(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        visit: SiteVisit,
    ) = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo).apply { mkdirs() }, MANIFEST)
        val all = if (f.exists()) runCatching { fromJson(f.readText()) }.getOrDefault(emptyList()) else emptyList()
        f.writeText(toJson(all + visit))
        android.util.Log.i("LR", "SiteStore: appended visit ts=${visit.ts} -> ${f.absolutePath}")
    }

    /** Read every visit, newest first (empty list if nothing captured yet). */
    suspend fun read(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
    ): List<SiteVisit> = withContext(Dispatchers.IO) {
        val f = File(dir(context, district, taluka, village, surveyNo), MANIFEST)
        if (!f.exists()) return@withContext emptyList()
        runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
            .sortedByDescending { it.ts }
    }

    /** Delete one visit (matched by [ts]) and its photo file. Returns true if a row was removed. */
    suspend fun delete(
        context: Context,
        district: String, taluka: String, village: String, surveyNo: String,
        ts: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val d = dir(context, district, taluka, village, surveyNo)
        val f = File(d, MANIFEST)
        if (!f.exists()) return@withContext false
        val all = runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
        val (drop, keep) = all.partition { it.ts == ts }
        if (drop.isEmpty()) return@withContext false
        for (v in drop) if (v.photo.isNotBlank()) runCatching { File(d, v.photo).delete() }
        f.writeText(toJson(keep))
        true
    }

    /**
     * Write GPS EXIF tags (lat/lng/accuracy timestamp) into a captured JPEG in place, using the
     * built-in [android.media.ExifInterface]. Coordinates are written as the rational DMS strings
     * ExifInterface has understood since API 24 (setLatLong itself only landed in API 29). Call this
     * off the main thread — it reads and rewrites the whole file.
     */
    fun writeExifGps(path: String, lat: Double, lng: Double, tsMillis: Long) {
        runCatching {
            val exif = ExifInterface(path)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDmsRational(lat))
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDmsRational(lng))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lng >= 0) "E" else "W")

            val date = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val time = SimpleDateFormat("HH/1,mm/1,ss/1", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, date.format(Date(tsMillis)))
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, time.format(Date(tsMillis)))
            exif.saveAttributes()
        }.onFailure { android.util.Log.w("LR", "SiteStore.writeExifGps failed: ${it.message}") }
    }

    /** Degrees → "deg/1,min/1,sec/1000" rational triplet (magnitude only; the ref tag carries sign). */
    private fun toDmsRational(coord: Double): String {
        val a = abs(coord)
        val deg = a.toInt()
        val minFull = (a - deg) * 60.0
        val min = minFull.toInt()
        val secThousandths = ((minFull - min) * 60.0 * 1000.0).toLong()
        return "$deg/1,$min/1,$secThousandths/1000"
    }

    private fun toJson(entries: List<SiteVisit>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("ts", e.ts)
                    // Only write coordinates when known — a missing key reads back as null ("—").
                    e.lat?.let { put("lat", it) }
                    e.lng?.let { put("lng", it) }
                    e.accuracy?.let { put("accuracy", it) }
                    if (e.note.isNotBlank()) put("note", e.note)
                    if (e.photo.isNotBlank()) put("photo", e.photo)
                },
            )
        }
        return arr.toString()
    }

    private fun fromJson(text: String): List<SiteVisit> {
        val arr = JSONArray(text)
        val out = ArrayList<SiteVisit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                SiteVisit(
                    ts = o.optLong("ts"),
                    lat = if (o.has("lat")) o.getDouble("lat") else null,
                    lng = if (o.has("lng")) o.getDouble("lng") else null,
                    accuracy = if (o.has("accuracy")) o.getDouble("accuracy") else null,
                    note = o.optString("note"),
                    photo = o.optString("photo"),
                ),
            )
        }
        return out
    }
}
