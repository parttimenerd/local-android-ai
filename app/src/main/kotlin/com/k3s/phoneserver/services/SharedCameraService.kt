package me.bechberger.phoneserver.services

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import me.bechberger.phoneserver.ai.ImageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Shared camera capture service with common validation and error handling
 */
class SharedCameraService(private val context: Context) {
    
    private val cameraService = CameraService(context)
    
    /**
     * Camera capture result with metadata and timing information
     */
    data class CameraResult(
        val bitmap: Bitmap?,
        val captureTime: Long,
        val imageMetadata: ImageMetadata,
        val success: Boolean,
        val error: String? = null
    )
    
    /**
     * Capture image with standardized validation and error handling
     */
    suspend fun captureImage(
        lifecycleOwner: LifecycleOwner,
        side: String = "rear",
        zoom: Float? = null
    ): CameraResult = withContext(Dispatchers.IO) {
        
        val startTime = SystemClock.uptimeMillis()
        
        try {
            // Validate camera side parameter
            val lensFacing = when (side.lowercase()) {
                "front" -> LensFacingSelection.FRONT
                "rear" -> LensFacingSelection.REAR
                else -> {
                    return@withContext CameraResult(
                        bitmap = null,
                        captureTime = SystemClock.uptimeMillis() - startTime,
                        imageMetadata = ImageMetadata(
                            width = 0,
                            height = 0,
                            rotation = 0,
                            camera = side,
                            timestamp = System.currentTimeMillis()
                        ),
                        success = false,
                        error = "Invalid side parameter. Must be 'front' or 'rear'"
                    )
                }
            }
            
            // Capture image on main thread
            val captureResult = withContext(Dispatchers.Main) {
                try {
                    Timber.d("Starting camera capture - side: $side, zoom: $zoom")
                    val result = cameraService.captureWithRotation(lifecycleOwner, lensFacing, zoom)
                    Timber.d("Camera capture completed: ${result != null}")
                    result
                } catch (e: Exception) {
                    Timber.e(e, "Camera capture failed in main context")
                    throw e
                }
            }
            
            val captureTime = SystemClock.uptimeMillis() - startTime
            
            if (captureResult?.bitmap == null) {
                return@withContext CameraResult(
                    bitmap = null,
                    captureTime = captureTime,
                    imageMetadata = ImageMetadata(
                        width = 0,
                        height = 0,
                        rotation = 0,
                        camera = side,
                        timestamp = System.currentTimeMillis()
                    ),
                    success = false,
                    error = "Failed to capture image from camera"
                )
            }
            
            // Success case
            CameraResult(
                bitmap = captureResult.bitmap,
                captureTime = captureTime,
                imageMetadata = ImageMetadata(
                    width = captureResult.bitmap!!.width,
                    height = captureResult.bitmap!!.height,
                    rotation = captureResult.rotationDegrees,
                    camera = side,
                    timestamp = System.currentTimeMillis()
                ),
                success = true,
                error = null
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Camera capture failed")
            CameraResult(
                bitmap = null,
                captureTime = SystemClock.uptimeMillis() - startTime,
                imageMetadata = ImageMetadata(
                    width = 0,
                    height = 0,
                    rotation = 0,
                    camera = side,
                    timestamp = System.currentTimeMillis()
                ),
                success = false,
                error = e.message ?: "Unknown camera error"
            )
        }
    }
}
