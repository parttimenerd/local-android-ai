package me.bechberger.k3s.handlers;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.bechberger.k3s.ServerInfoServer;
import me.bechberger.k3s.util.HttpUtils;

/**
 * Main request handler - returns hostname and timestamp as JSON
 */
public class RootHandler implements HttpHandler {
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Only handle GET requests
        if (!HttpUtils.isGetRequest(exchange)) {
            HttpUtils.sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        
        // Generate current timestamp
        String timestamp = Instant.now()
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_INSTANT);
        
        // Create JSON response
        String jsonResponse = String.format(
            "{\n" +
            "  \"hostname\": \"%s\",\n" +
            "  \"timestamp\": \"%s\",\n" +
            "  \"message\": \"Hello from K3s on Phone!\",\n" +
            "  \"uptime_ms\": %d\n" +
            "}",
            ServerInfoServer.HOSTNAME,
            timestamp,
            System.currentTimeMillis() - ServerInfoServer.START_TIME
        );
        
        // Set response headers
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        
        // Send response
        HttpUtils.sendResponse(exchange, 200, jsonResponse);
        
        // Log request
        String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();
        System.out.printf("[%s] %s %s - %s%n", 
            timestamp, 
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            clientIP
        );
    }
}
