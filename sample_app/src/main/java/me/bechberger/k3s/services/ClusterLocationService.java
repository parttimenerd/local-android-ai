package me.bechberger.k3s.services;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Service to fetch location information of all nodes in the K3s cluster
 * Uses Kubernetes API to read node labels containing geolocation data
 */
public class ClusterLocationService {
    
    private static final String KUBERNETES_API_HOST = "https://kubernetes.default.svc";
    private static final String SERVICE_ACCOUNT_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    
    private final HttpClient httpClient;
    private String bearerToken;
    
    public ClusterLocationService() {
        this.httpClient = HttpClient.newBuilder()
            .build(); // Use default SSL context for now
        this.bearerToken = loadServiceAccountToken();
    }
    
    /**
     * Get location information for all nodes in the cluster
     */
    public List<NodeLocation> getAllNodeLocations() throws IOException, InterruptedException {
        String apiUrl = KUBERNETES_API_HOST + "/api/v1/nodes";
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Authorization", "Bearer " + bearerToken)
            .header("Accept", "application/json")
            .GET()
            .build();
            
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
            
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch nodes: " + response.statusCode());
        }
        
        return parseNodeLocations(response.body());
    }
    
    /**
     * Get location information for phone nodes only
     */
    public List<NodeLocation> getPhoneNodeLocations() throws IOException, InterruptedException {
        String apiUrl = KUBERNETES_API_HOST + "/api/v1/nodes?labelSelector=device-type=phone";
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Authorization", "Bearer " + bearerToken)
            .header("Accept", "application/json")
            .GET()
            .build();
            
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
            
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch phone nodes: " + response.statusCode());
        }
        
        return parseNodeLocations(response.body());
    }
    
    private List<NodeLocation> parseNodeLocations(String jsonResponse) throws IOException {
        List<NodeLocation> locations = new ArrayList<>();
        
        // Use regex to parse JSON (simple approach without external dependencies)
        Pattern nodePattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"labels\"\\s*:\\s*\\{([^}]+)\\}");
        Pattern labelPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
        Pattern ipPattern = Pattern.compile("\"type\"\\s*:\\s*\"InternalIP\"[^}]*?\"address\"\\s*:\\s*\"([^\"]+)\"");
        
        Matcher nodeMatcher = nodePattern.matcher(jsonResponse);
        
        while (nodeMatcher.find()) {
            String nodeName = nodeMatcher.group(1);
            String labelsSection = nodeMatcher.group(2);
            
            // Parse labels
            String latitude = null, longitude = null, altitude = null, updated = null, deviceType = null, cityName = null;
            
            Matcher labelMatcher = labelPattern.matcher(labelsSection);
            while (labelMatcher.find()) {
                String key = labelMatcher.group(1);
                String value = labelMatcher.group(2);
                
                switch (key) {
                    case "phone.location/latitude":
                        latitude = value;
                        break;
                    case "phone.location/longitude":
                        longitude = value;
                        break;
                    case "phone.location/altitude":
                        altitude = value;
                        break;
                    case "phone.location/updated":
                        updated = value;
                        break;
                    case "phone.location/city":
                        cityName = value;
                        break;
                    case "device-type":
                        deviceType = value;
                        break;
                }
            }
            
            // Only include nodes with location data
            if (latitude != null && longitude != null) {
                NodeLocation location = new NodeLocation();
                location.setNodeName(nodeName);
                location.setLatitude(Double.parseDouble(latitude));
                location.setLongitude(Double.parseDouble(longitude));
                location.setAltitude(altitude != null ? Double.parseDouble(altitude) : 0.0);
                location.setLastUpdated(updated);
                location.setDeviceType(deviceType);
                
                // Set city name from node label (updated by update-node-city.sh script)
                // Replace underscores with spaces (Kubernetes labels escape spaces)
                location.setCityName(cityName != null ? cityName.replace("_", " ") : "Unknown");
                
                // Extract IP address for this node
                Matcher ipMatcher = ipPattern.matcher(jsonResponse);
                while (ipMatcher.find()) {
                    // This is a simplified approach - in reality you'd need to match IP to specific node
                    location.setNodeIP(ipMatcher.group(1));
                    break; // Take first IP for now
                }
                
                locations.add(location);
            }
        }
        
        return locations;
    }
    
    private String loadServiceAccountToken() {
        try {
            return Files.readString(Paths.get(SERVICE_ACCOUNT_TOKEN_PATH)).trim();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load service account token", e);
        }
    }
    
    /**
     * Node location data class
     */
    public static class NodeLocation {
        private String nodeName;
        private String nodeIP;
        private double latitude;
        private double longitude;
        private double altitude;
        private String lastUpdated;
        private String deviceType;
        private String cityName;
        
        // Getters and setters
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        
        public String getNodeIP() { return nodeIP; }
        public void setNodeIP(String nodeIP) { this.nodeIP = nodeIP; }
        
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
        
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
        
        public double getAltitude() { return altitude; }
        public void setAltitude(double altitude) { this.altitude = altitude; }
        
        public String getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }
        
        public String getDeviceType() { return deviceType; }
        public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
        
        public String getCityName() { return cityName; }
        public void setCityName(String cityName) { this.cityName = cityName; }
        
        @Override
        public String toString() {
            return String.format("NodeLocation{name='%s', ip='%s', lat=%.6f, lon=%.6f, alt=%.1f, type='%s', city='%s'}", 
                nodeName, nodeIP, latitude, longitude, altitude, deviceType, cityName);
        }
    }
}
