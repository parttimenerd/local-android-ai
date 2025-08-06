package me.bechberger.k3s.geocoder;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Reverse geocoding service using GeoNames data.
 * Downloads and caches city data for multiple countries to resolve coordinates to city names.
 */
public class ReverseGeocodingService {
    
    public record City(String name, String country, double lat, double lon, int population) {}
    
    private static final String GEONAMES_BASE_URL = "https://download.geonames.org/export/dump/";
    private static final String DATA_DIR = "geonames-data";
    private static final Map<String, List<City>> COUNTRY_CACHE = new ConcurrentHashMap<>();
    
    // Countries to support (ISO 2-letter codes)
    private static final Set<String> SUPPORTED_COUNTRIES = Set.of(
        "DE", "US", "GB", "FR", "IT", "ES", "NL", "BE", "AT", "CH", "DK", "SE", "NO", "FI", "PL"
    );
    
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
     * Get reverse geocoder with cities from multiple countries
     */
    public static ReverseGeocoder getGlobalGeocoder() {
        List<City> allCities = new ArrayList<>();
        
        for (String countryCode : SUPPORTED_COUNTRIES) {
            try {
                List<City> cities = loadCountryCities(countryCode);
                allCities.addAll(cities);
                System.out.println("Loaded " + cities.size() + " cities from " + countryCode);
            } catch (Exception e) {
                System.err.println("Failed to load cities for " + countryCode + ": " + e.getMessage());
            }
        }
        
        System.out.println("Total cities loaded: " + allCities.size());
        return new ReverseGeocoder(allCities);
    }
    
    /**
     * Get reverse geocoder for specific country
     */
    public static ReverseGeocoder getCountryGeocoder(String countryCode) throws IOException {
        List<City> cities = loadCountryCities(countryCode.toUpperCase());
        return new ReverseGeocoder(cities);
    }
    
    /**
     * Load cities for a specific country
     */
    public static List<City> loadCountryCities(String countryCode) throws IOException {
        // Check cache first
        if (COUNTRY_CACHE.containsKey(countryCode)) {
            return COUNTRY_CACHE.get(countryCode);
        }
        
        // Create data directory
        Path dataDir = Path.of(DATA_DIR);
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }
        
        String zipFile = dataDir.resolve(countryCode + ".zip").toString();
        String txtFile = dataDir.resolve(countryCode + ".txt").toString();
        
        // Download if not already there
        if (!Files.exists(Path.of(zipFile))) {
            System.out.println("Downloading GeoNames " + countryCode + ".zip...");
            try {
                String url = GEONAMES_BASE_URL + countryCode + ".zip";
                try (InputStream in = URI.create(url).toURL().openStream()) {
                    Files.copy(in, Path.of(zipFile), StandardCopyOption.REPLACE_EXISTING);
                }
                System.out.println("Downloaded " + countryCode + ".zip");
            } catch (Exception e) {
                System.err.println("Failed to download " + countryCode + ".zip: " + e.getMessage());
                throw new IOException("Could not download GeoNames data for " + countryCode, e);
            }
        }
        
        // Unzip if needed
        if (!Files.exists(Path.of(txtFile))) {
            System.out.println("Extracting " + countryCode + ".txt...");
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().equals(countryCode + ".txt")) {
                        Files.copy(zis, Path.of(txtFile), StandardCopyOption.REPLACE_EXISTING);
                        break;
                    }
                }
            }
        }
        
        // Load cities from file
        System.out.println("Loading cities from " + countryCode + ".txt...");
        List<City> cities = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(txtFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length < 15) continue;
                
                // Only include populated places
                if (!parts[6].equals("P")) continue;
                
                String featureCode = parts[7];
                // Include various types of populated places
                if (!Set.of("PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLC", "PPLF", "PPLG", "PPLL", "PPLR", "PPLS", "STLMT").contains(featureCode)) {
                    continue;
                }
                
                try {
                    String name = parts[1];
                    double lat = Double.parseDouble(parts[4]);
                    double lon = Double.parseDouble(parts[5]);
                    int population = parts[14].isEmpty() ? 0 : Integer.parseInt(parts[14]);
                    
                    // Only include places with some population or major cities
                    if (population > 1000 || Set.of("PPLA", "PPLA2", "PPLC").contains(featureCode)) {
                        cities.add(new City(name, countryCode, lat, lon, population));
                    }
                } catch (NumberFormatException e) {
                    // Skip invalid entries
                    continue;
                }
            }
        }
        
        // Sort by population (largest first) to prefer major cities
        cities.sort((a, b) -> Integer.compare(b.population(), a.population()));
        
        System.out.println("Loaded " + cities.size() + " cities from " + countryCode);
        
        // Cache the result
        COUNTRY_CACHE.put(countryCode, cities);
        
        return cities;
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
