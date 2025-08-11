package com.k3s.phoneserver.server

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.k3s.phoneserver.lifecycle.SimpleLifecycleOwner
import com.k3s.phoneserver.logging.RequestLogger
import com.k3s.phoneserver.manager.AppPermissionManager
import com.k3s.phoneserver.services.CameraService
import com.k3s.phoneserver.services.LensFacingSelection
import com.k3s.phoneserver.services.LocationService
import com.k3s.phoneserver.services.OrientationService
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
    private val permissionManager = AppPermissionManager.getInstance()

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
        // Request logging middleware
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
                
                call.respond(mapOf(
                    "status" to "running",
                    "timestamp" to System.currentTimeMillis(),
                    "server" to "K3s Phone Server",
                    "version" to "1.0.0-simplified",
                    "features" to mapOf(
                        "location" to hasLocation,
                        "orientation" to true,
                        "camera" to hasCamera,
                        "ai" to false
                    ),
                    "permissions" to mapOf(
                        "location" to hasLocation,
                        "camera" to hasCamera
                    )
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
                val hasLocation = permissionManager.hasLocationPermissions(this@WebServer.context)
                val hasCamera = permissionManager.hasCameraPermissions(this@WebServer.context)
                
                call.respond(mapOf(
                    "server" to "K3s Phone Server",
                    "version" to "1.0.0-simplified",
                    "capabilities" to mapOf(
                        "location" to mapOf(
                            "available" to hasLocation,
                            "endpoints" to if (hasLocation) listOf("/location") else emptyList<String>(),
                            "methods" to if (hasLocation) listOf("GET") else emptyList<String>(),
                            "description" to if (hasLocation) "GPS location data with latitude, longitude, altitude, accuracy" else "Location permission not granted"
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
                            "description" to if (hasCamera) "Camera capture with zoom and front/rear selection" else "Camera permission not granted"
                        ),
                        "health" to mapOf(
                            "available" to true,
                            "endpoints" to listOf("/status", "/health"),
                            "methods" to listOf("GET"),
                            "description" to "Server status and health information"
                        ),
                        "ai" to mapOf(
                            "available" to false,
                            "endpoints" to emptyList<String>(),
                            "methods" to emptyList<String>(),
                            "description" to "AI capabilities removed for simplified version"
                        )
                    ),
                    "features" to mapOf(
                        "location" to hasLocation,
                        "orientation" to true,
                        "camera" to hasCamera,
                        "ai" to false
                    ),
                    "port" to 8005,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
            
            // Location endpoint (GPS coordinates) - permission-aware and fast
            get("/location") {
                try {
                    if (!permissionManager.hasLocationPermissions(this@WebServer.context)) {
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
                        call.respond(mapOf(
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "altitude" to location.altitude,
                            "accuracy" to location.accuracy,
                            "timestamp" to location.time,
                            "provider" to location.provider
                        ))
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
                    
                    val lensFacing = when (sideParam.lowercase()) {
                        "front" -> LensFacingSelection.FRONT
                        "rear" -> LensFacingSelection.REAR
                        else -> {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf(
                                    "error" to "Invalid side parameter",
                                    "code" to "INVALID_PARAMETER",
                                    "description" to "side parameter must be 'front' or 'rear'"
                                )
                            )
                            return@get
                        }
                    }

                    // Camera operations and lifecycle owner creation must be performed on the main thread
                    val bitmap = withContext(Dispatchers.Main) {
                        val lifecycleOwner = SimpleLifecycleOwner()
                        try {
                            Timber.d("Starting camera capture - side: $sideParam, zoom: $zoomParam")
                            val result = cameraService.capture(lifecycleOwner, lensFacing, zoomParam)
                            Timber.d("Camera capture completed successfully: ${result != null}")
                            result
                        } catch (e: Exception) {
                            Timber.e(e, "Camera capture failed in main context")
                            throw e
                        } finally {
                            lifecycleOwner.destroy()
                        }
                    }
                    
                    if (bitmap != null) {
                            // Convert bitmap to base64
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            val imageBytes = stream.toByteArray()
                            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                            
                            val responseJson = mapOf(
                                "success" to true,
                                "image" to "data:image/jpeg;base64,$base64Image",
                                "metadata" to mapOf(
                                    "side" to sideParam,
                                    "zoom" to zoomParam,
                                    "width" to bitmap.width,
                                    "height" to bitmap.height,
                                    "timestamp" to System.currentTimeMillis(),
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
                        } else {
                            // Log more detailed error information
                            Timber.w("Camera capture returned null bitmap - side: $sideParam, zoom: $zoomParam, hasPermissions: ${permissionManager.hasCameraPermissions(this@WebServer.context)}")
                            
                            // Log the response for request log
                            RequestLogger.logRequest(
                                method = "GET",
                                path = call.request.uri,
                                clientIp = call.request.local.remoteHost,
                                statusCode = 500,
                                responseTime = System.currentTimeMillis() - startTime,
                                userAgent = call.request.headers["User-Agent"],
                                responseData = "Camera capture failed - null bitmap",
                                responseType = "error"
                            )
                            
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf(
                                    "error" to "Camera capture failed",
                                    "code" to "CAPTURE_FAILED",
                                    "description" to "Unable to capture image from camera - returned null bitmap",
                                    "debug" to mapOf(
                                        "side" to sideParam,
                                        "zoom" to zoomParam,
                                        "hasPermissions" to permissionManager.hasCameraPermissions(this@WebServer.context)
                                    )
                                )
                            )
                        }
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
            
            // Simple root endpoint
            get("/") {
                call.respond(mapOf(
                    "server" to "K3s Phone Server",
                    "version" to "1.0.0-simplified",
                    "message" to "Interactive API testing available in the Android app",
                    "available_endpoints" to listOf(
                        "/status", "/health", "/capabilities", 
                        "/location", "/orientation", 
                        "/capture"
                    )
                ))
            }
        }
    }
}
