package com.k3s.phoneserver.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class CameraSelection {
    BACK, FRONT, WIDE, ZOOM
}

class GemmaAIService(private val context: Context) {

    private var imageClassifier: ImageClassifier? = null
    private var objectDetector: ObjectDetector? = null
    private var imageEmbedder: ImageEmbedder? = null
    private var textEmbedder: TextEmbedder? = null
    private var llmInference: LlmInference? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isInitialized = false
    private var isInitializing = false // To track initialization state

    // Local models directory
    private val modelsDir = java.io.File(context.filesDir, "ai_models")

    init {
        CoroutineScope(Dispatchers.IO).launch {
            isInitializing = true
            try {
                initializeAI() // Renamed for clarity
                isInitialized = true
                Timber.d("AI Services initialized successfully on background thread.")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize AI services on background thread")
                // Handle initialization failure (e.g., set a flag, notify user)
            } finally {
                isInitializing = false
            }
        }
    }

    private fun initializeAI() {
        try {
            // Check if models directory exists and has models
            if (!modelsDir.exists() || !modelsDir.isDirectory) {
                Timber.w("Models directory not found, AI services will be limited")
                return
            }
            
            // Initialize MediaPipe Image Classifier
            val classifierOptions = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(getModelPath("efficientnet_lite0.tflite"))
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(5)
                .setScoreThreshold(0.3f)
                .build()
            
            imageClassifier = ImageClassifier.createFromOptions(context, classifierOptions)
            
            // Initialize MediaPipe Object Detector
            val detectorOptions = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(getModelPath("efficientdet_lite0.tflite"))
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(10)
                .setScoreThreshold(0.5f)
                .build()
            
            objectDetector = ObjectDetector.createFromOptions(context, detectorOptions)
            
            // Initialize Image Embedder for multimodal understanding
            try {
                val imageEmbedderOptions = ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(getModelPath("mobilenet_v3_small.tflite"))
                            .build()
                    )
                    .setRunningMode(RunningMode.IMAGE)
                    .setQuantize(true)
                    .build()
                
                imageEmbedder = ImageEmbedder.createFromOptions(context, imageEmbedderOptions)
                Timber.d("Image embedder initialized successfully")
            } catch (e: Exception) {
                Timber.w(e, "Image embedder not available, using classification only")
            }
            
            // Initialize Text Embedder for multimodal understanding
            try {
                val textEmbedderOptions = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(getModelPath("universal_sentence_encoder.tflite"))
                            .build()
                    )
                    .setQuantize(true)
                    .build()
                
                textEmbedder = TextEmbedder.createFromOptions(context, textEmbedderOptions)
                Timber.d("Text embedder initialized successfully")
            } catch (e: Exception) {
                Timber.w(e, "Text embedder not available, using rule-based text processing")
            }
            
            // Initialize Gemma LLM (if gemma.task exists in assets)
            try {
                val assetFiles = context.assets.list("") ?: emptyArray()
                if (assetFiles.contains("gemma.task")) {
                    val llmOptions = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath("gemma.task")  // Use asset path directly
                        .setMaxTokens(512)
                        .build()
                    
                    llmInference = LlmInference.createFromOptions(context, llmOptions)
                    Timber.d("Gemma LLM initialized successfully from assets: gemma.task")
                } else {
                    Timber.i("gemma.task not found in assets - AI features will use vision models only")
                    Timber.i("To enable full AI features, add gemma.task to the assets folder")
                }
            } catch (e: Exception) {
                Timber.w(e, "Gemma LLM initialization failed, using vision-only mode")
            }
            
            isInitialized = true
            Timber.d("Multimodal AI services initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AI services")
        }
    }
    
    private fun getModelPath(filename: String): String {
        val modelFile = java.io.File(modelsDir, filename)
        return if (modelFile.exists()) {
            modelFile.absolutePath
        } else {
            // For development: check adb push location
            val devModelFile = java.io.File("/data/local/tmp/k3s_phone_server/models", filename)
            if (devModelFile.exists()) {
                Timber.d("Using development model from adb push location: $filename")
                return devModelFile.absolutePath
            }
            
            // Check if model exists in assets folder as fallback
            try {
                val assetFiles = context.assets.list("") ?: emptyArray()
                if (assetFiles.contains(filename)) {
                    Timber.d("Using model from assets: $filename")
                    return filename
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to check assets for model: $filename")
            }
            
            // Model not found anywhere - return path anyway for graceful degradation
            Timber.w("Model file not found in storage, dev location, or assets: $filename")
            modelFile.absolutePath
        }
    }

    suspend fun analyzeImage(task: String, imageBase64: String?): String {
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized && isInitializing) {
                    // Optionally wait for initialization to complete if it's in progress
                    // This is a simple busy-wait, consider a more robust solution like a CompletableFuture or callback
                    while(isInitializing) {
                        delay(100) // Wait a bit
                    }
                }
                
                if (imageBase64.isNullOrEmpty()) {
                    return@withContext "Error: No image provided"
                }

                val bitmap = decodeBase64Image(imageBase64)
                if (bitmap == null) {
                    return@withContext "Error: Failed to decode image"
                }

                val result = processImageWithMultimodalAI(bitmap, task)
                result
            } catch (e: Exception) {
                Timber.e(e, "Error analyzing image")
                "Error: Failed to analyze image - ${e.message}"
            }
        }
    }

    suspend fun captureAndAnalyze(task: String, camera: CameraSelection = CameraSelection.BACK): String {
        return withContext(Dispatchers.Main) {
            try {
                if (!isInitialized && isInitializing) {
                    // Optionally wait for initialization to complete if it's in progress
                    // This is a simple busy-wait, consider a more robust solution like a CompletableFuture or callback
                    while(isInitializing) {
                        delay(100) // Wait a bit
                    }
                }
                
                val bitmap = captureImageFromCamera(camera)
                if (bitmap == null) {
                    return@withContext "Error: Failed to capture image from camera"
                }

                withContext(Dispatchers.Default) {
                    processImageWithMultimodalAI(bitmap, task)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error capturing and analyzing image")
                "Error: Failed to capture and analyze image - ${e.message}"
            }
        }
    }

    suspend fun captureImage(camera: CameraSelection = CameraSelection.BACK): String? {
        return withContext(Dispatchers.Main) {
            try {
                if (!isInitialized && isInitializing) {
                    // Optionally wait for initialization to complete if it's in progress
                    // This is a simple busy-wait, consider a more robust solution like a CompletableFuture or callback
                    while(isInitializing) {
                        delay(100) // Wait a bit
                    }
                }
                
                val bitmap = captureImageFromCamera(camera)
                if (bitmap == null) {
                    Timber.e("Failed to capture image from camera")
                    return@withContext null
                }

                withContext(Dispatchers.Default) {
                    // Convert bitmap to base64
                    val outputStream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
                    val imageBytes = outputStream.toByteArray()
                    val base64String = android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT)
                    "data:image/jpeg;base64,$base64String"
                }
            } catch (e: Exception) {
                Timber.e(e, "Error capturing image")
                null
            }
        }
    }

    private suspend fun captureImageFromCamera(camera: CameraSelection = CameraSelection.BACK): Bitmap? = suspendCoroutine { continuation ->
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val cameraSelector = when (camera) {
                        CameraSelection.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                        CameraSelection.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                        CameraSelection.WIDE -> CameraSelector.DEFAULT_BACK_CAMERA
                        CameraSelection.ZOOM -> CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    cameraProvider?.unbindAll()
                    
                    // Camera binding requires a LifecycleOwner context
                    if (context is LifecycleOwner) {
                        val cameraInstance = cameraProvider?.bindToLifecycle(
                            context,
                            cameraSelector,
                            imageCapture
                        )
                        
                        // Set zoom level based on camera selection
                        cameraInstance?.cameraControl?.let { cameraControl ->
                            when (camera) {
                                CameraSelection.WIDE -> {
                                    // Set zoom to minimum (wide angle)
                                    cameraControl.setZoomRatio(0.5f)
                                }
                                CameraSelection.ZOOM -> {
                                    // Set zoom to 2x for telephoto effect
                                    cameraControl.setZoomRatio(2.0f)
                                }
                                else -> {
                                    // Default zoom level
                                    cameraControl.setZoomRatio(1.0f)
                                }
                            }
                        }
                        
                        val outputFile = java.io.File(context.cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
                        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

                        imageCapture.takePicture(
                            outputFileOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                                    outputFile.delete()
                                    continuation.resume(bitmap)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Timber.e(exception, "Image capture failed")
                                    continuation.resume(null)
                                }
                            }
                        )
                    } else {
                        Timber.w("Camera capture requires LifecycleOwner context, but got: ${context.javaClass.simpleName}")
                        continuation.resume(null)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Camera binding failed")
                    continuation.resume(null)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Timber.e(e, "Camera initialization failed")
            continuation.resume(null)
        }
    }

    private suspend fun processImageWithMultimodalAI(bitmap: Bitmap, task: String): String = withContext(Dispatchers.Default) {
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            
            val result = StringBuilder()
            result.append("🤖 Multimodal AI Analysis\n")
            result.append("Task: $task\n\n")

            // Extract multimodal features
            var classifications = listOf<String>()
            var detectedObjects = listOf<String>()
            var imageEmbeddings: FloatArray? = null
            var textEmbeddings: FloatArray? = null

            // Image classification with MediaPipe
            imageClassifier?.let { classifier ->
                val classificationResult = classifier.classify(mpImage)
                result.append("🔍 Image Classification:\n")
                classificationResult.classificationResult().classifications().firstOrNull()?.let { classification ->
                    classifications = classification.categories().take(5).map { category ->
                        val categoryName = category.categoryName()
                        val confidence = String.format("%.1f", category.score() * 100)
                        result.append("• $categoryName ($confidence%)\n")
                        categoryName
                    }
                }
                result.append("\n")
            }

            // Object detection with MediaPipe
            objectDetector?.let { detector ->
                val detectionResult = detector.detect(mpImage)
                result.append("📦 Object Detection:\n")
                detectedObjects = detectionResult.detections().take(8).mapNotNull { detection ->
                    detection.categories().firstOrNull()?.let { category ->
                        val objectName = category.categoryName()
                        val confidence = String.format("%.1f", category.score() * 100)
                        result.append("• $objectName ($confidence%)\n")
                        objectName
                    }
                }
                result.append("\n")
            }

            // Extract image embeddings for multimodal reasoning
            imageEmbedder?.let { embedder ->
                try {
                    val embeddingResult = embedder.embed(mpImage)
                    val embedding = embeddingResult.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                    imageEmbeddings = embedding?.let { FloatArray(it.size) { index -> it.get(index) } }
                    result.append("🧠 Image embeddings extracted (${imageEmbeddings?.size ?: 0} dimensions)\n")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to extract image embeddings")
                }
            }

            // Extract text embeddings for the task
            textEmbedder?.let { embedder ->
                try {
                    val embeddingResult = embedder.embed(task)
                    val embedding = embeddingResult.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                    textEmbeddings = embedding?.let { FloatArray(it.size) { index -> it.get(index) } }
                    result.append("📝 Task embeddings extracted (${textEmbeddings?.size ?: 0} dimensions)\n")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to extract text embeddings")
                }
            }

            // Calculate multimodal similarity if both embeddings are available
            val imageEmb = imageEmbeddings
            val textEmb = textEmbeddings
            if (imageEmb != null && textEmb != null) {
                val similarity = calculateCosineSimilarity(imageEmb, textEmb)
                result.append("🔗 Multimodal similarity: ${String.format("%.3f", similarity)}\n\n")
            }

            // Generate multimodal response
            result.append("💭 Multimodal AI Response:\n")
            val aiResponse = generateMultimodalResponse(task, classifications, detectedObjects, imageEmbeddings, textEmbeddings, bitmap)
            result.append(aiResponse)

            result.toString()
        } catch (e: Exception) {
            Timber.e(e, "AI processing failed")
            "Error: AI processing failed - ${e.message}"
        }
    }

    private suspend fun generateMultimodalResponse(
        task: String, 
        classifications: List<String>, 
        objects: List<String>,
        imageEmbeddings: FloatArray?,
        textEmbeddings: FloatArray?,
        bitmap: Bitmap
    ): String = withContext(Dispatchers.Default) {
        
        // Try using Gemma LLM with multimodal context first
        llmInference?.let { llm ->
            try {
                val prompt = buildMultimodalPrompt(task, classifications, objects, imageEmbeddings, textEmbeddings, bitmap)
                val response = llm.generateResponse(prompt)
                return@withContext response
            } catch (e: Exception) {
                Timber.w(e, "Gemma LLM failed, using multimodal fallback")
            }
        }
        
        // Fallback to enhanced multimodal response generation
        return@withContext generateEnhancedFallbackResponse(task, classifications, objects, imageEmbeddings, textEmbeddings, bitmap)
    }

    private fun buildMultimodalPrompt(
        task: String, 
        classifications: List<String>, 
        objects: List<String>, 
        imageEmbeddings: FloatArray?,
        textEmbeddings: FloatArray?,
        bitmap: Bitmap
    ): String {
        val imageInfo = "Image size: ${bitmap.width}x${bitmap.height}px"
        val classificationText = if (classifications.isNotEmpty()) {
            "Detected categories: ${classifications.joinToString(", ")}"
        } else "No clear categories detected"
        
        val objectText = if (objects.isNotEmpty()) {
            "Detected objects: ${objects.joinToString(", ")}"
        } else "No specific objects detected"

        val embeddingInfo = when {
            imageEmbeddings != null && textEmbeddings != null -> {
                val similarity = calculateCosineSimilarity(imageEmbeddings, textEmbeddings)
                "Multimodal similarity between image and task: ${String.format("%.3f", similarity)}"
            }
            imageEmbeddings != null -> "Image features extracted (${imageEmbeddings.size} dimensions)"
            else -> "Basic visual analysis only"
        }

        return """
        You are a multimodal AI assistant analyzing an image with advanced vision-language understanding. Here's the comprehensive analysis:
        
        VISUAL ANALYSIS:
        $imageInfo
        $classificationText
        $objectText
        
        MULTIMODAL FEATURES:
        $embeddingInfo
        
        USER REQUEST: "$task"
        
        Please provide a detailed, contextual response that demonstrates understanding of both the visual content and the user's request. Use the multimodal analysis to give specific, relevant insights about what you can see and how it relates to the user's query.
        """.trimIndent()
    }

    private fun generateEnhancedFallbackResponse(
        task: String, 
        classifications: List<String>, 
        objects: List<String>,
        imageEmbeddings: FloatArray?,
        textEmbeddings: FloatArray?,
        bitmap: Bitmap
    ): String {
        val width = bitmap.width
        val height = bitmap.height
        val allItems = (classifications + objects).distinct()
        
        // Calculate relevance score if embeddings are available
        val relevanceScore = if (imageEmbeddings != null && textEmbeddings != null) {
            calculateCosineSimilarity(imageEmbeddings, textEmbeddings)
        } else null
        
        val contextualPrefix = relevanceScore?.let { score ->
            when {
                score > 0.7 -> "The image content strongly relates to your request. "
                score > 0.4 -> "The image content moderately relates to your request. "
                score > 0.2 -> "The image content somewhat relates to your request. "
                else -> "The image content appears different from your request, but I can still analyze it. "
            }
        } ?: ""
        
        return when {
            task.contains("describe", ignoreCase = true) || task.contains("surroundings", ignoreCase = true) -> {
                if (allItems.isNotEmpty()) {
                    "$contextualPrefix Based on multimodal analysis, I can see a ${width}x${height} image containing: ${allItems.joinToString(", ")}. " +
                    "This appears to be ${generateSceneDescription(allItems)}. ${generateDetailedDescription(allItems, bitmap)}"
                } else {
                    "$contextualPrefix I can see a ${width}x${height} image. While specific object detection is limited, I can analyze the visual composition and provide contextual insights."
                }
            }
            task.contains("identify", ignoreCase = true) || task.contains("what", ignoreCase = true) -> {
                if (allItems.isNotEmpty()) {
                    "$contextualPrefix I can identify several elements in this multimodal analysis: ${allItems.joinToString(", ")}. " +
                    "The most prominent features appear to be ${allItems.take(3).joinToString(" and ")}. ${generateIdentificationDetails(allItems)}"
                } else {
                    "$contextualPrefix This appears to be an image that I'm analyzing with multimodal AI capabilities, though specific object identification is challenging."
                }
            }
            task.contains("count", ignoreCase = true) -> {
                val objectCount = objects.size
                "$contextualPrefix Using object detection, I identified approximately $objectCount distinct objects: ${objects.joinToString(", ")}. Note that this is based on visual analysis and may not capture all objects in the scene."
            }
            task.contains("color", ignoreCase = true) -> {
                val colorAnalysis = analyzeImageColors(bitmap)
                "$contextualPrefix Color analysis using multimodal processing: $colorAnalysis. ${generateColorContext(allItems)}"
            }
            else -> {
                if (allItems.isNotEmpty()) {
                    "$contextualPrefix Through multimodal AI analysis, I've identified: ${allItems.joinToString(", ")}. " +
                    "${generateContextualResponse(task, allItems)} ${generateAdvancedInsights(allItems, relevanceScore)}"
                } else {
                    "$contextualPrefix I've processed the image using multimodal AI capabilities. " +
                    "While specific object detection is limited, the image appears to be a ${width}x${height} visual content that I can analyze in context of your request."
                }
            }
        }
    }

    private fun calculateCosineSimilarity(vector1: FloatArray, vector2: FloatArray): Float {
        if (vector1.size != vector2.size) return 0f
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in vector1.indices) {
            dotProduct += vector1[i] * vector2[i]
            norm1 += vector1[i] * vector1[i]
            norm2 += vector2[i] * vector2[i]
        }
        
        val magnitude = kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2)
        return if (magnitude > 0f) dotProduct / magnitude else 0f
    }

    private fun generateDetailedDescription(@Suppress("UNUSED_PARAMETER") items: List<String>, bitmap: Bitmap): String {
        val colorInfo = analyzeImageColors(bitmap)
        return "The visual composition suggests $colorInfo, complementing the detected elements."
    }

    private fun generateIdentificationDetails(items: List<String>): String {
        return when {
            items.size >= 5 -> "This appears to be a complex scene with multiple elements."
            items.size >= 3 -> "This seems to be a moderately detailed scene."
            else -> "This appears to be a relatively simple composition."
        }
    }

    private fun generateColorContext(items: List<String>): String {
        return when {
            items.any { it.contains("outdoor", ignoreCase = true) || it.contains("nature", ignoreCase = true) } ->
                "The colors are consistent with an outdoor/natural environment."
            items.any { it.contains("indoor", ignoreCase = true) || it.contains("room", ignoreCase = true) } ->
                "The color palette suggests an indoor setting."
            else -> "The color distribution provides visual context for the scene."
        }
    }

    private fun generateAdvancedInsights(@Suppress("UNUSED_PARAMETER") items: List<String>, relevanceScore: Float?): String {
        val relevanceText = relevanceScore?.let { score ->
            "Multimodal relevance score: ${String.format("%.2f", score)}."
        } ?: ""
        
        return "Advanced multimodal analysis enables contextual understanding of visual-textual relationships. $relevanceText"
    }

    private fun generateSceneDescription(items: List<String>): String {
        return when {
            items.any { it.contains("person", ignoreCase = true) || it.contains("people", ignoreCase = true) } -> 
                "a scene with people"
            items.any { it.contains("building", ignoreCase = true) || it.contains("house", ignoreCase = true) } -> 
                "an architectural or urban scene"
            items.any { it.contains("animal", ignoreCase = true) || it.contains("dog", ignoreCase = true) || it.contains("cat", ignoreCase = true) } -> 
                "a scene with animals"
            items.any { it.contains("vehicle", ignoreCase = true) || it.contains("car", ignoreCase = true) } -> 
                "a transportation scene"
            items.any { it.contains("food", ignoreCase = true) || it.contains("fruit", ignoreCase = true) } -> 
                "a food-related scene"
            else -> "a general scene"
        }
    }

    private fun generateContextualResponse(task: String, @Suppress("UNUSED_PARAMETER") items: List<String>): String {
        return "This seems relevant to your request about '$task' - the identified elements could provide useful context."
    }

    private fun analyzeImageColors(bitmap: Bitmap): String {
        // Sample pixels to analyze dominant colors
        val sampleSize = minOf(100, bitmap.width * bitmap.height / 100)
        var redSum = 0
        var greenSum = 0
        var blueSum = 0
        
        for (i in 0 until sampleSize) {
            val x = (i % 10) * (bitmap.width / 10)
            val y = (i / 10) * (bitmap.height / 10)
            if (x < bitmap.width && y < bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                redSum += (pixel shr 16) and 0xff
                greenSum += (pixel shr 8) and 0xff
                blueSum += pixel and 0xff
            }
        }
        
        val avgRed = redSum / sampleSize
        val avgGreen = greenSum / sampleSize
        val avgBlue = blueSum / sampleSize
        
        val dominantColor = when {
            avgRed > avgGreen && avgRed > avgBlue -> "warm/reddish tones"
            avgGreen > avgRed && avgGreen > avgBlue -> "natural/greenish tones"  
            avgBlue > avgRed && avgBlue > avgGreen -> "cool/bluish tones"
            avgRed + avgGreen + avgBlue < 128 * 3 -> "dark/low-light conditions"
            avgRed + avgGreen + avgBlue > 200 * 3 -> "bright/well-lit conditions"
            else -> "balanced/neutral colors"
        }
        
        return "The image has predominantly $dominantColor (R:$avgRed, G:$avgGreen, B:$avgBlue)"
    }

    private fun decodeBase64Image(base64String: String): Bitmap? {
        return try {
            val cleanBase64 = if (base64String.startsWith("data:image")) {
                base64String.substring(base64String.indexOf(",") + 1)
            } else {
                base64String
            }
            
            val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode base64 image")
            null
        }
    }

    /**
     * Check if AI services are available and functional
     */
    fun isAIAvailable(): Boolean {
        return try {
            // Check if basic vision models are available
            val hasVisionModels = imageClassifier != null || objectDetector != null
            
            // Check if Gemma LLM is available for advanced AI features
            val hasLLM = llmInference != null
            
            // Check if gemma.task exists in assets for full AI capabilities
            val assetFiles = context.assets.list("") ?: emptyArray()
            val hasGemmaAsset = assetFiles.contains("gemma.task")
            
            // Return true if we have at least basic vision capabilities
            // Full AI features require both vision models and LLM
            return hasVisionModels || hasLLM || hasGemmaAsset
        } catch (e: Exception) {
            Timber.w(e, "Error checking AI availability")
            false
        }
    }

    /**
     * Get detailed AI capabilities information
     */
    fun getAICapabilities(): Map<String, Any> {
        return try {
            val assetFiles = context.assets.list("") ?: emptyArray()
            val hasGemmaAsset = assetFiles.contains("gemma.task")
            
            mapOf(
                "available" to isAIAvailable(),
                "capabilities" to mapOf(
                    "imageClassification" to (imageClassifier != null),
                    "objectDetection" to (objectDetector != null),
                    "imageEmbedding" to (imageEmbedder != null),
                    "textEmbedding" to (textEmbedder != null),
                    "llmInference" to (llmInference != null),
                    "gemmaAsset" to hasGemmaAsset
                ),
                "features" to listOf(
                    if (imageClassifier != null) "Image Classification" else null,
                    if (objectDetector != null) "Object Detection" else null,
                    if (imageEmbedder != null) "Image Embedding" else null,
                    if (textEmbedder != null) "Text Understanding" else null,
                    if (llmInference != null || hasGemmaAsset) "Language Generation" else null,
                    "Camera Capture"
                ).filterNotNull(),
                "limitations" to listOf(
                    if (llmInference == null && !hasGemmaAsset) "Advanced language generation not available" else null,
                    if (imageEmbedder == null) "Image similarity analysis limited" else null,
                    if (textEmbedder == null) "Text understanding limited" else null
                ).filterNotNull()
            )
        } catch (e: Exception) {
            Timber.w(e, "Error getting AI capabilities")
            mapOf(
                "available" to false,
                "error" to "Failed to check AI capabilities"
            )
        }
    }

    fun cleanup() {
        try {
            imageClassifier?.close()
            objectDetector?.close()
            imageEmbedder?.close()
            textEmbedder?.close()
            llmInference?.close()
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Timber.e(e, "Error during AI service cleanup")
        }
    }
}
