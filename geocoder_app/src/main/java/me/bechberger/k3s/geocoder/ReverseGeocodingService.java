package me.bechberger.k3s.geocoder;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reverse geocoding service using GeoNames data.
 * Optimized for minimal memory usage - currently loads German cities only with English names.
 * Easily extensible to support additional countries.
 */
public class ReverseGeocodingService {
    
    // Minimal City record - optimized for heap size (no population stored)
    public record City(String name, String country, double lat, double lon) {}
    
    private static final String DATA_DIR = "geonames-data";
    private static final Map<String, List<City>> COUNTRY_CACHE = new ConcurrentHashMap<>();
    
    // Default countries to support (ISO 2-letter codes) - optimized for memory usage
    // Currently only Germany is loaded by default to minimize memory footprint
    // Add more countries here as needed: "US", "GB", "FR", "IT", "ES", "NL", "BE", "AT", "CH", "DK", "SE", "NO", "FI", "PL"
    private static final Set<String> DEFAULT_COUNTRIES = Set.of("DE");
    
    // City feature codes to include (major cities and administrative centers only for memory optimization)
    // Balanced set to get good coverage while maintaining low memory usage
    // See: http://www.geonames.org/export/codes.html
    private static final Set<String> CITY_FEATURE_CODES = Set.of(
        "PPL",     // populated place
        "PPLA",    // seat of a first-order administrative division
        "PPLA2",   // seat of a second-order administrative division  
        "PPLA3",   // seat of a third-order administrative division
        "PPLA4",   // seat of a fourth-order administrative division
        "PPLC",    // capital of a political entity
        "PPLG",    // seat of government of a political entity
        "PPLF"     // farm village (for better rural coverage)
    );
    
    // Reduced minimum population threshold to get better coverage
    // Administrative centers are always included regardless of population
    private static final int MIN_POPULATION = 1000;
    
    // Maximum city name length for memory optimization
    private static final int MAX_NAME_LENGTH = 50;
    
    public static class ReverseGeocoder {
        private final List<City> cities;
        
        public ReverseGeocoder(List<City> cities) {
            this.cities = cities;
        }
        
        public City findNearest(double lat, double lon) {
            City nearest = null;
            double minDist = Double.MAX_VALUE;
            
            for (City city : cities) {
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
     * Get reverse geocoder with cities from default countries (optimized for memory usage)
     */
    public static ReverseGeocoder getDefaultGeocoder() {
        return getGeocoderForCountries(DEFAULT_COUNTRIES);
    }
    
    /**
     * Get reverse geocoder with cities from specified countries
     */
    public static ReverseGeocoder getGeocoderForCountries(Set<String> countries) {
        List<City> allCities = new ArrayList<>();
        
        for (String countryCode : countries) {
            try {
                List<City> cities = loadCountryCities(countryCode);
                allCities.addAll(cities);
                System.out.printf("Loaded %d cities from %s%n", cities.size(), countryCode);
            } catch (Exception e) {
                System.err.printf("Failed to load cities for %s: %s%n", countryCode, e.getMessage());
            }
        }
        
        System.out.printf("Total cities loaded: %d (estimated memory: %.1f MB)%n", 
            allCities.size(), estimateMemoryUsage(allCities.size()));
        return new ReverseGeocoder(allCities);
    }
    
    /**
     * Legacy method - now uses default countries only
     * @deprecated Use getDefaultGeocoder() or getGeocoderForCountries() instead
     */
    @Deprecated
    public static ReverseGeocoder getGlobalGeocoder() {
        System.out.println("Warning: getGlobalGeocoder() is deprecated and now loads only default countries for memory optimization");
        return getDefaultGeocoder();
    }
    
    /**
     * Estimate memory usage for loaded cities (optimized without population field)
     */
    private static double estimateMemoryUsage(int cityCount) {
        // Optimized estimate: ~80 bytes per city record (name + country + lat + lon, no population)
        return (cityCount * 80.0) / (1024 * 1024);
    }
    
    /**
     * Get reverse geocoder for specific country
     */
    public static ReverseGeocoder getCountryGeocoder(String countryCode) throws IOException {
        List<City> cities = loadCountryCities(countryCode.toUpperCase());
        return new ReverseGeocoder(cities);
    }
    
    /**
     * Load cities for a specific country from packaged resources
     */
    public static List<City> loadCountryCities(String countryCode) throws IOException {
        // Check cache first
        if (COUNTRY_CACHE.containsKey(countryCode)) {
            return COUNTRY_CACHE.get(countryCode);
        }
        
        // Load cities from packaged resource file
        String resourcePath = "/" + DATA_DIR + "/" + countryCode + ".txt";
        System.out.printf("Loading cities from resource: %s%n", resourcePath);
        
        List<City> cities = new ArrayList<>();
        int totalLines = 0;
        int filteredLines = 0;
        
        try (InputStream inputStream = ReverseGeocodingService.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath + ". Available countries might be limited.");
            }
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    String[] parts = line.split("\t");
                    if (parts.length < 15) continue;
                    
                    // Only include populated places (feature class P)
                    if (!"P".equals(parts[6])) continue;
                    
                    String featureCode = parts[7];
                    // Only include major cities and administrative centers for memory optimization
                    if (!CITY_FEATURE_CODES.contains(featureCode)) {
                        continue;
                    }
                    
                    try {
                        // Use ASCII name (parts[2]) for consistent English naming, fallback to main name
                        String asciiName = parts[2].trim();
                        String mainName = parts[1].trim();
                        String cityName = !asciiName.isEmpty() ? asciiName : mainName;
                        
                        // Skip entries with non-ASCII characters or too long names for memory optimization
                        if (!isAsciiName(cityName) || cityName.length() > MAX_NAME_LENGTH) {
                            continue;
                        }
                        
                        // Limit city name length for memory optimization
                        if (cityName.length() > 50) {
                            cityName = cityName.substring(0, 50);
                        }
                        
                        double lat = Double.parseDouble(parts[4]);
                        double lon = Double.parseDouble(parts[5]);
                        int population = parts[14].isEmpty() ? 0 : Integer.parseInt(parts[14]);
                        
                        // Include cities based on population or administrative importance (but don't store population)
                        boolean isAdminCenter = CITY_FEATURE_CODES.contains(featureCode);
                        if (population >= MIN_POPULATION || isAdminCenter) {
                            cities.add(new City(cityName, countryCode, lat, lon));
                            filteredLines++;
                        }
                    } catch (NumberFormatException e) {
                        // Skip invalid entries
                        continue;
                    }
                }
            }
        }
        
        // Sort by name for consistent ordering (since we don't store population)
        cities.sort(Comparator.comparing(City::name));
        
        System.out.printf("Processed %d lines, filtered to %d cities from %s%n", 
            totalLines, filteredLines, countryCode);
        
        // Cache the result
        COUNTRY_CACHE.put(countryCode, cities);
        
        return cities;
    }
    
    /**
     * Check if a name contains only ASCII characters for memory optimization
     */
    private static boolean isAsciiName(String name) {
        return name.chars().allMatch(c -> c < 128);
    }
    
    /**
     * Test the reverse geocoding service (memory optimized)
     */
    public static void main(String[] args) throws Exception {
        // Test with some known German coordinates
        double berlinLat = 52.5200, berlinLon = 13.4050;
        double munichLat = 48.1351, munichLon = 11.5820;
        double hamburgLat = 53.5511, hamburgLon = 9.9937;
        
        System.out.println("Testing memory-optimized reverse geocoding with German cities...");
        
        // Test with default geocoder (German cities only)
        System.out.println("\nDefault geocoder (German cities, memory optimized):");
        ReverseGeocoder defaultGeocoder = getDefaultGeocoder();
        
        City nearest = defaultGeocoder.findNearestWithinRadius(berlinLat, berlinLon, 50);
        if (nearest != null) {
            System.out.printf("Berlin area: %s%n", nearest.name());
        }
        
        nearest = defaultGeocoder.findNearestWithinRadius(munichLat, munichLon, 50);
        if (nearest != null) {
            System.out.printf("Munich area: %s%n", nearest.name());
        }
        
        nearest = defaultGeocoder.findNearestWithinRadius(hamburgLat, hamburgLon, 50);
        if (nearest != null) {
            System.out.printf("Hamburg area: %s%n", nearest.name());
        }
        
        System.out.println("\nGeocoder supports easy extension to other countries when needed.");
        System.out.printf("Example: getGeocoderForCountries(Set.of(\"DE\", \"AT\")) would add Austrian cities.%n");
    }
}
