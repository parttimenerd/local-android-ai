package com.k3s.phoneserver.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.k3s.phoneserver.services.SharedCameraService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Service for object detection using MediaPipe and camera integration
 */
class ObjectDetectionService(private val context: Context) {
    
    private val sharedCameraService = SharedCameraService(context)
    
    /**
     * Perform object detection with camera capture
     */
    suspend fun detectObjects(
        lifecycleOwner: LifecycleOwner,
        side: String = "rear",
        zoom: Float? = null,
        threshold: Float = 0.5f,
        maxResults: Int = 10,
        returnImage: Boolean = false,
        imageScaling: ImageScaling = ImageScaling.DEFAULT
    ): ObjectDetectionResponse {
        
        val startTime = SystemClock.uptimeMillis()
        
        try {
            // Capture image using shared camera service (this needs to be on main thread)
            val cameraResult = withContext(Dispatchers.Main) {
                sharedCameraService.captureImage(
                    lifecycleOwner = lifecycleOwner,
                    side = side,
                    zoom = zoom
                )
            }
            
            if (!cameraResult.success || cameraResult.bitmap == null) {
                return ObjectDetectionResponse(
                    success = false,
                    objects = emptyList(),
                    inferenceTime = 0,
                    captureTime = cameraResult.captureTime,
                    threshold = threshold,
                    imageMetadata = cameraResult.imageMetadata,
                    error = cameraResult.error ?: "Failed to capture image"
                )
            }
            
            val bitmap = cameraResult.bitmap
            
            // Perform object detection using EfficientDet Lite 2 (this can be on background thread)
            val inferenceStartTime = SystemClock.uptimeMillis()
            val detectionResult = withContext(Dispatchers.IO) {
                performObjectDetection(
                    bitmap = bitmap,
                    threshold = threshold,
                    maxResults = maxResults
                )
            }
            val inferenceTime = SystemClock.uptimeMillis() - inferenceStartTime
            
            // Convert to base64 only if requested (apply ImageScaling for output quality)
            val imageBase64 = if (returnImage) {
                val outputBitmap = scaleImageForOutput(bitmap, imageScaling)
                val outputStream = ByteArrayOutputStream()
                val quality = when (imageScaling) {
                    ImageScaling.SMALL -> 70
                    ImageScaling.MEDIUM -> 85
                    ImageScaling.LARGE -> 90
                    ImageScaling.ULTRA -> 95
                    ImageScaling.NONE -> 95
                }
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                val imageBytes = outputStream.toByteArray()
                "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            } else null
            
            return ObjectDetectionResponse(
                success = true,
                objects = detectionResult,
                inferenceTime = inferenceTime,
                captureTime = cameraResult.captureTime,
                threshold = threshold,
                imageMetadata = cameraResult.imageMetadata,
                image = imageBase64
            )
            
        } catch (e: Exception) {
            val isMainThreadError = e.message?.contains("main thread", ignoreCase = true) == true
            val errorContext = when {
                isMainThreadError -> "Threading issue detected - MediaPipe/Camera operations require main thread"
                e.message?.contains("MediaPipe", ignoreCase = true) == true -> "MediaPipe framework error"
                e.message?.contains("camera", ignoreCase = true) == true -> "Camera operation error"
                else -> "General object detection error"
            }
            
            val enhancedMessage = buildString {
                append("Object detection failed: ")
                append(e.message ?: "Unknown error")
                append(" [Context: $errorContext]")
                if (isMainThreadError) {
                    append(" - Check that camera and MediaPipe operations run on main thread")
                }
            }
            
            Timber.e(e, "Object detection failed - Context: $errorContext, Thread: ${Thread.currentThread().name}")
            
            return ObjectDetectionResponse(
                success = false,
                objects = emptyList(),
                inferenceTime = 0,
                captureTime = SystemClock.uptimeMillis() - startTime,
                threshold = threshold,
                imageMetadata = ImageMetadata(
                    width = 0,
                    height = 0,
                    camera = side,
                    timestamp = System.currentTimeMillis()
                ),
                error = enhancedMessage
            )
        }
    }
    
    /**
     * Perform object detection on a bitmap using MediaPipe EfficientDet Lite 2
     */
    private suspend fun performObjectDetection(
        bitmap: Bitmap,
        threshold: Float,
        maxResults: Int
    ): List<DetectedObject> {
        
        try {
            // Scale down the image for better performance (max 512px on largest side)
            val scaledBitmap = scaleImageForInference(bitmap, 512)
            val scaledWidth = scaledBitmap.width.toFloat()
            val scaledHeight = scaledBitmap.height.toFloat()
            
            // Create MediaPipe object detector on main thread (required for MediaPipe)
            Timber.d("Creating MediaPipe detector on thread: ${Thread.currentThread().name}")
            val objectDetector = withContext(Dispatchers.Main) {
                Timber.d("Inside withContext(Dispatchers.Main) - thread: ${Thread.currentThread().name}")
                val baseOptionsBuilder = BaseOptions.builder()
                    .setDelegate(Delegate.CPU) // Use CPU for better compatibility
                
                // Always use EfficientDet Lite 2 model
                baseOptionsBuilder.setModelAssetPath("efficientdet-lite2.tflite")
                
                val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setScoreThreshold(threshold)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(maxResults)
                
                ObjectDetector.createFromOptions(context, optionsBuilder.build())
            }
            
            // Convert Android Bitmap to MediaPipe Image (can be done on any thread)
            val mpImage = BitmapImageBuilder(scaledBitmap).build()
            
            // Perform detection on main thread (MediaPipe requirement)
            Timber.d("Performing detection on thread: ${Thread.currentThread().name}")
            val detectionResult: ObjectDetectorResult = withContext(Dispatchers.Main) {
                Timber.d("Inside detection withContext(Dispatchers.Main) - thread: ${Thread.currentThread().name}")
                objectDetector.detect(mpImage)
            }
            
            // Convert results to our format with percentage-based coordinates
            return detectionResult.detections().map { detection ->
                val category = detection.categories().firstOrNull()
                val boundingBox = detection.boundingBox()
                
                DetectedObject(
                    category = category?.categoryName() ?: "unknown",
                    score = category?.score() ?: 0f,
                    boundingBox = BoundingBox(
                        // Convert absolute coordinates from scaled image to percentage coordinates
                        left = boundingBox.left / scaledWidth,
                        top = boundingBox.top / scaledHeight,
                        right = boundingBox.right / scaledWidth,
                        bottom = boundingBox.bottom / scaledHeight,
                        width = boundingBox.width() / scaledWidth,
                        height = boundingBox.height() / scaledHeight
                    ),
                    categoryIndex = category?.index() ?: -1
                )
            }
            
        } catch (e: Exception) {
            val isMainThreadError = e.message?.contains("main thread", ignoreCase = true) == true
            val currentThread = Thread.currentThread().name
            
            val errorMessage = buildString {
                append("MediaPipe object detection failed: ")
                append(e.message ?: "Unknown error")
                if (isMainThreadError) {
                    append(" [Threading issue - Current thread: $currentThread, MediaPipe requires main thread]")
                } else {
                    append(" [Thread: $currentThread]")
                }
            }
            
            Timber.e(e, errorMessage)
            return emptyList()
        }
    }
    
    /**
     * Scale image for inference while maintaining aspect ratio
     * @param bitmap Original bitmap
     * @param maxSize Maximum size for the largest dimension
     * @return Scaled bitmap
     */
    private fun scaleImageForInference(bitmap: Bitmap, maxSize: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        // If image is already small enough, return as is
        if (originalWidth <= maxSize && originalHeight <= maxSize) {
            return bitmap
        }
        
        // Calculate scaling factor to maintain aspect ratio
        val scaleFactor = if (originalWidth > originalHeight) {
            maxSize.toFloat() / originalWidth
        } else {
            maxSize.toFloat() / originalHeight
        }
        
        val newWidth = (originalWidth * scaleFactor).toInt()
        val newHeight = (originalHeight * scaleFactor).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Scale image for output based on ImageScaling settings
     * @param bitmap Original bitmap
     * @param imageScaling The scaling settings to apply
     * @return Scaled bitmap for output
     */
    private fun scaleImageForOutput(bitmap: Bitmap, imageScaling: ImageScaling): Bitmap {
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
        val maxAllowedSize = min(imageScaling.maxWidth, imageScaling.maxHeight)
        val scaleFactor = maxAllowedSize.toFloat() / maxDimension
        
        val newWidth = (originalWidth * scaleFactor).toInt()
        val newHeight = (originalHeight * scaleFactor).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
