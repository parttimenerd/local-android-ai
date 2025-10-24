package com.k3s.phoneserver.ai

/**
 * Request payload for /ai/text endpoint
 */
data class AITextRequest(
    val text: String,
    val model: String = AIModel.GEMMA_3_1B_IT.name,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val image: String? = null,  // Base64 encoded image
    val captureConfig: CaptureConfig? = null,  // Camera capture configuration
    val returnImage: Boolean = false,  // Whether to return captured/processed image in response
    val imageScaling: ImageScaling = ImageScaling.MEDIUM  // Image scaling for returned image
)

/**
 * Camera capture configuration for AI requests
 */
data class CaptureConfig(
    val camera: String = "rear",  // "rear", "front"
    val quality: Int = 90,        // JPEG quality 1-100
    val maxWidth: Int = 1024,     // Maximum image width
    val maxHeight: Int = 1024     // Maximum image height
)

/**
 * Image scaling presets for AI inference
 */
enum class ImageScaling(
    val maxWidth: Int,
    val maxHeight: Int,
    val minWidth: Int,
    val minHeight: Int,
    val description: String
) {
    NONE(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1, "No scaling - use original image size"),
    SMALL(512, 512, 64, 64, "Small images - fastest inference, lower quality"),
    MEDIUM(1024, 1024, 128, 128, "Medium images - balanced performance and quality (default)"),
    LARGE(2048, 2048, 256, 256, "Large images - better quality, slower inference"),
    ULTRA(4096, 4096, 512, 512, "Ultra high resolution - best quality, slowest inference");
    
    companion object {
        val DEFAULT = MEDIUM
    }
}

/**
 * Response from /ai/text endpoint
 */
data class AITextResponse(
    val response: String,
    val model: String,
    val thinking: String? = null,  // For models that show thinking process
    val license: String? = null,   // Required license statement for certain models (e.g., Gemma)
    val metadata: AIResponseMetadata,
    val image: String? = null      // Base64 encoded image (if returnImage=true)
)

/**
 * Metadata about the AI response
 */
data class AIResponseMetadata(
    val model: String,
    val inferenceTime: Long,      // Milliseconds
    val tokenCount: Int,          // Estimated tokens processed
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val backend: String,          // "CPU" or "GPU"
    val isMultimodal: Boolean = false // Whether request included image/vision input
)

/**
 * Error response for AI operations
 */
data class AIErrorResponse(
    val error: String,
    val code: String,
    val details: String? = null
)

/**
 * Supported models list response
 */
data class SupportedModelsResponse(
    val models: List<ModelInfo>
)

/**
 * Information about a model
 */
data class ModelInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val needsLicense: Boolean,
    val available: Boolean,       // Whether the model is downloaded and available
    val currentlyLoaded: Boolean, // Whether the model is currently loaded in memory
    val backend: String,
    val isMultiModal: Boolean     // Whether model supports vision/image input
)

/**
 * Request to download a model
 */
data class ModelDownloadRequest(
    val modelName: String
)

/**
 * Response from model download attempt
 */
data class ModelDownloadResponse(
    val success: Boolean,
    val message: String,
    val modelName: String,
    val status: String,           // "download_started", "already_available", "download_failed"
    val downloadUrl: String? = null,  // URL for manual download if auto-download fails
    val persistenceInfo: ModelPersistenceInfo? = null  // Details about model storage
)

/**
 * Model persistence information for API responses
 */
data class ModelPersistenceInfo(
    val downloadPath: String,
    val fileSize: Long,
    val formattedSize: String,
    val downloadTimestamp: Long,
    val lastAccessedTimestamp: Long,
    val isLoaded: Boolean,
    val downloadStatus: String,
    val ageInDays: Long
)

/**
 * Model status and persistence summary for API responses
 */
data class ModelStatusSummary(
    val totalModels: Int,
    val downloadedModels: Int,
    val loadedModels: Int,
    val totalDownloadSize: Long,
    val formattedTotalSize: String,
    val lastLoadedModel: String?,
    val modelsDirectory: String?,
    val availableModels: List<ModelPersistenceInfo>,
    val statistics: ModelStatisticsResponse
)

/**
 * Model statistics for API responses
 */
data class ModelStatisticsResponse(
    val totalModels: Int,
    val downloadedModels: Int,
    val loadedModels: Int,
    val totalDownloadSize: Long,
    val formattedTotalSize: String,
    val oldestDownloadTimestamp: Long?,
    val newestDownloadTimestamp: Long?,
    val lastAccessedTimestamp: Long?
)

/**
 * Streaming chunk for model test output
 */
data class ModelTestStreamChunk(
    val type: String,               // "token", "complete", "error"
    val token: String? = null,      // The token being generated (for type="token")
    val fullText: String? = null,   // Complete text so far (for type="complete")
    val success: Boolean? = null,   // Whether test succeeded (for type="complete")
    val error: String? = null,      // Error message (for type="error")
    val model: String,              // Model name
    val elapsedTime: Long? = null,  // Time elapsed since test start (for type="complete")
    val metadata: AIResponseMetadata? = null  // Complete metadata (for type="complete")
)

/**
 * Request for object detection endpoint
 */
data class ObjectDetectionRequest(
    val side: String = "rear",              // "front" or "rear"
    val zoom: Float? = null,                // Zoom level
    val threshold: Float = 0.5f,            // Detection confidence threshold (0.0-1.0)
    val maxResults: Int = 10,               // Maximum number of objects to detect
    val returnImage: Boolean = false,       // Whether to include the captured image in response
    val imageScaling: ImageScaling = ImageScaling.DEFAULT  // Image quality/scaling for returned image
)

/**
 * Response from object detection endpoint
 */
data class ObjectDetectionResponse(
    val success: Boolean,
    val objects: List<DetectedObject>,
    val inferenceTime: Long,               // Milliseconds
    val captureTime: Long,                 // Milliseconds
    val threshold: Float,
    val imageMetadata: ImageMetadata,
    val image: String? = null,             // Base64 encoded image (if returnImage=true)
    val error: String? = null
)

/**
 * A detected object with bounding box and classification
 */
data class DetectedObject(
    val category: String,                  // Object category/class name
    val score: Float,                      // Confidence score (0.0-1.0)
    val boundingBox: BoundingBox,          // Object location
    val categoryIndex: Int                 // Category index in the model
)

/**
 * Bounding box coordinates for detected objects
 */
data class BoundingBox(
    val left: Float,                       // Normalized x coordinate (0.0-1.0)
    val top: Float,                        // Normalized y coordinate (0.0-1.0)
    val right: Float,                      // Normalized x coordinate (0.0-1.0)
    val bottom: Float,                     // Normalized y coordinate (0.0-1.0)
    val width: Float,                      // Normalized width (0.0-1.0)
    val height: Float                      // Normalized height (0.0-1.0)
)

/**
 * Metadata about the captured image used for object detection
 */
data class ImageMetadata(
    val width: Int,                        // Image width in pixels
    val height: Int,                       // Image height in pixels
    val rotation: Int = 0,                 // Image rotation in degrees
    val camera: String,                    // "front" or "rear"
    val timestamp: Long                    // Capture timestamp
)
