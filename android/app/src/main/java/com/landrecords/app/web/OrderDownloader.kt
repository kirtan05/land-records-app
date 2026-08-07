package com.landrecords.app.web

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a disposed case's order PDF — the faithful port of desktop test-order.mjs
 * (`ctx.request.post(action, {form: fields})`). The order form is read in JS
 * ([IrcmsInjection.orderFormJs]); here we replay the POST with the WebView's live session
 * cookies, so the bytes never cross the JS→Kotlin Binder limit.
 */
object OrderDownloader {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

    /** [formJson] is the output of orderFormJs: {action, fields}. Returns the order PDF or null. */
    suspend fun fetch(formJson: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(formJson)
            val action = o.getString("action")
            val fields = o.getJSONObject("fields")
            val cookie = CookieManager.getInstance().getCookie(action) ?: ""

            val body = StringBuilder()
            val keys = fields.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (body.isNotEmpty()) body.append('&')
                body.append(enc(k)).append('=').append(enc(fields.getString(k)))
            }

            val conn = (URL(action).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 40_000
                instanceFollowRedirects = true
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", DESKTOP_UA)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Referer", Ircms.CASE_DETAIL_URL)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()

            val isPdf = bytes.size >= 4 &&
                bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
            if (isPdf) {
                android.util.Log.i("LR", "order PDF fetched (${bytes.size} bytes)")
                bytes
            } else {
                android.util.Log.i("LR", "order response not a PDF (${bytes.size} bytes)")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("LR", "order download failed: ${e.message}")
            null
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
