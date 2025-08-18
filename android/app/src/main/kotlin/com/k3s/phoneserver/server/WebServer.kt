package com.k3s.phoneserver.server

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.k3s.phoneserver.lifecycle.SimpleLifecycleOwner
import com.k3s.phoneserver.logging.RequestLogger
import com.k3s.phoneserver.manager.AppPermissionManager
import com.k3s.phoneserver.services.CameraService
import com.k3s.phoneserver.services.SharedCameraService
import com.k3s.phoneserver.services.LocationService
import com.k3s.phoneserver.services.OrientationService
import com.k3s.phoneserver.ai.AIService
import com.k3s.phoneserver.ai.AIModel
import com.k3s.phoneserver.ai.ModelDetector
import com.k3s.phoneserver.ai.ModelDownloadRequest
import com.k3s.phoneserver.ai.ModelDownloadResponse
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream

class WebServer(private val context: Context) {

    private var server: NettyApplicationEngine? = null
    private val locationService = LocationService(context)
    private val orientationService = OrientationService(context)
    private val cameraService = CameraService(context)
    private val sharedCameraService = SharedCameraService(context)
    private val permissionManager = AppPermissionManager.getInstance()
    private val aiService = AIService(context)
    private val objectDetectionService = com.k3s.phoneserver.ai.ObjectDetectionService(context)

    suspend fun start(port: Int) {
        withContext(Dispatchers.IO) {
            try {
                server = embeddedServer(Netty, port = port) {
                    configureServer()
                }.start(wait = false)
                
                Timber.d("Web server started on port $port")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start server on port $port")
                throw e
            }
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            server?.stop(1000, 2000)
            locationService.cleanup()
            orientationService.cleanup()
            cameraService.cleanup()
            Timber.d("Web server stopped")
        }
    }

    private fun Application.configureServer() {
        // Request logging middleware - automatically logs all incoming requests except /ai/text
        intercept(ApplicationCallPipeline.Monitoring) {
            val startTime = System.currentTimeMillis()
            val uri = call.request.uri
            val method = call.request.httpMethod.value
            val clientIp = call.request.local.remoteHost
            val userAgent = call.request.headers["User-Agent"]
            
            // Skip automatic logging for /ai/text - it handles its own logging
            val skipAutoLogging = uri == "/ai/text" && method == "POST"
            
            try {
                proceed()
                
                if (!skipAutoLogging) {
                    // Log successful response for non-AI-text endpoints
                    val responseTime = System.currentTimeMillis() - startTime
                    val statusCode = call.response.status()?.value ?: 200
                    
                    RequestLogger.logRequest(
                        method = method,
                        path = uri,
                        clientIp = clientIp,
                        statusCode = statusCode,
                        responseTime = responseTime,
                        userAgent = userAgent,
                        responseData = null, // Don't duplicate response data for general middleware
                        responseType = "auto"
                    )
                }
                
            } catch (e: Exception) {
                if (!skipAutoLogging) {
                    // Log error response for non-AI-text endpoints
                    val responseTime = System.currentTimeMillis() - startTime
                    val statusCode = call.response.status()?.value ?: 500
                    
                    RequestLogger.logRequest(
                        method = method,
                        path = uri,
                        clientIp = clientIp,
                        statusCode = statusCode,
                        responseTime = responseTime,
                        userAgent = userAgent,
                        responseData = "Error: ${e.message}",
                        responseType = "error"
                    )
                }
                
                throw e
            }
        }
        
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }
        
        install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            anyHost()
        }
        
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Timber.e(cause, "Server error")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Internal server error"))
                )
            }
        }
        
        routing {
            // Health check endpoint
            get("/status") {
                val hasLocation = permissionManager.hasLocationPermissions(this@WebServer.context)
                val hasCamera = permissionManager.hasCameraPermissions(this@WebServer.context)
                val aiStatus = aiService.getStatus()
                
                call.respond(mapOf(
                    "status" to "running",
                    "timestamp" to System.currentTimeMillis(),
                    "server" to "K3s Phone Server",
                    "version" to "1.0.0-ai-enabled",
                    "features" to mapOf(
                        "location" to hasLocation,
                        "orientation" to true,
                        "camera" to hasCamera,
                        "ai" to aiStatus.isEnabled
                    ),
                    "permissions" to mapOf(
                        "location" to hasLocation,
                        "camera" to hasCamera
                    ),
                    "ai" to aiStatus
                ))
            }
            
            // Health check endpoint 
            get("/health") {
                call.respond(mapOf(
                    "status" to "healthy",
                    "services" to mapOf(
                        "location" to "available",
                        "orientation" to "available",
                        "camera" to "available"
                    )
                ))
            }
            
            // Capabilities endpoint for dynamic API discovery
            get("/capabilities") {
                val hasBasicLocation = permissionManager.hasBasicLocationPermissions(this@WebServer.context)
                val hasBackgroundLocation = permissionManager.hasLocationPermissions(this@WebServer.context)
                val hasCamera = permissionManager.hasCameraPermissions(this@WebServer.context)
                
                call.respond(mapOf(
                    "server" to "K3s Phone Server",
                    "version" to "1.0.0-simplified",
                    "capabilities" to mapOf(
                        "location" to mapOf(
                            "available" to hasBasicLocation,
                            "backgroundAccess" to hasBackgroundLocation,
                            "endpoints" to if (hasBasicLocation) listOf("/location") else emptyList<String>(),
                            "methods" to if (hasBasicLocation) listOf("GET") else emptyList<String>(),
                            "description" to if (hasBasicLocation) "GPS location data with latitude, longitude, altitude, accuracy" else "Location permission not granted",
                            "backgroundDescription" to if (hasBackgroundLocation) "Works in background" else "Requires app visible (Android 10+)"
                        ),
                        "orientation" to mapOf(
                            "available" to true,
                            "endpoints" to listOf("/orientation"),
                            "methods" to listOf("GET"),
                            "description" to "Device compass/orientation data with azimuth, pitch, roll"
                        ),
                        "camera" to mapOf(
                            "available" to hasCamera,
                            "endpoints" to if (hasCamera) listOf("/capture") else emptyList<String>(),
                            "methods" to if (hasCamera) listOf("GET") else emptyList<String>(),
                            "description" to if (hasCamera) "Camera capture with zoom and front/rear selection" else "Camera permission not granted",
                            "visibilityRequirement" to "App must be visible (Android privacy requirement)"
                        ),
                        "health" to mapOf(
                            "available" to true,
                            "endpoints" to listOf("/status", "/health"),
                            "methods" to listOf("GET"),
                            "description" to "Server status and health information"
                        ),
                        "ai" to mapOf(
                            "available" to aiService.getStatus().isEnabled,
                            "endpoints" to listOf("/ai/text", "/ai/models", "/ai/models/status"),
                            "methods" to listOf("GET", "POST"),
                            "description" to "LLM text generation with MediaPipe inference"
                        )
                    ),
                    "features" to mapOf(
                        "location" to hasBasicLocation,
                        "orientation" to true,
                        "camera" to hasCamera,
                        "ai" to aiService.getStatus().isEnabled
                    ),
                    "port" to 8005,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
            
            // Location endpoint (GPS coordinates) - permission-aware and fast
            get("/location") {
                try {
                    // Check basic location permissions (works for both foreground and background)
                    if (!permissionManager.hasBasicLocationPermissions(this@WebServer.context)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf(
                                "error" to "Location permission not granted",
                                "code" to "PERMISSION_DENIED",
                                "description" to "Location access requires user permission"
                            )
                        )
                        return@get
                    }

                    val location = locationService.getCurrentLocation()
                    if (location != null) {
                        val response = mapOf(
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "altitude" to location.altitude,
                            "accuracy" to location.accuracy,
                            "timestamp" to location.time,
                            "provider" to location.provider,
                            "backgroundAccess" to permissionManager.hasLocationPermissions(this@WebServer.context)
                        )
                        call.respond(response)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf(
                                "error" to "Location not available",
                                "code" to "LOCATION_UNAVAILABLE",
                                "description" to "GPS location is not currently available"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error getting location")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to get location: ${e.message}",
                            "code" to "INTERNAL_ERROR"
                        )
                    )
                }
            }
            
            // Orientation/Compass endpoint
            get("/orientation") {
                try {
                    val orientation = orientationService.getCurrentOrientation()
                    call.respond(mapOf(
                        "azimuth" to orientation.azimuth,
                        "pitch" to orientation.pitch,
                        "roll" to orientation.roll,
                        "timestamp" to orientation.timestamp,
                        "accuracy" to orientation.accuracy
                    ))
                } catch (e: Exception) {
                    Timber.e(e, "Error getting orientation")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get orientation: ${e.message}")
                    )
                }
            }
            
            // Camera capture endpoint
            get("/capture") {
                val startTime = System.currentTimeMillis()
                try {
                    if (!permissionManager.hasCameraPermissions(this@WebServer.context)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf(
                                "error" to "Camera permission not granted",
                                "code" to "PERMISSION_DENIED",
                                "description" to "Camera access requires user permission"
                            )
                        )
                        return@get
                    }

                    val sideParam = call.request.queryParameters["side"] ?: "rear"
                    val zoomParam = call.request.queryParameters["zoom"]?.toFloatOrNull()
                    val imageScalingParam = call.request.queryParameters["imageScaling"]?.let { scalingName ->
                        try {
                            com.k3s.phoneserver.ai.ImageScaling.valueOf(scalingName.uppercase())
                        } catch (e: IllegalArgumentException) {
                            com.k3s.phoneserver.ai.ImageScaling.DEFAULT
                        }
                    } ?: com.k3s.phoneserver.ai.ImageScaling.DEFAULT
                    
                    // Use shared camera service - camera operations must run on main thread
                    val cameraResult = withContext(Dispatchers.Main) {
                        val lifecycleOwner = SimpleLifecycleOwner()
                        try {
                            sharedCameraService.captureImage(
                                lifecycleOwner = lifecycleOwner,
                                side = sideParam,
                                zoom = zoomParam
                            )
                        } finally {
                            lifecycleOwner.destroy()
                        }
                    }
                    
                    if (!cameraResult.success || cameraResult.bitmap == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to (cameraResult.error ?: "Camera capture failed"),
                                "code" to "CAPTURE_FAILED",
                                "description" to "Failed to capture image from camera"
                            )
                        )
                        return@get
                    }

                    val bitmap = cameraResult.bitmap
                    
                    // Apply ImageScaling for output quality and size
                    val outputBitmap = scaleImageForOutput(bitmap, imageScalingParam)
                    val quality = when (imageScalingParam) {
                        com.k3s.phoneserver.ai.ImageScaling.SMALL -> 70
                        com.k3s.phoneserver.ai.ImageScaling.MEDIUM -> 85
                        com.k3s.phoneserver.ai.ImageScaling.LARGE -> 90
                        com.k3s.phoneserver.ai.ImageScaling.ULTRA -> 95
                        com.k3s.phoneserver.ai.ImageScaling.NONE -> 95
                    }
                    
                    // Convert bitmap to base64
                    val stream = ByteArrayOutputStream()
                    outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    val imageBytes = stream.toByteArray()
                    val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    
                    val responseJson = mapOf(
                        "success" to true,
                        "image" to "data:image/jpeg;base64,$base64Image",
                        "metadata" to mapOf(
                            "side" to cameraResult.imageMetadata.camera,
                            "zoom" to zoomParam,
                            "originalWidth" to cameraResult.imageMetadata.width,
                            "originalHeight" to cameraResult.imageMetadata.height,
                            "outputWidth" to outputBitmap.width,
                            "outputHeight" to outputBitmap.height,
                            "rotation" to cameraResult.imageMetadata.rotation,
                            "imageScaling" to imageScalingParam.name,
                            "quality" to quality,
                            "timestamp" to cameraResult.imageMetadata.timestamp,
                            "captureTime" to cameraResult.captureTime,
                            "cached" to false
                        )
                    )
                    
                    // Log the response for request log with full JSON for image extraction
                    RequestLogger.logRequest(
                        method = "GET",
                        path = call.request.uri,
                        clientIp = call.request.local.remoteHost,
                        statusCode = 200,
                        responseTime = System.currentTimeMillis() - startTime,
                        userAgent = call.request.headers["User-Agent"],
                        responseData = com.google.gson.Gson().toJson(responseJson),
                        responseType = "image"
                    )
                            
                    call.respond(responseJson)
                } catch (e: Exception) {
                    Timber.e(e, "Error capturing image")
                    
                    // Log the error response for request log
                    RequestLogger.logRequest(
                        method = "GET",
                        path = call.request.uri,
                        clientIp = call.request.local.remoteHost,
                        statusCode = 500,
                        responseTime = System.currentTimeMillis() - startTime,
                        userAgent = call.request.headers["User-Agent"],
                        responseData = "Camera capture error: ${e.message}",
                        responseType = "error"
                    )
                    
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Camera capture error: ${e.message}",
                            "code" to "INTERNAL_ERROR",
                            "exceptionType" to e.javaClass.simpleName,
                            "debug" to mapOf(
                                "hasPermissions" to permissionManager.hasCameraPermissions(this@WebServer.context),
                                "stackTrace" to e.stackTrace.take(3).map { it.toString() }
                            )
                        )
                    )
                }
            }
            
            // AI endpoints
            route("/ai") {
                
                // Text generation endpoint
                post("/text") {
                    val startTime = System.currentTimeMillis()
                    val clientIp = call.request.local.remoteHost
                    val userAgent = call.request.headers["User-Agent"]
                    
                    // Capture raw request body first
                    val requestBodyText = call.receiveText()
                    
                    // Log initial request immediately (without duration and response)
                    val requestId = RequestLogger.logRequest(
                        method = "POST",
                        path = "/ai/text",
                        clientIp = clientIp,
                        statusCode = 0, // Will be updated
                        responseTime = 0L, // Will be updated
                        userAgent = userAgent,
                        responseData = "Processing...", // Will be updated
                        responseType = "ai_text_pending",
                        requestBody = requestBodyText
                    )
                    
                    try {
                        // Check if app has storage permissions for model access
                        if (!permissionManager.hasStoragePermissions(this@WebServer.context)) {
                            val responseTime = System.currentTimeMillis() - startTime
                            val errorResponse = com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Storage permissions required for AI model access",
                                code = "STORAGE_PERMISSION_DENIED",
                                details = "The app needs storage permissions to access AI models. Please grant storage permissions in app settings and ensure models are available."
                            )
                            
                            // Update log with error result
                            RequestLogger.updateRequest(
                                requestId = requestId,
                                statusCode = 403,
                                responseTime = responseTime,
                                responseData = com.google.gson.Gson().toJson(errorResponse),
                                responseType = "ai_text_error"
                            )
                            
                            call.respond(HttpStatusCode.Forbidden, errorResponse)
                            return@post
                        }
                        
                        // Parse the request from the captured text
                        val request = com.google.gson.Gson().fromJson(requestBodyText, com.k3s.phoneserver.ai.AITextRequest::class.java)
                        val response = aiService.handleTextRequest(request)
                        val responseTime = System.currentTimeMillis() - startTime
                        
                        // Update log with successful result
                        RequestLogger.updateRequest(
                            requestId = requestId,
                            statusCode = 200,
                            responseTime = responseTime,
                            responseData = com.google.gson.Gson().toJson(response),
                            responseType = "ai_text_success"
                        )
                        
                        call.respond(response)
                    } catch (e: com.k3s.phoneserver.ai.ModelNotDownloadedException) {
                        Timber.w(e, "AI model not available")
                        val responseTime = System.currentTimeMillis() - startTime
                        val errorResponse = com.k3s.phoneserver.ai.AIErrorResponse(
                            error = "AI model not available",
                            code = "MODEL_NOT_AVAILABLE",
                            details = e.message
                        )
                        
                        // Update log with error result
                        RequestLogger.updateRequest(
                            requestId = requestId,
                            statusCode = 404,
                            responseTime = responseTime,
                            responseData = com.google.gson.Gson().toJson(errorResponse),
                            responseType = "ai_text_error"
                        )
                        
                        call.respond(HttpStatusCode.NotFound, errorResponse)
                    } catch (e: com.k3s.phoneserver.ai.AIServiceException) {
                        Timber.w(e, "AI service error")
                        val responseTime = System.currentTimeMillis() - startTime
                        val errorResponse = com.k3s.phoneserver.ai.AIErrorResponse(
                            error = "AI service error",
                            code = "AI_SERVICE_ERROR",
                            details = e.message
                        )
                        
                        // Update log with error result
                        RequestLogger.updateRequest(
                            requestId = requestId,
                            statusCode = 400,
                            responseTime = responseTime,
                            responseData = com.google.gson.Gson().toJson(errorResponse),
                            responseType = "ai_text_error"
                        )
                        
                        call.respond(HttpStatusCode.BadRequest, errorResponse)
                    } catch (e: Exception) {
                        Timber.e(e, "AI text generation error")
                        val responseTime = System.currentTimeMillis() - startTime
                        val errorResponse = com.k3s.phoneserver.ai.AIErrorResponse(
                            error = "AI text generation failed",
                            code = "AI_ERROR",
                            details = e.message
                        )
                        
                        // Update log with error result
                        RequestLogger.updateRequest(
                            requestId = requestId,
                            statusCode = 500,
                            responseTime = responseTime,
                            responseData = com.google.gson.Gson().toJson(errorResponse),
                            responseType = "ai_text_error"
                        )
                        
                        call.respond(HttpStatusCode.InternalServerError, errorResponse)
                    }
                }
                
                // Upload model endpoint (remote model installation)
                post("/text/upload-model-api") {
                    try {
                        // Expect JSON with model URL and metadata
                        val requestBody = call.receiveText()
                        val gson = com.google.gson.Gson()
                        val uploadRequest = gson.fromJson(requestBody, com.google.gson.JsonObject::class.java)
                        
                        val modelUrl = uploadRequest.get("url")?.asString
                        val modelName = uploadRequest.get("name")?.asString
                        val fileName = uploadRequest.get("fileName")?.asString
                        
                        if (modelUrl == null || modelName == null || fileName == null) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf(
                                    "error" to "Missing required fields",
                                    "code" to "INVALID_REQUEST",
                                    "required" to listOf("url", "name", "fileName")
                                )
                            )
                            return@post
                        }
                        
                        // Trigger model download
                        val response = aiService.downloadModel(modelUrl, fileName, modelName)
                        call.respond(response)
                        
                    } catch (e: Exception) {
                        Timber.e(e, "Model upload API error")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf(
                                "error" to "Model upload failed",
                                "code" to "UPLOAD_ERROR",
                                "details" to e.message
                            )
                        )
                    }
                }
                
                // Get supported models
                get("/models") {
                    try {
                        val response = aiService.getSupportedModels()
                        call.respond(response)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get AI models")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Failed to get models",
                                code = "AI_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Download model (hidden from API tester, for non-auth models only)
                post("/models/download") {
                    try {
                        // Check storage permissions first
                        if (!permissionManager.hasEnhancedStoragePermissions(this@WebServer.context)) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                com.k3s.phoneserver.ai.AIErrorResponse(
                                    error = "Storage permissions required",
                                    code = "STORAGE_PERMISSION_DENIED",
                                    details = "The app needs storage permissions to download and access AI models. Please grant storage permissions in app settings."
                                )
                            )
                            return@post
                        }
                        
                        val request = call.receive<ModelDownloadRequest>()
                        val model = AIModel.fromString(request.modelName)
                        
                        if (model == null) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                com.k3s.phoneserver.ai.AIErrorResponse(
                                    error = "Unknown model",
                                    code = "INVALID_MODEL",
                                    details = "Model '${request.modelName}' not found"
                                )
                            )
                            return@post
                        }
                        
                        if (model.needsAuth) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                com.k3s.phoneserver.ai.AIErrorResponse(
                                    error = "Model requires authentication",
                                    code = "AUTH_REQUIRED",
                                    details = "Model '${model.modelName}' requires manual download from ${model.url}"
                                )
                            )
                            return@post
                        }
                        
                        // Check if model is already downloaded
                        if (ModelDetector.isModelAvailable(this@WebServer.context, model)) {
                            call.respond(
                                ModelDownloadResponse(
                                    success = true,
                                    message = "Model already downloaded",
                                    modelName = model.modelName,
                                    status = "already_available"
                                )
                            )
                            return@post
                        }
                        
                        // Start download process
                        val downloadResult = aiService.downloadModel(model)
                        
                        call.respond(
                            ModelDownloadResponse(
                                success = downloadResult.success,
                                message = downloadResult.message,
                                modelName = model.modelName,
                                status = if (downloadResult.success) "download_started" else "download_failed",
                                downloadUrl = if (!downloadResult.success) model.url else null
                            )
                        )
                        
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to download model")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Failed to download model",
                                code = "DOWNLOAD_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Get model persistence status and statistics
                get("/models/status") {
                    try {
                        val statusSummary = aiService.getModelStatusSummary()
                        call.respond(statusSummary)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get model status")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Failed to get model status",
                                code = "STATUS_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Get persistence info for a specific model
                get("/models/{modelName}/status") {
                    try {
                        val modelName = call.parameters["modelName"] ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Model name required",
                                code = "MISSING_PARAMETER",
                                details = "Model name parameter is required"
                            )
                        )
                        
                        val model = AIModel.fromString(modelName)
                        if (model == null) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                com.k3s.phoneserver.ai.AIErrorResponse(
                                    error = "Model not found",
                                    code = "MODEL_NOT_FOUND",
                                    details = "Model '$modelName' is not supported"
                                )
                            )
                            return@get
                        }
                        
                        val persistenceInfo = aiService.getModelPersistenceInfo(model)
                        if (persistenceInfo == null) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                com.k3s.phoneserver.ai.AIErrorResponse(
                                    error = "Model not downloaded",
                                    code = "MODEL_NOT_DOWNLOADED",
                                    details = "Model '$modelName' has not been downloaded"
                                )
                            )
                            return@get
                        }
                        
                        call.respond(persistenceInfo)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get model persistence info")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Failed to get model info",
                                code = "INFO_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Diagnostic endpoint for permission and storage issues
                get("/models/diagnostics") {
                    try {
                        val diagnostics = ModelDetector.generatePermissionDiagnostics(this@WebServer.context)
                        
                        call.respond(mapOf(
                            "timestamp" to System.currentTimeMillis(),
                            "diagnostics" to diagnostics,
                            "summary" to mapOf(
                                "hasStoragePermissions" to permissionManager.hasStoragePermissions(this@WebServer.context),
                                "hasEnhancedStoragePermissions" to permissionManager.hasEnhancedStoragePermissions(this@WebServer.context),
                                "availableModels" to ModelDetector.getAvailableModels(this@WebServer.context).size,
                                "totalModels" to AIModel.getAllModels().size,
                                "androidVersion" to android.os.Build.VERSION.SDK_INT
                            )
                        ))
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to generate diagnostics")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Failed to generate diagnostics",
                                code = "DIAGNOSTICS_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Clean up deleted models from persistence
                post("/models/cleanup") {
                    try {
                        val cleanedCount = aiService.cleanupDeletedModels()
                        call.respond(mapOf(
                            "success" to true,
                            "message" to "Cleanup completed",
                            "cleanedModels" to cleanedCount
                        ))
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to cleanup models")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Cleanup failed",
                                code = "CLEANUP_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Remove a model completely (delete file and persistence)
                delete("/models/{modelName}") {
                    try {
                        val modelName = call.parameters["modelName"] ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Model name required",
                                code = "MISSING_PARAMETER",
                                details = "Model name parameter is required"
                            )
                        )
                        
                        val model = AIModel.fromString(modelName)
                        if (model == null) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                com.k3s.phoneserver.ai.AIErrorResponse(
                                    error = "Model not found",
                                    code = "MODEL_NOT_FOUND",
                                    details = "Model '$modelName' is not supported"
                                )
                            )
                            return@delete
                        }
                        
                        val removed = aiService.removeModel(model)
                        if (removed) {
                            call.respond(mapOf(
                                "success" to true,
                                "message" to "Model removed successfully",
                                "modelName" to modelName
                            ))
                        } else {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                com.k3s.phoneserver.ai.AIErrorResponse(
                                    error = "Failed to remove model",
                                    code = "REMOVAL_ERROR",
                                    details = "Model removal failed"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to remove model")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Model removal failed",
                                code = "REMOVAL_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Get model loading status
                get("/models/loading-status") {
                    try {
                        val loadingInfo = aiService.getModelLoadingInfo()
                        if (loadingInfo != null) {
                            call.respond(loadingInfo)
                        } else {
                            call.respond(mapOf(
                                "isLoading" to false,
                                "message" to "No model is currently being loaded"
                            ))
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get model loading status")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.AIErrorResponse(
                                error = "Failed to get loading status",
                                code = "STATUS_ERROR",
                                details = e.message
                            )
                        )
                    }
                }
                
                // Object detection endpoint
                post("/object_detection") {
                    val startTime = System.currentTimeMillis()
                    try {
                        if (!permissionManager.hasCameraPermissions(this@WebServer.context)) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                com.k3s.phoneserver.ai.ObjectDetectionResponse(
                                    success = false,
                                    objects = emptyList(),
                                    inferenceTime = 0,
                                    captureTime = 0,
                                    threshold = 0.5f,
                                    imageMetadata = com.k3s.phoneserver.ai.ImageMetadata(
                                        width = 0,
                                        height = 0,
                                        camera = "rear",
                                        timestamp = System.currentTimeMillis()
                                    ),
                                    error = "Camera permission not granted"
                                )
                            )
                            return@post
                        }

                        // Parse request parameters from JSON body or use defaults
                        val requestBody = try {
                            call.receiveNullable<com.k3s.phoneserver.ai.ObjectDetectionRequest>()
                        } catch (e: Exception) {
                            null
                        } ?: com.k3s.phoneserver.ai.ObjectDetectionRequest()

                        // Camera operations must be performed on the main thread with lifecycle
                        // Create SimpleLifecycleOwner on main thread (required by Android)
                        val lifecycleOwner = withContext(Dispatchers.Main) {
                            SimpleLifecycleOwner()
                        }
                        try {
                            Timber.d("Starting object detection - side: ${requestBody.side}, model: efficientdet-lite2")
                            
                            val result = objectDetectionService.detectObjects(
                                lifecycleOwner = lifecycleOwner,
                                side = requestBody.side,
                                zoom = requestBody.zoom,
                                threshold = requestBody.threshold,
                                maxResults = requestBody.maxResults,
                                returnImage = requestBody.returnImage,
                                imageScaling = requestBody.imageScaling
                            )
                            
                            Timber.d("Object detection completed: ${result.objects.size} objects detected")
                            
                            // Log the response for request log
                            RequestLogger.logRequest(
                                method = "POST",
                                path = call.request.uri,
                                clientIp = call.request.local.remoteHost,
                                statusCode = 200,
                                responseTime = System.currentTimeMillis() - startTime,
                                userAgent = call.request.headers["User-Agent"],
                                responseData = "Object detection: ${result.objects.size} objects, model: efficientdet-lite2",
                                responseType = "object_detection"
                            )
                            
                            call.respond(result)
                            
                        } finally {
                            withContext(Dispatchers.Main) {
                                lifecycleOwner.destroy()
                            }
                        }
                        
                    } catch (e: Exception) {
                        Timber.e(e, "Object detection error")
                        
                        // Log the error response for request log
                        RequestLogger.logRequest(
                            method = "POST",
                            path = call.request.uri,
                            clientIp = call.request.local.remoteHost,
                            statusCode = 500,
                            responseTime = System.currentTimeMillis() - startTime,
                            userAgent = call.request.headers["User-Agent"],
                            responseData = "Object detection error: ${e.message}",
                            responseType = "error"
                        )
                        
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            com.k3s.phoneserver.ai.ObjectDetectionResponse(
                                success = false,
                                objects = emptyList(),
                                inferenceTime = 0,
                                captureTime = System.currentTimeMillis() - startTime,
                                threshold = 0.5f,
                                imageMetadata = com.k3s.phoneserver.ai.ImageMetadata(
                                    width = 0,
                                    height = 0,
                                    camera = "rear",
                                    timestamp = System.currentTimeMillis()
                                ),
                                error = "Object detection failed: ${e.message}"
                            )
                        )
                    }
                }
            }
            
            // API Documentation endpoint
            // COPILOT INSTRUCTIONS: When adding new endpoints, always update this /help endpoint with:
            // 1. Endpoint path and HTTP method
            // 2. Description of functionality  
            // 3. Request format (if applicable)
            // 4. Response format with all possible status codes
            // 5. Sample request/response examples
            // 6. Any required permissions or authentication
            get("/help") {
                call.respond(mapOf(
                    "server" to "K3s Phone Server API Documentation",
                    "version" to "1.0.0-ai-enabled",
                    "base_url" to "http://localhost:8005",
                    "endpoints" to mapOf(
                        
                        // Health & Status Endpoints
                        "GET /status" to mapOf(
                            "description" to "Server status with features and permissions",
                            "permissions" to "None required",
                            "response" to mapOf(
                                "status" to "running",
                                "timestamp" to 1692720000000,
                                "server" to "K3s Phone Server",
                                "version" to "1.0.0-ai-enabled",
                                "features" to mapOf(
                                    "location" to true,
                                    "orientation" to true,
                                    "camera" to true,
                                    "ai" to true
                                ),
                                "permissions" to mapOf(
                                    "location" to true,
                                    "camera" to true
                                ),
                                "ai" to mapOf(
                                    "isEnabled" to true,
                                    "currentModel" to "gemma-2b-it-cpu",
                                    "availableModels" to 3
                                )
                            ),
                            "sample_request" to "GET /status"
                        ),
                        
                        "GET /health" to mapOf(
                            "description" to "Simple health check",
                            "permissions" to "None required",
                            "response" to mapOf(
                                "status" to "healthy",
                                "services" to mapOf(
                                    "location" to "available",
                                    "orientation" to "available",
                                    "camera" to "available"
                                )
                            ),
                            "sample_request" to "GET /health"
                        ),
                        
                        "GET /capabilities" to mapOf(
                            "description" to "Dynamic API discovery with detailed capability information",
                            "permissions" to "None required",
                            "response" to mapOf(
                                "server" to "K3s Phone Server",
                                "capabilities" to mapOf(
                                    "location" to mapOf(
                                        "available" to true,
                                        "endpoints" to listOf("/location"),
                                        "methods" to listOf("GET"),
                                        "description" to "GPS location data with latitude, longitude, altitude, accuracy"
                                    )
                                )
                            ),
                            "sample_request" to "GET /capabilities"
                        ),
                        
                        // Location Services
                        "GET /location" to mapOf(
                            "description" to "Get current GPS location coordinates",
                            "permissions" to "Location permission required",
                            "response_success" to mapOf(
                                "latitude" to 37.7749,
                                "longitude" to -122.4194,
                                "altitude" to 10.0,
                                "accuracy" to 5.0,
                                "timestamp" to 1692720000000,
                                "provider" to "gps"
                            ),
                            "response_forbidden" to mapOf(
                                "error" to "Location permission not granted",
                                "code" to "PERMISSION_DENIED",
                                "description" to "Location access requires user permission"
                            ),
                            "response_unavailable" to mapOf(
                                "error" to "Location not available",
                                "code" to "LOCATION_UNAVAILABLE",
                                "description" to "GPS location is not currently available"
                            ),
                            "sample_request" to "GET /location"
                        ),
                        
                        // Orientation Services
                        "GET /orientation" to mapOf(
                            "description" to "Get current device orientation/compass data",
                            "permissions" to "None required",
                            "response" to mapOf(
                                "azimuth" to 45.0,
                                "pitch" to 10.0,
                                "roll" to -5.0,
                                "timestamp" to 1692720000000,
                                "accuracy" to 3
                            ),
                            "sample_request" to "GET /orientation"
                        ),
                        
                        // Camera Services
                        "GET /capture" to mapOf(
                            "description" to "Capture image from device camera with configurable quality",
                            "permissions" to "Camera permission required",
                            "parameters" to mapOf(
                                "side" to "Optional: 'front' or 'rear' (default: 'rear')",
                                "zoom" to "Optional: float zoom level",
                                "imageScaling" to "Optional: 'SMALL', 'MEDIUM', 'LARGE', 'ULTRA', 'NONE' (default: 'MEDIUM')"
                            ),
                            "response_success" to mapOf(
                                "success" to true,
                                "image" to "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ...",
                                "metadata" to mapOf(
                                    "side" to "rear",
                                    "zoom" to 1.0,
                                    "originalWidth" to 4032,
                                    "originalHeight" to 3024,
                                    "outputWidth" to 1024,
                                    "outputHeight" to 768,
                                    "rotation" to 90,
                                    "imageScaling" to "MEDIUM",
                                    "quality" to 85,
                                    "timestamp" to 1692720000000,
                                    "cached" to false
                                )
                            ),
                            "response_forbidden" to mapOf(
                                "error" to "Camera permission not granted",
                                "code" to "PERMISSION_DENIED",
                                "description" to "Camera access requires user permission"
                            ),
                            "sample_request" to "GET /capture?side=rear&zoom=2.0&imageScaling=LARGE"
                        ),
                        
                        // AI Services
                        "POST /ai/text" to mapOf(
                            "description" to "Generate text using AI language model",
                            "permissions" to "None required",
                            "request_body" to mapOf(
                                "prompt" to "What is the capital of France?",
                                "model" to "gemma-2b-it-cpu",
                                "maxTokens" to 100,
                                "temperature" to 0.7
                            ),
                            "response_success" to mapOf(
                                "success" to true,
                                "text" to "The capital of France is Paris. It is the largest city in France and serves as the political, economic, and cultural center of the country.",
                                "model" to "gemma-2b-it-cpu",
                                "tokensUsed" to 45,
                                "timestamp" to 1692720000000
                            ),
                            "response_error" to mapOf(
                                "error" to "AI text generation failed",
                                "code" to "AI_ERROR",
                                "details" to "Model not available"
                            ),
                            "sample_request" to "POST /ai/text\n{\n  \"prompt\": \"Explain quantum computing\",\n  \"model\": \"gemma-2b-it-cpu\",\n  \"maxTokens\": 150\n}"
                        ),
                        
                        "POST /ai/object_detection" to mapOf(
                            "description" to "Detect objects in camera image using MediaPipe EfficientDet Lite 2",
                            "permissions" to "Camera permission required",
                            "request_body" to mapOf(
                                "side" to "rear",
                                "zoom" to 1.0,
                                "threshold" to 0.5,
                                "maxResults" to 10,
                                "returnImage" to false,
                                "imageScaling" to "MEDIUM"
                            ),
                            "imageScaling_options" to mapOf(
                                "SMALL" to "512x512 max, 70% quality - fastest",
                                "MEDIUM" to "1024x1024 max, 85% quality - balanced (default)",
                                "LARGE" to "2048x2048 max, 90% quality - high quality",
                                "ULTRA" to "4096x4096 max, 95% quality - maximum quality",
                                "NONE" to "Original size, 95% quality - no scaling"
                            ),
                            "response_success" to mapOf(
                                "success" to true,
                                "objects" to listOf(
                                    mapOf(
                                        "category" to "person",
                                        "score" to 0.9,
                                        "boundingBox" to mapOf(
                                            "left" to 0.1,
                                            "top" to 0.2,
                                            "right" to 0.8,
                                            "bottom" to 0.9,
                                            "width" to 0.7,
                                            "height" to 0.7
                                        ),
                                        "categoryIndex" to 0
                                    ),
                                    mapOf(
                                        "category" to "bicycle",
                                        "score" to 0.7,
                                        "boundingBox" to mapOf(
                                            "left" to 0.3,
                                            "top" to 0.4,
                                            "right" to 0.9,
                                            "bottom" to 0.8,
                                            "width" to 0.6,
                                            "height" to 0.4
                                        ),
                                        "categoryIndex" to 1
                                    )
                                ),
                                "inferenceTime" to 150,
                                "captureTime" to 800,
                                "threshold" to 0.5,
                                "imageMetadata" to mapOf(
                                    "width" to 1920,
                                    "height" to 1080,
                                    "camera" to "rear",
                                    "timestamp" to 1692720000000
                                ),
                                "image" to null
                            ),
                            "response_forbidden" to mapOf(
                                "success" to false,
                                "objects" to emptyList<Any>(),
                                "error" to "Camera permission not granted"
                            ),
                            "response_with_image" to mapOf(
                                "note" to "When returnImage=true, includes base64 encoded image",
                                "image" to "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ..."
                            ),
                            "sample_request" to "POST /ai/object_detection\n{\n  \"side\": \"rear\",\n  \"threshold\": 0.6,\n  \"maxResults\": 5,\n  \"returnImage\": true,\n  \"imageScaling\": \"LARGE\"\n}"
                        ),
                        
                        "GET /ai/models" to mapOf(
                            "description" to "Get list of supported AI models",
                            "permissions" to "None required",
                            "response" to mapOf(
                                "models" to listOf(
                                    mapOf(
                                        "modelName" to "gemma-2b-it-cpu",
                                        "displayName" to "Gemma 2B IT CPU",
                                        "fileName" to "gemma-2b-it-cpu-int4.bin",
                                        "url" to "https://huggingface.co/google/gemma-2b-it-onnx-cpu",
                                        "needsAuth" to false
                                    )
                                )
                            ),
                            "sample_request" to "GET /ai/models"
                        ),
                        
                        "POST /ai/models/download" to mapOf(
                            "description" to "Auto-download AI model (non-auth models only)",
                            "permissions" to "None required",
                            "note" to "Hidden from API tester - for programmatic use only",
                            "request_body" to mapOf(
                                "modelName" to "gemma-2b-it-cpu"
                            ),
                            "response_success" to mapOf(
                                "success" to true,
                                "message" to "Download started successfully",
                                "modelName" to "gemma-2b-it-cpu",
                                "status" to "download_started"
                            ),
                            "response_already_available" to mapOf(
                                "success" to true,
                                "message" to "Model already downloaded",
                                "modelName" to "gemma-2b-it-cpu",
                                "status" to "already_available"
                            ),
                            "response_auth_required" to mapOf(
                                "success" to false,
                                "message" to "Model requires manual download",
                                "modelName" to "llama-2-7b-chat",
                                "status" to "auth_required",
                                "downloadUrl" to "https://huggingface.co/microsoft/Llama-2-7b-chat-hf-onnx-cpu"
                            ),
                            "sample_request" to "POST /ai/models/download\n{\n  \"modelName\": \"gemma-2b-it-cpu\"\n}"
                        ),
                        
                        "POST /ai/text/upload-model-api" to mapOf(
                            "description" to "Upload/install remote AI model",
                            "permissions" to "None required",
                            "request_body" to mapOf(
                                "url" to "https://example.com/model.bin",
                                "name" to "Custom Model",
                                "fileName" to "custom-model.bin"
                            ),
                            "response_success" to mapOf(
                                "success" to true,
                                "message" to "Download started",
                                "downloadProgress" to 0
                            ),
                            "sample_request" to "POST /ai/text/upload-model-api\n{\n  \"url\": \"https://huggingface.co/model.bin\",\n  \"name\": \"My Model\",\n  \"fileName\": \"my-model.bin\"\n}"
                        ),
                        
                        // Model Persistence & Management
                        "GET /ai/models/status" to mapOf(
                            "description" to "Get comprehensive model persistence status and statistics",
                            "permissions" to "None required",
                            "response" to mapOf(
                                "totalModels" to 3,
                                "downloadedModels" to 2,
                                "loadedModels" to 1,
                                "totalDownloadSize" to 4294967296,
                                "formattedTotalSize" to "4.0 GB",
                                "lastLoadedModel" to "gemma-2b-it-cpu",
                                "modelsDirectory" to "/storage/emulated/0/Download/k3s_ai_models",
                                "availableModels" to listOf(
                                    mapOf(
                                        "downloadPath" to "/storage/emulated/0/Download/k3s_ai_models/gemma-2b-it-cpu.bin",
                                        "fileSize" to 2147483648,
                                        "formattedSize" to "2.0 GB",
                                        "downloadTimestamp" to 1692720000000,
                                        "lastAccessedTimestamp" to 1692721000000,
                                        "isLoaded" to true,
                                        "downloadStatus" to "COMPLETED",
                                        "ageInDays" to 5
                                    )
                                ),
                                "statistics" to mapOf(
                                    "totalModels" to 3,
                                    "downloadedModels" to 2,
                                    "loadedModels" to 1,
                                    "formattedTotalSize" to "4.0 GB"
                                )
                            ),
                            "sample_request" to "GET /ai/models/status"
                        ),
                        
                        "GET /ai/models/{modelName}/status" to mapOf(
                            "description" to "Get persistence information for a specific model",
                            "permissions" to "None required",
                            "parameters" to mapOf(
                                "modelName" to "Model identifier (e.g., 'gemma-2b-it-cpu')"
                            ),
                            "response_success" to mapOf(
                                "downloadPath" to "/storage/emulated/0/Download/k3s_ai_models/gemma-2b-it-cpu.bin",
                                "fileSize" to 2147483648,
                                "formattedSize" to "2.0 GB",
                                "downloadTimestamp" to 1692720000000,
                                "lastAccessedTimestamp" to 1692721000000,
                                "isLoaded" to true,
                                "downloadStatus" to "COMPLETED",
                                "ageInDays" to 5
                            ),
                            "response_not_found" to mapOf(
                                "error" to "Model not downloaded",
                                "code" to "MODEL_NOT_DOWNLOADED",
                                "details" to "Model has not been downloaded"
                            ),
                            "sample_request" to "GET /ai/models/gemma-2b-it-cpu/status"
                        ),
                        
                        "POST /ai/models/cleanup" to mapOf(
                            "description" to "Clean up models that are no longer available on disk",
                            "permissions" to "None required",
                            "response" to mapOf(
                                "success" to true,
                                "message" to "Cleanup completed",
                                "cleanedModels" to 2
                            ),
                            "sample_request" to "POST /ai/models/cleanup"
                        ),
                        
                        "DELETE /ai/models/{modelName}" to mapOf(
                            "description" to "Remove a model completely (delete file and persistence data)",
                            "permissions" to "None required",
                            "parameters" to mapOf(
                                "modelName" to "Model identifier to remove"
                            ),
                            "response_success" to mapOf(
                                "success" to true,
                                "message" to "Model removed successfully",
                                "modelName" to "gemma-2b-it-cpu"
                            ),
                            "response_not_found" to mapOf(
                                "error" to "Model not found",
                                "code" to "MODEL_NOT_FOUND",
                                "details" to "Model is not supported"
                            ),
                            "sample_request" to "DELETE /ai/models/gemma-2b-it-cpu"
                        ),
                        
                        "GET /ai/models/loading-status" to mapOf(
                            "description" to "Get current model loading status and progress",
                            "permissions" to "None required",
                            "response_loading" to mapOf(
                                "isLoading" to true,
                                "elapsedTime" to 15000,
                                "timeoutTime" to 60000,
                                "progressPercentage" to 25.0,
                                "modelName" to "gemma-2b-it-cpu"
                            ),
                            "response_not_loading" to mapOf(
                                "isLoading" to false,
                                "message" to "No model is currently being loaded"
                            ),
                            "sample_request" to "GET /ai/models/loading-status"
                        )
                    ),
                    
                    "common_errors" to mapOf(
                        "403 Forbidden" to "Permission not granted for required feature (location, camera)",
                        "400 Bad Request" to "Invalid request parameters or missing required fields",
                        "500 Internal Server Error" to "Server error - check logs for details",
                        "503 Service Unavailable" to "Service temporarily unavailable (e.g., GPS not ready)"
                    ),
                    
                    "authentication" to "No authentication required - permission-based access control",
                    
                    "content_types" to mapOf(
                        "request" to "application/json",
                        "response" to "application/json"
                    ),
                    
                    "cors" to "Enabled for all origins with GET, POST, OPTIONS methods",
                    
                    "notes" to listOf(
                        "All endpoints return JSON responses",
                        "Base64 encoded images are returned for camera capture",
                        "AI model downloads are asynchronous - use status endpoints to check progress",
                        "Location and camera require Android permissions granted by user"
                    )
                ))
            }
            
            // Simple root endpoint
            get("/") {
                call.respond(mapOf(
                    "server" to "K3s Phone Server",
                    "version" to "1.0.0-ai-enabled",
                    "message" to "Interactive API testing available in the Android app",
                    "documentation" to "Visit /help for complete API documentation",
                    "available_endpoints" to listOf(
                        "/status", "/health", "/capabilities", "/help",
                        "/location", "/orientation", 
                        "/capture",
                        "/ai/text", "/ai/object_detection", "/ai/models", "/ai/models/download", 
                        "/ai/models/status", "/ai/models/{modelName}/status",
                        "/ai/models/cleanup", "/ai/models/{modelName}"
                    )
                ))
            }
        }
    }
    
    /**
     * Scale image for output based on ImageScaling settings
     * @param bitmap Original bitmap
     * @param imageScaling The scaling settings to apply
     * @return Scaled bitmap for output
     */
    private fun scaleImageForOutput(bitmap: Bitmap, imageScaling: com.k3s.phoneserver.ai.ImageScaling): Bitmap {
        if (imageScaling == com.k3s.phoneserver.ai.ImageScaling.NONE) {
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
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
