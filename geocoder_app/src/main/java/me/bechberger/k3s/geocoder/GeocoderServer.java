package me.bechberger.k3s.geocoder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Standalone reverse geocoding HTTP server
 * Lightweight service that runs independently and provides reverse geocoding for the cluster
 */
public class GeocoderServer {
    
    private static final int PORT = 8090;
    private static ReverseGeocodingService.ReverseGeocoder geocoder;
    
    /**
     * Initialize the geocoder (for testing)
     */
    public static void initializeGeocoder() throws Exception {
        if (geocoder == null) {
            geocoder = ReverseGeocodingService.getGlobalGeocoder();
        }
    }
    
    public static void main(String[] args) {
        try {
            System.out.println("🌍 Starting Reverse Geocoder Service on port " + PORT);
            
            // Initialize the geocoder with global city data
            System.out.println("📊 Loading geocoding data...");
            initializeGeocoder();
            System.out.println("✅ Geocoding data loaded successfully");
            
            // Create HTTP server
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            
            // Add endpoints
            server.createContext("/health", new HealthHandler());
            server.createContext("/api/reverse-geocode", new ReverseGeocodeHandler());
            server.createContext("/api/", new ApiNotFoundHandler());
            server.createContext("/", new RootHandler());
            
            // Start server
            server.setExecutor(null); // Use default executor
            server.start();
            
            System.out.println("🚀 Reverse Geocoder Service started successfully!");
            System.out.println("📍 Health check: http://localhost:" + PORT + "/health");
            System.out.println("🔍 API endpoint: http://localhost:" + PORT + "/api/reverse-geocode?lat=51.5074&lon=-0.1278");
            System.out.println("⏹️  Press Ctrl+C to stop");
            
            // Keep the server running
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutting down Reverse Geocoder Service...");
                server.stop(2);
                System.out.println("✅ Service stopped gracefully");
            }));
            
        } catch (Exception e) {
            System.err.println("❌ Failed to start Reverse Geocoder Service: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Health check endpoint
     */
    public static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String timestamp = java.time.Instant.now().toString();
            String response = String.format(
                "{\"status\":\"healthy\",\"service\":\"reverse-geocoder\",\"timestamp\":\"%s\"}", 
                timestamp
            );
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
    
    /**
     * Root endpoint with service info
     */
    public static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>K3s Reverse Geocoder Service</title>
                </head>
                <body>
                    <h1>🌍 K3s Reverse Geocoder Service</h1>
                    <p>Standalone reverse geocoding service for K3s cluster</p>
                    
                    <h2>API Endpoints:</h2>
                    <ul>
                        <li><code>/health</code> - Health check</li>
                        <li><code>/api/reverse-geocode?lat=&lt;lat&gt;&lon=&lt;lon&gt;&method=&lt;method&gt;</code> - Reverse geocoding</li>
                    </ul>
                    
                    <h2>Example:</h2>
                    <p><a href="/api/reverse-geocode?lat=51.5074&lon=-0.1278&method=geonames">
                        /api/reverse-geocode?lat=51.5074&lon=-0.1278&method=geonames
                    </a></p>
                    
                    <h2>Methods:</h2>
                    <ul>
                        <li><code>geonames</code> - Use local GeoNames data (default)</li>
                        <li><code>hybrid</code> - Same as geonames (for compatibility)</li>
                    </ul>
                </body>
                </html>
                """;
            
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
    
    /**
     * API 404 handler for unknown endpoints
     */
    public static class ApiNotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"error\":\"Endpoint not found\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    /**
     * Reverse geocoding API endpoint
     */
    public static class ReverseGeocodeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                // Parse query parameters
                String query = exchange.getRequestURI().getQuery();
                if (query == null) {
                    sendError(exchange, 400, "Missing required parameters: lat, lon");
                    return;
                }
                
                double lat = 0, lon = 0;
                String method = "geonames";
                boolean hasLat = false, hasLon = false;
                
                for (String param : query.split("&")) {
                    String[] parts = param.split("=", 2);
                    if (parts.length == 2) {
                        switch (parts[0]) {
                            case "lat" -> {
                                lat = Double.parseDouble(parts[1]);
                                hasLat = true;
                            }
                            case "lon" -> {
                                lon = Double.parseDouble(parts[1]);
                                hasLon = true;
                            }
                            case "method" -> method = parts[1];
                        }
                    }
                }
                
                if (!hasLat || !hasLon) {
                    sendError(exchange, 400, "Missing required parameters: lat, lon");
                    return;
                }
                
                // Get location name
                String location = getLocationName(lat, lon, method);
                
                // Build JSON response with coordinates object as expected by tests
                String response = String.format(
                    "{\"location\":\"%s\",\"method\":\"%s\",\"coordinates\":{\"latitude\":%.6f,\"longitude\":%.6f}}",
                    location, method, lat, lon
                );
                
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
                
            } catch (NumberFormatException e) {
                sendError(exchange, 400, "Invalid coordinate format");
            } catch (Exception e) {
                System.err.println("Error processing reverse geocoding request: " + e.getMessage());
                sendError(exchange, 500, "Internal server error");
            }
        }
        
        private void sendError(HttpExchange exchange, int code, String message) throws IOException {
            String response = String.format("{\"error\":\"%s\"}", message);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
        
        private String getLocationName(double lat, double lon, String method) {
            try {
                // All methods now use local GeoNames data only
                return getLocationNameLocal(lat, lon);
            } catch (Exception e) {
                System.err.println("Failed to get location name: " + e.getMessage());
                return "Unknown";
            }
        }
        
        private String getLocationNameLocal(double lat, double lon) {
            try {
                ReverseGeocodingService.City nearestCity = geocoder.findNearestWithinRadius(lat, lon, 50.0);
                if (nearestCity != null) {
                    return nearestCity.name() + ", " + nearestCity.country();
                }
                return "Unknown";
            } catch (Exception e) {
                System.err.println("Local geocoding failed: " + e.getMessage());
                return "Unknown";
            }
        }
    }
}
