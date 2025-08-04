package me.bechberger.k3s.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.bechberger.k3s.services.ClusterLocationService;
import me.bechberger.k3s.services.ClusterLocationService.NodeLocation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handler for cluster location data API endpoint
 * Returns location information for all nodes in the K3s cluster
 */
public class ClusterLocationHandler implements HttpHandler {
    
    private final ClusterLocationService locationService;
    
    public ClusterLocationHandler() {
        this.locationService = new ClusterLocationService();
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGetRequest(exchange);
            } else {
                // Method not allowed
                exchange.sendResponseHeaders(405, -1);
            }
        } catch (Exception e) {
            System.err.println("Error in ClusterLocationHandler: " + e.getMessage());
            e.printStackTrace();
            
            // Send error response
            String errorResponse = "{\"error\": \"Failed to fetch cluster locations: " + 
                                 e.getMessage().replace("\"", "\\\"") + "\"}";
            byte[] response = errorResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
    
    private void handleGetRequest(HttpExchange exchange) throws IOException, InterruptedException {
        // Check if phone-only filter is requested
        String query = exchange.getRequestURI().getQuery();
        boolean phoneOnly = query != null && query.contains("phone-only=true");
        
        List<NodeLocation> locations;
        if (phoneOnly) {
            locations = locationService.getPhoneNodeLocations();
        } else {
            locations = locationService.getAllNodeLocations();
        }
        
        // Build JSON response
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{\n");
        jsonBuilder.append("  \"cluster\": \"k3s-phone\",\n");
        jsonBuilder.append("  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n");
        jsonBuilder.append("  \"nodeCount\": ").append(locations.size()).append(",\n");
        jsonBuilder.append("  \"nodes\": [\n");
        
        for (int i = 0; i < locations.size(); i++) {
            NodeLocation location = locations.get(i);
            jsonBuilder.append("    {\n");
            jsonBuilder.append("      \"name\": \"").append(escapeJson(location.getNodeName())).append("\",\n");
            jsonBuilder.append("      \"ip\": \"").append(escapeJson(location.getNodeIP())).append("\",\n");
            jsonBuilder.append("      \"latitude\": ").append(location.getLatitude()).append(",\n");
            jsonBuilder.append("      \"longitude\": ").append(location.getLongitude()).append(",\n");
            jsonBuilder.append("      \"altitude\": ").append(location.getAltitude()).append(",\n");
            jsonBuilder.append("      \"deviceType\": \"").append(escapeJson(location.getDeviceType())).append("\",\n");
            jsonBuilder.append("      \"cityName\": \"").append(escapeJson(location.getCityName())).append("\",\n");
            jsonBuilder.append("      \"lastUpdated\": \"").append(escapeJson(location.getLastUpdated())).append("\"\n");
            jsonBuilder.append("    }");
            
            if (i < locations.size() - 1) {
                jsonBuilder.append(",");
            }
            jsonBuilder.append("\n");
        }
        
        jsonBuilder.append("  ]\n");
        jsonBuilder.append("}");
        
        // Send response
        byte[] response = jsonBuilder.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // Enable CORS
        exchange.sendResponseHeaders(200, response.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
    
    private String escapeJson(String input) {
        if (input == null) {
            return "null";
        }
        return input.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
