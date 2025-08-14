package me.bechberger.k3s.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.bechberger.k3s.services.PhoneServerClient;
import me.bechberger.k3s.services.PhoneServerClient.PhoneData;
import me.bechberger.k3s.services.PhoneServerClient.LocationData;
import me.bechberger.k3s.services.PhoneServerClient.OrientationData;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Handler for phone data API endpoint.
 * Provides optional integration with Android K3s Phone Server.
 * Returns enhanced data (location, orientation, camera, object detection) when available,
 * or gracefully indicates unavailability when phone server is not connected.
 * 
 * GET /api/phone - Returns JSON with phone data if available, status if not
 * GET /api/phone?refreshAI=true - Forces fresh object detection via /ai/object_detection
 * GET /api/phone?includeImage=true - Includes base64 image in object detection response
 */
public class PhoneDataHandler implements HttpHandler {
    
    private final PhoneServerClient phoneClient;
    
    public PhoneDataHandler() {
        this.phoneClient = new PhoneServerClient();
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            // Parse query parameters
            String query = exchange.getRequestURI().getQuery();
            boolean refreshAI = false;
            boolean includeImage = false;
            if (query != null) {
                refreshAI = query.contains("refreshAI=true");
                includeImage = query.contains("includeImage=true");
            }

            // Check if phone server is available
            if (!phoneClient.isPhoneServerAvailable()) {
                phoneClient.checkPhoneServerAvailability(); // Retry once
            }

            if (!phoneClient.isPhoneServerAvailable()) {
                String response = "{\n" +
                        "  \"available\": false,\n" +
                        "  \"message\": \"Android K3s Phone Server not detected on port 8005\",\n" +
                        "  \"timestamp\": " + System.currentTimeMillis() + "\n" +
                        "}";
                sendResponse(exchange, 200, response);
                return;
            }

            // Get phone data with timeout
            PhoneData phoneData = phoneClient.getPhoneData()
                    .get(3, TimeUnit.SECONDS);

            // Check server capabilities first to avoid unnecessary requests
            PhoneServerClient.ServerCapabilities capabilities = phoneClient.getServerCapabilities()
                    .get(2, TimeUnit.SECONDS);
            
            System.out.println("Server capabilities: " + capabilities);

            // Try to get object detection data only if AI is available (faster than LLM description)
            String objectDetectionData = null;
            if (capabilities.aiAvailable && refreshAI) {
                try {
                    System.out.println("Fetching object detection data via /ai/object_detection...");
                    objectDetectionData = phoneClient.getObjectDetection(includeImage)
                            .get(8, TimeUnit.SECONDS); // Object detection is faster than LLM
                } catch (Exception aiEx) {
                    System.out.println("Object detection failed: " + aiEx.getMessage());
                }
            } else if (capabilities.aiAvailable) {
                System.out.println("AI available but refreshAI=false, skipping object detection");
            } else {
                System.out.println("AI capabilities not available on server");
            }

            // Try to get camera image only if camera is available and image not already included in object detection
            String cameraImage = null;
            if (capabilities.cameraAvailable && (objectDetectionData == null || !includeImage)) {
                try {
                    cameraImage = phoneClient.captureImage()
                            .get(3, TimeUnit.SECONDS);
                } catch (Exception camEx) {
                    System.out.println("Camera capture failed: " + camEx.getMessage());
                }
            } else {
                System.out.println("Camera capabilities not available or image already included in object detection");
            }
            
            String response = buildPhoneDataJson(phoneData, objectDetectionData, cameraImage, capabilities);
            sendResponse(exchange, 200, response);
            
        } catch (Exception e) {
            String errorResponse = "{\n" +
                    "  \"available\": false,\n" +
                    "  \"error\": \"" + e.getMessage() + "\",\n" +
                    "  \"timestamp\": " + System.currentTimeMillis() + "\n" +
                    "}";
            sendResponse(exchange, 500, errorResponse);
        }
    }
    
    private String buildPhoneDataJson(PhoneData phoneData, String objectDetectionData, String cameraImage, PhoneServerClient.ServerCapabilities capabilities) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"available\": true,\n");
        json.append("  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n");
        
        // Include server capabilities for frontend decision making
        json.append("  \"capabilities\": {\n");
        json.append("    \"ai\": ").append(capabilities.aiAvailable).append(",\n");
        json.append("    \"camera\": ").append(capabilities.cameraAvailable).append(",\n");
        json.append("    \"location\": ").append(capabilities.locationAvailable).append("\n");
        json.append("  },\n");
        
        if (phoneData != null) {
            LocationData location = phoneData.location;
            OrientationData orientation = phoneData.orientation;
            
            if (location != null) {
                json.append("  \"location\": {\n");
                json.append("    \"latitude\": ").append(location.latitude).append(",\n");
                json.append("    \"longitude\": ").append(location.longitude).append(",\n");
                json.append("    \"altitude\": ").append(location.altitude).append(",\n");
                json.append("    \"accuracy\": ").append(location.accuracy).append(",\n");
                json.append("    \"timestamp\": ").append(location.timestamp).append("\n");
                json.append("  }");
                
                if (orientation != null || objectDetectionData != null || cameraImage != null) {
                    json.append(",\n");
                }
            }
            
            if (orientation != null) {
                json.append("  \"orientation\": {\n");
                json.append("    \"azimuth\": ").append(orientation.azimuth).append(",\n");
                json.append("    \"pitch\": ").append(orientation.pitch).append(",\n");
                json.append("    \"roll\": ").append(orientation.roll).append(",\n");
                json.append("    \"accuracy\": \"").append(orientation.accuracy).append("\",\n");
                json.append("    \"timestamp\": ").append(orientation.timestamp).append("\n");
                json.append("  }");
                
                if (objectDetectionData != null || cameraImage != null) {
                    json.append(",\n");
                }
            }
            
            // Add camera image if available (separate from object detection)
            if (cameraImage != null && !cameraImage.trim().isEmpty()) {
                json.append("  \"camera\": {\n");
                json.append("    \"image\": \"").append(cameraImage.replace("\"", "\\\"")).append("\",\n");
                json.append("    \"timestamp\": ").append(System.currentTimeMillis()).append("\n");
                json.append("  }");
                
                if (objectDetectionData != null) {
                    json.append(",\n");
                }
            }
            
            // Add object detection data if available (includes detected objects and optionally image)
            if (objectDetectionData != null && !objectDetectionData.trim().isEmpty()) {
                // Object detection response is already JSON, so we embed it directly
                json.append("  \"objectDetection\": ");
                json.append(objectDetectionData);
            }
            
            if (location == null && orientation == null && objectDetectionData == null && cameraImage == null) {
                json.append("  \"message\": \"No location, orientation, camera, or object detection data available\"");
            }
        } else {
            json.append("  \"message\": \"Phone server reachable but no data available\"");
        }
        
        json.append("\n}");
        return json.toString();
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, response.getBytes().length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }
}
