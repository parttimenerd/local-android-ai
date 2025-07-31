package com.k3s.phoneserver.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

class LocationService(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var lastKnownLocation: Location? = null

    suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) {
            Timber.w("Location permission not granted")
            return null
        }

        // Try to get cached location first
        lastKnownLocation?.let { location ->
            // Return cached location if it's recent (less than 5 minutes old)
            if (System.currentTimeMillis() - location.time < 5 * 60 * 1000) {
                return location
            }
        }

        // Request fresh location
        return try {
            requestLocationUpdate()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get location")
            // Fallback to last known location
            getLastKnownLocation()
        }
    }

    private suspend fun requestLocationUpdate(): Location? = suspendCancellableCoroutine { continuation ->
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastKnownLocation = location
                locationManager.removeUpdates(this)
                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            val providers = locationManager.getProviders(true)
            val bestProvider = when {
                providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (bestProvider != null) {
                locationManager.requestSingleUpdate(bestProvider, locationListener, null)
                
                // Set up cancellation
                continuation.invokeOnCancellation {
                    locationManager.removeUpdates(locationListener)
                }
                
                // Timeout after 10 seconds and use last known location
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (continuation.isActive) {
                        locationManager.removeUpdates(locationListener)
                        continuation.resume(getLastKnownLocation())
                    }
                }, 10000)
            } else {
                continuation.resume(null)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception requesting location")
            continuation.resume(null)
        }
    }

    private fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null

        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            var bestLocation: Location? = null
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null && (bestLocation == null || location.accuracy < bestLocation.accuracy)) {
                        bestLocation = location
                    }
                }
            }
            
            lastKnownLocation = bestLocation
            return bestLocation
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception getting last known location")
            return null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun cleanup() {
        // No ongoing listeners to clean up in this implementation
    }
}
