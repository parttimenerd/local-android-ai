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
 * Returns enhanced data (location, orientation, camera, AI) when available,
 * or gracefully indicates unavailability when phone server is not connected.
 * 
 * GET /api/phone - Returns JSON with phone data if available, status if not
 * GET /api/phone?refreshAI=true - Forces fresh AI description via /ai/capture
 * GET /api/phone?refreshAI=false - Uses cached/quick AI data (default behavior)
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
            if (query != null && query.contains("refreshAI=true")) {
                refreshAI = true;
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

            // Try to get AI description - fetch fresh if requested
            String aiDescription = null;
            if (refreshAI) {
                try {
                    System.out.println("Fetching fresh AI description via /ai/capture...");
                    aiDescription = phoneClient.getAIDescription()
                            .get(10, TimeUnit.SECONDS); // Longer timeout for fresh AI
                } catch (Exception aiEx) {
                    // AI description is optional, don't fail the whole request
                    System.out.println("Fresh AI description not available: " + aiEx.getMessage());
                }
            } else {
                try {
                    aiDescription = phoneClient.getAIDescription()
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception aiEx) {
                    // AI description is optional, don't fail the whole request
                    System.out.println("AI description not available: " + aiEx.getMessage());
                }
            }            // Try to get camera image (optional)
            String cameraImage = null;
            try {
                cameraImage = phoneClient.captureImage()
                        .get(3, TimeUnit.SECONDS);
            } catch (Exception camEx) {
                // Camera is optional, don't fail the whole request
                System.out.println("Camera capture not available: " + camEx.getMessage());
            }
            
            String response = buildPhoneDataJson(phoneData, aiDescription, cameraImage);
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
    
    private String buildPhoneDataJson(PhoneData phoneData, String aiDescription, String cameraImage) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"available\": true,\n");
        json.append("  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n");
        
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
                
                if (orientation != null || aiDescription != null || cameraImage != null) {
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
                
                if (aiDescription != null || cameraImage != null) {
                    json.append(",\n");
                }
            }
            
            // Add camera image if available
            if (cameraImage != null && !cameraImage.trim().isEmpty()) {
                json.append("  \"camera\": {\n");
                json.append("    \"image\": \"").append(cameraImage.replace("\"", "\\\"")).append("\",\n");
                json.append("    \"timestamp\": ").append(System.currentTimeMillis()).append("\n");
                json.append("  }");
                
                if (aiDescription != null) {
                    json.append(",\n");
                }
            }
            
            // Add AI description if available
            if (aiDescription != null && !aiDescription.trim().isEmpty()) {
                json.append("  \"aiDescription\": {\n");
                json.append("    \"description\": \"").append(aiDescription.replace("\"", "\\\"")).append("\",\n");
                json.append("    \"timestamp\": ").append(System.currentTimeMillis()).append("\n");
                json.append("  }");
            }
            
            if (location == null && orientation == null && aiDescription == null && cameraImage == null) {
                json.append("  \"message\": \"No location, orientation, camera, or AI data available\"");
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
