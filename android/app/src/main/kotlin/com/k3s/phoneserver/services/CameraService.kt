package com.k3s.phoneserver.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.k3s.phoneserver.manager.AppPermissionManager
import timber.log.Timber
import java.io.File
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

// Keep these data structures from the previous simplified version
data class SimplifiedCameraLensInfo(
    val lensFacing: Int,
    val availableFixedLenses: List<FixedZoomLens>
)

data class FixedZoomLens(
    val name: String,
    val approximateZoomFactor: Float
)

// Enum for lens facing (more explicit than CameraSelector.LENS_FACING_*)
enum class LensFacingSelection {
    FRONT,
    REAR
}

class CameraService(private val context: Context) {

    private val TAG = "CameraServiceUnified"
    private val permissionManager = AppPermissionManager.getInstance()

    // --- Public API ---

    /**
     * Check if camera permissions are available
     */
    fun hasCameraPermissions(): Boolean {
        return permissionManager.hasCameraPermissions(context)
    }

    /**
     * Captures an image with the specified lens facing and an optional target approximate zoom factor.
     * If targetApproxZoomFactor is null, it attempts a standard capture (around 1.0x) for the given facing.
     *
     * @param lifecycleOwner The LifecycleOwner to bind the camera to.
     * @param lensFacingSelection Enum indicating FRONT or REAR camera.
     * @param targetApproxZoomFactor Optional: The desired approximate zoom factor (e.g., 0.6f, 1.0f, 2.0f).
     * @return A CaptureResult with bitmap and rotation degrees, or null if capture failed.
     */
    suspend fun captureWithRotation(
        lifecycleOwner: LifecycleOwner,
        lensFacingSelection: LensFacingSelection,
        targetApproxZoomFactor: Float? = null // Default to null, implying standard 1.0x behavior
    ): CaptureResult? {
        if (!hasCameraPermissions()) {
            Timber.w("Camera permission not granted")
            return null
        }

        val cameraSelectorLensFacing = if (lensFacingSelection == LensFacingSelection.FRONT) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        // If a specific zoom factor is targeted, use the logic to find the best matching fixed lens
        if (targetApproxZoomFactor != null) {
            return captureImageMatchingZoomFactorInternal(
                lifecycleOwner,
                targetApproxZoomFactor,
                cameraSelectorLensFacing
            )
        } else {
            // Default behavior: capture with the primary lens for the selected facing (approx 1.0x)
            // This can also use captureImageMatchingZoomFactorInternal with 1.0f or a more direct approach.
            // For simplicity and to ensure it hits the "standard" 1x:
            return captureWithStandardLens(
                lifecycleOwner,
                cameraSelectorLensFacing
            )
        }
    }

    /**
     * Backward compatibility function - returns only the bitmap
     * @deprecated Use captureWithRotation() instead to get rotation information
     */
    suspend fun capture(
        lifecycleOwner: LifecycleOwner,
        lensFacingSelection: LensFacingSelection,
        targetApproxZoomFactor: Float? = null // Default to null, implying standard 1.0x behavior
    ): Bitmap? {
        return captureWithRotation(lifecycleOwner, lensFacingSelection, targetApproxZoomFactor)?.bitmap
    }

    /**
     * Retrieves information about available fixed lenses (approximate zoom factors) for front and rear cameras.
     */
    @SuppressLint("UnsafeOptInUsageError")
    suspend fun getAvailableFixedLenses(
        lifecycleOwner: LifecycleOwner
    ): List<SimplifiedCameraLensInfo> = suspendCoroutine { continuation ->
        if (!hasCameraPermissions()) {
            Timber.w("Camera permission not granted for getAvailableFixedLenses")
            continuation.resume(emptyList())
            return@suspendCoroutine
        }

        // (Implementation from the previous version - no changes needed here)
        val results = mutableListOf<SimplifiedCameraLensInfo>()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val lensFacingOptions = listOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT)
                var pendingQueries = lensFacingOptions.size

                for (lensFacing in lensFacingOptions) {
                    val fixedLenses = mutableListOf<FixedZoomLens>()
                    try {
                        val preview = Preview.Builder().build()
                        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        val cameraInfo = camera.cameraInfo
                        val physicalDetails = getInternalPhysicalCameraDetails(cameraManager, cameraInfo)

                        if (physicalDetails.isNotEmpty()) {
                            physicalDetails.forEachIndexed { index, detail ->
                                val name = when {
                                    physicalDetails.size == 1 -> if (lensFacing == CameraSelector.LENS_FACING_FRONT) "Selfie" else "Main"
                                    detail.approxZoomFactor < 0.8f -> "Ultra-Wide"
                                    detail.approxZoomFactor > 1.5f && detail.approxZoomFactor < 3.5f -> "Telephoto"
                                    detail.approxZoomFactor >= 3.5f -> "Super Telephoto"
                                    else -> if (lensFacing == CameraSelector.LENS_FACING_FRONT) "Selfie ${index + 1}" else "Lens ${index + 1}"
                                }
                                fixedLenses.add(FixedZoomLens(name, detail.approxZoomFactor))
                            }
                        } else {
                            fixedLenses.add(FixedZoomLens(if (lensFacing == CameraSelector.LENS_FACING_FRONT) "Selfie" else "Main", 1.0f))
                        }
                        results.add(SimplifiedCameraLensInfo(lensFacing, fixedLenses.sortedBy { it.approximateZoomFactor }))
                        cameraProvider.unbind(preview)
                    } catch (exc: Exception) {
                        Log.e(TAG, "Could not query camera for lens facing: $lensFacing", exc)
                        results.add(SimplifiedCameraLensInfo(lensFacing, listOf(FixedZoomLens("Error", 1.0f))))
                    } finally {
                        pendingQueries--
                        if (pendingQueries == 0) {
                            continuation.resume(results)
                        }
                    }
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to get camera provider or camera manager for getAvailableFixedLenses", exc)
                continuation.resume(emptyList())
            }
        }, mainExecutor)
    }


    // --- Internal Implementation Details ---

    /**
     * Internal function to capture with the "standard" or default lens for a given facing.
     * This typically corresponds to an approximate zoom factor of 1.0x.
     */
    private suspend fun captureWithStandardLens(
        lifecycleOwner: LifecycleOwner,
        @CameraSelector.LensFacing cameraSelectorLensFacing: Int
    ): CaptureResult? = suspendCoroutine { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Default selector for the given facing
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(cameraSelectorLensFacing)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)
                Log.d(TAG, "Capturing with standard lens for facing: $cameraSelectorLensFacing")
                takePictureInternal(imageCapture, mainExecutor, continuation)

            } catch (e: Exception) {
                Log.e(TAG, "Failed in captureWithStandardLens", e)
                continuation.resume(null)
            }
        }, mainExecutor)
    }


    /**
     * Internal implementation for capturing image by matching zoom factor.
     * (Previously named captureImageMatchingZoomFactor)
     */
    @SuppressLint("UnsafeOptInUsageError", "CameraSelectorMakesDifficultBehaviorAdjustment")
    private suspend fun captureImageMatchingZoomFactorInternal(
        lifecycleOwner: LifecycleOwner,
        targetApproxZoomFactor: Float,
        @CameraSelector.LensFacing cameraSelectorLensFacing: Int
    ): CaptureResult? = suspendCoroutine { continuation ->
        // (Implementation from the previous version - no changes needed in its core logic)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                var finalCameraSelector: CameraSelector = CameraSelector.Builder().requireLensFacing(cameraSelectorLensFacing).build()
                val availableLogicalCameras = cameraProvider.getAvailableCameraInfos().filter { it.lensFacing == cameraSelectorLensFacing }
                var bestMatchCameraInfo: CameraInfo? = null
                var smallestDiff = Float.MAX_VALUE

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (logicalCamInfo in availableLogicalCameras) {
                        val internalDetails = getInternalPhysicalCameraDetails(cameraManager, logicalCamInfo)
                        if (internalDetails.isNotEmpty()) {
                            for (detail in internalDetails) {
                                val diff = abs(detail.approxZoomFactor - targetApproxZoomFactor)
                                if (diff < smallestDiff) {
                                    smallestDiff = diff
                                    bestMatchCameraInfo = logicalCamInfo
                                }
                                if (diff < 0.05f) break
                            }
                        } else {
                            val logicalCameraId = Camera2CameraInfo.from(logicalCamInfo).cameraId
                            val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)
                            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val primaryFocalLength = focalLengths?.firstOrNull() ?: calculateApproxFocalLengthFor1x(characteristics)
                            val approxZoom = calculateApproxZoomFactor(primaryFocalLength, characteristics)
                            val diff = abs(approxZoom - targetApproxZoomFactor)
                            if (diff < smallestDiff) {
                                smallestDiff = diff
                                bestMatchCameraInfo = logicalCamInfo
                            }
                        }
                        if (smallestDiff < 0.05f) break
                    }
                } else {
                    bestMatchCameraInfo = availableLogicalCameras.firstOrNull()
                }

                if (bestMatchCameraInfo != null) {
                    Log.d(TAG, "Best match for zoom $targetApproxZoomFactor found. Smallest diff: $smallestDiff. Selecting logical camera: ${Camera2CameraInfo.from(bestMatchCameraInfo).cameraId}")
                    finalCameraSelector = CameraSelector.Builder().addCameraFilter { Collections.singletonList(bestMatchCameraInfo) }.build()
                } else {
                    Log.w(TAG, "Could not find a specific camera for zoom $targetApproxZoomFactor. Using default for lens facing $cameraSelectorLensFacing.")
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, finalCameraSelector, imageCapture)
                takePictureInternal(imageCapture, mainExecutor, continuation)
            } catch (exc: Exception) {
                Log.e(TAG, "Failed in captureImageMatchingZoomFactorInternal", exc)
                continuation.resume(null)
            }
        }, mainExecutor)
    }

    // --- Core Helper Methods ---
    // (getInternalPhysicalCameraDetails, calculateApproxFocalLengthFor1x, calculateApproxZoomFactor remain the same)

    private data class InternalPhysicalCameraDetail(val id: String, val approxZoomFactor: Float)

    @SuppressLint("UnsafeOptInUsageError")
    private fun getInternalPhysicalCameraDetails(
        cameraManager: CameraManager,
        cameraInfo: CameraInfo
    ): List<InternalPhysicalCameraDetail> {
        val details = mutableListOf<InternalPhysicalCameraDetail>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            try {
                val cameraId = Camera2CameraInfo.from(cameraInfo).cameraId
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val firstFocalLength = focalLengths?.firstOrNull() ?: calculateApproxFocalLengthFor1x(characteristics)
                val approxZoom = calculateApproxZoomFactor(firstFocalLength, characteristics)
                details.add(InternalPhysicalCameraDetail(cameraId, approxZoom))
            } catch (e: Exception) { Log.e(TAG, "Error on pre-P device for physical details", e) }
            return details
        }
        try {
            val logicalCameraId = Camera2CameraInfo.from(cameraInfo).cameraId
            val logicalChars = cameraManager.getCameraCharacteristics(logicalCameraId)
            val physicalCameraIds = logicalChars.physicalCameraIds
            if (physicalCameraIds.isNotEmpty()) {
                physicalCameraIds.forEach { physicalId ->
                    try {
                        val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                        val focalLengths = physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val primaryFocalLength = focalLengths?.firstOrNull() ?: calculateApproxFocalLengthFor1x(physicalChars)
                        val approxZoom = calculateApproxZoomFactor(primaryFocalLength, physicalChars)
                        details.add(InternalPhysicalCameraDetail(physicalId, approxZoom))
                    } catch (e: Exception) { Log.e(TAG, "Failed to process physical camera ID: $physicalId", e) }
                }
            } else {
                val focalLengths = logicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val primaryFocalLength = focalLengths?.firstOrNull() ?: calculateApproxFocalLengthFor1x(logicalChars)
                val approxZoom = calculateApproxZoomFactor(primaryFocalLength, logicalChars)
                details.add(InternalPhysicalCameraDetail(logicalCameraId, approxZoom))
            }
        } catch (e: Exception) { Log.e(TAG, "Error accessing C2 chars for physical details", e) }
        return details
    }

    private fun calculateApproxFocalLengthFor1x(characteristics: CameraCharacteristics): Float {
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return if (sensorSize != null && sensorSize.width > 0) (sensorSize.width / 2.5f).coerceAtLeast(3.5f) else 4.5f
    }

    private fun calculateApproxZoomFactor(actualFocalLength: Float, characteristics: CameraCharacteristics): Float {
        val baseline1xFocalLength = calculateApproxFocalLengthFor1x(characteristics)
        if (baseline1xFocalLength <= 0 || actualFocalLength <=0) return 1.0f
        return actualFocalLength / baseline1xFocalLength
    }

    /** 
     * Capture result with rotation information
     * Note: The bitmap is already rotated according to EXIF data for proper display orientation
     */
    data class CaptureResult(
        val bitmap: Bitmap?, // Pre-rotated bitmap ready for display
        val rotationDegrees: Int // Original EXIF rotation that was applied to the bitmap
    )

    /** Renamed from takePicture to takePictureInternal to avoid conflict if CameraService has other public methods. */
    private fun takePictureInternal(
        imageCapture: ImageCapture,
        executor: Executor,
        continuation: kotlin.coroutines.Continuation<CaptureResult?>
    ) {
        val outputFile = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        
        imageCapture.takePicture(
            outputOptions, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    outputFile.delete()
                    continuation.resume(null)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")
                    
                    try {
                        // Get EXIF rotation information
                        val exif = ExifInterface(outputFile.absolutePath)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        
                        val rotationDegrees = when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                        
                        Log.d(TAG, "Image EXIF orientation: $orientation, rotation: $rotationDegrees degrees")
                        
                        val originalBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                        outputFile.delete()
                        
                        // Apply rotation to the bitmap if needed
                        val rotatedBitmap = if (rotationDegrees != 0 && originalBitmap != null) {
                            Log.d(TAG, "Applying $rotationDegrees degree rotation to bitmap")
                            rotateImage(originalBitmap, rotationDegrees.toFloat())
                        } else {
                            originalBitmap
                        }
                        
                        continuation.resume(CaptureResult(rotatedBitmap, rotationDegrees))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process image EXIF data", e)
                        val originalBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                        outputFile.delete()
                        continuation.resume(CaptureResult(originalBitmap, 0))
                    }
                }
            }
        )
    }

    /**
     * Rotates a bitmap by the specified degrees
     * @param source The source bitmap to rotate
     * @param degrees The rotation angle in degrees (90, 180, 270, etc.)
     * @return The rotated bitmap
     */
    private fun rotateImage(source: Bitmap, degrees: Float): Bitmap {
        return try {
            val matrix = Matrix()
            matrix.postRotate(degrees)
            val rotatedBitmap = Bitmap.createBitmap(
                source, 0, 0, 
                source.width, source.height, 
                matrix, true
            )
            
            // Clean up the original bitmap if it's different from the rotated one
            if (rotatedBitmap != source) {
                source.recycle()
            }
            
            Log.d(TAG, "Successfully rotated image by $degrees degrees. Original: ${source.width}x${source.height}, Rotated: ${rotatedBitmap.width}x${rotatedBitmap.height}")
            rotatedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate image by $degrees degrees", e)
            // Return original bitmap if rotation fails
            source
        }
    }

    fun cleanup() {
        // No specific cleanup needed for this implementation
    }
}
