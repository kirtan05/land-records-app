package com.landrecords.app.data.storage

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Report a problem": gathers a plain-text diagnostic — app version, device, the last crash, and
 * the app's own recent logs — writes it into the library (so it's kept even offline: "send next
 * time"), and opens a share sheet pre-addressed to us. Non-technical-user friendly: one tap →
 * WhatsApp/Gmail/Drive.
 *
 * On Android an app can read its OWN process logs via `logcat` without any special permission, so
 * we capture the LR fetch/merge trace that actually explains failures. The crash log is written by
 * the uncaught-exception handler in [com.landrecords.app.LandRecordsApp].
 */
object DiagnosticsReport {

    private const val REPORT_TO = "kirtanjain0504@gmail.com"

    private fun diagDir(context: Context) = File(context.filesDir, "diag").apply { mkdirs() }

    /** Where the crash handler appends fatal stack traces. */
    fun crashLogFile(context: Context) = File(diagDir(context), "crash.log")

    /** The persisted report — kept on disk so it can be re-shared later if there's no network now. */
    fun lastReportFile(context: Context) = File(diagDir(context), "land_records_report.txt")

    /** Builds the report file (also persisted) and returns it. */
    suspend fun build(context: Context): File = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        sb.appendLine("Land Records — problem report")
        sb.appendLine("Time: $ts")
        sb.appendLine("App: ${pkg?.versionName} (code ${pkg?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) }})")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        val crash = crashLogFile(context)
        if (crash.exists() && crash.length() > 0) {
            sb.appendLine("=== Last crash ===")
            sb.appendLine(crash.readText().takeLast(6000))
            sb.appendLine()
        }
        sb.appendLine("=== Recent app logs ===")
        sb.appendLine(recentLogcat().takeLast(14000))

        val out = lastReportFile(context)
        out.writeText(sb.toString())
        out
    }

    /** Build + open a share sheet pre-addressed to us. Returns false if nothing could be shared. */
    suspend fun share(context: Context): Boolean {
        return try {
            val file = build(context)
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_TO))
                putExtra(Intent.EXTRA_SUBJECT, "Land Records — problem report (v$version)")
                putExtra(Intent.EXTRA_TEXT, "Problem report attached. (You can add a note about what went wrong.)")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Report a problem").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.w("LR", "diag share failed: ${e.message}")
            false
        }
    }

    /** Our own recent process logs — best effort. */
    private fun recentLogcat(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "800"))
        p.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "logcat unavailable: ${e.message}"
    }
}
