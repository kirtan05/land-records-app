package com.landrecords.app.data.storage

import android.content.Context
import java.io.File

/**
 * Persists the app's own `LR` trace to a capped ring-buffer file so "Report a problem" carries real
 * logs even hours later — logcat's in-memory buffer rolls fast (SurfaceFlinger etc.), so by the time
 * dad reports, the useful lines are usually gone. On Android an app can read its OWN process logs via
 * `logcat` with no permission; we follow the LR tag and append to [file], trimming oldest past ~2 MB.
 */
object AppLog {

    private const val MAX_BYTES = 2L * 1024 * 1024   // hard cap
    private const val KEEP_BYTES = 1024 * 1024        // trim back to ~1 MB (drop-oldest)

    @Volatile private var started = false

    fun file(context: Context): File = File(File(context.filesDir, "diag").apply { mkdirs() }, "applog.txt")

    fun start(context: Context) {
        if (started) return
        started = true
        val f = file(context)
        Thread({
            try {
                // Only LR-tagged lines (our fetch/merge/prefill trace), following live.
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "LR:V", "*:S"))
                proc.inputStream.bufferedReader().useLines { seq ->
                    val batch = StringBuilder()
                    var n = 0
                    for (line in seq) {
                        batch.append(line).append('\n')
                        if (++n >= 8) {
                            runCatching { f.appendText(batch.toString()) }
                            batch.setLength(0); n = 0
                            if (f.length() > MAX_BYTES) trim(f)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("LR", "AppLog capture ended: ${e.message}")
            }
        }, "app-log").apply { isDaemon = true; start() }
    }

    /** Keep only the last ~[KEEP_BYTES], aligned to a line boundary (drop the oldest). */
    private fun trim(f: File) {
        runCatching {
            val bytes = f.readBytes()
            if (bytes.size <= KEEP_BYTES) return
            var start = bytes.size - KEEP_BYTES
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            f.writeBytes(bytes.copyOfRange((start + 1).coerceAtMost(bytes.size), bytes.size))
        }
    }
}
