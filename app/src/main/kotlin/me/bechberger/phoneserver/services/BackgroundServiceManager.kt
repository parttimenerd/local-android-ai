package me.bechberger.phoneserver.services

import android.content.Context
import android.location.Location
import me.bechberger.phoneserver.manager.AppPermissionManager
import timber.log.Timber

/**
 * Manager for background services that don't depend on Activity lifecycle
 * Provides location access from within background services
 * Note: Camera access requires app visibility and is NOT available in background
 */
class BackgroundServiceManager(private val context: Context) {
    
    private val permissionManager = AppPermissionManager.getInstance()
    private var locationService: LocationService? = null
    
    companion object {
        @Volatile
        private var INSTANCE: BackgroundServiceManager? = null
        
        fun getInstance(context: Context): BackgroundServiceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackgroundServiceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Initialize services with application context for background operation
     */
    fun initializeServices() {
        try {
            // Use application context to avoid Activity dependencies
            locationService = LocationService(context)
            Timber.d("Background location service initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize background location service")
        }
    }
    
    /**
     * Get location in background without Activity dependency
     */
    suspend fun getBackgroundLocation(): Location? {
        return try {
            if (!permissionManager.hasLocationPermissions(context)) {
                Timber.w("Background location access denied - missing permissions")
                return null
            }
            
            locationService?.getCurrentLocation()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get background location")
            null
        }
    }

    /**
     * Check if background services are ready
     */
    fun areServicesReady(): Boolean {
        return locationService != null
    }

    /**
     * Clean up services
     */
    fun cleanup() {
        locationService?.cleanup()
        locationService = null
        Timber.d("Background location service cleaned up")
    }
}