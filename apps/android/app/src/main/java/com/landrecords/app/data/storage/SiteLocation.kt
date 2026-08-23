package com.landrecords.app.data.storage

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A tiny GPS helper built on the framework [LocationManager] — NO Google Play Services (it is not a
 * dependency of this app). [current] asks for one fresh fix and returns null if permission is missing
 * or no fix arrives in time; the caller (the on-site capture screen) handles that gracefully.
 */
object SiteLocation {

    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    private const val FRESH_FIX_TIMEOUT_MS = 15_000L

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Best available current location, or null. On API 30+ it asks for a genuine fresh fix (bounded by
     * a 15s timeout) and falls back to the newest last-known reading; on older APIs it returns the
     * newest last-known reading across the enabled providers.
     */
    @SuppressLint("MissingPermission") // guarded by hasPermission() above
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = PROVIDERS.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && provider != null) {
            val fresh = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            }
            if (fresh != null) return fresh
        }
        return bestLastKnown(lm)
    }

    @SuppressLint("MissingPermission") // guarded by the caller
    private fun bestLastKnown(lm: LocationManager): Location? =
        PROVIDERS.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
}
