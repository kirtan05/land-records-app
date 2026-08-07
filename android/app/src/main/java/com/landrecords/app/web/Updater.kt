package com.landrecords.app.web

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app self-update for the sideloaded build. Checks a small JSON manifest you host for a newer
 * versionCode, downloads the APK, and hands it to the system package installer — the user taps
 * "Install" once (Android won't silent-install a sideloaded app). Paired with the "Report a
 * problem" button, this is the whole remote-maintenance loop without a Play Store.
 *
 * Host anywhere that serves a plain public URL (GitHub release asset, Firebase Hosting/Storage, a
 * static bucket). [MANIFEST_URL] must point at a JSON like:
 *   { "versionCode": 7, "versionName": "0.7.0",
 *     "apkUrl": "https://…/land-records-0.7.0.apk", "notes": "What changed" }
 */
object Updater {

    // Public update channel (the private code repo can't serve unauthenticated downloads).
    // Publishing a release = push a slim APK there + bump this update.json's versionCode/apkUrl.
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/kirtan05/land-records-releases/main/update.json"

    data class Update(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

    /** Returns an [Update] iff the manifest advertises a newer versionCode than what's installed. */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        if (MANIFEST_URL.contains("REPLACE-ME")) return@withContext null
        try {
            val text = httpGet(MANIFEST_URL) ?: return@withContext null
            val o = JSONObject(text)
            val remote = o.getLong("versionCode")
            val current = PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
            if (remote <= current) return@withContext null
            Update(remote, o.optString("versionName"), o.getString("apkUrl"), o.optString("notes"))
        } catch (e: Exception) {
            android.util.Log.w("LR", "update check failed: ${e.message}"); null
        }
    }

    /** Downloads the APK to cache and returns it, or null on failure. */
    suspend fun download(context: Context, update: Update): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "land-records-${update.versionCode}.apk")
            val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000; instanceFollowRedirects = true
            }
            conn.inputStream.use { input -> apk.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (apk.length() < 1_000) { apk.delete(); null } else apk
        } catch (e: Exception) {
            android.util.Log.w("LR", "apk download failed: ${e.message}"); null
        }
    }

    /** Launches the system installer for [apk] (needs the REQUEST_INSTALL_PACKAGES permission). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun httpGet(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
        }
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        android.util.Log.w("LR", "manifest fetch failed: ${e.message}"); null
    }
}
