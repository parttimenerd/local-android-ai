package me.bechberger.k3s.handlers;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.bechberger.k3s.ServerInfoServer;
import me.bechberger.k3s.util.HttpUtils;

/**
 * Health check handler for Kubernetes probes and system monitoring
 */
public class HealthHandler implements HttpHandler {
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Generate current timestamp
        String timestamp = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        
        // Get system information for health check
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        
        // Calculate memory usage
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = Runtime.getRuntime().maxMemory();
        
        // Get CPU load (if available)
        double cpuLoad = osBean.getSystemLoadAverage();
        
        // Get JVM vendor information
        String jvmVendor = System.getProperty("java.vendor", "Unknown");
        
        // Get OS description (LSB release equivalent)
        String osDescription = getOSDescription();
        
        // Create comprehensive health response
        String healthResponse = String.format(
            "{\n" +
            "  \"status\": \"healthy\",\n" +
            "  \"hostname\": \"%s\",\n" +
            "  \"timestamp\": \"%s\",\n" +
            "  \"uptime_ms\": %d,\n" +
            "  \"memory\": {\n" +
            "    \"used_mb\": %.2f,\n" +
            "    \"total_mb\": %.2f,\n" +
            "    \"max_mb\": %.2f,\n" +
            "    \"usage_percent\": %.2f\n" +
            "  },\n" +
            "  \"system\": {\n" +
            "    \"os_name\": \"%s\",\n" +
            "    \"os_version\": \"%s\",\n" +
            "    \"os_description\": \"%s\",\n" +
            "    \"os_arch\": \"%s\",\n" +
            "    \"jvm_name\": \"%s\",\n" +
            "    \"jvm_version\": \"%s\",\n" +
            "    \"jvm_vendor\": \"%s\",\n" +
            "    \"cpu_load_avg\": %s\n" +
            "  }\n" +
            "}",
            ServerInfoServer.HOSTNAME,
            timestamp,
            System.currentTimeMillis() - ServerInfoServer.START_TIME,
            usedMemory / (1024.0 * 1024.0),
            totalMemory / (1024.0 * 1024.0),
            maxMemory / (1024.0 * 1024.0),
            (usedMemory * 100.0) / totalMemory,
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            osDescription,
            System.getProperty("os.arch"),
            runtimeBean.getVmName(),
            runtimeBean.getVmVersion(),
            jvmVendor,
            cpuLoad >= 0 ? String.format("%.2f", cpuLoad) : "null"
        );
        
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        HttpUtils.sendResponse(exchange, 200, healthResponse);
    }
    
    /**
     * Get OS description similar to lsb_release -a Description
     */
    private String getOSDescription() {
        try {
            // First try to get from /etc/os-release
            ProcessBuilder processBuilder = new ProcessBuilder("cat", "/etc/os-release");
            Process process = processBuilder.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("PRETTY_NAME=")) {
                        // Extract value between quotes
                        String prettyName = line.substring("PRETTY_NAME=".length());
                        if (prettyName.startsWith("\"") && prettyName.endsWith("\"")) {
                            return prettyName.substring(1, prettyName.length() - 1);
                        }
                        return prettyName;
                    }
                }
            }
            
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            // Ignore and fall back
        }
        
        try {
            // Try lsb_release command as fallback
            ProcessBuilder processBuilder = new ProcessBuilder("lsb_release", "-d", "-s");
            Process process = processBuilder.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String description = reader.readLine();
                if (description != null && !description.trim().isEmpty()) {
                    // Remove quotes if present
                    description = description.trim();
                    if (description.startsWith("\"") && description.endsWith("\"")) {
                        return description.substring(1, description.length() - 1);
                    }
                    return description;
                }
            }
            
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            // Ignore and fall back
        }
        
        // Final fallback - construct from system properties
        return System.getProperty("os.name") + " " + System.getProperty("os.version");
    }
}
