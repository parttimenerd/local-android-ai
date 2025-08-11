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
 * Handler for camera capture API endpoint.
 * Triggers photo capture on Android K3s Phone Server and returns base64 image.
 * 
 * POST /api/phone/capture - Captures photo and returns base64 encoded image
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
            } catch (Exception capEx) {
                sendResponse(exchange, 500, "Failed to check server capabilities: " + capEx.getMessage());
                return;
            }

            // Read request body (camera configuration)
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
            
            // Make request to phone server AI capture endpoint
            String imageBase64 = capturePhotoFromPhone(requestBody);
            
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                // Set response headers for image data
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                sendResponse(exchange, 200, imageBase64);
            } else {
                sendResponse(exchange, 500, "Failed to capture photo");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "Camera capture error: " + e.getMessage());
        }
    }
    
    private String capturePhotoFromPhone(String requestBody) throws Exception {
        // Get phone server URL from PhoneServerClient
        String phoneServerUrl = phoneClient.getPhoneServerUrl();
        if (phoneServerUrl == null) {
            throw new RuntimeException("Phone server URL not available");
        }
        
        URI uri = URI.create(phoneServerUrl + "/ai/capture");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/plain");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000); // Camera operations can take time
            
            // Send request body if provided
            if (requestBody != null && !requestBody.isEmpty()) {
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }
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
                throw new RuntimeException("Phone server returned status: " + responseCode);
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
