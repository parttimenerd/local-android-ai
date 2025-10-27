package me.bechberger.phoneserver.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Base64
import me.bechberger.phoneserver.services.CameraService
import me.bechberger.phoneserver.services.SharedCameraService
import me.bechberger.phoneserver.lifecycle.SimpleLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL

/**
 * Result of processing image input for multimodal inference
 */
private data class ProcessedImageInput(
    val prompt: String,
    val bitmap: Bitmap?
)

/**
 * Main AI service that coordinates model management, inference, and camera integration.
 */
class AIService(private val context: Context) {
    
    private val cameraService = CameraService(context)
    private val sharedCameraService = SharedCameraService(context)
    private val persistenceManager = ModelPersistenceManager.getInstance(context)
    private var currentInferenceService: AIInferenceService? = null
    private var currentModel: AIModel? = null
    private var modelLoadedAt: Long? = null
    
    // Processing state tracking
    @Volatile
    private var isProcessing = false
    @Volatile
    private var processingStartTime: Long? = null
    @Volatile
    private var isModelLoading = false
    @Volatile
    private var modelLoadingStartTime: Long? = null
    
    // Timeout constants
    private val MODEL_LOADING_TIMEOUT_MS = 60_000L // 60 seconds
    private val INFERENCE_TIMEOUT_MS = 300_000L // 5 minutes - increased for image processing
    
    companion object {
        private const val DEFAULT_MODEL = "GEMMA_3_1B_IT"
    }
    
    /**
     * Handle text generation request
     */
    suspend fun handleTextRequest(request: AITextRequest): AITextResponse {
        return withContext(Dispatchers.IO) {
            try {
                // Set processing state
                isProcessing = true
                processingStartTime = System.currentTimeMillis()
                
                // Get or validate model
                val model = AIModel.fromString(request.model) 
                    ?: AIModel.fromString(DEFAULT_MODEL)
                    ?: throw AIServiceException("Invalid model: ${request.model}. Available models: ${AIModel.getAllModels().joinToString(", ") { it.name }}")
                
                // Ensure model is available in assets
                if (!ModelDetector.isModelAvailable(context, model)) {
                    val availableModels = ModelDetector.getAvailableModels(context)
                    val availableNames = availableModels.joinToString(", ") { it.name }
                    throw ModelNotDownloadedException("Model ${model.modelName} (${model.fileName}) is not available. Please download it first. Available models: [$availableNames]")
                }
                
                // Initialize inference service if needed
                if (currentModel != model) {
                    try {
                        // Record unloading of previous model
                        if (currentModel != null && currentInferenceService != null) {
                            persistenceManager.recordModelUnloaded(currentModel!!)
                        }
                        
                        // Track model loading state
                        isModelLoading = true
                        modelLoadingStartTime = System.currentTimeMillis()
                        
                        // Only close the previous service when switching to a different model
                        currentInferenceService?.close()
                        Timber.d("Switching to model: ${model.modelName} (from: ${currentModel?.modelName ?: "none"})")
                        
                        // Validate model file before attempting to load
                        val modelFile = ModelDetector.getModelFile(context, model)
                        if (modelFile == null || !modelFile.exists()) {
                            throw AIServiceException("Model file not found: ${modelFile?.absolutePath ?: "unknown path"}. Please ensure the model is downloaded and accessible.")
                        }
                        
                        // Enhanced permission checks with detailed error reporting
                        try {
                            if (!modelFile.canRead()) {
                                throw AIServiceException("Model file is not readable: ${modelFile.absolutePath}. " +
                                    "This may be due to file permissions or storage access restrictions. " +
                                    "Try moving the model to the Downloads folder or granting storage permissions.")
                            }
                            
                            // Additional file validation
                            if (modelFile.length() == 0L) {
                                throw AIServiceException("Model file is empty: ${modelFile.absolutePath}. " +
                                    "The file may be corrupted or incomplete. Please re-download the model.")
                            }
                            
                            // Try to actually read the file to verify access
                            modelFile.inputStream().use { stream ->
                                val buffer = ByteArray(1024)
                                val bytesRead = stream.read(buffer)
                                if (bytesRead <= 0) {
                                    throw AIServiceException("Cannot read model file content: ${modelFile.absolutePath}. " +
                                        "File may be corrupted or access is restricted.")
                                }
                            }
                        } catch (e: SecurityException) {
                            throw AIServiceException("Security restriction accessing model file: ${modelFile.absolutePath}. " +
                                "Please check app permissions for storage access and ensure the file is in an accessible location.")
                        } catch (e: java.io.IOException) {
                            throw AIServiceException("I/O error reading model file: ${modelFile.absolutePath}. " +
                                "Error: ${e.message}. The file may be corrupted, in use by another app, or on inaccessible storage.")
                        }
                        
                        Timber.d("Model file validation passed: ${modelFile.absolutePath} (${formatBytes(modelFile.length())})")
                        
                        // Create inference service with timeout
                        currentInferenceService = createInferenceServiceWithTimeout(model)
                        currentModel = model
                        modelLoadedAt = System.currentTimeMillis()
                        
                        // Record model loading in persistence manager
                        persistenceManager.recordModelLoaded(model)
                        
                        val loadingTime = System.currentTimeMillis() - (modelLoadingStartTime ?: 0)
                        Timber.d("Successfully loaded AI model: ${model.modelName} in ${loadingTime}ms")
                    } catch (e: Exception) {
                        currentInferenceService = null
                        currentModel = null
                        modelLoadedAt = null
                        
                        // Check if this was a timeout
                        val loadingTime = System.currentTimeMillis() - (modelLoadingStartTime ?: 0)
                        val isTimeout = loadingTime > MODEL_LOADING_TIMEOUT_MS
                        
                        Timber.e(e, "Failed to initialize AI inference service for model: ${model.modelName} (loading time: ${loadingTime}ms, timeout: $isTimeout)")
                        
                        // Mark model as failed for future filtering
                        ModelDetector.markModelAsFailed(context, model)
                        
                        // Provide detailed error diagnostics
                        val errorDetails = buildString {
                            if (isTimeout) {
                                append("Model loading timed out after ${loadingTime}ms (limit: ${MODEL_LOADING_TIMEOUT_MS}ms).")
                                appendLine()
                                append("This usually indicates MediaPipe initialization issues or insufficient system resources.")
                            } else {
                                append("Failed to initialize AI inference service for model ${model.modelName}.")
                            }
                            appendLine()
                            appendLine("Error: ${e.message}")
                            appendLine("Exception type: ${e.javaClass.simpleName}")
                            appendLine("Loading time: ${loadingTime}ms")
                            
                            // Model file diagnostics
                            try {
                                val modelFile = ModelDetector.getModelFile(context, model)
                                appendLine("Model file: ${modelFile?.absolutePath ?: "Not found"}")
                                appendLine("File exists: ${modelFile?.exists() ?: false}")
                                if (modelFile?.exists() == true) {
                                    appendLine("File size: ${formatBytes(modelFile.length())}")
                                    appendLine("File readable: ${modelFile.canRead()}")
                                    appendLine("Last modified: ${java.util.Date(modelFile.lastModified())}")
                                }
                            } catch (fileE: Exception) {
                                appendLine("Could not get model file info: ${fileE.message}")
                            }
                            
                            // Basic system info
                            appendLine("MediaPipe backend: ${model.preferredBackend?.name ?: "AUTO"}")
                            
                            // Check for common issues and provide targeted help
                            if (e.message?.contains("Failed to create session") == true) {
                                appendLine()
                                appendLine("Model compatibility issue detected.")
                                appendLine("This model may not be compatible with the current MediaPipe version.")
                            } else if (e.message?.contains("OutOfMemory") == true || e.message?.contains("memory") == true) {
                                appendLine()
                                appendLine("Memory issue detected. Try restarting the app or using a smaller model.")
                            }
                            
                            appendLine()
                            appendLine("This model has been marked as failed and will be excluded from future selections.")
                            appendLine("You can retry testing it from the Model Manager.")
                            
                            if (isTimeout) {
                                appendLine()
                                appendLine("Troubleshooting timeout issues:")
                                appendLine("- Restart the app to clear any stuck MediaPipe processes")
                                appendLine("- Check if the model file is corrupted by re-downloading")
                            }
                        }
                        
                        throw AIServiceException(errorDetails)
                    } finally {
                        // Clear model loading state
                        isModelLoading = false
                        modelLoadingStartTime = null
                    }
                } else {
                    Timber.d("Using already loaded model: ${model.modelName}")
                }
                
                val inferenceService = currentInferenceService 
                    ?: throw AIServiceException("Inference service is null after initialization attempt. Model: ${model.modelName}. This may indicate a MediaPipe initialization failure or insufficient device resources.")
                
                // Handle image input if provided
                val hasImageInput = request.image != null || request.captureConfig != null
                val canUseMultimodal = hasImageInput && model.supportsVision
                
                val result = try {
                    // Apply timeout to the entire inference operation
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        if (canUseMultimodal) {
                            // Use multimodal inference for vision-capable models with image input
                            val processedInput = processImageInput(request)
                            if (processedInput.bitmap != null) {
                                Timber.d("Using multimodal inference with ${model.modelName}")
                                inferenceService.generateMultimodalText(
                                    prompt = request.text,
                                    image = processedInput.bitmap,
                                    temperature = request.temperature,
                                    topK = request.topK,
                                    topP = request.topP
                                )
                            } else {
                                // Fallback to text-only if bitmap extraction failed
                                Timber.w("Bitmap extraction failed, falling back to text-only inference")
                                inferenceService.generateText(
                                    prompt = processedInput.prompt,
                                    temperature = request.temperature,
                                    topK = request.topK,
                                    topP = request.topP
                                )
                            }
                        } else {
                            // Use text-only inference
                            val finalPrompt = if (hasImageInput) {
                                val processedInput = processImageInput(request)
                                processedInput.prompt
                            } else {
                                request.text
                            }
                            
                            Timber.d("Starting text-only inference with ${model.modelName}")
                            inferenceService.generateText(
                                prompt = finalPrompt,
                                temperature = request.temperature,
                                topK = request.topK,
                                topP = request.topP
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Timber.e(e, "AI inference timed out after ${INFERENCE_TIMEOUT_MS}ms for model: ${model.modelName}")
                    throw AIServiceException("AI inference timed out after ${INFERENCE_TIMEOUT_MS / 1000} seconds for model ${model.modelName}. This typically happens with complex image processing or when the model is overloaded. Try using a smaller image or restart the app.")
                } catch (e: Exception) {
                    Timber.e(e, "AI inference execution failed for model: ${model.modelName}")
                    throw AIServiceException("AI inference failed for model ${model.modelName}: ${e.message}. This may be due to insufficient memory, corrupted model file, or MediaPipe runtime issues.")
                }
                
                // Handle image capture if returnImage is requested
                if (request.returnImage) {
                    return@withContext addImageToResponse(result, request.imageScaling)
                }
                
                result
                
            } catch (e: ModelNotDownloadedException) {
                Timber.e(e, "Model not available: ${e.message}")
                throw e
            } catch (e: AIServiceException) {
                Timber.e(e, "AI service error: ${e.message}")
                throw e
            } catch (e: OutOfMemoryError) {
                Timber.e(e, "Out of memory during AI inference")
                throw AIServiceException("Insufficient memory for AI inference. Try using a smaller model or reduce image size.")
            } catch (e: Exception) {
                Timber.e(e, "Unexpected AI text request failure")
                throw AIServiceException("Unexpected AI service error: ${e.javaClass.simpleName} - ${e.message}")
            } finally {
                // Clear processing state
                isProcessing = false
                processingStartTime = null
            }
        }
    }
    
    /**
     * Test if a model can be loaded and used for basic inference
     */
    suspend fun testModel(model: AIModel): ModelTestResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                Timber.d("Testing model: ${model.modelName}")
                
                // Validate model file
                if (!ModelDetector.isModelAvailable(context, model)) {
                    return@withContext ModelTestResult(
                        success = false,
                        model = model.modelName,
                        message = "Model file not found or not available",
                        testDuration = System.currentTimeMillis() - startTime,
                        output = null,
                        error = "Model ${model.fileName} is not available in storage"
                    )
                }
                
                // Create a temporary inference service for testing
                val testInferenceService = try {
                    AIInferenceService.create(context, model)
                } catch (e: Exception) {
                    // Provide detailed error diagnostics similar to handleTextRequest
                    val detailedError = buildString {
                        append("Failed to initialize AI inference service for model ${model.modelName}.")
                        appendLine()
                        appendLine("Error: ${e.message}")
                        appendLine("Exception type: ${e.javaClass.simpleName}")
                        
                        // Model file diagnostics
                        try {
                            val modelFile = ModelDetector.getModelFile(context, model)
                            appendLine("Model file: ${modelFile?.absolutePath ?: "Not found"}")
                            appendLine("File exists: ${modelFile?.exists() ?: false}")
                            if (modelFile?.exists() == true) {
                                appendLine("File size: ${formatBytes(modelFile.length())}")
                                appendLine("File readable: ${modelFile.canRead()}")
                                appendLine("Last modified: ${java.util.Date(modelFile.lastModified())}")
                            }
                        } catch (fileE: Exception) {
                            appendLine("Could not get model file info: ${fileE.message}")
                        }
                        
                        // MediaPipe backend info
                        appendLine("MediaPipe backend: ${model.preferredBackend?.name ?: "AUTO"}")
                        appendLine()
                        appendLine("Troubleshooting tips:")
                        appendLine("- Ensure the model file is not corrupted")
                        appendLine("- Try restarting the app")
                        appendLine("- Check if device has sufficient RAM for this model")
                    }
                    
                    return@withContext ModelTestResult(
                        success = false,
                        model = model.modelName,
                        message = "Failed to initialize model",
                        testDuration = System.currentTimeMillis() - startTime,
                        output = null,
                        error = detailedError
                    )
                }
                
                // Run a simple test inference
                val testOutput = try {
                    testInferenceService.generateText(
                        prompt = "Create a Python Hello World program",
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.95f
                    )
                } catch (e: Exception) {
                    testInferenceService.close()
                    
                    val detailedError = buildString {
                        append("Failed to generate test output with model ${model.modelName}.")
                        appendLine()
                        appendLine("Error: ${e.message}")
                        appendLine("Exception type: ${e.javaClass.simpleName}")
                        appendLine()
                        appendLine("Test prompt: \"Hello\"")
                        appendLine("Temperature: 0.7, TopK: 40, TopP: 0.95")
                        appendLine()
                        appendLine("This suggests the model loaded successfully but failed during inference.")
                        appendLine("Possible causes:")
                        appendLine("- Model file corruption during download/transfer")
                        appendLine("- Insufficient memory for inference")
                        appendLine("- Incompatible model format or version")
                        appendLine("- MediaPipe backend compatibility issues")
                    }
                    
                    return@withContext ModelTestResult(
                        success = false,
                        model = model.modelName,
                        message = "Failed to generate test output",
                        testDuration = System.currentTimeMillis() - startTime,
                        output = null,
                        error = detailedError
                    )
                }
                
                // Clean up test service
                testInferenceService.close()
                
                ModelTestResult(
                    success = true,
                    model = model.modelName,
                    message = "Model test successful",
                    testDuration = System.currentTimeMillis() - startTime,
                    output = testOutput.response,
                    error = null
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Model test failed for ${model.modelName}")
                
                val detailedError = buildString {
                    append("Unexpected test failure for model ${model.modelName}.")
                    appendLine()
                    appendLine("Error: ${e.message}")
                    appendLine("Exception type: ${e.javaClass.simpleName}")
                    appendLine("Stack trace: ${e.stackTraceToString().take(500)}")
                    appendLine()
                    appendLine("This is an unexpected error during model testing.")
                    appendLine("Please report this issue with the error details above.")
                }
                
                ModelTestResult(
                    success = false,
                    model = model.modelName,
                    message = "Unexpected test failure",
                    testDuration = System.currentTimeMillis() - startTime,
                    output = null,
                    error = detailedError
                )
            }
        }
    }

    /**
     * Test a model with streaming token output
     */
    suspend fun testModelStreaming(
        model: AIModel,
        prompt: String = "Ping",
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        onProgress: (ModelTestStreamChunk) -> Unit
    ): ModelTestResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                Timber.d("Testing model with streaming: ${model.modelName}")
                
                // Validate model file
                if (!ModelDetector.isModelAvailable(context, model)) {
                    val errorChunk = ModelTestStreamChunk(
                        type = "error",
                        error = "Model file not found or not available",
                        model = model.modelName
                    )
                    onProgress(errorChunk)
                    
                    return@withContext ModelTestResult(
                        success = false,
                        model = model.modelName,
                        message = "Model file not found or not available",
                        testDuration = System.currentTimeMillis() - startTime,
                        output = null,
                        error = "Model ${model.fileName} is not available in storage"
                    )
                }
                
                // Create a temporary inference service for testing
                val testInferenceService = try {
                    AIInferenceService.create(context, model)
                } catch (e: Exception) {
                    val detailedError = buildString {
                        append("Failed to initialize AI inference service for model ${model.modelName}.")
                        appendLine()
                        appendLine("Error: ${e.message}")
                        appendLine("Exception type: ${e.javaClass.simpleName}")
                        
                        // Model file diagnostics
                        try {
                            val modelFile = ModelDetector.getModelFile(context, model)
                            appendLine("Model file: ${modelFile?.absolutePath ?: "Not found"}")
                            appendLine("File exists: ${modelFile?.exists() ?: false}")
                            if (modelFile?.exists() == true) {
                                appendLine("File size: ${formatBytes(modelFile.length())}")
                                appendLine("File readable: ${modelFile.canRead()}")
                                appendLine("Last modified: ${java.util.Date(modelFile.lastModified())}")
                            }
                        } catch (fileE: Exception) {
                            appendLine("Could not get model file info: ${fileE.message}")
                        }
                        
                        // MediaPipe backend info
                        appendLine("MediaPipe backend: ${model.preferredBackend?.name ?: "AUTO"}")
                    }
                    
                    val errorChunk = ModelTestStreamChunk(
                        type = "error",
                        error = detailedError,
                        model = model.modelName
                    )
                    onProgress(errorChunk)
                    
                    return@withContext ModelTestResult(
                        success = false,
                        model = model.modelName,
                        message = "Failed to initialize model",
                        testDuration = System.currentTimeMillis() - startTime,
                        output = null,
                        error = detailedError
                    )
                }
                
                // Run a simple test inference with streaming
                var fullOutput = ""
                var accumulatedText = "" // Simple accumulation like gallery
                val testOutput = try {
                    testInferenceService.generateText(
                        prompt = prompt,
                        temperature = temperature ?: 0.7f,
                        topK = topK ?: 40,
                        topP = topP ?: 0.95f
                    ) { partialResult, isDone ->
                        fullOutput = partialResult
                        
                        // Direct concatenation like gallery: accumulatedText = "$accumulatedText$partialResult"
                        accumulatedText = "$accumulatedText$partialResult"
                        
                        if (isDone) {
                            // Send completion chunk
                            val completeChunk = ModelTestStreamChunk(
                                type = "complete",
                                fullText = accumulatedText,
                                success = true,
                                model = model.modelName,
                                elapsedTime = System.currentTimeMillis() - startTime
                            )
                            onProgress(completeChunk)
                        } else {
                            // Send token chunk with accumulated text
                            val tokenChunk = ModelTestStreamChunk(
                                type = "token",
                                token = partialResult,
                                fullText = accumulatedText,
                                model = model.modelName
                            )
                            onProgress(tokenChunk)
                        }
                    }
                } catch (e: Exception) {
                    testInferenceService.close()
                    
                    val detailedError = buildString {
                        append("Failed to generate test output with model ${model.modelName}.")
                        appendLine()
                        appendLine("Error: ${e.message}")
                        appendLine("Exception type: ${e.javaClass.simpleName}")
                        appendLine()
                        appendLine("Test prompt: \"$prompt\"")
                        appendLine("Temperature: ${temperature ?: 0.7}, TopK: ${topK ?: 40}, TopP: ${topP ?: 0.95}")
                        appendLine()
                        appendLine("This suggests the model loaded successfully but failed during inference.")
                        appendLine("Possible causes:")
                        appendLine("- Model file corruption during download/transfer")
                        appendLine("- Insufficient memory for inference")
                        appendLine("- Incompatible model format or version")
                        appendLine("- MediaPipe backend compatibility issues")
                    }
                    
                    val errorChunk = ModelTestStreamChunk(
                        type = "error",
                        error = detailedError,
                        model = model.modelName
                    )
                    onProgress(errorChunk)
                    
                    return@withContext ModelTestResult(
                        success = false,
                        model = model.modelName,
                        message = "Failed to generate test output",
                        testDuration = System.currentTimeMillis() - startTime,
                        output = null,
                        error = detailedError
                    )
                }
                
                // Clean up test service
                testInferenceService.close()
                
                ModelTestResult(
                    success = true,
                    model = model.modelName,
                    message = "Model test successful",
                    testDuration = System.currentTimeMillis() - startTime,
                    output = testOutput.response,
                    error = null
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Model test failed for ${model.modelName}")
                
                val detailedError = buildString {
                    append("Unexpected test failure for model ${model.modelName}.")
                    appendLine()
                    appendLine("Error: ${e.message}")
                    appendLine("Exception type: ${e.javaClass.simpleName}")
                    appendLine("Stack trace: ${e.stackTraceToString().take(500)}")
                    appendLine()
                    appendLine("This is an unexpected error during model testing.")
                    appendLine("Please report this issue with the error details above.")
                }
                
                val errorChunk = ModelTestStreamChunk(
                    type = "error",
                    error = detailedError,
                    model = model.modelName
                )
                onProgress(errorChunk)
                
                ModelTestResult(
                    success = false,
                    model = model.modelName,
                    message = "Unexpected test failure",
                    testDuration = System.currentTimeMillis() - startTime,
                    output = null,
                    error = detailedError
                )
            }
        }
    }

    /**
     * Get supported models with availability status
     */
    fun getSupportedModels(): SupportedModelsResponse {
        val models = AIModel.getAllModels().map { model ->
            val isAvailable = ModelDetector.isModelAvailable(context, model)
            val isCurrentlyLoaded = currentModel?.name == model.name
            
            ModelInfo(
                name = model.name,
                displayName = model.modelName,
                description = model.description,
                needsLicense = model.licenseUrl.isNotEmpty(),
                available = isAvailable,
                currentlyLoaded = isCurrentlyLoaded,
                backend = model.preferredBackend?.name ?: "CPU",
                isMultiModal = model.supportsVision
            )
        }
        
        return SupportedModelsResponse(models)
    }
    

    
    /**
     * Get AI service status
     */
    fun getStatus(): AIServiceStatus {
        val availableModels = ModelDetector.getAvailableModels(context).size
        val totalModels = AIModel.getAllModels().size
        
        val currentModelDetails = currentModel?.let { model ->
            LoadedModelInfo(
                name = model.name,
                displayName = model.modelName,
                description = model.description,
                supportsVision = model.supportsVision,
                preferredBackend = model.preferredBackend?.name,
                loadedAt = modelLoadedAt ?: 0L,
                isReady = currentInferenceService != null
            )
        }
        
        return AIServiceStatus(
            isEnabled = ModelDetector.isAITextEnabled(context),
            downloadedModels = availableModels,
            totalModels = totalModels,
            currentModel = currentModel?.modelName,
            currentModelDetails = currentModelDetails,
            hasAuthentication = false, // No auth needed for resource-based models
            supportedFeatures = listOf("text-generation", "camera-integration"),
            isProcessing = isProcessing,
            processingStartTime = processingStartTime,
            isModelLoading = isModelLoading,
            modelLoadingStartTime = modelLoadingStartTime
        )
    }
    /**
     * Calculate estimated file size of a bitmap in bytes
     */
    private fun estimateBitmapSize(bitmap: Bitmap): Long {
        return (bitmap.width * bitmap.height * 4).toLong() // 4 bytes per pixel for ARGB_8888
    }
    
    /**
     * Process image input (camera capture or base64 image)
     */
    private suspend fun processImageInput(request: AITextRequest): ProcessedImageInput {
        val (imageDescription, bitmap) = when {
            request.image != null -> {
                // Process base64 image
                try {
                    val imageBytes = Base64.decode(request.image, Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) {
                        // Use original bitmap directly - MediaPipe handles any needed preprocessing
                        val estimatedSize = estimateBitmapSize(bitmap)
                        
                        val description = "Image provided (${bitmap.width}x${bitmap.height}, ~${formatFileSize(estimatedSize)})"
                        
                        description to bitmap
                    } else {
                        "Invalid image data - could not decode bitmap" to null
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to decode base64 image")
                    "Invalid image data" to null
                }
            }
            
            request.captureConfig != null -> {
                // Capture image from camera using shared service
                // Camera operations must run on main thread
                try {
                    val (description, bitmap) = withContext(Dispatchers.Main) {
                        val lifecycleOwner = SimpleLifecycleOwner()
                        val cameraResult = sharedCameraService.captureImage(
                            lifecycleOwner = lifecycleOwner,
                            side = request.captureConfig.camera,
                            zoom = null // AIService doesn't currently use zoom
                        )
                        
                        if (cameraResult.success && cameraResult.bitmap != null) {
                            val bitmap = cameraResult.bitmap
                            // Use original bitmap directly - MediaPipe handles any needed preprocessing
                            val estimatedSize = estimateBitmapSize(bitmap)
                            
                            val description = "Camera image captured (${bitmap.width}x${bitmap.height}, ${request.captureConfig.camera} camera, ~${formatFileSize(estimatedSize)})"
                            
                            description to bitmap
                        } else {
                            "Failed to capture camera image: ${cameraResult.error ?: "Unknown error"}" to null
                        }
                    }
                    
                    description to bitmap
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture camera image")
                    "Failed to capture camera image: ${e.message}" to null
                }
            }
            
            else -> "" to null
        }
        
        // Combine text prompt with image description for non-multimodal fallback
        val combinedPrompt = if (imageDescription.isNotEmpty()) {
            "${request.text}\n\n[Image Context: $imageDescription]\n\nPlease respond considering both the text and image context."
        } else {
            request.text
        }
        
        return ProcessedImageInput(
            prompt = combinedPrompt,
            bitmap = bitmap
        )
    }
    
    /**
     * Format file size for display
     */
    private fun formatFileSize(bytes: Long): String {
        if (bytes == 0L) return "Unknown"
        
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return "%.1f %s".format(size, units[unitIndex])
    }

    /**
     * Download a model from remote URL and save to persistent storage
     * Then create a reference to it
     * @param progressCallback Called with (bytesDownloaded, totalBytes, percentage) during download
     */
    suspend fun downloadModel(url: String, fileName: String, displayName: String, progressCallback: ((Long, Long, Int) -> Unit)? = null): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            var model: AIModel? = null
            try {
                Timber.i("Starting download of model: $displayName from $url")
                
                // Find the model for persistence tracking
                model = AIModel.getAllModels().find { it.fileName == fileName }
                
                // Use a dedicated external directory for downloads that's not tied to our app
                val downloadDir = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                    val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    File(publicDownloads, "local_ai_models")
                } else {
                    // Fallback to app external files
                    File(context.getExternalFilesDir(null), "downloaded_models")
                }
                
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                
                // Set models directory in persistence manager
                persistenceManager.setModelsDirectory(downloadDir.absolutePath)
                
                val targetFile = File(downloadDir, fileName)
                
                // Check if file already exists
                if (targetFile.exists()) {
                    // Create reference to existing file
                    if (model != null) {
                        ModelDetector.createModelReference(context, model, targetFile.absolutePath)
                        
                        // Record in persistence manager
                        persistenceManager.recordModelDownloadCompleted(model, targetFile.length())
                    }
                    
                    return@withContext mapOf(
                        "success" to true,
                        "message" to "Model file already exists, reference created",
                        "model" to mapOf(
                            "name" to displayName,
                            "fileName" to fileName,
                            "url" to url,
                            "size" to formatBytes(targetFile.length()),
                            "path" to targetFile.absolutePath
                        ),
                        "status" to "ALREADY_EXISTS"
                    )
                }
                
                // Download the file
                val connection = URL(url).openConnection()
                connection.connectTimeout = 30000 // 30 seconds
                connection.readTimeout = 300000 // 5 minutes
                
                val totalBytes = connection.contentLength.toLong()
                Timber.d("Total download size: ${formatBytes(totalBytes)}")
                
                // Record download start in persistence manager
                if (model != null) {
                    persistenceManager.recordModelDownloadStarted(model, targetFile.absolutePath, totalBytes)
                }
                
                connection.getInputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        var bytesRead: Int
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            // Calculate and report progress
                            val progress = if (totalBytes > 0) (totalRead * 100 / totalBytes).toInt() else 0
                            progressCallback?.invoke(totalRead, totalBytes, progress)
                            
                            // Log progress every 10MB
                            if (totalRead % (10 * 1024 * 1024) == 0L) {
                                Timber.d("Download progress: ${formatBytes(totalRead)} / ${formatBytes(totalBytes)} ($progress%)")
                            }
                        }
                    }
                }
                
                // Create reference to downloaded file
                if (model != null) {
                    ModelDetector.createModelReference(context, model, targetFile.absolutePath)
                    
                    // Record successful download completion
                    persistenceManager.recordModelDownloadCompleted(model, targetFile.length())
                }
                
                Timber.i("Successfully downloaded model: $displayName (${formatBytes(targetFile.length())})")
                
                mapOf(
                    "success" to true,
                    "message" to "Model downloaded and referenced successfully",
                    "model" to mapOf(
                        "name" to displayName,
                        "fileName" to fileName,
                        "url" to url,
                        "size" to formatBytes(targetFile.length()),
                        "path" to targetFile.absolutePath
                    ),
                    "status" to "DOWNLOADED"
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to download model: $displayName")
                
                // Record download failure
                if (model != null) {
                    persistenceManager.recordModelDownloadFailed(model, e.message ?: "Unknown error")
                }
                
                mapOf(
                    "success" to false,
                    "error" to "Download failed: ${e.message}",
                    "model" to mapOf(
                        "name" to displayName,
                        "fileName" to fileName,
                        "url" to url
                    ),
                    "status" to "ERROR"
                )
            }
        }
    }
    
    /**
     * Download model with AIModel parameter
     */
    suspend fun downloadModel(model: AIModel): ModelDownloadResponse {
        return downloadModel(model, null)
    }
    
    /**
     * Download model with AIModel parameter and progress callback
     * @param progressCallback Called with (bytesDownloaded, totalBytes, percentage) during download
     */
    suspend fun downloadModel(model: AIModel, progressCallback: ((Long, Long, Int) -> Unit)?): ModelDownloadResponse {
        return try {
            val result = downloadModel(model.url, model.fileName, model.modelName, progressCallback)
            val success = result["success"] as? Boolean ?: false
            val message = result["error"] as? String ?: "Download completed successfully"
            
            // Get persistence information if available
            val persistenceInfo = getModelPersistenceInfo(model)
            
            ModelDownloadResponse(
                success = success,
                message = message,
                modelName = model.modelName,
                status = if (success) "download_completed" else "download_failed",
                downloadUrl = if (!success) model.url else null,
                persistenceInfo = persistenceInfo
            )
        } catch (e: Exception) {
            ModelDownloadResponse(
                success = false,
                message = "Download failed: ${e.message}",
                modelName = model.modelName,
                status = "download_failed",
                downloadUrl = model.url,
                persistenceInfo = getModelPersistenceInfo(model)
            )
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        // Record model unloading if a model is currently loaded
        if (currentModel != null && currentInferenceService != null) {
            persistenceManager.recordModelUnloaded(currentModel!!)
        }
        
        currentInferenceService?.close()
        currentInferenceService = null
        currentModel = null
        modelLoadedAt = null
    }
    
    /**
     * Get comprehensive model status including persistence information
     */
    fun getModelStatusSummary(): ModelStatusSummary {
        val statistics = persistenceManager.getStatistics()
        val availableModels = persistenceManager.getAllPersistedModels().values.map { info ->
            ModelPersistenceInfo(
                downloadPath = info.downloadPath,
                fileSize = info.fileSize,
                formattedSize = info.getFormattedSize(),
                downloadTimestamp = info.downloadTimestamp,
                lastAccessedTimestamp = info.lastAccessedTimestamp,
                isLoaded = info.isLoaded,
                downloadStatus = info.downloadStatus.name,
                ageInDays = info.getAgeInDays()
            )
        }
        
        return ModelStatusSummary(
            totalModels = statistics.totalModels,
            downloadedModels = statistics.downloadedModels,
            loadedModels = statistics.loadedModels,
            totalDownloadSize = statistics.totalDownloadSize,
            formattedTotalSize = statistics.getFormattedTotalSize(),
            lastLoadedModel = persistenceManager.getLastLoadedModelName(),
            modelsDirectory = persistenceManager.getModelsDirectory(),
            availableModels = availableModels,
            statistics = ModelStatisticsResponse(
                totalModels = statistics.totalModels,
                downloadedModels = statistics.downloadedModels,
                loadedModels = statistics.loadedModels,
                totalDownloadSize = statistics.totalDownloadSize,
                formattedTotalSize = statistics.getFormattedTotalSize(),
                oldestDownloadTimestamp = statistics.oldestDownload,
                newestDownloadTimestamp = statistics.newestDownload,
                lastAccessedTimestamp = statistics.lastAccessed
            )
        )
    }
    
    /**
     * Get persistence information for a specific model
     */
    fun getModelPersistenceInfo(model: AIModel): ModelPersistenceInfo? {
        val info = persistenceManager.getModelInfo(model) ?: return null
        
        return ModelPersistenceInfo(
            downloadPath = info.downloadPath,
            fileSize = info.fileSize,
            formattedSize = info.getFormattedSize(),
            downloadTimestamp = info.downloadTimestamp,
            lastAccessedTimestamp = info.lastAccessedTimestamp,
            isLoaded = info.isLoaded,
            downloadStatus = info.downloadStatus.name,
            ageInDays = info.getAgeInDays()
        )
    }
    
    /**
     * Clean up models that are no longer available on disk
     */
    fun cleanupDeletedModels(): Int {
        return persistenceManager.cleanupDeletedModels()
    }
    
    /**
     * Remove a model completely (delete from disk and persistence)
     */
    suspend fun removeModel(model: AIModel): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Unload model if it's currently loaded
                if (currentModel == model) {
                    persistenceManager.recordModelUnloaded(model)
                    currentInferenceService?.close()
                    currentInferenceService = null
                    currentModel = null
                    modelLoadedAt = null
                }
                
                // Get file path from persistence manager
                val persistedInfo = persistenceManager.getModelInfo(model)
                if (persistedInfo != null) {
                    val file = File(persistedInfo.downloadPath)
                    if (file.exists()) {
                        val deleted = file.delete()
                        if (deleted) {
                            Timber.i("Deleted model file: ${persistedInfo.downloadPath}")
                        } else {
                            Timber.w("Failed to delete model file: ${persistedInfo.downloadPath}")
                        }
                    }
                }
                
                // Remove from persistence manager
                persistenceManager.removeModel(model)
                
                // Remove model reference
                ModelDetector.removeModelReference(context, model)
                
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove model: ${model.modelName}")
                false
            }
        }
    }
    
    /**
     * Create AI inference service with timeout protection
     */
    private suspend fun createInferenceServiceWithTimeout(model: AIModel): AIInferenceService {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                Timber.d("Creating AI inference service for model: ${model.modelName} with ${MODEL_LOADING_TIMEOUT_MS}ms timeout")
                
                // Use kotlinx.coroutines.withTimeout for proper cancellation
                kotlinx.coroutines.withTimeout(MODEL_LOADING_TIMEOUT_MS) {
                    val service = AIInferenceService.create(context, model)
                    val creationTime = System.currentTimeMillis() - startTime
                    Timber.d("AI inference service created successfully in ${creationTime}ms")
                    service
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val timeoutTime = System.currentTimeMillis() - startTime
                Timber.e("Model loading timed out after ${timeoutTime}ms for model: ${model.modelName}")
                throw AIServiceException("Model loading timed out after ${timeoutTime}ms. This usually indicates MediaPipe initialization issues or insufficient system resources.")
            } catch (e: Exception) {
                val failureTime = System.currentTimeMillis() - startTime
                Timber.e(e, "Failed to create AI inference service in ${failureTime}ms for model: ${model.modelName}")
                throw e // Re-throw the original exception
            }
        }
    }
    
    /**
     * Check if model loading is currently in progress
     */
    fun isModelLoading(): Boolean = isModelLoading
    
    /**
     * Add captured image to AI text response
     */
    private suspend fun addImageToResponse(response: AITextResponse, imageScaling: ImageScaling): AITextResponse {
        return try {
            // Create a simple lifecycle owner for camera operations
            val lifecycleOwner = withContext(Dispatchers.Main) {
                SimpleLifecycleOwner()
            }
            
            try {
                // Capture image using the shared camera service
                val cameraResult = withContext(Dispatchers.Main) {
                    sharedCameraService.captureImage(
                        lifecycleOwner = lifecycleOwner,
                        side = "rear",  // Default to rear camera
                        zoom = null
                    )
                }
                
                if (cameraResult.success && cameraResult.bitmap != null) {
                    // Apply image scaling if needed
                    val scaledBitmap = scaleImageForOutput(cameraResult.bitmap!!, imageScaling)
                    
                    // Convert to base64
                    val stream = java.io.ByteArrayOutputStream()
                    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                    val imageBytes = stream.toByteArray()
                    val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    
                    // Clean up bitmaps
                    if (scaledBitmap != cameraResult.bitmap) {
                        scaledBitmap.recycle()
                    }
                    cameraResult.bitmap?.recycle()
                    
                    // Return response with image
                    response.copy(image = "data:image/jpeg;base64,$base64Image")
                } else {
                    // Return original response if image capture failed
                    Timber.w("Failed to capture image for AI text response: ${cameraResult.error}")
                    response
                }
            } finally {
                withContext(Dispatchers.Main) {
                    lifecycleOwner.destroy()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error capturing image for AI text response")
            // Return original response if anything fails
            response
        }
    }
    
    /**
     * Scale image for output based on ImageScaling settings
     */
    private fun scaleImageForOutput(bitmap: android.graphics.Bitmap, imageScaling: ImageScaling): android.graphics.Bitmap {
        if (imageScaling == ImageScaling.NONE) {
            return bitmap
        }
        
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val maxDimension = originalWidth.coerceAtLeast(originalHeight)
        
        // If image is already within bounds, return as is
        if (maxDimension <= imageScaling.maxWidth && maxDimension <= imageScaling.maxHeight) {
            return bitmap
        }
        
        // Calculate scaling factor to fit within imageScaling bounds
        val maxAllowedSize = kotlin.math.min(imageScaling.maxWidth, imageScaling.maxHeight)
        val scaleFactor = maxAllowedSize.toFloat() / maxDimension
        
        val newWidth = (originalWidth * scaleFactor).toInt()
        val newHeight = (originalHeight * scaleFactor).toInt()
        
        return android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Format AI model response text to be more readable
     * Handle <think> tags and basic cleanup without aggressive concatenation fixes
     */
    fun formatResponseText(rawResponse: String): String {
        if (rawResponse.isBlank()) return rawResponse
        
        var formatted = rawResponse
        
        // Basic cleanup - fix obvious spacing issues but preserve MediaPipe's tokenization
        formatted = formatted.replace(Regex(" {3,}"), "  ") // Reduce 3+ spaces to 2
        formatted = formatted.replace(Regex(" {2}"), " ")   // Reduce double spaces to single
        formatted = formatted.trim()
        
        // Keep <think> tags as-is for raw text display
        // They will be displayed as raw text which is fine
        
        return formatted
    }
    
    /**
     * Get model loading progress information
     */
    fun getModelLoadingInfo(): ModelLoadingInfo? {
        return if (isModelLoading && modelLoadingStartTime != null) {
            val elapsed = System.currentTimeMillis() - modelLoadingStartTime!!
            val progress = (elapsed.toFloat() / MODEL_LOADING_TIMEOUT_MS * 100).coerceAtMost(100f)
            
            ModelLoadingInfo(
                isLoading = true,
                elapsedTime = elapsed,
                timeoutTime = MODEL_LOADING_TIMEOUT_MS,
                progressPercentage = progress,
                modelName = currentModel?.modelName ?: "Unknown"
            )
        } else null
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

/**
 * AI service status information
 */
data class AIServiceStatus(
    val isEnabled: Boolean,
    val downloadedModels: Int,
    val totalModels: Int,
    val currentModel: String?,
    val currentModelDetails: LoadedModelInfo?,
    val hasAuthentication: Boolean,
    val supportedFeatures: List<String>,
    val isProcessing: Boolean = false,
    val processingStartTime: Long? = null,
    val isModelLoading: Boolean = false,
    val modelLoadingStartTime: Long? = null
)

/**
 * Information about the currently loaded model
 */
data class LoadedModelInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val supportsVision: Boolean,
    val preferredBackend: String?,
    val loadedAt: Long,
    val isReady: Boolean
)

/**
 * Result of testing a model
 */
data class ModelTestResult(
    val success: Boolean,
    val model: String,
    val message: String,
    val testDuration: Long,
    val output: String?,
    val error: String?
)

/**
 * Information about model loading progress
 */
data class ModelLoadingInfo(
    val isLoading: Boolean,
    val elapsedTime: Long,
    val timeoutTime: Long,
    val progressPercentage: Float,
    val modelName: String
)
