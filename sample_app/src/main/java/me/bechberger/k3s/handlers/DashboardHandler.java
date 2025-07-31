package me.bechberger.k3s.handlers;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.bechberger.k3s.util.HttpUtils;

/**
 * SAP UI5 Dashboard handler - serves HTML dashboard from resources
 * Provides a modern web interface for system monitoring
 */
public class DashboardHandler implements HttpHandler {
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Only handle GET requests
        if (!HttpUtils.isGetRequest(exchange)) {
            HttpUtils.sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        
        // Check if test mode is requested
        String query = exchange.getRequestURI().getQuery();
        boolean testMode = query != null && query.contains("test=true");
        boolean fallbackTest = query != null && query.contains("fallback=true");
        
        String htmlFile = "/static/dashboard.html";
        
        String htmlContent = HttpUtils.loadResourceAsString(htmlFile);
        
        if (htmlContent == null) {
            String errorMsg = "Dashboard template not found";
            HttpUtils.sendResponse(exchange, 500, errorMsg);
            return;
        }
        
        // Set response headers
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        
        // Send response
        HttpUtils.sendResponse(exchange, 200, htmlContent);
        
        // Log request
        String timestamp = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();
        System.out.printf("[%s] %s %s - %s%n", 
            timestamp, 
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            clientIP
        );
    }
}
