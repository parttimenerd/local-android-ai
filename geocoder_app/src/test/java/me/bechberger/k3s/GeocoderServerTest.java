package me.bechberger.k3s;

import me.bechberger.k3s.geocoder.GeocoderServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the GeocoderServer HTTP endpoints
 * Tests focus on German and French cities with parametric test approach
 */
public class GeocoderServerTest {

    private static int port;
    private static HttpClient httpClient;
    private static String baseUrl;

    /**
     * Test data for German and French cities
     */
    static Stream<Arguments> germanAndFrenchCities() {
        return Stream.of(
            // German cities
            Arguments.of("Berlin", 52.5200, 13.4050, new String[]{"berlin", "germany", "de"}),
            Arguments.of("Munich", 48.1351, 11.5820, new String[]{"munich", "münchen", "germany", "de"}),
            Arguments.of("Hamburg", 53.5511, 9.9937, new String[]{"hamburg", "germany", "de"}),
            Arguments.of("Cologne", 50.9375, 6.9603, new String[]{"cologne", "köln", "germany", "de"}),
            Arguments.of("Frankfurt", 50.1109, 8.6821, new String[]{"frankfurt", "germany", "de"}),
            
            // French cities  
            Arguments.of("Paris", 48.8566, 2.3522, new String[]{"paris", "france", "fr"}),
            Arguments.of("Lyon", 45.7640, 4.8357, new String[]{"lyon", "france", "fr"}),
            Arguments.of("Marseille", 43.2965, 5.3698, new String[]{"marseille", "france", "fr"}),
            Arguments.of("Nice", 43.7102, 7.2620, new String[]{"nice", "france", "fr"}),
            Arguments.of("Strasbourg", 48.5734, 7.7521, new String[]{"strasbourg", "france", "fr"})
        );
    }

    /**
     * Test data for different geocoding methods
     */
    static Stream<String> geocodingMethods() {
        return Stream.of("geonames");
    }

    @BeforeAll
    static void setUpAll() throws IOException {
        System.out.println("Starting GeocoderServer for all tests...");
        
        // Start the actual GeocoderServer in a separate thread
        Thread serverThread = new Thread(() -> {
            try {
                GeocoderServer.main(new String[]{});
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        
        // Wait a moment for the server to start and geocoder to initialize
        try {
            Thread.sleep(5000); // Give it more time to start up
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        port = 8090; // GeocoderServer runs on port 8090
        
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        baseUrl = "http://localhost:" + port;
        
        System.out.println("GeocoderServer started on port " + port + " for all tests");
    }

    @AfterAll
    static void tearDownAll() {
        // The server runs in a daemon thread and will be cleaned up automatically
        System.out.println("All tests completed");
    }

    @Test
    void testHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"healthy\""));
        assertTrue(response.body().contains("\"service\":\"reverse-geocoder\""));
        assertTrue(response.body().contains("\"timestamp\""));
        
        // Verify JSON structure
        String body = response.body();
        assertTrue(body.startsWith("{") && body.endsWith("}"));
        System.out.println("Health endpoint response: " + body);
    }

    @ParameterizedTest(name = "Test reverse geocoding for {0} ({1}, {2})")
    @MethodSource("germanAndFrenchCities")
    void testReverseGeocodeEndpoint_Cities(String cityName, double latitude, double longitude, String[] expectedTerms) throws Exception {
        String url = baseUrl + "/api/reverse-geocode?lat=" + latitude + "&lon=" + longitude + "&method=geonames";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        
        // Verify JSON structure
        assertTrue(body.contains("\"location\""), "Response should contain location field");
        assertTrue(body.contains("\"method\""), "Response should contain method field");
        assertTrue(body.contains("\"coordinates\""), "Response should contain coordinates field");
        
        // Verify coordinates are correct in response
        assertTrue(body.contains(String.valueOf(latitude)), "Response should contain latitude");
        assertTrue(body.contains(String.valueOf(longitude)), "Response should contain longitude");
        
        // Check that at least one expected term is found in the response (case-insensitive)
        String bodyLower = body.toLowerCase();
        boolean foundExpectedTerm = false;
        String foundTerm = "";
        
        for (String term : expectedTerms) {
            if (bodyLower.contains(term.toLowerCase())) {
                foundExpectedTerm = true;
                foundTerm = term;
                break;
            }
        }
        
        assertTrue(foundExpectedTerm, 
            "Response should contain one of the expected terms " + java.util.Arrays.toString(expectedTerms) + 
            " for " + cityName + ". Actual response: " + body);
        
        System.out.println(cityName + " geocoding response (found: " + foundTerm + "): " + body);
    }

    @ParameterizedTest(name = "Test geocoding method: {0}")
    @MethodSource("geocodingMethods") 
    void testReverseGeocodeEndpoint_Methods(String method) throws Exception {
        // Test with Berlin coordinates for each method
        double latitude = 52.5200;
        double longitude = 13.4050;
        String url = baseUrl + "/api/reverse-geocode?lat=" + latitude + "&lon=" + longitude + "&method=" + method;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        
        // Verify JSON structure
        assertTrue(body.contains("\"location\""), "Response should contain location field");
        assertTrue(body.contains("\"method\""), "Response should contain method field");
        assertTrue(body.contains("\"coordinates\""), "Response should contain coordinates field");
        
        // Should always be geonames method
        assertTrue(body.contains("\"method\":\"geonames\""),
            "Response should indicate geonames method: " + method);
        
        System.out.println("Method " + method + " response: " + body);
    }

    @Test
    void testReverseGeocodeEndpoint_MissingParameters() throws Exception {
        // Test missing latitude parameter
        String url = baseUrl + "/api/reverse-geocode?lon=2.3522";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("error"));
        
        System.out.println("Missing latitude parameter response: " + response.body());
    }

    @Test
    void testReverseGeocodeEndpoint_MissingLongitude() throws Exception {
        // Test missing longitude parameter
        String url = baseUrl + "/api/reverse-geocode?lat=48.8566";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("error"));
        
        System.out.println("Missing longitude parameter response: " + response.body());
    }

    @ParameterizedTest(name = "Test invalid coordinates: lat={0}, lon={1}")
    @ValueSource(strings = {"999,999", "-999,-999", "abc,def", "91,181"})
    void testReverseGeocodeEndpoint_InvalidCoordinates(String coordinates) throws Exception {
        String[] coords = coordinates.split(",");
        String url = baseUrl + "/api/reverse-geocode?lat=" + coords[0] + "&lon=" + coords[1] + "&method=geonames";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Invalid coordinates should either return 400 (bad request) or 200 with appropriate response
        assertTrue(response.statusCode() == 400 || response.statusCode() == 200);
        
        if (response.statusCode() == 200) {
            String body = response.body();
            assertTrue(body.contains("\"coordinates\""), "Response should contain coordinates field");
        }
        
        System.out.println("Invalid coordinates " + coordinates + " response: " + response.statusCode() + " - " + response.body());
    }

    @Test
    void testReverseGeocodeEndpoint_DefaultMethod() throws Exception {
        // Test without specifying method (should default to geonames) - using Paris coordinates
        String url = baseUrl + "/api/reverse-geocode?lat=48.8566&lon=2.3522";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        
        // Verify JSON structure
        assertTrue(body.contains("\"location\""));
        assertTrue(body.contains("\"method\""));
        assertTrue(body.contains("\"coordinates\""));
        
        // Default should be geonames
        assertTrue(body.contains("\"method\":\"geonames\""),
            "Default method should be geonames");
        
        System.out.println("Default method response: " + body);
    }

    @Test
    void testNonExistentEndpoint() throws Exception {
        String url = baseUrl + "/api/nonexistent";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        
        System.out.println("Non-existent endpoint response: " + response.statusCode());
    }
}
