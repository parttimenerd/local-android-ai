package me.bechberger.k3s.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for communicating with the Android K3s Phone Server.
 * Detects phone server availability and retrieves location/orientation data.
 * 
 * Expected Android server endpoints:
 * - GET /status      - Server health and info
 * - GET /location    - GPS coordinates with accuracy
 * - GET /orientation - Device compass/orientation data
 */
public class PhoneServerClient {
    
    private static final String PHONE_SERVER_HOST = "localhost";
    private static final int PHONE_SERVER_PORT = 8005;
    private static final String BASE_URL = "http://" + PHONE_SERVER_HOST + ":" + PHONE_SERVER_PORT;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    
    private final HttpClient httpClient;
    private volatile boolean phoneServerAvailable = false;
    
    public PhoneServerClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        
        // Check availability on startup
        checkPhoneServerAvailability();
    }
    
    /**
     * Check if the Android phone server is reachable.
     * Updates internal availability flag.
     */
    public void checkPhoneServerAvailability() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/status"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            phoneServerAvailable = (response.statusCode() == 200);
            
            if (phoneServerAvailable) {
                System.out.println("✅ Android K3s Phone Server detected at " + BASE_URL);
            }
        } catch (Exception e) {
            phoneServerAvailable = false;
            // Don't log errors - phone server might not be available, which is normal
        }
    }
    
    /**
     * Get current device location from Android phone server.
     * 
     * @return LocationData object with coordinates and accuracy, or null if unavailable
     */
    public CompletableFuture<LocationData> getLocation() {
        if (!phoneServerAvailable) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/location"))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    String json = response.body();
                    return new LocationData(
                            parseJsonDouble(json, "latitude"),
                            parseJsonDouble(json, "longitude"),
                            parseJsonDouble(json, "altitude"),
                            parseJsonDouble(json, "accuracy"),
                            parseJsonLong(json, "timestamp")
                    );
                }
            } catch (Exception e) {
                System.err.println("Failed to get location from phone server: " + e.getMessage());
            }
            return null;
        });
    }
    
    /**
     * Get current device orientation from Android phone server.
     * 
     * @return OrientationData object with compass bearing and pitch/roll, or null if unavailable
     */
    public CompletableFuture<OrientationData> getOrientation() {
        if (!phoneServerAvailable) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/orientation"))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    String json = response.body();
                    return new OrientationData(
                            parseJsonDouble(json, "azimuth"),
                            parseJsonDouble(json, "pitch"),
                            parseJsonDouble(json, "roll"),
                            parseJsonString(json, "accuracy"),
                            parseJsonLong(json, "timestamp")
                    );
                }
            } catch (Exception e) {
                System.err.println("Failed to get orientation from phone server: " + e.getMessage());
            }
            return null;
        });
    }
    
    /**
     * Get combined location and orientation data for dashboard display.
     */
    public CompletableFuture<PhoneData> getPhoneData() {
        if (!phoneServerAvailable) {
            return CompletableFuture.completedFuture(null);
        }
        
        CompletableFuture<LocationData> locationFuture = getLocation();
        CompletableFuture<OrientationData> orientationFuture = getOrientation();
        
        return CompletableFuture.allOf(locationFuture, orientationFuture)
                .thenApply(v -> {
                    LocationData location = locationFuture.join();
                    OrientationData orientation = orientationFuture.join();
                    
                    if (location != null || orientation != null) {
                        return new PhoneData(location, orientation, System.currentTimeMillis());
                    }
                    return null;
                });
    }
    
    public boolean isPhoneServerAvailable() {
        return phoneServerAvailable;
    }
    
    /**
     * Get the base URL for the phone server.
     * 
     * @return Phone server base URL or null if not available
     */
    public String getPhoneServerUrl() {
        return phoneServerAvailable ? BASE_URL : null;
    }
    
    /**
     * Check if AI services are available on the phone server.
     * 
     * @return CompletableFuture<Boolean> indicating AI availability
     */
    public CompletableFuture<Boolean> checkAIAvailability() {
        if (!phoneServerAvailable) {
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/has-ai"))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    String responseBody = response.body();
                    // Simple JSON parsing to check "available" field
                    return responseBody.contains("\"available\"") && 
                           responseBody.contains("true") &&
                           !responseBody.contains("\"available\"\\s*:\\s*false");
                }
                return false;
            } catch (Exception e) {
                System.err.println("Failed to check AI availability: " + e.getMessage());
                return false;
            }
        });
    }
    
    /**
     * Request AI description of surroundings if AI is available.
     * 
     * @return CompletableFuture<String> with AI description or null if unavailable
     */
    public CompletableFuture<String> getAIDescription() {
        if (!phoneServerAvailable) {
            return CompletableFuture.completedFuture(null);
        }
        
        return checkAIAvailability().thenCompose(aiAvailable -> {
            if (!aiAvailable) {
                System.out.println("AI not available on phone server");
                return CompletableFuture.completedFuture(null);
            }
            
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Create JSON request body
                    String requestBody = "{\"task\":\"describe your surroundings\",\"camera\":\"back\"}";
                    
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/ai/capture"))
                            .timeout(Duration.ofSeconds(15)) // AI requests can take longer
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();
                    
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        String responseBody = response.body();
                        // Extract result from JSON response
                        return parseJsonString(responseBody, "result");
                    } else {
                        System.err.println("AI description request failed with status: " + response.statusCode());
                        return null;
                    }
                } catch (Exception e) {
                    System.err.println("Failed to get AI description: " + e.getMessage());
                    return null;
                }
            });
        });
    }
    
    /**
     * Capture image from phone camera.
     * 
     * @return CompletableFuture<String> with base64 encoded image or null if unavailable
     */
    public CompletableFuture<String> captureImage() {
        if (!phoneServerAvailable) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/capture"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    String responseBody = response.body();
                    // Check if it's a base64 image
                    if (responseBody.startsWith("data:image/")) {
                        return responseBody;
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to capture image from phone: " + e.getMessage());
            }
            return null;
        });
    }
    
    /**
     * Check server capabilities using the /capabilities endpoint.
     * 
     * @return CompletableFuture<ServerCapabilities> with available features
     */
    public CompletableFuture<ServerCapabilities> getServerCapabilities() {
        if (!phoneServerAvailable) {
            return CompletableFuture.completedFuture(new ServerCapabilities(false, false, false));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/capabilities"))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    String responseBody = response.body();
                    
                    // Parse capabilities from JSON response
                    boolean aiAvailable = parseCapabilityAvailable(responseBody, "ai");
                    boolean cameraAvailable = parseCapabilityAvailable(responseBody, "camera");
                    boolean locationAvailable = parseCapabilityAvailable(responseBody, "location");
                    
                    return new ServerCapabilities(aiAvailable, cameraAvailable, locationAvailable);
                }
                return new ServerCapabilities(false, false, false);
            } catch (Exception e) {
                System.err.println("Failed to check server capabilities: " + e.getMessage());
                return new ServerCapabilities(false, false, false);
            }
        });
    }
    
    /**
     * Parse capability availability from capabilities JSON response.
     */
    private boolean parseCapabilityAvailable(String json, String capability) {
        try {
            // Look for "capability": { "available": true/false }
            String capabilitySection = extractJsonSection(json, "\"" + capability + "\"");
            if (capabilitySection != null) {
                return capabilitySection.contains("\"available\"") && 
                       capabilitySection.contains("true") &&
                       !capabilitySection.contains("\"available\"\\s*:\\s*false");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Extract a JSON section starting from a key.
     */
    private String extractJsonSection(String json, String key) {
        int startIndex = json.indexOf(key);
        if (startIndex == -1) return null;
        
        int braceIndex = json.indexOf('{', startIndex);
        if (braceIndex == -1) return null;
        
        int braceCount = 0;
        int endIndex = braceIndex;
        
        for (int i = braceIndex; i < json.length(); i++) {
            if (json.charAt(i) == '{') braceCount++;
            if (json.charAt(i) == '}') braceCount--;
            if (braceCount == 0) {
                endIndex = i + 1;
                break;
            }
        }
        
        return json.substring(braceIndex, endIndex);
    }
    
    /**
     * Simple JSON parsing utilities for extracting values without external dependencies
     */
    private double parseJsonDouble(String json, String key) {
        try {
            Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([\\d\\.-]+)");
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return 0.0;
    }
    
    private long parseJsonLong(String json, String key) {
        try {
            Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return System.currentTimeMillis();
    }
    
    private String parseJsonString(String json, String key) {
        try {
            Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return "UNKNOWN";
    }
    
    /**
     * Data class for server capabilities information
     */
    public static class ServerCapabilities {
        public final boolean aiAvailable;
        public final boolean cameraAvailable;
        public final boolean locationAvailable;
        
        public ServerCapabilities(boolean aiAvailable, boolean cameraAvailable, boolean locationAvailable) {
            this.aiAvailable = aiAvailable;
            this.cameraAvailable = cameraAvailable;
            this.locationAvailable = locationAvailable;
        }
        
        @Override
        public String toString() {
            return String.format("ServerCapabilities{ai=%s, camera=%s, location=%s}", 
                    aiAvailable, cameraAvailable, locationAvailable);
        }
    }
    
    /**
     * Data class for location information
     */
    public static class LocationData {
        public final double latitude;
        public final double longitude;
        public final double altitude;
        public final double accuracy;
        public final long timestamp;
        
        public LocationData(double latitude, double longitude, double altitude, double accuracy, long timestamp) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.accuracy = accuracy;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Data class for orientation information
     */
    public static class OrientationData {
        public final double azimuth;   // Compass bearing (0-360°)
        public final double pitch;     // Forward/backward tilt (-180 to 180°)
        public final double roll;      // Left/right tilt (-90 to 90°)
        public final String accuracy;  // HIGH, MEDIUM, LOW, UNRELIABLE
        public final long timestamp;
        
        public OrientationData(double azimuth, double pitch, double roll, String accuracy, long timestamp) {
            this.azimuth = azimuth;
            this.pitch = pitch;
            this.roll = roll;
            this.accuracy = accuracy;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Combined phone data for dashboard display
     */
    public static class PhoneData {
        public final LocationData location;
        public final OrientationData orientation;
        public final long timestamp;
        
        public PhoneData(LocationData location, OrientationData orientation, long timestamp) {
            this.location = location;
            this.orientation = orientation;
            this.timestamp = timestamp;
        }
    }
}
