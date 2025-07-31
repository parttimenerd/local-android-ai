package me.bechberger.k3s.handlers;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.bechberger.k3s.util.HttpUtils;

/**
 * System information API handler - returns detailed system info as JSON
 * This handler delegates to HealthHandler for consistency
 */
public class SystemInfoHandler implements HttpHandler {
    
    private final HealthHandler healthHandler = new HealthHandler();
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Only handle GET requests
        if (!HttpUtils.isGetRequest(exchange)) {
            HttpUtils.sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        
        // Use the same logic as health endpoint for consistency
        healthHandler.handle(exchange);
    }
}
