package me.bechberger.k3s.geocoder;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.*;

/**
 * Reverse geocoding service using pre-packaged GeoNames data.
 * Uses GeoNames city data cached at build time for fast startup and reliable operation.
 */
public class ReverseGeocodingService {
    
    public record City(String name, String country, double lat, double lon, int population) {}
    
    private static final String RESOURCE_PATH_PREFIX = "/geonames-data/";
    private static final Map<String, List<City>> COUNTRY_CACHE = new ConcurrentHashMap<>();
    
    // Countries to support (ISO 2-letter codes) - must match prepare-geonames-data.sh
    private static final Set<String> SUPPORTED_COUNTRIES = Set.of(
        "DE", "US", "GB", "FR", "IT", "ES", "NL", "BE", "AT", "CH", "DK", "SE", "NO", "FI", "PL"
    );
    
    // Priority countries for fast startup (major countries loaded first)
    private static final Set<String> PRIORITY_COUNTRIES = Set.of(
        "DE", "GB", "FR", "IT", "ES", "US"
    );
    
    // Minimum population for cities (higher = faster loading)
    private static final int MIN_POPULATION_FAST_LOAD = 5000;
    private static final int MIN_POPULATION_FULL_LOAD = 1000;
    
    public static class ReverseGeocoder {
        private final List<City> cities;
        
        public ReverseGeocoder(List<City> cities) {
            // Use synchronized list for thread-safe background loading
            this.cities = Collections.synchronizedList(new ArrayList<>(cities));
        }
        
        public City findNearest(double lat, double lon) {
            City nearest = null;
            double minDist = Double.MAX_VALUE;
            
            // Create a snapshot to avoid concurrent modification
            List<City> snapshot;
            synchronized (cities) {
                snapshot = new ArrayList<>(cities);
            }
            
            for (City city : snapshot) {
                double dist = haversine(lat, lon, city.lat(), city.lon());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = city;
                }
            }
            return nearest;
        }
        
        public City findNearestWithinRadius(double lat, double lon, double maxDistanceKm) {
            City nearest = findNearest(lat, lon);
            if (nearest != null) {
                double distance = haversine(lat, lon, nearest.lat(), nearest.lon());
                if (distance <= maxDistanceKm) {
                    return nearest;
                }
            }
            return null;
        }
        
        public int getCityCount() {
            synchronized (cities) {
                return cities.size();
            }
        }
        
        private static double haversine(double lat1, double lon1, double lat2, double lon2) {
            double R = 6371; // Earth radius in km
            double dLat = Math.toRadians(lat2 - lat1);
            double dLon = Math.toRadians(lon2 - lon1);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                     + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                     * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }
    }
    
    /**
     * Get reverse geocoder with fast startup - loads only major cities initially
     */
    public static ReverseGeocoder getFastGeocoder() {
        long startTime = System.currentTimeMillis();
        System.out.println("🚀 Starting fast geocoder initialization...");
        
        // Use parallel streams for concurrent loading
        List<City> majorCities = PRIORITY_COUNTRIES.parallelStream()
            .flatMap(countryCode -> {
                try {
                    List<City> cities = loadCountryCitiesFiltered(countryCode, MIN_POPULATION_FAST_LOAD);
                    System.out.println("✓ Loaded " + cities.size() + " major cities from " + countryCode);
                    return cities.stream();
                } catch (Exception e) {
                    System.err.println("✗ Failed to load major cities for " + countryCode + ": " + e.getMessage());
                    return java.util.stream.Stream.empty();
                }
            })
            .collect(Collectors.toList());
        
        long endTime = System.currentTimeMillis();
        System.out.println("🎯 Fast geocoder ready with " + majorCities.size() + 
                          " major cities in " + (endTime - startTime) + "ms");
        
        return new ReverseGeocoder(majorCities);
    }
    
    /**
     * Get reverse geocoder with fast startup - loads Germany and France first, then others in background
     */
    public static ReverseGeocoder getGlobalGeocoder() {
        long startTime = System.currentTimeMillis();
        System.out.println("🚀 Starting fast geocoder with Germany and France first...");
        
        List<City> initialCities = new ArrayList<>();
        
        // Load Germany and France first for immediate functionality
        String[] priorityCountries = {"DE", "FR"};
        for (String countryCode : priorityCountries) {
            try {
                List<City> cities = loadCountryCities(countryCode);
                initialCities.addAll(cities);
                System.out.println("✅ Loaded " + cities.size() + " cities from " + countryCode);
            } catch (Exception e) {
                System.err.println("❌ Failed to load cities for " + countryCode + ": " + e.getMessage());
            }
        }
        
        long initialLoadTime = System.currentTimeMillis() - startTime;
        System.out.println("🎯 Initial load completed in " + initialLoadTime + "ms");
        
        // Create geocoder with initial German and French data
        ReverseGeocoder geocoder = new ReverseGeocoder(initialCities);
        
        // Load other countries in background
        CompletableFuture.runAsync(() -> {
            System.out.println("📥 Loading additional countries in background...");
            ExecutorService executor = Executors.newFixedThreadPool(4);
            
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (String countryCode : SUPPORTED_COUNTRIES) {
                // Skip already loaded countries
                if ("DE".equals(countryCode) || "FR".equals(countryCode)) continue;
                
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        List<City> cities = loadCountryCities(countryCode);
                        synchronized (geocoder.cities) {
                            geocoder.cities.addAll(cities);
                        }
                        System.out.println("✅ Background loaded " + cities.size() + " cities from " + countryCode);
                    } catch (Exception e) {
                        System.err.println("❌ Background failed to load cities for " + countryCode + ": " + e.getMessage());
                    }
                }, executor);
                
                futures.add(future);
            }
            
            // Wait for all background loading to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    executor.shutdown();
                    long totalTime = System.currentTimeMillis() - startTime;
                    System.out.println("� All geocoding data loaded! Total cities: " + geocoder.cities.size() + 
                        " in " + totalTime + "ms");
                });
        });
        
        System.out.println("🚀 Geocoder ready with " + initialCities.size() + " cities (DE + FR) in " + 
            initialLoadTime + "ms");
        return geocoder;
    }
    
    /**
     * Get reverse geocoder for specific country
     */
    public static ReverseGeocoder getCountryGeocoder(String countryCode) throws IOException {
        List<City> cities = loadCountryCities(countryCode.toUpperCase());
        return new ReverseGeocoder(cities);
    }
    
    /**
     * Load cities for a specific country from pre-packaged resources with filtering
     */
    public static List<City> loadCountryCitiesFiltered(String countryCode, int minPopulation) throws IOException {
        // Check cache first
        String cacheKey = countryCode + "_" + minPopulation;
        if (COUNTRY_CACHE.containsKey(cacheKey)) {
            return COUNTRY_CACHE.get(cacheKey);
        }
        
        // Load from pre-packaged resources
        String resourcePath = RESOURCE_PATH_PREFIX + countryCode + ".txt";
        
        InputStream resourceStream = ReverseGeocodingService.class.getResourceAsStream(resourcePath);
        if (resourceStream == null) {
            throw new IOException("GeoNames data not found for country " + countryCode + 
                                " at resource path: " + resourcePath);
        }
        
        List<City> cities = new ArrayList<>();
        int totalLines = 0;
        int processedLines = 0;
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceStream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                
                // Fast pre-filter before parsing
                if (!line.contains("\tP\t")) continue; // Not a populated place
                
                String[] parts = line.split("\t");
                if (parts.length < 15) continue;
                
                String featureCode = parts[7];
                // Quick filter for major city types
                if (!Set.of("PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLC").contains(featureCode)) {
                    continue;
                }
                
                try {
                    // Parse population early to filter
                    int population = parts[14].isEmpty() ? 0 : Integer.parseInt(parts[14]);
                    
                    // Apply population filter
                    if (population < minPopulation && !Set.of("PPLA", "PPLA2", "PPLC").contains(featureCode)) {
                        continue;
                    }
                    
                    String name = parts[1];
                    double lat = Double.parseDouble(parts[4]);
                    double lon = Double.parseDouble(parts[5]);
                    
                    cities.add(new City(name, countryCode, lat, lon, population));
                    processedLines++;
                    
                } catch (NumberFormatException e) {
                    // Skip invalid entries
                    continue;
                }
            }
        }
        
        // Sort by population (largest first) to prefer major cities
        cities.sort((a, b) -> Integer.compare(b.population(), a.population()));
        
        System.out.println("Loaded " + cities.size() + " cities from " + countryCode + 
                          " (processed " + processedLines + "/" + totalLines + " lines, min pop: " + minPopulation + ")");
        
        // Cache the result
        COUNTRY_CACHE.put(cacheKey, cities);
        
        return cities;
    }
    
    /**
     * Load cities for a specific country from pre-packaged resources
     */
    public static List<City> loadCountryCities(String countryCode) throws IOException {
        return loadCountryCitiesFiltered(countryCode, MIN_POPULATION_FULL_LOAD);
    }
    
    /**
     * Test the reverse geocoding service
     */
    public static void main(String[] args) throws Exception {
        // Test with some known coordinates
        double berlinLat = 52.5200, berlinLon = 13.4050;
        double londonLat = 51.5074, londonLon = -0.1278;
        double nyLat = 40.7128, nyLon = -74.0060;
        
        System.out.println("Testing reverse geocoding with local GeoNames data...");
        
        // Test with local data
        System.out.println("\nLocal data:");
        ReverseGeocoder deGeocoder = getCountryGeocoder("DE");
        City nearest = deGeocoder.findNearestWithinRadius(berlinLat, berlinLon, 50);
        if (nearest != null) {
            System.out.println("Berlin area: " + nearest.name() + " (pop: " + nearest.population() + ")");
        }
        
        ReverseGeocoder gbGeocoder = getCountryGeocoder("GB");
        nearest = gbGeocoder.findNearestWithinRadius(londonLat, londonLon, 50);
        if (nearest != null) {
            System.out.println("London area: " + nearest.name() + " (pop: " + nearest.population() + ")");
        }
        
        // Test with global geocoder
        System.out.println("\nGlobal geocoder:");
        ReverseGeocoder globalGeocoder = getGlobalGeocoder();
        
        nearest = globalGeocoder.findNearestWithinRadius(berlinLat, berlinLon, 50);
        if (nearest != null) {
            System.out.println("Berlin: " + nearest.name() + ", " + nearest.country());
        }
        
        nearest = globalGeocoder.findNearestWithinRadius(londonLat, londonLon, 50);
        if (nearest != null) {
            System.out.println("London: " + nearest.name() + ", " + nearest.country());
        }
    }
}
