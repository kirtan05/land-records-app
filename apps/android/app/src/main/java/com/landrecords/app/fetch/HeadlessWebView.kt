package com.landrecords.app.fetch

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An offscreen WebView that behaves like the one inside a fetch screen, but with no screen.
 *
 * This is what lets §6's `FetchService` reuse the existing, proven injections unchanged. The
 * WebView is created against the **service** context and laid out manually — never attached to
 * a window — which was MEASURED on 2026-08-14 (SM-X730, API 36) to render byte- and
 * pixel-identically whether the app is foreground, backgrounded, or the screen is off.
 *
 * The layout size matters: a WebView with no window has no size, and a zero-height WebView
 * renders nothing at all. [WIDTH_PX] × [HEIGHT_PX] mirrors the probe's `layout(0,0,1080,1920)`.
 *
 * Page loads are counted rather than merely awaited, because AnyRoR is WebForms: almost every
 * interaction is a full postback, and the only reliable way to know a postback finished is that
 * the load counter moved past the value it had when we submitted.
 */
@SuppressLint("SetJavaScriptEnabled")
class HeadlessWebView private constructor(val webView: WebView) {

    @Volatile var loadCount: Int = 0
        private set

    @Volatile var lastHttpStatus: Int = 200
        private set

    private var pageSignal: CompletableDeferred<Unit>? = null

    /** Suspends until the next page finishes loading, or the timeout expires. */
    suspend fun awaitNextLoad(timeoutMs: Long = 45_000): Boolean {
        val signal = CompletableDeferred<Unit>()
        pageSignal = signal
        return withTimeoutOrNull(timeoutMs) { signal.await() } != null
    }

    /** Suspends until [loadCount] exceeds [after] — the WebForms postback completion test. */
    suspend fun awaitLoadAfter(after: Int, timeoutMs: Long = 45_000): Boolean {
        if (loadCount > after) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!awaitNextLoad(deadline - System.currentTimeMillis())) return loadCount > after
            if (loadCount > after) return true
        }
        return loadCount > after
    }

    suspend fun load(url: String, timeoutMs: Long = 45_000): Boolean {
        withContext(Dispatchers.Main) { webView.loadUrl(url) }
        return awaitNextLoad(timeoutMs)
    }

    suspend fun destroy() = withContext(Dispatchers.Main) {
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        Unit
    }

    companion object {
        /** Matches the background-render probe's layout exactly — see the class doc. */
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 1920

        /**
         * Desktop UA. AnyRoR serves a different (and for deeds, incomplete) page to a mobile
         * UA, and iRCMS 500s on some POSTs without it — the same header set the desktop
         * scrapers had to send.
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        suspend fun create(context: Context, desktopUa: Boolean = true): HeadlessWebView =
            withContext(Dispatchers.Main) {
                val wv = WebView(context)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = false
                    useWideViewPort = false
                    if (desktopUa) userAgentString = DESKTOP_UA
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                val host = HeadlessWebView(wv)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        host.loadCount++
                        host.pageSignal?.complete(Unit)
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        // Only the MAIN document's status matters; a failed image must not be
                        // read as a WAF block and trigger a backoff for the whole host.
                        if (request?.isForMainFrame == true) {
                            host.lastHttpStatus = errorResponse?.statusCode ?: 0
                        }
                    }
                }
                // No window, so no automatic size — and a zero-height WebView renders nothing.
                wv.layout(0, 0, WIDTH_PX, HEIGHT_PX)
                host
            }
    }
}
