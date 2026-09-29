package com.banyawa.sitescanner.ui.projects

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.banyawa.sitescanner.core.project.GeoPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Where the phone is, and what the address there is called. */
object SiteLocation {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Location is switched off in the phone settings. */
    class LocationOffException : Exception("Location is off")

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(context: Context) = PERMISSIONS.any { granted(context, it) }

    /**
     * Fixes from GPS and the network, each more accurate than the one before, starting with
     * a recent last-known fix if there is one. Completes once a fix is within [goodEnoughM]
     * or after [timeoutMs]; fails with [LocationOffException] when no provider is enabled.
     */
    @SuppressLint("MissingPermission")
    fun fixes(context: Context, goodEnoughM: Float = GOOD_ENOUGH_M, timeoutMs: Long = TIMEOUT_MS): Flow<Location> = callbackFlow {
        val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
        // GPS needs precise location; with "approximate" only the network provider is allowed.
        val providers = buildList {
            if (granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
            if (hasPermission(context)) add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager?.isProviderEnabled(it) == true }.getOrDefault(false) }
        if (manager == null || providers.isEmpty()) {
            close(LocationOffException())
            return@callbackFlow
        }

        var best: Location? = null
        fun offer(fix: Location) {
            val b = best
            if (b != null && (!fix.hasAccuracy() || (b.hasAccuracy() && fix.accuracy >= b.accuracy))) return
            best = fix
            trySend(fix)
            if (fix.hasAccuracy() && fix.accuracy <= goodEnoughM) close()
        }

        val now = SystemClock.elapsedRealtimeNanos()
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.elapsedRealtimeNanos < RECENT_NANOS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            ?.let(::offer)

        // Every method is implemented: before Android 11 only onLocationChanged has a default.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = offer(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        for (provider in providers) {
            runCatching { manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper()) }
        }
        val timeout = launch {
            delay(timeoutMs)
            close()
        }
        awaitClose {
            timeout.cancel()
            manager.removeUpdates(listener)
        }
    }

    fun Location.toPin() = GeoPin(latitude, longitude, if (hasAccuracy()) accuracy else null)

    /** The street address at [pin] in the phone's language, or null without a geocoder or network. */
    suspend fun address(context: Context, pin: GeoPin): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(pin.latitude, pin.longitude, 1)
                ?.firstOrNull()
                ?.let { a ->
                    a.getAddressLine(0)
                        ?: listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.adminArea).joinToString(", ")
                }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Places matching [query] (an address, a district, a landmark), best match first. */
    suspend fun search(context: Context, query: String): List<Pair<String, GeoPin>> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent() || query.isBlank()) return@withContext emptyList()
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocationName(query.trim(), 5).orEmpty()
                .filter { it.hasLatitude() && it.hasLongitude() }
                .map { (it.getAddressLine(0) ?: it.featureName ?: query) to GeoPin(it.latitude, it.longitude) }
        }.getOrDefault(emptyList())
    }

    /** A fix within this is as good as a site pin needs; GPS rarely does better outdoors. */
    const val GOOD_ENOUGH_M = 15f
    const val TIMEOUT_MS = 30_000L
    private const val RECENT_NANOS = 2 * 60 * 1_000_000_000L
}
