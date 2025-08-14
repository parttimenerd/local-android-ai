package me.bechberger.k3s.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.bechberger.k3s.services.PhoneServerClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Handler for AI text endpoint testing.
 * Processes text prompts with camera capture and returns AI-generated responses with optional image.
 * 
 * POST /api/phone/ai-text - Processes AI text request with camera capture and returns AI response with optional image
 */
public class AITextHandler implements HttpHandler {
    
    private final PhoneServerClient phoneClient;
    
    public AITextHandler() {
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
                    sendResponse(exchange, 503, "AI functionality not available on this phone server");
                    return;
                }
            } catch (Exception capEx) {
                sendResponse(exchange, 500, "Failed to check server capabilities: " + capEx.getMessage());
                return;
            }

            // Read request body (AI text request configuration)
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
            
            // Parse the request to extract text, camera, and returnImage parameters
            String text = extractJsonField(requestBody, "text", "Describe what you see");
            String camera = extractJsonField(requestBody, "camera", "rear");
            boolean returnImage = Boolean.parseBoolean(extractJsonField(requestBody, "returnImage", "true"));
            
            // Make request to phone server AI text endpoint
            String aiResult = getAITextFromPhone(text, camera, returnImage);
            
            if (aiResult != null && !aiResult.isEmpty()) {
                // Set response headers for JSON data
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                sendResponse(exchange, 200, aiResult);
            } else {
                sendResponse(exchange, 500, "Failed to get AI text response");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "AI text request error: " + e.getMessage());
        }
    }
    
    private String getAITextFromPhone(String text, String camera, boolean returnImage) throws Exception {
        try {
            return phoneClient.getAITextResponse(text, camera, returnImage)
                    .get(35, TimeUnit.SECONDS); // Allow extra time for AI processing
        } catch (Exception e) {
            System.err.println("Failed to get AI text response from phone: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Simple JSON field extraction (basic implementation)
     */
    private String extractJsonField(String json, String fieldName, String defaultValue) {
        try {
            String searchPattern = "\"" + fieldName + "\"";
            int start = json.indexOf(searchPattern);
            if (start == -1) return defaultValue;
            
            start = json.indexOf(":", start) + 1;
            start = json.indexOf("\"", start) + 1;
            int end = json.indexOf("\"", start);
            
            if (start > 0 && end > start) {
                return json.substring(start, end);
            }
        } catch (Exception e) {
            System.err.println("Failed to parse JSON field " + fieldName + ": " + e.getMessage());
        }
        return defaultValue;
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.sendResponseHeaders(statusCode, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
}
