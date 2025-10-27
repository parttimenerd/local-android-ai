package me.bechberger.phoneserver.manager

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import timber.log.Timber

/**
 * Singleton class to manage app permissions and preferences consistently across the application.
 * Handles location permissions, auto-start preferences, and other shared functionality.
 */
class AppPermissionManager private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: AppPermissionManager? = null
        
        private const val PREFS_NAME = "AutoStartPrefs"
        private const val KEY_AUTO_START_ENABLED = "AUTO_START_ENABLED"
        private const val KEY_HAS_LOCATION_PERMISSION = "HAS_LOCATION_PERMISSION"

        fun getInstance(): AppPermissionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPermissionManager().also { INSTANCE = it }
            }
        }
    }

    /**
     * Check if the app has camera permissions
     */
    fun hasCameraPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED
    }

    /**
     * Check if the app has location permissions (either fine or coarse)
     */
    fun hasLocationPermissions(context: Context): Boolean {
        val basicLocationAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PermissionChecker.PERMISSION_GRANTED
        
        // For Android 10+, also check background location for true background access
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val backgroundLocationAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PermissionChecker.PERMISSION_GRANTED
            basicLocationAccess && backgroundLocationAccess
        } else {
            basicLocationAccess
        }
    }
    
    /**
     * Check if the app has basic location permissions (without background requirement)
     */
    fun hasBasicLocationPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PermissionChecker.PERMISSION_GRANTED
    }

    /**
     * Check if the app has storage permissions for AI model persistence
     */
    fun hasStoragePermissions(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ needs media permissions for reading downloaded files
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PermissionChecker.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PermissionChecker.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PermissionChecker.PERMISSION_GRANTED
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10-12 doesn't need external storage permission for app-specific directories
            // but may need it for accessing Downloads folder
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED
        } else {
            // Android 9 and below need explicit storage permissions
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if the app has enhanced storage permissions for AI model access
     * This includes checking both general storage and ability to access common model locations
     */
    fun hasEnhancedStoragePermissions(context: Context): Boolean {
        val basicStorageAccess = hasStoragePermissions(context)
        
        // Test actual directory access
        val testDirectories = listOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            context.getExternalFilesDir(null)
        )
        
        var hasDirectoryAccess = false
        for (dir in testDirectories) {
            if (dir != null && dir.exists() && dir.canRead()) {
                hasDirectoryAccess = true
                break
            }
        }
        
        return basicStorageAccess && hasDirectoryAccess
    }

    /**
     * Check if the app has all required core permissions for basic operation
     */
    fun hasRequiredPermissions(context: Context): Boolean {
        val corePermissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.WAKE_LOCK
        )
        
        return corePermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED
        }
    }

    /**
     * Get the list of core permissions required for basic server operation
     */
    fun getCorePermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.WAKE_LOCK
        )
    }

    /**
     * Get the list of location permissions (optional)
     */
    fun getLocationPermissions(): Array<String> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ requires background location permission for background access
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
    
    /**
     * Get basic location permissions (without background location)
     */
    fun getBasicLocationPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    
    /**
     * Get background location permission (Android 10+ only)
     */
    fun getBackgroundLocationPermissions(): Array<String> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            emptyArray()
        }
    }

    /**
     * Get the list of camera permissions (optional)
     */
    fun getCameraPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.CAMERA
        )
    }

    /**
     * Get the list of storage permissions (for AI model persistence)
     */
    fun getStoragePermissions(): Array<String> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ doesn't need external storage permission for app-specific directories
            emptyArray()
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    /**
     * Get SharedPreferences instance for app preferences
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save auto-start preference
     */
    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_START_ENABLED, enabled)
            .apply()
        Timber.d("Auto-start preference set to: $enabled")
    }

    /**
     * Check if auto-start is enabled
     */
    fun isAutoStartEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_START_ENABLED, true)
    }

    /**
     * Save location permission state for use by other components
     */
    fun saveLocationPermissionState(context: Context) {
        val hasPermission = hasLocationPermissions(context)
        getPreferences(context).edit()
            .putBoolean(KEY_HAS_LOCATION_PERMISSION, hasPermission)
            .apply()
        Timber.d("Location permission state saved: $hasPermission")
    }

    /**
     * Get the saved location permission state from preferences
     */
    fun getSavedLocationPermissionState(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_HAS_LOCATION_PERMISSION, false)
    }

    /**
     * Update both current permission state and save it to preferences
     */
    fun updateAndSaveLocationPermissionState(context: Context): Boolean {
        val hasPermission = hasLocationPermissions(context)
        getPreferences(context).edit()
            .putBoolean(KEY_HAS_LOCATION_PERMISSION, hasPermission)
            .apply()
        Timber.d("Location permission state updated and saved: $hasPermission")
        return hasPermission
    }

    /**
     * Get all missing core permissions
     */
    fun getMissingCorePermissions(context: Context): List<String> {
        return getCorePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PermissionChecker.PERMISSION_GRANTED
        }
    }

    /**
     * Get all missing location permissions
     */
    fun getMissingLocationPermissions(context: Context): List<String> {
        return getLocationPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PermissionChecker.PERMISSION_GRANTED
        }
    }

    /**
     * Get all missing camera permissions
     */
    fun getMissingCameraPermissions(context: Context): List<String> {
        return getCameraPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PermissionChecker.PERMISSION_GRANTED
        }
    }

    /**
     * Check if the app should auto-start the server based on preferences and permissions
     */
    fun shouldAutoStartServer(context: Context): Boolean {
        val autoStartEnabled = isAutoStartEnabled(context)
        val hasRequiredPerms = hasRequiredPermissions(context)
        
        Timber.d("Auto-start check - enabled: $autoStartEnabled, has required permissions: $hasRequiredPerms")
        return autoStartEnabled && hasRequiredPerms
    }

    /**
     * Get permission status summary for debugging and UI display
     */
    fun getPermissionStatusSummary(context: Context): Map<String, Any> {
        return mapOf(
            "hasLocation" to hasLocationPermissions(context),
            "hasCamera" to hasCameraPermissions(context),
            "hasRequiredCore" to hasRequiredPermissions(context),
            "autoStartEnabled" to isAutoStartEnabled(context),
            "shouldAutoStart" to shouldAutoStartServer(context),
            "missingCorePermissions" to getMissingCorePermissions(context),
            "missingLocationPermissions" to getMissingLocationPermissions(context),
            "missingCameraPermissions" to getMissingCameraPermissions(context)
        )
    }
}
