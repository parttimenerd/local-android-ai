package com.k3s.phoneserver.ai

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import java.io.File

/**
 * Persistent model state information
 */
data class PersistedModelInfo(
    val modelName: String,
    val fileName: String,
    val downloadPath: String,
    val fileSize: Long,
    val downloadTimestamp: Long,
    val lastAccessedTimestamp: Long,
    val isLoaded: Boolean = false,
    val loadTimestamp: Long = 0L,
    val downloadStatus: ModelDownloadStatus,
    val checksumMD5: String? = null
) {
    fun getFormattedSize(): String {
        return when {
            fileSize >= 1024 * 1024 * 1024 -> "%.2f GB".format(fileSize / (1024.0 * 1024.0 * 1024.0))
            fileSize >= 1024 * 1024 -> "%.1f MB".format(fileSize / (1024.0 * 1024.0))
            fileSize >= 1024 -> "%.1f KB".format(fileSize / 1024.0)
            else -> "$fileSize bytes"
        }
    }
    
    fun isFileAvailable(): Boolean {
        return File(downloadPath).exists() && File(downloadPath).length() == fileSize
    }
    
    fun getAgeInDays(): Long {
        return (System.currentTimeMillis() - downloadTimestamp) / (1000 * 60 * 60 * 24)
    }
}

enum class ModelDownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CORRUPTED,
    DELETED
}

/**
 * Manages persistence of AI model download and loading state
 * Tracks where models are stored, when they were downloaded, and their current status
 */
class ModelPersistenceManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelPersistenceManager"
        private const val PREFS_NAME = "ai_model_persistence"
        private const val KEY_PERSISTED_MODELS = "persisted_models"
        private const val KEY_LAST_LOADED_MODEL = "last_loaded_model"
        private const val KEY_TOTAL_DOWNLOAD_SIZE = "total_download_size"
        private const val KEY_MODELS_DIRECTORY = "models_directory"
        
        @Volatile
        private var INSTANCE: ModelPersistenceManager? = null
        
        fun getInstance(context: Context): ModelPersistenceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelPersistenceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val persistedModels = mutableMapOf<String, PersistedModelInfo>()
    
    init {
        loadPersistedModels()
    }
    
    /**
     * Load persisted model information from SharedPreferences
     */
    private fun loadPersistedModels() {
        try {
            val json = prefs.getString(KEY_PERSISTED_MODELS, null)
            if (json != null) {
                val type = object : TypeToken<Map<String, PersistedModelInfo>>() {}.type
                val loaded: Map<String, PersistedModelInfo> = gson.fromJson(json, type)
                persistedModels.clear()
                persistedModels.putAll(loaded)
                
                // Verify file integrity on load
                verifyPersistedModels()
                
                Timber.i("Loaded ${persistedModels.size} persisted models")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load persisted models")
            persistedModels.clear()
        }
    }
    
    /**
     * Save persisted model information to SharedPreferences
     */
    private fun savePersistedModels() {
        try {
            val json = gson.toJson(persistedModels)
            prefs.edit().putString(KEY_PERSISTED_MODELS, json).apply()
            Timber.d("Saved ${persistedModels.size} persisted models")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save persisted models")
        }
    }
    
    /**
     * Verify that persisted models still exist and update their status
     */
    private fun verifyPersistedModels() {
        val toRemove = mutableListOf<String>()
        val toUpdate = mutableListOf<PersistedModelInfo>()
        
        persistedModels.forEach { (modelName, info) ->
            val file = File(info.downloadPath)
            when {
                !file.exists() -> {
                    Timber.w("Model file no longer exists: ${info.downloadPath}")
                    toUpdate.add(info.copy(downloadStatus = ModelDownloadStatus.DELETED))
                }
                file.length() != info.fileSize -> {
                    Timber.w("Model file size mismatch: ${info.downloadPath} (expected: ${info.fileSize}, actual: ${file.length()})")
                    toUpdate.add(info.copy(downloadStatus = ModelDownloadStatus.CORRUPTED))
                }
                info.downloadStatus == ModelDownloadStatus.DOWNLOADING -> {
                    // Check if download completed while app was closed
                    if (file.length() == info.fileSize) {
                        Timber.i("Download completed while app was closed: ${info.modelName}")
                        toUpdate.add(info.copy(downloadStatus = ModelDownloadStatus.COMPLETED))
                    }
                }
            }
        }
        
        // Remove deleted models
        toRemove.forEach { persistedModels.remove(it) }
        
        // Update status for existing models
        toUpdate.forEach { updatedInfo ->
            persistedModels[updatedInfo.modelName] = updatedInfo
        }
        
        if (toRemove.isNotEmpty() || toUpdate.isNotEmpty()) {
            savePersistedModels()
        }
    }
    
    /**
     * Record that a model download has started
     */
    fun recordModelDownloadStarted(model: AIModel, downloadPath: String, expectedSize: Long) {
        val info = PersistedModelInfo(
            modelName = model.modelName,
            fileName = model.fileName,
            downloadPath = downloadPath,
            fileSize = expectedSize,
            downloadTimestamp = System.currentTimeMillis(),
            lastAccessedTimestamp = System.currentTimeMillis(),
            downloadStatus = ModelDownloadStatus.DOWNLOADING
        )
        
        persistedModels[model.modelName] = info
        savePersistedModels()
        
        Timber.i("Recorded download start for model: ${model.modelName} at $downloadPath")
    }
    
    /**
     * Record that a model download has completed successfully
     */
    fun recordModelDownloadCompleted(model: AIModel, actualSize: Long, checksumMD5: String? = null) {
        val existing = persistedModels[model.modelName]
        if (existing != null) {
            val updated = existing.copy(
                fileSize = actualSize,
                downloadStatus = ModelDownloadStatus.COMPLETED,
                checksumMD5 = checksumMD5,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            persistedModels[model.modelName] = updated
            savePersistedModels()
            
            Timber.i("Recorded download completion for model: ${model.modelName} (${formatBytes(actualSize)})")
        }
    }
    
    /**
     * Record that a model download has failed
     */
    fun recordModelDownloadFailed(model: AIModel, reason: String) {
        val existing = persistedModels[model.modelName]
        if (existing != null) {
            val updated = existing.copy(
                downloadStatus = ModelDownloadStatus.FAILED,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            persistedModels[model.modelName] = updated
            savePersistedModels()
            
            Timber.w("Recorded download failure for model: ${model.modelName} - $reason")
        }
    }
    
    /**
     * Record that a model has been loaded into memory
     */
    fun recordModelLoaded(model: AIModel) {
        val existing = persistedModels[model.modelName]
        if (existing != null) {
            val updated = existing.copy(
                isLoaded = true,
                loadTimestamp = System.currentTimeMillis(),
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            persistedModels[model.modelName] = updated
            savePersistedModels()
            
            // Record as last loaded model
            prefs.edit().putString(KEY_LAST_LOADED_MODEL, model.modelName).apply()
            
            Timber.i("Recorded model loaded: ${model.modelName}")
        }
    }
    
    /**
     * Record that a model has been unloaded from memory
     */
    fun recordModelUnloaded(model: AIModel) {
        val existing = persistedModels[model.modelName]
        if (existing != null) {
            val updated = existing.copy(
                isLoaded = false,
                lastAccessedTimestamp = System.currentTimeMillis()
            )
            persistedModels[model.modelName] = updated
            savePersistedModels()
            
            Timber.i("Recorded model unloaded: ${model.modelName}")
        }
    }
    
    /**
     * Get persisted information for a specific model
     */
    fun getModelInfo(model: AIModel): PersistedModelInfo? {
        return persistedModels[model.modelName]
    }
    
    /**
     * Get all persisted models
     */
    fun getAllPersistedModels(): Map<String, PersistedModelInfo> {
        return persistedModels.toMap()
    }
    
    /**
     * Get models that are currently loaded
     */
    fun getLoadedModels(): List<PersistedModelInfo> {
        return persistedModels.values.filter { it.isLoaded }
    }
    
    /**
     * Get models that are downloaded but not loaded
     */
    fun getDownloadedModels(): List<PersistedModelInfo> {
        return persistedModels.values.filter { 
            it.downloadStatus == ModelDownloadStatus.COMPLETED && it.isFileAvailable() 
        }
    }
    
    /**
     * Get the last loaded model name
     */
    fun getLastLoadedModelName(): String? {
        return prefs.getString(KEY_LAST_LOADED_MODEL, null)
    }
    
    /**
     * Get total size of all downloaded models
     */
    fun getTotalDownloadedSize(): Long {
        return persistedModels.values
            .filter { it.downloadStatus == ModelDownloadStatus.COMPLETED && it.isFileAvailable() }
            .sumOf { it.fileSize }
    }
    
    /**
     * Get models directory path
     */
    fun getModelsDirectory(): String? {
        return prefs.getString(KEY_MODELS_DIRECTORY, null)
    }
    
    /**
     * Set models directory path
     */
    fun setModelsDirectory(directory: String) {
        prefs.edit().putString(KEY_MODELS_DIRECTORY, directory).apply()
    }
    
    /**
     * Remove persisted information for a model (when deleted)
     */
    fun removeModel(model: AIModel) {
        persistedModels.remove(model.modelName)
        savePersistedModels()
        Timber.i("Removed persisted model info: ${model.modelName}")
    }
    
    /**
     * Clean up models that no longer exist on disk
     */
    fun cleanupDeletedModels(): Int {
        val toRemove = persistedModels.values.filter { !it.isFileAvailable() }.map { it.modelName }
        toRemove.forEach { persistedModels.remove(it) }
        
        if (toRemove.isNotEmpty()) {
            savePersistedModels()
            Timber.i("Cleaned up ${toRemove.size} deleted models")
        }
        
        return toRemove.size
    }
    
    /**
     * Get summary statistics
     */
    fun getStatistics(): ModelStatistics {
        val downloaded = getDownloadedModels()
        val loaded = getLoadedModels()
        
        return ModelStatistics(
            totalModels = persistedModels.size,
            downloadedModels = downloaded.size,
            loadedModels = loaded.size,
            totalDownloadSize = getTotalDownloadedSize(),
            oldestDownload = downloaded.minByOrNull { it.downloadTimestamp }?.downloadTimestamp,
            newestDownload = downloaded.maxByOrNull { it.downloadTimestamp }?.downloadTimestamp,
            lastAccessed = persistedModels.values.maxByOrNull { it.lastAccessedTimestamp }?.lastAccessedTimestamp
        )
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}

/**
 * Model statistics summary
 */
data class ModelStatistics(
    val totalModels: Int,
    val downloadedModels: Int,
    val loadedModels: Int,
    val totalDownloadSize: Long,
    val oldestDownload: Long?,
    val newestDownload: Long?,
    val lastAccessed: Long?
) {
    fun getFormattedTotalSize(): String {
        return when {
            totalDownloadSize >= 1024 * 1024 * 1024 -> "%.2f GB".format(totalDownloadSize / (1024.0 * 1024.0 * 1024.0))
            totalDownloadSize >= 1024 * 1024 -> "%.1f MB".format(totalDownloadSize / (1024.0 * 1024.0))
            totalDownloadSize >= 1024 -> "%.1f KB".format(totalDownloadSize / 1024.0)
            else -> "$totalDownloadSize bytes"
        }
    }
}
