package me.bechberger.phoneserver.ai

import android.content.Context
import android.os.Environment
import timber.log.Timber
import java.io.File

/**
 * Model file information
 */
data class ModelFileInfo(
    val fileName: String,
    val fileSizeBytes: Long,
    val lastModified: Long,
    val isAvailable: Boolean,
    val storagePath: String = ""
) {
    fun getFormattedSize(): String {
        if (!isAvailable) return "Not downloaded"
        
        return when {
            fileSizeBytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(fileSizeBytes / (1024.0 * 1024.0 * 1024.0))
            fileSizeBytes >= 1024 * 1024 -> "%.1f MB".format(fileSizeBytes / (1024.0 * 1024.0))
            fileSizeBytes >= 1024 -> "%.1f KB".format(fileSizeBytes / 1024.0)
            else -> "$fileSizeBytes bytes"
        }
    }
}

/**
 * Detects available AI models based on reference files pointing to model locations
 * Uses reference files to avoid copying large model files into app storage
 */
object ModelDetector {
    
    private const val TAG = "ModelDetector"
    private const val MODELS_REFS_DIR = "local_ai_model_refs"
    private const val FAILED_MODELS_PREF = "failed_models"
    
    // Track models that have failed testing
    private val failedModels = mutableSetOf<String>()
    
    /**
     * Validate file permissions and accessibility with detailed error reporting
     */
    fun validateFileAccess(file: File): FileAccessResult {
        try {
            if (!file.exists()) {
                return FileAccessResult(false, "File does not exist: ${file.absolutePath}")
            }
            
            if (!file.isFile()) {
                return FileAccessResult(false, "Path is not a file: ${file.absolutePath}")
            }
            
            if (file.length() == 0L) {
                return FileAccessResult(false, "File is empty: ${file.absolutePath}")
            }
            
            if (!file.canRead()) {
                return FileAccessResult(false, "File is not readable (permission denied): ${file.absolutePath}")
            }
            
            // Try to actually read the file to verify access
            try {
                file.inputStream().use { stream ->
                    val buffer = ByteArray(1024)
                    val bytesRead = stream.read(buffer)
                    if (bytesRead <= 0) {
                        return FileAccessResult(false, "Cannot read file content: ${file.absolutePath}")
                    }
                }
            } catch (e: SecurityException) {
                return FileAccessResult(false, "Security restriction reading file: ${file.absolutePath} - ${e.message}")
            } catch (e: java.io.IOException) {
                return FileAccessResult(false, "I/O error reading file: ${file.absolutePath} - ${e.message}")
            }
            
            return FileAccessResult(true, "File is accessible")
            
        } catch (e: SecurityException) {
            return FileAccessResult(false, "Security exception accessing file: ${file.absolutePath} - ${e.message}")
        } catch (e: Exception) {
            return FileAccessResult(false, "Unexpected error accessing file: ${file.absolutePath} - ${e.message}")
        }
    }
    
    /**
     * Result of file access validation
     */
    data class FileAccessResult(
        val isAccessible: Boolean,
        val message: String
    )
    
    /**
     * Mark a model as failed for testing
     */
    fun markModelAsFailed(context: Context, model: AIModel) {
        failedModels.add(model.name)
        // Also persist to SharedPreferences
        val prefs = context.getSharedPreferences("ai_model_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(FAILED_MODELS_PREF, failedModels).apply()
        Timber.w("Marked model as failed: ${model.modelName}")
    }
    
    /**
     * Clear failed status for a model
     */
    fun clearModelFailedStatus(context: Context, model: AIModel) {
        failedModels.remove(model.name)
        val prefs = context.getSharedPreferences("ai_model_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(FAILED_MODELS_PREF, failedModels).apply()
        Timber.d("Cleared failed status for model: ${model.modelName}")
    }
    
    /**
     * Load failed models from persistent storage
     */
    private fun loadFailedModels(context: Context) {
        val prefs = context.getSharedPreferences("ai_model_prefs", Context.MODE_PRIVATE)
        val failed = prefs.getStringSet(FAILED_MODELS_PREF, emptySet()) ?: emptySet()
        failedModels.clear()
        failedModels.addAll(failed)
    }
    
    /**
     * Check if a model has been marked as failed
     */
    fun isModelFailed(context: Context, model: AIModel): Boolean {
        if (failedModels.isEmpty()) {
            loadFailedModels(context)
        }
        return failedModels.contains(model.name)
    }
    
    /**
     * Get all possible model reference storage directories in order of preference
     * (most persistent first)
     */
    private fun getModelRefsDirectories(context: Context): List<File> {
        val directories = mutableListOf<File>()
        
        // 1. App-specific external storage (persistent across updates, cleared on uninstall)
        // This is the most reliable option for modern Android versions
        context.getExternalFilesDir(null)?.let { externalFiles ->
            directories.add(File(externalFiles, MODELS_REFS_DIR))
        }
        
        // 2. Internal storage (least persistent, cleared on updates, but always writable)
        directories.add(File(context.filesDir, MODELS_REFS_DIR))
        
        // 3. Public Documents directory (most persistent, survives app uninstall)
        // Note: This may not be writable on newer Android versions without special permissions
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            directories.add(File(publicDocs, MODELS_REFS_DIR))
        }
        
        return directories
    }
    
    /**
     * Get the preferred directory for storing new model references
     */
    fun getModelRefsDirectory(context: Context): File {
        val directories = getModelRefsDirectories(context)
        
        Timber.v("🗂️  Finding preferred model references directory...")
        
        // Try to use the most persistent directory that's writable
        for ((index, dir) in directories.withIndex()) {
            Timber.v("   ${index + 1}. Trying directory: ${dir.absolutePath}")
            
            try {
                if (!dir.exists()) {
                    Timber.v("      Directory doesn't exist, attempting to create...")
                    val created = dir.mkdirs()
                    if (!created) {
                        Timber.w("      ⚠️  Failed to create directory")
                        continue
                    }
                    Timber.v("      ✅ Directory created successfully")
                }
                
                if (!dir.isDirectory()) {
                    Timber.w("      ⚠️  Path exists but is not a directory")
                    continue
                }
                
                // Test write permissions with actual file test
                if (dir.canWrite()) {
                    // Additional verification: try to create a test file
                    val testFile = File(dir, ".permission_test")
                    try {
                        testFile.writeText("test")
                        testFile.delete()
                        Timber.d("✅ Using model refs directory: ${dir.absolutePath}")
                        return dir
                    } catch (e: Exception) {
                        Timber.w("      ⚠️  Directory claims writable but test write failed: ${e.message}")
                        continue
                    }
                } else {
                    Timber.w("      ⚠️  Directory exists but is not writable")
                }
            } catch (e: SecurityException) {
                Timber.w("      🔒 Permission denied accessing directory: ${dir.absolutePath}")
            } catch (e: Exception) {
                Timber.w(e, "      ❌ Error accessing directory: ${dir.absolutePath}")
            }
        }
        
        // Fallback to the last directory even if checks failed
        val fallbackDir = directories.last()
        Timber.w("⚠️  Using fallback model refs directory: ${fallbackDir.absolutePath}")
        
        try {
            if (!fallbackDir.exists()) {
                fallbackDir.mkdirs()
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to create fallback directory")
        }
        
        return fallbackDir
    }
    
    /**
     * Create a reference file for a model pointing to its actual location
     */
    fun createModelReference(context: Context, model: AIModel, actualModelPath: String): Boolean {
        return try {
            Timber.d("🔧 Creating model reference for ${model.modelName}")
            Timber.d("   Model file name: ${model.fileName}")
            Timber.d("   Actual model path: $actualModelPath")
            
            // Validate input parameters
            if (actualModelPath.isBlank()) {
                Timber.e("❌ Cannot create reference: actualModelPath is blank for ${model.modelName}")
                return false
            }
            
            val actualFile = File(actualModelPath)
            if (!actualFile.exists()) {
                Timber.e("❌ Cannot create reference: model file does not exist at $actualModelPath")
                return false
            }
            
            if (!actualFile.isFile()) {
                Timber.e("❌ Cannot create reference: path is not a file at $actualModelPath")
                return false
            }
            
            if (actualFile.length() == 0L) {
                Timber.e("❌ Cannot create reference: model file is empty at $actualModelPath")
                return false
            }
            
            val refsDir = getModelRefsDirectory(context)
            Timber.d("   Reference directory: ${refsDir.absolutePath}")
            
            if (!refsDir.exists()) {
                Timber.d("   Creating reference directory...")
                refsDir.mkdirs()
            }
            
            if (!refsDir.canWrite()) {
                Timber.e("❌ Cannot write to reference directory: ${refsDir.absolutePath}")
                // Try to fix permissions or suggest alternative
                try {
                    // Attempt to use a different directory if this one fails
                    val alternativeDir = File(context.filesDir, MODELS_REFS_DIR)
                    if (!alternativeDir.exists()) {
                        alternativeDir.mkdirs()
                    }
                    if (alternativeDir.canWrite()) {
                        Timber.w("⚠️  Using alternative internal directory: ${alternativeDir.absolutePath}")
                        val altRefFile = File(alternativeDir, "${model.fileName}.ref")
                        altRefFile.writeText(actualModelPath)
                        return altRefFile.exists()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to use alternative directory")
                }
                return false
            }
            
            val refFileName = "${model.fileName}.ref"
            val refFile = File(refsDir, refFileName)
            Timber.d("   Reference file: ${refFile.absolutePath}")
            
            // Check if reference already exists and points to a different file
            if (refFile.exists()) {
                try {
                    val existingPath = refFile.readText().trim()
                    if (existingPath == actualModelPath) {
                        Timber.d("✅ Reference already exists and points to the same file")
                        return true
                    } else {
                        Timber.w("⚠️  Overwriting existing reference that pointed to: $existingPath")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "⚠️  Could not read existing reference file, will overwrite")
                }
            }
            
            refFile.writeText(actualModelPath)
            Timber.d("📝 Wrote reference content to: ${refFile.absolutePath}")
            
            // Verify the reference was created correctly
            if (!refFile.exists()) {
                Timber.e("❌ Reference file was not created successfully")
                return false
            }
            
            val writtenContent = refFile.readText().trim()
            if (writtenContent != actualModelPath) {
                Timber.e("❌ Reference file content mismatch. Expected: $actualModelPath, Got: $writtenContent")
                return false
            }
            
            Timber.i("✅ Created model reference: ${refFile.absolutePath} -> $actualModelPath")
            Timber.d("   Model size: ${formatBytes(actualFile.length())}")
            
            // Final validation that the reference works
            try {
                val finalValidation = getModelFileInfo(context, model)
                if (finalValidation.isAvailable) {
                    Timber.d("✅ Final validation successful - model is now available")
                    true
                } else {
                    Timber.e("❌ Final validation failed - model still not available after reference creation")
                    Timber.e("   Validation result: ${finalValidation}")
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Exception during final validation")
                false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "❌ Security exception creating model reference for ${model.modelName}: Permission denied")
            Timber.e("   This might be a file system permission issue")
            Timber.e("   Actual model path: $actualModelPath")
            false
        } catch (e: java.io.IOException) {
            Timber.e(e, "❌ IO exception creating model reference for ${model.modelName}")
            Timber.e("   This might be a file system or storage issue")
            Timber.e("   Actual model path: $actualModelPath")
            false
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to create model reference for ${model.modelName}")
            Timber.e("   Exception type: ${e.javaClass.simpleName}")
            Timber.e("   Exception message: ${e.message}")
            Timber.e("   Actual model path: $actualModelPath")
            false
        }
    }
    
    /**
     * Read the actual model path from a reference file
     */
    private fun readModelReference(context: Context, model: AIModel): String? {
        val directories = getModelRefsDirectories(context)
        val refFileName = "${model.fileName}.ref"
        
        Timber.v("🔍 Reading model reference for ${model.modelName}")
        Timber.v("   Looking for reference file: $refFileName")
        Timber.v("   Searching in ${directories.size} directories...")
        
        for ((index, dir) in directories.withIndex()) {
            Timber.v("   ${index + 1}. Checking directory: ${dir.absolutePath}")
            
            if (!dir.exists()) {
                Timber.v("      Directory does not exist")
                continue
            }
            
            if (!dir.isDirectory()) {
                Timber.v("      Path is not a directory")
                continue
            }
            
            val refFile = File(dir, refFileName)
            Timber.v("      Looking for: ${refFile.absolutePath}")
            
            if (!refFile.exists()) {
                Timber.v("      Reference file does not exist")
                continue
            }
            
            if (!refFile.isFile()) {
                Timber.w("      ⚠️  Reference path exists but is not a file")
                continue
            }
            
            try {
                val modelPath = refFile.readText().trim()
                Timber.v("      Reference content: '$modelPath'")
                
                if (modelPath.isBlank()) {
                    Timber.w("      ⚠️  Reference file is empty or contains only whitespace")
                    continue
                }
                
                val actualFile = File(modelPath)
                Timber.v("      Verifying target file: ${actualFile.absolutePath}")
                
                if (!actualFile.exists()) {
                    Timber.w("      ⚠️  Model reference points to non-existent file: $modelPath")
                    continue
                }
                
                if (!actualFile.isFile()) {
                    Timber.w("      ⚠️  Model reference points to non-file: $modelPath")
                    continue
                }
                
                if (actualFile.length() == 0L) {
                    Timber.w("      ⚠️  Model file is empty: $modelPath")
                    continue
                }
                
                Timber.d("✅ Found valid model reference: ${refFile.absolutePath} -> $modelPath")
                Timber.d("   Model file size: ${formatBytes(actualFile.length())}")
                return modelPath
            } catch (e: SecurityException) {
                Timber.w("      🔒 Permission denied reading reference file: ${refFile.absolutePath}")
                continue
            } catch (e: Exception) {
                Timber.w(e, "      ❌ Failed to read reference file: ${refFile.absolutePath}")
                continue
            }
        }
        
        Timber.d("❌ No valid model reference found for ${model.modelName}")
        return null
    }
    
    /**
     * Remove a model reference
     */
    fun removeModelReference(context: Context, model: AIModel): Boolean {
        val directories = getModelRefsDirectories(context)
        val refFileName = "${model.fileName}.ref"
        var removed = false
        
        Timber.d("🗑️  Removing model reference for ${model.modelName}")
        Timber.d("   Reference file name: $refFileName")
        
        for ((index, dir) in directories.withIndex()) {
            Timber.v("   ${index + 1}. Checking directory: ${dir.absolutePath}")
            
            if (!dir.exists()) {
                Timber.v("      Directory does not exist")
                continue
            }
            
            val refFile = File(dir, refFileName)
            if (refFile.exists()) {
                try {
                    // Log what we're about to remove
                    try {
                        val content = refFile.readText().trim()
                        Timber.d("      Found reference pointing to: $content")
                    } catch (e: Exception) {
                        Timber.d("      Found reference (could not read content)")
                    }
                    
                    val deleted = refFile.delete()
                    if (deleted) {
                        Timber.d("✅ Removed model reference: ${refFile.absolutePath}")
                        removed = true
                    } else {
                        Timber.w("⚠️  Failed to delete reference file: ${refFile.absolutePath}")
                    }
                } catch (e: SecurityException) {
                    Timber.w("🔒 Permission denied removing reference file: ${refFile.absolutePath}")
                } catch (e: Exception) {
                    Timber.w(e, "❌ Error removing reference file: ${refFile.absolutePath}")
                }
            } else {
                Timber.v("      Reference file does not exist")
            }
        }
        
        if (removed) {
            Timber.i("✅ Successfully removed reference for ${model.modelName}")
        } else {
            Timber.d("ℹ️  No reference files found to remove for ${model.modelName}")
        }
        
        return removed
    }
    
    /**
     * Get the file for a specific model if it exists (via reference)
     * Now includes enhanced permission validation
     */
    fun getModelFile(context: Context, model: AIModel): File? {
        val modelPath = readModelReference(context, model)
        return if (modelPath != null) {
            val file = File(modelPath)
            
            // Use enhanced validation
            val accessResult = validateFileAccess(file)
            if (accessResult.isAccessible) {
                Timber.d("✅ Model file is accessible: ${file.absolutePath}")
                file
            } else {
                Timber.w("❌ Model file access failed: ${accessResult.message}")
                null
            }
        } else {
            Timber.d("❌ No model reference found for ${model.modelName}")
            null
        }
    }
    
    /**
     * Find a model file via its reference
     */
    private fun findModelFile(context: Context, fileName: String): File? {
        // Find the model that matches this fileName
        val model = AIModel.getAllModels().find { it.fileName == fileName }
        return if (model != null) {
            getModelFile(context, model)
        } else null
    }
    
    /**
     * Get list of available models based on reference files pointing to actual .task files
     * Excludes models that have been marked as failed
     */
    fun getAvailableModels(context: Context, includeFailed: Boolean = false): List<AIModel> {
        val availableModels = mutableListOf<AIModel>()
        val directories = getModelRefsDirectories(context)
        
        Timber.v("🔍 Getting available models (includeFailed: $includeFailed)...")
        
        // Load failed models list
        if (failedModels.isEmpty()) {
            loadFailedModels(context)
        }
        Timber.v("   Failed models loaded: ${failedModels.size} (${failedModels.joinToString(", ")})")
        
        try {
            // Collect all .ref files from all directories
            val allRefFiles = mutableSetOf<String>()
            var totalRefCount = 0
            
            for ((dirIndex, dir) in directories.withIndex()) {
                Timber.v("   [${dirIndex + 1}/${directories.size}] Checking directory: ${dir.absolutePath}")
                
                if (!dir.exists()) {
                    Timber.v("      Directory does not exist")
                    continue
                }
                
                if (!dir.isDirectory()) {
                    Timber.v("      Path is not a directory")
                    continue
                }
                
                try {
                    val refFiles = dir.listFiles()?.filter { 
                        it.name.endsWith(".ref") && it.isFile() 
                    }?.map { it.name } ?: emptyList()
                    
                    totalRefCount += refFiles.size
                    allRefFiles.addAll(refFiles)
                    
                    Timber.v("      Found ${refFiles.size} reference files: ${refFiles.joinToString(", ")}")
                } catch (e: SecurityException) {
                    Timber.w("      🔒 Permission denied reading directory")
                } catch (e: Exception) {
                    Timber.w(e, "      ❌ Error reading directory")
                }
            }
            
            Timber.d("📋 Total reference files found: $totalRefCount unique: ${allRefFiles.size}")
            Timber.v("   All .ref files: ${allRefFiles.joinToString(", ")}")
            
            // Check each model enum to see if its corresponding reference file exists and points to a valid model
            val allModels = AIModel.getAllModels()
            Timber.d("   Checking ${allModels.size} known model types...")
            
            var validReferences = 0
            var invalidReferences = 0
            var skippedFailed = 0
            
            for ((modelIndex, model) in allModels.withIndex()) {
                val expectedRefFile = "${model.fileName}.ref"
                Timber.v("   [${modelIndex + 1}/${allModels.size}] Checking ${model.modelName}")
                Timber.v("      Expected reference file: $expectedRefFile")
                
                if (allRefFiles.contains(expectedRefFile)) {
                    Timber.v("      ✅ Reference file exists")
                    
                    // Check if the reference points to a valid model file
                    val modelFile = getModelFile(context, model)
                    if (modelFile != null && modelFile.exists() && modelFile.isFile() && modelFile.length() > 0) {
                        Timber.v("      ✅ Model file is valid: ${modelFile.absolutePath} (${formatBytes(modelFile.length())})")
                        
                        // Skip failed models unless explicitly requested
                        if (!includeFailed && isModelFailed(context, model)) {
                            Timber.d("      ⚠️  Skipping failed model: ${model.modelName}")
                            skippedFailed++
                            continue
                        }
                        
                        availableModels.add(model)
                        validReferences++
                        Timber.d("      ✅ Model added: ${model.modelName}")
                    } else {
                        Timber.w("      ❌ Model reference exists but points to invalid file: ${model.modelName}")
                        if (modelFile == null) {
                            Timber.v("         No model file returned from reference")
                        } else if (!modelFile.exists()) {
                            Timber.v("         Model file does not exist: ${modelFile.absolutePath}")
                        } else if (!modelFile.isFile()) {
                            Timber.v("         Path is not a file: ${modelFile.absolutePath}")
                        } else if (modelFile.length() == 0L) {
                            Timber.v("         Model file is empty: ${modelFile.absolutePath}")
                        }
                        invalidReferences++
                    }
                } else {
                    Timber.v("      ❌ Missing model reference: ${model.modelName}")
                }
            }
            
            // Check for unknown .ref files
            var unknownRefCount = 0
            for (refFileName in allRefFiles) {
                val taskFileName = refFileName.removeSuffix(".ref")
                val isKnown = allModels.any { it.fileName == taskFileName }
                if (!isKnown) {
                    Timber.w("⚠️ Unknown .ref file: $refFileName")
                    unknownRefCount++
                }
            }
            
            Timber.d("📊 Model availability summary:")
            Timber.d("   Total models checked: ${allModels.size}")
            Timber.d("   Valid references: $validReferences")
            Timber.d("   Invalid references: $invalidReferences")
            Timber.d("   Skipped (failed): $skippedFailed")
            Timber.d("   Unknown references: $unknownRefCount")
            Timber.d("   Available models: ${availableModels.size}")
            Timber.d("🎯 Final result: ${availableModels.size} models available (failed models ${if (includeFailed) "included" else "excluded"})")
            
        } catch (e: Exception) {
            Timber.e(e, "❌ Critical error while scanning for available models")
        }
        
        return availableModels
    }
    
    /**
     * Check if a specific model is available via reference
     */
    fun isModelAvailable(context: Context, model: AIModel): Boolean {
        return getModelFile(context, model) != null
    }
    
    /**
     * Get the full path to a model file (via reference)
     */
    fun getModelPath(context: Context, model: AIModel): String? {
        return getModelFile(context, model)?.absolutePath
    }
    
    /**
     * Get model file info with storage location
     */
    fun getModelFileInfo(context: Context, model: AIModel): ModelFileInfo {
        val modelFile = findModelFile(context, model.fileName)
        return if (modelFile != null && modelFile.exists()) {
            ModelFileInfo(
                fileName = model.fileName,
                fileSizeBytes = modelFile.length(),
                lastModified = modelFile.lastModified(),
                isAvailable = true,
                storagePath = modelFile.parent ?: ""
            )
        } else {
            ModelFileInfo(
                fileName = model.fileName,
                fileSizeBytes = 0,
                lastModified = 0,
                isAvailable = false,
                storagePath = ""
            )
        }
    }
    
    /**
     * Migrate model references from less persistent to more persistent storage
     */
    fun migrateModelReferencesToMostPersistentStorage(context: Context) {
        try {
            val directories = getModelRefsDirectories(context)
            val targetDir = directories.first() // Most persistent directory
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            if (!targetDir.canWrite()) {
                Timber.w("Cannot write to most persistent directory: ${targetDir.absolutePath}")
                return
            }
            
            var migratedCount = 0
            
            // Check other directories for references to migrate
            for (i in 1 until directories.size) {
                val sourceDir = directories[i]
                if (!sourceDir.exists()) continue
                
                val refFiles = sourceDir.listFiles()?.filter { it.name.endsWith(".ref") } ?: continue
                
                for (refFile in refFiles) {
                    val targetFile = File(targetDir, refFile.name)
                    
                    // Only migrate if target doesn't already exist
                    if (!targetFile.exists()) {
                        try {
                            refFile.copyTo(targetFile)
                            refFile.delete()
                            migratedCount++
                            Timber.d("Migrated reference: ${refFile.name}")
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to migrate reference: ${refFile.name}")
                        }
                    }
                }
            }
            
            if (migratedCount > 0) {
                Timber.i("Migrated $migratedCount model references to persistent storage")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to migrate model references")
        }
    }
    
    /**
     * Scan common locations for model files and automatically create references
     * This helps users who have manually downloaded models
     */
    fun scanAndCreateModelReferences(context: Context): Int {
        var referencesCreated = 0
        
        try {
            Timber.d("🔍 Starting scan for local model files...")
            val scanStartTime = System.currentTimeMillis()
            
            // Common locations where users might store models
            val scanLocations = mutableListOf<File>()
            
            // External storage directories
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                val externalStorage = Environment.getExternalStorageDirectory()
                Timber.d("   External storage available: ${externalStorage.absolutePath}")
                
                val externalDirs = listOf(
                    File(externalStorage, "Download"),
                    File(externalStorage, "Downloads"),
                    File(externalStorage, "Models"),
                    File(externalStorage, "AI"),
                    File(externalStorage, "local-ai-models")
                )
                scanLocations.addAll(externalDirs)
                
                // Add public directories if they exist
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { downloadsDir ->
                    scanLocations.add(downloadsDir)
                    Timber.d("   Added public downloads: ${downloadsDir.absolutePath}")
                }
                
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let { documentsDir ->
                    val docDirs = listOf(
                        File(documentsDir, "Models"),
                        File(documentsDir, "AI")
                    )
                    scanLocations.addAll(docDirs)
                    Timber.d("   Added documents subdirs: ${docDirs.map { it.absolutePath }}")
                }
            } else {
                Timber.w("⚠️  External storage not available for scanning")
            }
            
            // App-specific external storage
            context.getExternalFilesDir(null)?.let { appExternal ->
                val appDirs = listOf(
                    appExternal,
                    File(appExternal, "models"),
                    File(appExternal, "downloads")
                )
                scanLocations.addAll(appDirs)
                Timber.d("   Added app external dirs: ${appDirs.map { it.absolutePath }}")
            }
            
            // Internal storage
            val internalDirs = listOf(
                context.filesDir,
                File(context.filesDir, "models")
            )
            scanLocations.addAll(internalDirs)
            Timber.d("   Added internal dirs: ${internalDirs.map { it.absolutePath }}")
            
            Timber.d("   Total scan locations: ${scanLocations.size}")
            
            val allModelFiles = AIModel.getAllModels()
            Timber.d("   Looking for ${allModelFiles.size} known model types:")
            allModelFiles.forEach { model ->
                Timber.v("      - ${model.modelName} (${model.fileName})")
            }
            
            // Get existing references to avoid duplicates
            val existingRefs = getModelRefsDirectories(context)
                .flatMap { dir -> 
                    if (dir.exists()) {
                        try {
                            val refs = dir.listFiles()?.filter { it.name.endsWith(".ref") }?.map { it.name.removeSuffix(".ref") } ?: emptyList()
                            Timber.v("      Existing refs in ${dir.absolutePath}: ${refs.joinToString(", ")}")
                            refs
                        } catch (e: Exception) {
                            Timber.w(e, "      Error reading refs from ${dir.absolutePath}")
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }
            
            Timber.d("   Existing references (${existingRefs.size}): ${existingRefs.joinToString(", ")}")
            
            var scannedLocations = 0
            var scannedFiles = 0
            var validTaskFiles = 0
            
            for ((locationIndex, location) in scanLocations.withIndex()) {
                Timber.v("   [${locationIndex + 1}/${scanLocations.size}] Scanning: ${location.absolutePath}")
                
                if (!location.exists()) {
                    Timber.v("      Location does not exist")
                    continue
                }
                
                if (!location.isDirectory()) {
                    Timber.v("      Location is not a directory")
                    continue
                }
                
                scannedLocations++
                
                try {
                    val allFiles = location.listFiles()
                    if (allFiles == null) {
                        Timber.w("      ⚠️  Could not list files (permission denied?)")
                        continue
                    }
                    
                    val taskFiles = allFiles.filter { 
                        it.name.endsWith(".task") && it.isFile() && it.length() > 0 
                    }
                    
                    scannedFiles += allFiles.size
                    Timber.v("      Found ${allFiles.size} files total, ${taskFiles.size} .task files")
                    
                    if (taskFiles.isEmpty()) {
                        continue
                    }
                    
                    for ((fileIndex, taskFile) in taskFiles.withIndex()) {
                        val fileName = taskFile.name
                        val fileSize = taskFile.length()
                        
                        Timber.v("         [${fileIndex + 1}/${taskFiles.size}] Found .task file: $fileName (${formatBytes(fileSize)})")
                        validTaskFiles++
                        
                        // Check if this matches any known model
                        val matchingModel = allModelFiles.find { it.fileName == fileName }
                        if (matchingModel != null) {
                            Timber.d("         ✅ Matches known model: ${matchingModel.modelName}")
                            
                            // Check if reference already exists
                            if (!existingRefs.contains(fileName)) {
                                Timber.d("         🔧 Creating new reference...")
                                if (createModelReference(context, matchingModel, taskFile.absolutePath)) {
                                    referencesCreated++
                                    Timber.i("         ✅ Created reference for ${matchingModel.modelName}")
                                } else {
                                    Timber.w("         ❌ Failed to create reference for ${matchingModel.modelName}")
                                }
                            } else {
                                Timber.d("         ⏭️  Reference already exists, skipping")
                            }
                        } else {
                            Timber.d("         ❓ Unknown model file: $fileName")
                            Timber.v("            Expected one of: ${allModelFiles.map { it.fileName }.joinToString(", ")}")
                        }
                    }
                } catch (e: SecurityException) {
                    Timber.w("      🔒 Permission denied scanning: ${location.absolutePath}")
                } catch (e: Exception) {
                    Timber.w(e, "      ❌ Error scanning: ${location.absolutePath}")
                }
            }
            
            val scanTime = System.currentTimeMillis() - scanStartTime
            
            if (referencesCreated > 0) {
                Timber.i("🎉 Scan completed: Created $referencesCreated new model references")
            } else {
                Timber.d("ℹ️  Scan completed: No new model references needed")
            }
            
            Timber.d("📊 Scan statistics:")
            Timber.d("   Locations scanned: $scannedLocations/${scanLocations.size}")
            Timber.d("   Files examined: $scannedFiles")
            Timber.d("   Valid .task files found: $validTaskFiles")
            Timber.d("   New references created: $referencesCreated")
            Timber.d("   Scan duration: ${scanTime}ms")
            
        } catch (e: Exception) {
            Timber.e(e, "❌ Critical error during model file scanning")
        }
        
        return referencesCreated
    }

    /**
     * Initialize model detection system - call this on app startup
     * Combines migration and scanning in one operation
     */
    fun initializeModelSystem(context: Context): Pair<Int, Int> {
        var migratedCount = 0
        var scannedCount = 0
        
        Timber.i("🚀 Initializing AI model system...")
        val initStartTime = System.currentTimeMillis()
        
        try {
            // First migrate existing references
            Timber.d("   Step 1: Migrating existing model references...")
            val migrationStartTime = System.currentTimeMillis()
            migrateModelReferencesToMostPersistentStorage(context)
            val migrationTime = System.currentTimeMillis() - migrationStartTime
            Timber.d("   Migration completed in ${migrationTime}ms")
            
            // Then scan for new models
            Timber.d("   Step 2: Scanning for local model files...")
            val scanStartTime = System.currentTimeMillis()
            scannedCount = scanAndCreateModelReferences(context)
            val scanTime = System.currentTimeMillis() - scanStartTime
            Timber.d("   Scanning completed in ${scanTime}ms")
            
            // Log status for debugging
            Timber.d("   Step 3: Logging model status...")
            logModelStatus(context)
            
            val totalTime = System.currentTimeMillis() - initStartTime
            Timber.i("✅ Model system initialization completed in ${totalTime}ms")
            Timber.i("   Migration results: $migratedCount references migrated")
            Timber.i("   Scan results: $scannedCount new references created")
            
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - initStartTime
            Timber.e(e, "❌ Failed to initialize model system after ${totalTime}ms")
        }
        
        return Pair(migratedCount, scannedCount)
    }

    /**
     * Check if any models are available (for enabling /ai/text endpoints)
     */
    fun isAITextEnabled(context: Context): Boolean {
        val available = getAvailableModels(context).isNotEmpty()
        Timber.v("🤖 AI text enabled check: $available")
        return available
    }
    
    /**
     * Validate a specific model's setup and return detailed diagnostic information
     */
    fun validateModelSetup(context: Context, model: AIModel): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        Timber.d("🔍 Validating model setup for ${model.modelName}...")
        
        try {
            // Check if reference file exists
            val directories = getModelRefsDirectories(context)
            val refFileName = "${model.fileName}.ref"
            var refFileFound = false
            var refFilePath: String? = null
            var refFileContent: String? = null
            
            for (dir in directories) {
                val refFile = File(dir, refFileName)
                if (refFile.exists()) {
                    refFileFound = true
                    refFilePath = refFile.absolutePath
                    try {
                        refFileContent = refFile.readText().trim()
                        break
                    } catch (e: Exception) {
                        Timber.w(e, "Could not read reference file: ${refFile.absolutePath}")
                    }
                }
            }
            
            result["referenceFileExists"] = refFileFound
            result["referenceFilePath"] = refFilePath ?: "Not found"
            result["referenceFileContent"] = refFileContent ?: "Could not read"
            
            // Check if model file exists
            var modelFileExists = false
            var modelFilePath: String? = null
            var modelFileSize = 0L
            var modelFileReadable = false
            
            if (refFileContent != null) {
                val modelFile = File(refFileContent)
                modelFileExists = modelFile.exists()
                modelFilePath = refFileContent
                if (modelFileExists) {
                    modelFileSize = modelFile.length()
                    modelFileReadable = modelFile.canRead()
                }
            }
            
            result["modelFileExists"] = modelFileExists
            result["modelFilePath"] = modelFilePath ?: "No reference"
            result["modelFileSize"] = modelFileSize
            result["modelFileSizeFormatted"] = formatBytes(modelFileSize)
            result["modelFileReadable"] = modelFileReadable
            
            // Check if model is marked as failed
            val isFailed = isModelFailed(context, model)
            result["markedAsFailed"] = isFailed
            
            // Overall availability
            val isAvailable = refFileFound && modelFileExists && modelFileSize > 0 && modelFileReadable && !isFailed
            result["isAvailable"] = isAvailable
            
            Timber.d("   Reference file: $refFileFound at $refFilePath")
            Timber.d("   Model file: $modelFileExists at $modelFilePath (${formatBytes(modelFileSize)})")
            Timber.d("   Failed status: $isFailed")
            Timber.d("   Overall available: $isAvailable")
            
        } catch (e: Exception) {
            Timber.e(e, "Error validating model setup for ${model.modelName}")
            result["error"] = e.message ?: "Unknown error"
        }
        
        return result
    }
    
    /**
     * Get comprehensive diagnostic information about the model system
     */
    fun getModelSystemDiagnostics(context: Context): Map<String, Any> {
        val diagnostics = mutableMapOf<String, Any>()
        
        try {
            Timber.d("🔬 Generating model system diagnostics...")
            
            // Storage directories info
            val directories = getModelRefsDirectories(context)
            val dirInfo = directories.mapIndexed { index, dir ->
                mapOf(
                    "index" to index + 1,
                    "path" to dir.absolutePath,
                    "exists" to dir.exists(),
                    "isDirectory" to (dir.exists() && dir.isDirectory()),
                    "canRead" to (dir.exists() && dir.canRead()),
                    "canWrite" to (dir.exists() && dir.canWrite()),
                    "refCount" to if (dir.exists()) {
                        try {
                            dir.listFiles()?.count { it.name.endsWith(".ref") } ?: 0
                        } catch (e: Exception) {
                            -1
                        }
                    } else 0
                )
            }
            diagnostics["referenceDirectories"] = dirInfo
            
            // Model availability
            val allModels = AIModel.getAllModels()
            val modelInfo = allModels.map { model ->
                val validation = validateModelSetup(context, model)
                mapOf(
                    "modelName" to model.modelName,
                    "fileName" to model.fileName,
                    "validation" to validation
                )
            }
            diagnostics["models"] = modelInfo
            
            // Summary stats
            val availableCount = getAvailableModels(context, includeFailed = false).size
            val availableWithFailedCount = getAvailableModels(context, includeFailed = true).size
            val failedCount = failedModels.size
            
            diagnostics["summary"] = mapOf(
                "totalModels" to allModels.size,
                "availableModels" to availableCount,
                "availableIncludingFailed" to availableWithFailedCount,
                "failedModels" to failedCount,
                "aiTextEnabled" to isAITextEnabled(context)
            )
            
            Timber.d("📊 Diagnostics summary: $availableCount/$allModels.size models available")
            
        } catch (e: Exception) {
            Timber.e(e, "Error generating diagnostics")
            diagnostics["error"] = e.message ?: "Unknown error"
        }
        
        return diagnostics
    }
    
    /**
     * Log detailed model status for debugging with storage locations
     */
    fun logModelStatus(context: Context) {
        Timber.d("📊 AI Model Status Report:")
        Timber.d("Reference storage directories (in order of persistence):")
        val directories = getModelRefsDirectories(context)
        directories.forEachIndexed { index, dir ->
            val exists = dir.exists()
            val writable = if (exists) dir.canWrite() else false
            val refCount = if (exists) (dir.listFiles()?.filter { it.name.endsWith(".ref") }?.size ?: 0) else 0
            val taskCount = if (exists) (dir.listFiles()?.filter { it.name.endsWith(".task") }?.size ?: 0) else 0
            Timber.d("   ${index + 1}. ${dir.absolutePath} (exists: $exists, writable: $writable, refs: $refCount, tasks: $taskCount)")
            
            if (exists && refCount > 0) {
                val refFiles = dir.listFiles()?.filter { it.name.endsWith(".ref") } ?: emptyList()
                refFiles.forEach { refFile ->
                    try {
                        val targetPath = refFile.readText().trim()
                        val targetFile = File(targetPath)
                        val status = if (targetFile.exists() && targetFile.isFile()) "✅" else "❌"
                        Timber.d("      $status ${refFile.name} -> $targetPath")
                    } catch (e: Exception) {
                        Timber.d("      ❌ ${refFile.name} -> ERROR reading reference")
                    }
                }
            }
        }
        
        Timber.d("Model availability:")
        for (model in AIModel.getAllModels()) {
            val modelFile = getModelFile(context, model)
            val refFileName = "${model.fileName}.ref"
            val status = if (modelFile != null) {
                val sizeFormatted = formatBytes(modelFile.length())
                "✅ AVAILABLE ($sizeFormatted) at ${modelFile.absolutePath}"
            } else {
                "❌ MISSING (no valid reference file: $refFileName)"
            }
            val failedStatus = if (isModelFailed(context, model)) " [MARKED AS FAILED]" else ""
            Timber.d("   $status ${model.modelName}$failedStatus")
        }
        val totalAvailable = getAvailableModels(context).size
        val totalModels = AIModel.getAllModels().size
        Timber.d("📈 Summary: $totalAvailable/$totalModels models available")
    }
    
    /**
     * Generate comprehensive permission and storage diagnostics
     */
    fun generatePermissionDiagnostics(context: Context): String {
        val diagnostics = StringBuilder()
        
        diagnostics.appendLine("🔍 AI Model Permission & Storage Diagnostics")
        diagnostics.appendLine("====================================================")
        
        // Android version and SDK info
        diagnostics.appendLine("📱 Device Info:")
        diagnostics.appendLine("   Android Version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        diagnostics.appendLine("   Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        
        // Permission status
        diagnostics.appendLine("\n🔐 Permission Status:")
        val appPermissionManager = me.bechberger.phoneserver.manager.AppPermissionManager.getInstance()
        diagnostics.appendLine("   Storage Permissions: ${appPermissionManager.hasStoragePermissions(context)}")
        diagnostics.appendLine("   Enhanced Storage: ${appPermissionManager.hasEnhancedStoragePermissions(context)}")
        
        // Storage directory analysis
        diagnostics.appendLine("\n📂 Storage Directory Analysis:")
        val directories = getModelRefsDirectories(context)
        directories.forEachIndexed { index, dir ->
            val priority = when (index) {
                0 -> "HIGH (Most Persistent)"
                1 -> "MEDIUM (App-Specific)"
                else -> "LOW (Internal Only)"
            }
            
            diagnostics.appendLine("   ${index + 1}. Priority: $priority")
            diagnostics.appendLine("      Path: ${dir.absolutePath}")
            diagnostics.appendLine("      Exists: ${dir.exists()}")
            if (dir.exists()) {
                diagnostics.appendLine("      Readable: ${dir.canRead()}")
                diagnostics.appendLine("      Writable: ${dir.canWrite()}")
                
                // Test actual write access
                val testFile = File(dir, ".permission_test_${System.currentTimeMillis()}")
                try {
                    testFile.writeText("test")
                    testFile.delete()
                    diagnostics.appendLine("      Write Test: ✅ SUCCESS")
                } catch (e: Exception) {
                    diagnostics.appendLine("      Write Test: ❌ FAILED (${e.javaClass.simpleName}: ${e.message})")
                }
                
                val refCount = dir.listFiles()?.filter { it.name.endsWith(".ref") }?.size ?: 0
                diagnostics.appendLine("      Reference Files: $refCount")
            }
        }
        
        // Model file analysis
        diagnostics.appendLine("\n🤖 Model File Analysis:")
        for (model in AIModel.getAllModels()) {
            diagnostics.appendLine("   ${model.modelName}:")
            val modelFile = getModelFile(context, model)
            if (modelFile != null) {
                val accessResult = validateFileAccess(modelFile)
                diagnostics.appendLine("      Status: ${if (accessResult.isAccessible) "✅ ACCESSIBLE" else "❌ INACCESSIBLE"}")
                diagnostics.appendLine("      Path: ${modelFile.absolutePath}")
                diagnostics.appendLine("      Size: ${formatBytes(modelFile.length())}")
                if (!accessResult.isAccessible) {
                    diagnostics.appendLine("      Issue: ${accessResult.message}")
                }
            } else {
                diagnostics.appendLine("      Status: ❌ NOT FOUND")
                diagnostics.appendLine("      Expected Reference: ${model.fileName}.ref")
            }
            
            if (isModelFailed(context, model)) {
                diagnostics.appendLine("      ⚠️  Marked as failed in previous tests")
            }
        }
        
        // Summary and recommendations
        diagnostics.appendLine("\n💡 Recommendations:")
        val availableModels = getAvailableModels(context).size
        val totalModels = AIModel.getAllModels().size
        
        if (availableModels == 0) {
            diagnostics.appendLine("   1. No models available - download models to Downloads folder")
            diagnostics.appendLine("   2. Grant storage permissions in Android Settings")
            diagnostics.appendLine("   3. Restart the app to trigger model detection")
        } else if (availableModels < totalModels) {
            diagnostics.appendLine("   1. Some models missing - check Downloads folder for model files")
            diagnostics.appendLine("   2. Ensure model files have correct names")
        } else {
            diagnostics.appendLine("   ✅ All models are accessible!")
        }
        
        if (!appPermissionManager.hasEnhancedStoragePermissions(context)) {
            diagnostics.appendLine("   ⚠️  Consider granting additional storage permissions for better model access")
        }
        
        return diagnostics.toString()
    }

    /**
     * Format bytes to human readable format
     */
    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        if (bytes == 0L) return "0 B"
        
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        
        return String.format("%.1f %s", value, units[digitGroups])
    }
}
