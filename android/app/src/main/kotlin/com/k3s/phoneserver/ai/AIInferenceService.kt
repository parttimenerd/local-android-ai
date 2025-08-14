package com.k3s.phoneserver.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.max

/**
 * Core AI inference service using MediaPipe LLM inference.
 * Manages model loading, session creation, and text generation.
 */
class AIInferenceService private constructor(
    private val context: Context,
    private val model: AIModel
) {
    
    private var llmInference: LlmInference? = null
    private var llmInferenceSession: LlmInferenceSession? = null
    private val tag = "AIInferenceService"
    
    companion object {
        // Maximum tokens the model can process
        private const val MAX_TOKENS = 1024
        
        // Token offset for response generation
        private const val DECODE_TOKEN_OFFSET = 256
        
        /**
         * Create an AI inference service for the specified model
         */
        suspend fun create(context: Context, model: AIModel): AIInferenceService {
            return withContext(Dispatchers.IO) {
                val service = AIInferenceService(context, model)
                
                // Add progress logging during initialization
                Timber.d("Starting MediaPipe initialization for model: ${model.modelName}")
                val initStartTime = System.currentTimeMillis()
                
                try {
                    service.initialize()
                    val initTime = System.currentTimeMillis() - initStartTime
                    Timber.d("MediaPipe initialization completed in ${initTime}ms for model: ${model.modelName}")
                } catch (e: Exception) {
                    val initTime = System.currentTimeMillis() - initStartTime
                    Timber.e(e, "MediaPipe initialization failed after ${initTime}ms for model: ${model.modelName}")
                    throw e
                }
                
                service
            }
        }
    }
    
    /**
     * Initialize the inference engine and session
     */
    private suspend fun initialize() {
        if (!ModelDetector.isModelAvailable(context, model)) {
            throw ModelNotDownloadedException("Model ${model.modelName} is not available (missing .task file)")
        }
        
        try {
            createEngine()
            createSession()
            Timber.i("AI inference service initialized for model: ${model.modelName}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AI inference service")
            throw AIServiceException("Failed to initialize AI service", e)
        }
    }
    
    /**
     * Create the LLM inference engine
     */
    private fun createEngine() {
        val modelFile = ModelDetector.getModelFile(context, model)
            ?: throw ModelNotDownloadedException("Model file not found for ${model.modelName}")
            
        if (!modelFile.exists()) {
            throw ModelNotDownloadedException("Model file does not exist: ${modelFile.absolutePath}")
        }
        
        if (!modelFile.canRead()) {
            throw AIServiceException("Model file is not readable: ${modelFile.absolutePath}")
        }
        
        if (modelFile.length() == 0L) {
            throw AIServiceException("Model file is empty: ${modelFile.absolutePath}")
        }
        
        val modelPath = modelFile.absolutePath
        
        // Log model information
        Timber.d("Loading model: ${model.modelName}")
        Timber.d("  Model path: $modelPath")
        Timber.d("  Model size: ${formatBytes(modelFile.length())}")
        
        try {
            Timber.d("Creating LLM inference engine...")
            val engineStartTime = System.currentTimeMillis()
            
            val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxNumImages(if (model.supportsVision) 1 else 0)  // Enable image support
            
            // Set backend preference if specified
            model.preferredBackend?.let { backend ->
                optionsBuilder.setPreferredBackend(backend)
                Timber.d("Using preferred backend: ${backend.name}")
            }
            
            Timber.d("Creating LLM inference engine with:")
            Timber.d("  Model path: $modelPath")
            Timber.d("  Max tokens: $MAX_TOKENS")
            Timber.d("  Preferred backend: ${model.preferredBackend?.name ?: "Default"}")
            
            llmInference = LlmInference.createFromOptions(context, optionsBuilder.build())
            
            val engineTime = System.currentTimeMillis() - engineStartTime
            Timber.i("LLM inference engine created successfully for model: ${model.modelName} in ${engineTime}ms")
        } catch (e: OutOfMemoryError) {
            val errorMsg = "Out of memory while loading model ${model.modelName}. " +
                    "Try a smaller model or restart the app to free memory."
            Timber.e(e, errorMsg)
            throw AIServiceException(errorMsg, e)
        } catch (e: Exception) {
            val errorMsg = "Failed to create LLM inference engine for model ${model.modelName}: ${e.message}"
            Timber.e(e, errorMsg)
            throw AIServiceException(errorMsg, e)
        }
    }
    
    /**
     * Generate text response asynchronously
     */
    suspend fun generateText(
        prompt: String,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        progressCallback: ((String, Boolean) -> Unit)? = null
    ): AITextResponse {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                // Update session parameters if provided
                if (temperature != null || topK != null || topP != null) {
                    updateSessionParameters(temperature, topK, topP)
                }
                
                // Generate response
                val session = llmInferenceSession 
                    ?: throw AIServiceException("Inference session not initialized")
                
                session.addQueryChunk(prompt)
                
                val progressListener = ProgressListener<String> { partialResult, isDone ->
                    progressCallback?.invoke(partialResult, isDone)
                }
                
                val future: ListenableFuture<String> = session.generateResponseAsync(progressListener)
                val response = future.get() // This blocks, but we're in IO context
                
                val inferenceTime = System.currentTimeMillis() - startTime
                val tokenCount = estimateTokenCount(prompt + response)
                
                AITextResponse(
                    response = response,
                    model = model.name,
                    thinking = if (model.thinking) extractThinking(response) else null,
                    license = model.licenseStatement,
                    metadata = AIResponseMetadata(
                        model = model.modelName,
                        inferenceTime = inferenceTime,
                        tokenCount = tokenCount,
                        temperature = temperature ?: model.temperature,
                        topK = topK ?: model.topK,
                        topP = topP ?: model.topP,
                        backend = model.preferredBackend?.name ?: "CPU"
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate text")
                throw AIServiceException("Text generation failed", e)
            }
        }
    }
    
    /**
     * Generate multimodal text response with image input
     */
    suspend fun generateMultimodalText(
        prompt: String,
        image: Bitmap,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        progressCallback: ((String, Boolean) -> Unit)? = null
    ): AITextResponse {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                // Check if model supports vision
                if (!model.supportsVision) {
                    throw AIServiceException("Model ${model.modelName} does not support vision/multimodal input")
                }
                
                // Create a new session with vision support
                val sessionOptions = LlmInferenceSessionOptions.builder()
                    .setTemperature(temperature ?: model.temperature)
                    .setTopK(topK ?: model.topK)
                    .setTopP(topP ?: model.topP)
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(model.supportsVision)
                            .build()
                    )
                    .build()
                
                val session = LlmInferenceSession.createFromOptions(llmInference!!, sessionOptions)
                
                // Add text prompt BEFORE image (gallery order)
                session.addQueryChunk(prompt)
                
                // Add image using MediaPipe format
                val mpImage = BitmapImageBuilder(image).build()
                session.addImage(mpImage)
                
                Timber.d("Added image to LLM session for multimodal inference (${image.width}x${image.height})")
                
                val progressListener = ProgressListener<String> { partialResult, isDone ->
                    progressCallback?.invoke(partialResult, isDone)
                }
                
                val future: ListenableFuture<String> = session.generateResponseAsync(progressListener)
                val response = future.get() // This blocks, but we're in IO context
                
                val inferenceTime = System.currentTimeMillis() - startTime
                val tokenCount = estimateTokenCount(prompt + response)
                
                // Close the session as it's single-use for multimodal
                session.close()
                
                AITextResponse(
                    response = response,
                    model = model.name,
                    thinking = if (model.thinking) extractThinking(response) else null,
                    license = model.licenseStatement,
                    metadata = AIResponseMetadata(
                        model = model.modelName,
                        inferenceTime = inferenceTime,
                        tokenCount = tokenCount,
                        temperature = temperature ?: model.temperature,
                        topK = topK ?: model.topK,
                        topP = topP ?: model.topP,
                        backend = model.preferredBackend?.name ?: "CPU",
                        isMultimodal = true
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate multimodal text - detailed error analysis")
                
                // Detailed error analysis
                val errorDetails = buildString {
                    appendLine("=== MULTIMODAL AI ERROR ANALYSIS ===")
                    appendLine("Error Type: ${e.javaClass.simpleName}")
                    appendLine("Error Message: ${e.message}")
                    appendLine("Model: ${model.modelName} (${model.name})")
                    appendLine("Image Size: ${image.width}x${image.height}")
                    appendLine("Image Format: ${image.config}")
                    appendLine("Estimated Image Memory: ${estimateBitmapMemoryUsage(image)}MB")
                    appendLine("Prompt Length: ${prompt.length} characters")
                    appendLine("Backend: ${model.preferredBackend?.name ?: "CPU"}")
                    appendLine("Model Type: ${if (model.supportsVision) "Multimodal" else "Text-only"}")
                    
                    // Add stack trace summary (first 10 frames)
                    e.stackTrace.take(10).forEach { frame ->
                        appendLine("Stack: ${frame.className}.${frame.methodName}:${frame.lineNumber}")
                    }
                }
                
                Timber.e("Detailed multimodal error: $errorDetails")
                throw AIServiceException("Multimodal text generation failed. $errorDetails", e)
            }
        }
    }
    
    /**
     * Generate text with streaming updates
     */
    suspend fun generateTextStreaming(
        prompt: String,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        onProgress: (String, Boolean) -> Unit
    ): AITextResponse {
        val response = generateText(prompt, temperature, topK, topP) { partial, done ->
            onProgress(partial, done)
        }
        return response
    }
    
    /**
     * Estimate remaining tokens for context length management
     */
    fun estimateRemainingTokens(prompt: String): Int {
        val session = llmInferenceSession ?: return -1
        
        return try {
            val sizeOfPrompt = session.sizeInTokens(prompt)
            val remainingTokens = MAX_TOKENS - sizeOfPrompt - DECODE_TOKEN_OFFSET
            max(0, remainingTokens)
        } catch (e: Exception) {
            Timber.w(e, "Failed to estimate token count")
            -1
        }
    }
    
    /**
     * Reset the inference session
     */
    suspend fun resetSession() {
        withContext(Dispatchers.IO) {
            try {
                llmInferenceSession?.close()
                createSession()
                Timber.d("Inference session reset")
            } catch (e: Exception) {
                Timber.e(e, "Failed to reset session")
                throw AIServiceException("Failed to reset session", e)
            }
        }
    }
    
    /**
     * Close the inference service and free resources
     */
    fun close() {
        try {
            llmInferenceSession?.close()
            llmInference?.close()
            Timber.d("AI inference service closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing AI inference service")
        }
    }
    
    /**
     * Create the inference session with model parameters
     */
    private fun createSession() {
        val inference = llmInference 
            ?: throw AIServiceException("LLM inference engine not initialized")
        
        try {
            Timber.d("Creating inference session...")
            val sessionStartTime = System.currentTimeMillis()
            
            val sessionOptions = LlmInferenceSessionOptions.builder()
                .setTemperature(model.temperature)
                .setTopK(model.topK)
                .setTopP(model.topP)
                .build()
            
            Timber.d("Creating inference session with parameters:")
            Timber.d("  Temperature: ${model.temperature}")
            Timber.d("  TopK: ${model.topK}")
            Timber.d("  TopP: ${model.topP}")
            
            llmInferenceSession = LlmInferenceSession.createFromOptions(inference, sessionOptions)
            
            val sessionTime = System.currentTimeMillis() - sessionStartTime
            Timber.i("Inference session created successfully for model: ${model.modelName} in ${sessionTime}ms")
        } catch (e: Exception) {
            val errorMsg = "Failed to create inference session for model ${model.modelName}: ${e.message}"
            Timber.e(e, errorMsg)
            throw AIServiceException(errorMsg, e)
        }
    }
    
    /**
     * Update session parameters dynamically
     */
    private fun updateSessionParameters(temperature: Float?, topK: Int?, topP: Float?) {
        // Note: MediaPipe doesn't support dynamic parameter updates
        // We would need to recreate the session, but for now we'll log this
        Timber.d("Dynamic parameter updates not supported by MediaPipe, using model defaults")
    }
    
    /**
     * Extract thinking process from response for reasoning models
     */
    private fun extractThinking(response: String): String? {
        // For models with thinking capability, extract the thinking process
        // This would depend on the specific model's output format
        return if (model.thinking) {
            // Simple implementation - look for thinking markers
            val thinkingPattern = Regex("""\<think\>(.*?)\</think\>""", RegexOption.DOT_MATCHES_ALL)
            thinkingPattern.find(response)?.groupValues?.get(1)?.trim()
        } else null
    }
    
    /**
     * Estimate token count for text
     */
    private fun estimateTokenCount(text: String): Int {
        // Simple estimation: roughly 4 characters per token
        return (text.length / 4).coerceAtLeast(1)
    }
    
    /**
     * Format AI model response text to be more readable
     * Fixes concatenated words that often occur in AI model outputs
     */
    fun formatResponseText(rawResponse: String): String {
        if (rawResponse.isBlank()) return rawResponse
        
        var formatted = rawResponse
        
        // Fix concatenated words by adding spaces before capital letters (but preserve acronyms)
        formatted = formatted.replace(Regex("([a-z])([A-Z])")) { matchResult ->
            "${matchResult.groupValues[1]} ${matchResult.groupValues[2]}"
        }
        
        // Fix common concatenated patterns
        formatted = formatted.replace(Regex("([.!?])([A-Z])")) { matchResult ->
            "${matchResult.groupValues[1]} ${matchResult.groupValues[2]}"
        }
        
        // Fix specific common concatenated words that the model produces
        val commonConcatenations = mapOf(
            "HowcanI" to "How can I",
            "helpyou" to "help you",
            "today?I'm" to "today? I'm",
            "readyfor" to "ready for",
            "yourquestions" to "your questions",
            "requests,or" to "requests, or",
            "justachat" to "just a chat",
            "Letme" to "Let me",
            "knowwhat's" to "know what's",
            "onyour" to "on your",
            "mind.For" to "mind. For",
            "example,I" to "example, I",
            "Answerquestions" to "Answer questions",
            "onawidevariety" to "on a wide variety",
            "oftopics" to "of topics",
            "Generatecreative" to "Generate creative",
            "textformats" to "text formats",
            "likepoems" to "like poems",
            "code,scripts" to "code, scripts",
            "musicalpieces" to "musical pieces",
            "email,letters" to "email, letters",
            "Summarizetext" to "Summarize text",
            "Translatelanguages" to "Translate languages",
            "Helpyou" to "Help you",
            "brainstormideas" to "brainstorm ideas",
            "Justhave" to "Just have",
            "acasual" to "a casual",
            "conversation!" to "conversation!",
            "Justtell" to "Just tell",
            "mewhat" to "me what",
            "you'dlike" to "you'd like",
            "todo" to "to do"
        )
        
        // Apply all concatenation fixes
        for ((concatenated, fixed) in commonConcatenations) {
            formatted = formatted.replace(concatenated, fixed)
        }
        
        // Clean up extra whitespace
        formatted = formatted.replace(Regex("\\s+"), " ")
        formatted = formatted.trim()
        
        return formatted
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
    
    /**
     * Estimate memory usage of a bitmap in megabytes
     */
    private fun estimateBitmapMemoryUsage(bitmap: Bitmap): Float {
        val bytesPerPixel = when (bitmap.config) {
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.ALPHA_8 -> 1
            else -> 4 // Default to ARGB_8888
        }
        val totalBytes = bitmap.width * bitmap.height * bytesPerPixel
        return totalBytes / (1024f * 1024f) // Convert to MB
    }

}

/**
 * Exception thrown when a model is not downloaded
 */
class ModelNotDownloadedException(message: String) : Exception(message)

/**
 * General AI service exception
 */
class AIServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
