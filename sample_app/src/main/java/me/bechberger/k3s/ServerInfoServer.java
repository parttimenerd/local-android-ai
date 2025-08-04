package me.bechberger.k3s;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;
import me.bechberger.k3s.handlers.RootHandler;
import me.bechberger.k3s.handlers.HealthHandler;
import me.bechberger.k3s.handlers.DashboardHandler;
import me.bechberger.k3s.handlers.SystemInfoHandler;
import me.bechberger.k3s.handlers.PhoneDataHandler;
import me.bechberger.k3s.handlers.CameraCaptureHandler;
import me.bechberger.k3s.handlers.ClusterLocationHandler;

/**
 * Simple HTTP server that returns hostname and current timestamp.
 * Designed for Kubernetes demonstration on resource-constrained devices.
 * 
 * Port Configuration:
 * - Default port: 8080
 * - Configurable via PORT environment variable
 * - Note: Android K3s Phone Server app uses port 8005 (separate application)
 * 
 * Serves a SAP UI5 health monitoring dashboard at /dashboard endpoint.
 * 
 * Available endpoints:
 * - GET /           - Root handler with basic info
 * - GET /health     - Health check endpoint  
 * - GET /dashboard  - SAP UI5 monitoring dashboard
 * - GET /api/system - System information API
 * - GET /api/phone  - Android phone location/orientation data (if available)
 */
public class ServerInfoServer {
    
    private static final int DEFAULT_PORT = 8080;
    public static final String HOSTNAME = getHostname();
    
    // Track start time for uptime calculation
    public static final long START_TIME = System.currentTimeMillis();
    
    /**
     * Get the system hostname by executing the hostname command.
     * Falls back to environment variable or default if command fails.
     */
    private static String getHostname() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("hostname");
            Process process = processBuilder.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String hostname = reader.readLine();
                if (hostname != null && !hostname.trim().isEmpty()) {
                    return hostname.trim();
                }
            }
            
            // Wait for process to complete
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("hostname command failed with exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to execute hostname command: " + e.getMessage());
        }
        
        // Fallback to environment variable or default
        return System.getenv().getOrDefault("HOSTNAME", "unknown-host");
    }
    
    public static void main(String[] args) throws IOException {
        // Determine port to use
        // Default: 8080, but can be overridden via PORT environment variable
        // This allows flexible deployment in containerized environments like Kubernetes
        int port = DEFAULT_PORT;
        String portProperty = System.getenv("PORT"); // Read PORT environment variable
        if (portProperty != null) {
            try {
                port = Integer.parseInt(portProperty);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + portProperty + ". Using default port " + DEFAULT_PORT);
            }
        }
        
        // Create HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Create thread pool with minimal threads for phone resource constraints
        server.setExecutor(Executors.newFixedThreadPool(2));
        
        // Add handlers
        server.createContext("/", new RootHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/dashboard", new DashboardHandler());
        server.createContext("/api/system", new SystemInfoHandler());
        server.createContext("/api/phone", new PhoneDataHandler());
        server.createContext("/api/phone/capture", new CameraCaptureHandler());
        server.createContext("/api/cluster/locations", new ClusterLocationHandler());
        
        // Start server
        server.start();
        
        System.out.println("ServerInfoServer started on port " + port);
        System.out.println("Hostname: " + HOSTNAME);
        System.out.println("Access at: http://localhost:" + port);
        System.out.println("Dashboard at: http://localhost:" + port + "/dashboard");
        System.out.println("Phone data API: http://localhost:" + port + "/api/phone");
        System.out.println("Cluster locations API: http://localhost:" + port + "/api/cluster/locations");
        System.out.println("Note: Phone integration is optional - enhanced features available when Android K3s Phone Server runs on port 8005");
        
        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            server.stop(2);
        }));
    }
}
