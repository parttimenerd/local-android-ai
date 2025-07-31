package me.bechberger.k3s.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;

/**
 * Utility class for HTTP operations and common functionality.
 */
public class HttpUtils {
    
    /**
     * Helper method to send HTTP response
     */
    public static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
    
    /**
     * Helper method to load resource as string
     */
    public static String loadResourceAsString(String resourcePath) {
        try (InputStream is = HttpUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.err.println("Resource not found: " + resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Error loading resource: " + resourcePath + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if the HTTP method is GET
     */
    public static boolean isGetRequest(HttpExchange exchange) {
        return "GET".equals(exchange.getRequestMethod());
    }
}
