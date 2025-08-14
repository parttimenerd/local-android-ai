package me.bechberger.k3s.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.bechberger.k3s.services.PhoneServerClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Handler for camera capture with object detection API endpoint.
 * Captures photo using object detection on Android K3s Phone Server and returns JSON with detected objects and optional image.
 * 
 * POST /api/phone/capture - Captures photo with object detection and returns detected objects and optional base64 image
 */
public class CameraCaptureHandler implements HttpHandler {
    
    private final PhoneServerClient phoneClient;
    
    public CameraCaptureHandler() {
        this.phoneClient = new PhoneServerClient();
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }
        
        try {
            // Check if phone server is available
            if (!phoneClient.isPhoneServerAvailable()) {
                phoneClient.checkPhoneServerAvailability(); // Retry once
            }
            
            if (!phoneClient.isPhoneServerAvailable()) {
                sendResponse(exchange, 503, "Android K3s Phone Server not available");
                return;
            }

            // Check server capabilities first
            try {
                PhoneServerClient.ServerCapabilities capabilities = phoneClient.getServerCapabilities()
                        .get(2, TimeUnit.SECONDS);
                
                if (!capabilities.cameraAvailable) {
                    sendResponse(exchange, 503, "Camera functionality not available on this phone server");
                    return;
                }
                
                if (!capabilities.aiAvailable) {
                    sendResponse(exchange, 503, "AI object detection not available on this phone server");
                    return;
                }
            } catch (Exception capEx) {
                sendResponse(exchange, 500, "Failed to check server capabilities: " + capEx.getMessage());
                return;
            }

            // Read request body (object detection configuration)
            String requestBody = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                requestBody = sb.toString();
            }
            
            // Make request to phone server object detection endpoint
            String detectionResult = captureWithObjectDetectionFromPhone(requestBody);
            
            if (detectionResult != null && !detectionResult.isEmpty()) {
                // Set response headers for JSON data
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                sendResponse(exchange, 200, detectionResult);
            } else {
                sendResponse(exchange, 500, "Failed to capture photo with object detection");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "Camera capture error: " + e.getMessage());
        }
    }
    
    private String captureWithObjectDetectionFromPhone(String requestBody) throws Exception {
        // Get phone server URL from PhoneServerClient
        String phoneServerUrl = phoneClient.getPhoneServerUrl();
        if (phoneServerUrl == null) {
            throw new RuntimeException("Phone server URL not available");
        }
        
        URI uri = URI.create(phoneServerUrl + "/ai/object_detection");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000); // Object detection can take time
            
            // Create default request body if none provided
            String objectDetectionRequest = requestBody;
            if (objectDetectionRequest == null || objectDetectionRequest.trim().isEmpty()) {
                // Default object detection configuration for faster processing
                objectDetectionRequest = "{\n" +
                    "  \"side\": \"rear\",\n" +
                    "  \"threshold\": 0.5,\n" +
                    "  \"maxResults\": 10,\n" +
                    "  \"returnImage\": true\n" +
                    "}";
            }
            
            // Send request body
            try (OutputStream os = connection.getOutputStream()) {
                os.write(objectDetectionRequest.getBytes(StandardCharsets.UTF_8));
            }
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return response.toString();
                }
            } else {
                // Try to read error response
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    throw new RuntimeException("Phone server returned status: " + responseCode + ", error: " + errorResponse.toString());
                }
            }
            
        } finally {
            connection.disconnect();
        }
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
        }
        if (!exchange.getResponseHeaders().containsKey("Access-Control-Allow-Origin")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        }
        
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
