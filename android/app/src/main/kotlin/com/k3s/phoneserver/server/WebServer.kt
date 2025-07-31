package com.k3s.phoneserver.server

import android.content.Context
import com.k3s.phoneserver.logging.RequestLogger
import com.k3s.phoneserver.services.LocationService
import com.k3s.phoneserver.services.OrientationService
import com.k3s.phoneserver.services.AIService
import com.k3s.phoneserver.services.CameraSelection
import io.ktor.http.*
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
import java.util.*

class WebServer(private val context: Context) {

    private var server: NettyApplicationEngine? = null
    private val locationService = LocationService(context)
    private val orientationService = OrientationService(context)
    private val aiService = AIService(context)

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
            aiService.cleanup()
            Timber.d("Web server stopped")
        }
    }

    private fun Application.configureServer() {
        // Request logging middleware
        install(createApplicationPlugin(name = "RequestLogging") {
            onCall { call ->
                val startTime = System.currentTimeMillis()
                
                call.response.pipeline.intercept(ApplicationSendPipeline.Before) {
                    val endTime = System.currentTimeMillis()
                    val responseTime = endTime - startTime
                    
                    RequestLogger.logRequest(
                        method = call.request.httpMethod.value,
                        path = call.request.uri,
                        clientIp = call.request.local.remoteHost,
                        statusCode = call.response.status()?.value ?: 0,
                        responseTime = responseTime,
                        userAgent = call.request.headers["User-Agent"]
                    )
                }
            }
        })
        
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
                call.respond(mapOf(
                    "status" to "ok",
                    "timestamp" to System.currentTimeMillis(),
                    "server" to "K3s Phone Server",
                    "version" to "1.0.0"
                ))
            }
            
            // AI availability check endpoint
            get("/has-ai") {
                try {
                    val capabilities = aiService.getAICapabilities()
                    call.respond(capabilities)
                } catch (e: Exception) {
                    Timber.e(e, "Error checking AI availability")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "available" to false,
                            "error" to "Failed to check AI availability: ${e.message}"
                        )
                    )
                }
            }
            
            // Location endpoint
            get("/location") {
                try {
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
                            mapOf("error" to "Location not available")
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error getting location")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get location: ${e.message}")
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
            
            // AI analysis with provided image
            post("/ai/analyze") {
                try {
                    val request = call.receive<AIAnalysisRequest>()
                    val result = aiService.analyzeImage(request.task, request.imageBase64)
                    call.respond(mapOf(
                        "task" to request.task,
                        "result" to result,
                        "timestamp" to System.currentTimeMillis()
                    ))
                } catch (e: Exception) {
                    Timber.e(e, "Error in AI analysis")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "AI analysis failed: ${e.message}")
                    )
                }
            }
            
            // AI analysis with camera capture
            post("/ai/capture") {
                try {
                    val request = call.receive<AICaptureRequest>()
                    val camera = parseCameraSelection(request.camera)
                    val result = aiService.captureAndAnalyze(request.task, camera)
                    call.respond(mapOf(
                        "task" to request.task,
                        "camera" to request.camera,
                        "result" to result,
                        "timestamp" to System.currentTimeMillis()
                    ))
                } catch (e: Exception) {
                    Timber.e(e, "Error in AI capture and analysis")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "AI capture and analysis failed: ${e.message}")
                    )
                }
            }

            // Image capture endpoint
            post("/capture") {
                try {
                    val request = call.receive<CaptureRequest>()
                    val camera = parseCameraSelection(request.camera)
                    val imageBase64 = aiService.captureImage(camera)
                    
                    if (imageBase64 != null) {
                        call.respond(mapOf(
                            "camera" to request.camera,
                            "imageBase64" to imageBase64,
                            "timestamp" to System.currentTimeMillis(),
                            "format" to "jpeg"
                        ))
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to capture image")
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error capturing image")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Image capture failed: ${e.message}")
                    )
                }
            }
            
            // API documentation endpoint
            get("/") {
                call.respondText(
                    """
                    K3s Phone Server API
                    
                    Available Endpoints:
                    
                    GET /status
                    - Returns server status and information
                    
                    GET /has-ai
                    - Returns AI availability and capabilities information
                    - Response: {"available": true/false, "capabilities": {...}, "features": [...]}
                    
                    GET /location
                    - Returns current GPS location (latitude, longitude, altitude, accuracy)
                    
                    GET /orientation
                    - Returns device orientation/compass data (azimuth, pitch, roll)
                    
                    POST /ai/analyze
                    - Analyze provided image with AI
                    - Body: {"task": "describe this image", "imageBase64": "data:image/jpeg;base64,..."}
                    
                    POST /ai/capture
                    - Capture image with camera and analyze with AI
                    - Body: {"task": "describe your surroundings", "camera": "back|front|wide|zoom"}
                    
                    POST /capture
                    - Capture image with camera and return base64 image
                    - Body: {"camera": "back|front|wide|zoom"}
                    
                    Camera Options:
                    - back: Main rear camera (default)
                    - front: Front-facing camera
                    - wide: Wide-angle camera (fallback to back if not available)
                    - zoom: Telephoto/zoom camera (fallback to back if not available)
                    
                    All endpoints return JSON responses.
                    """.trimIndent(),
                    ContentType.Text.Plain
                )
            }
        }
    }

    private fun parseCameraSelection(camera: String?): CameraSelection {
        return when (camera?.lowercase()) {
            "front" -> CameraSelection.FRONT
            "wide" -> CameraSelection.WIDE
            "zoom" -> CameraSelection.ZOOM
            else -> CameraSelection.BACK // default
        }
    }
}

data class AIAnalysisRequest(
    val task: String,
    val imageBase64: String? = null
)

data class AICaptureRequest(
    val task: String,
    val camera: String? = "back"
)

data class CaptureRequest(
    val camera: String? = "back"
)
