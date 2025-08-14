# Reverse Geocoder Service

Memory-optimized reverse geocoding service using local GeoNames data for GPS coordinate to city name conversion.

## Overview

REST API for converting GPS coordinates to location names using offline GeoNames database. Optimized for low-resource K3s phone clusters.

## Memory Optimization

- **Ultra-low memory**: ~0.8 MB for German cities (10,390+ cities)
- **Filtered data**: Cities and administrative centers with ASCII names only
- **Auto-sizing**: Memory requirements auto-detected, K8s configs updated
- **Population-free**: Essential data only (name, country, coordinates)

## Architecture

- Standalone service on cluster master node
- Kubernetes service accessible from all nodes
- Zero external dependencies for geocoding
- Auto-configuration with calculated memory limits
- Optimized for Android phone clusters

## K3s Integration

Used by k3s-on-phone project for location monitoring:
- Server-side operation (not on individual phones)
- SSH-based querying via `update-node-locations.sh`
- Minimal resource usage for phone environments

```bash
# Usage in cluster
ssh phone-node "curl -s localhost:8005/location" | jq '.latitude,.longitude' | \
  xargs -I {} curl -s "http://reverse-geocoder:8090/api/reverse-geocode?lat={}&lon={}"
```

## API Endpoints

### Health Check
```
GET /health
```
Returns service health status with timestamp.

**Response:**
```json
{
  "status": "healthy",
  "service": "reverse-geocoder",
  "timestamp": "2025-08-04T11:37:53.392394819Z"
}
```

### Reverse Geocoding
```
GET /api/reverse-geocode?lat={latitude}&lon={longitude}&method={method}
```

**Parameters:**
- `lat`: Latitude coordinate (required)
- `lon`: Longitude coordinate (required)  
- `method`: Resolution method (optional, default: "geonames")
  - `geonames`: Use local GeoNames database (default and recommended)
  - `hybrid`: Same as geonames (for compatibility)

**Response:**
```json
{
  "location": "London, GB",
  "method": "geonames",
  "coordinates": {
    "latitude": 51.507400,
    "longitude": -0.127800
  }
}
```

## Local Database Coverage

The service is optimized for minimal memory usage with intelligent country selection:

### Current Configuration (Memory Optimized)
- **Germany (DE)**: 10,390 cities including Berlin, Munich, Hamburg, Cologne, Frankfurt
  - Memory usage: ~0.8 MB
  - ASCII names only for consistent memory usage
  - Includes cities, administrative centers, and populated places

### Adding More Countries
To extend coverage, simply update the Java configuration and re-run memory detection:

1. **Edit the country list:**
   ```java
   // In ReverseGeocodingService.java
   private static final Set<String> DEFAULT_COUNTRIES = Set.of("DE", "AT", "CH");
   ```

2. **Auto-configure memory:**
   ```bash
   ./configure-memory.sh  # Detects new requirements automatically
   ```

**Available for extension**: AT, BE, CH, DE, DK, ES, FI, FR, GB, IT, NL, NO, PL, SE, US

**Memory scaling**: Approximately 0.5-2 MB per country depending on population density.

## Deployment

### Automatic Memory Configuration
The service includes an intelligent memory configuration system that automatically determines optimal resource requirements:

```bash
# Automatically detect memory requirements and update K8s configs
./configure-memory.sh
```

This script:
- **Analyzes actual memory usage** by running the geocoder service
- **Calculates optimal resource limits** with safety margins
- **Updates Kubernetes deployment files** automatically
- **Provides deployment recommendations** based on current configuration

**Example output:**
```
📊 Memory Configuration Summary
• Cities loaded: 10,390 (German cities)
• Data size: 0.8 MB
• Kubernetes request: 107Mi  
• Kubernetes limit: 214Mi
• JVM heap: 74MB
```

### Build and Deploy
```bash
# 1. Configure memory requirements automatically
./configure-memory.sh

# 2. Build the Docker image
./build.sh

# 3. Deploy to Kubernetes  
./deploy.sh
```

### Manual Deployment
```bash
# Build Docker image
docker build -t reverse-geocoder:latest .

# Apply Kubernetes manifests
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

## Service Configuration

The service is configured to:
- **Port**: 8090 (HTTP)
- **NodePort**: 30090 (for external debugging access)
- **Deployment**: Single replica on master node
- **Resources**: 256Mi memory, 200m CPU
- **Health Checks**: Liveness and readiness probes

## Integration with Node Location Monitoring

The K3s geolocation monitoring service automatically uses this reverse geocoder:

1. **Service Discovery**: Looks for `reverse-geocoder` service in the cluster
2. **Endpoint**: `http://reverse-geocoder.default.svc.cluster.local:8090`
3. **Local-Only Operation**: No external dependencies or fallbacks needed
4. **High Performance**: Fast response times with local database lookup

## Testing

The service includes comprehensive unit tests with parametric testing covering German and French cities:

### Run Tests
```bash
# Run all tests (20+ test cases)
mvn test

# Run specific test classes
mvn test -Dtest=GeocoderServerTest

# Run tests with detailed output
mvn test -Dtest=GeocoderServerTest -Dtest.verbose=true
```

### Test Coverage
- **Parametric City Testing**: Berlin, Munich, Hamburg, Cologne, Frankfurt, Paris, Lyon, Marseille, Nice, Strasbourg
- **API Functionality**: Health endpoints, parameter validation, error handling
- **Response Format**: JSON structure validation, coordinates object verification
- **Error Conditions**: Missing parameters, invalid coordinates, non-existent endpoints

## Usage Examples

### Test Health Endpoint
```bash
kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- \
  curl 'http://reverse-geocoder.default.svc.cluster.local:8090/health'
```

### Test Reverse Geocoding
```bash
# London, UK
kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- \
  curl 'http://reverse-geocoder.default.svc.cluster.local:8090/api/reverse-geocode?lat=51.5074&lon=-0.1278&method=geonames'

# Berlin, Germany
kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- \
  curl 'http://reverse-geocoder.default.svc.cluster.local:8090/api/reverse-geocode?lat=52.5200&lon=13.4050&method=geonames'

# Paris, France
kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- \
  curl 'http://reverse-geocoder.default.svc.cluster.local:8090/api/reverse-geocode?lat=48.8566&lon=2.3522&method=geonames'
```

## Data Sources

**Local GeoNames Database**: Complete offline operation using curated city data from 14 countries with population-based filtering and major city prioritization.

## Monitoring

### Check Deployment Status
```bash
kubectl get deployment reverse-geocoder
kubectl get pods -l app=reverse-geocoder
kubectl get service reverse-geocoder
```

### View Logs
```bash
kubectl logs -l app=reverse-geocoder -f
```

### Scale Service
```bash
kubectl scale deployment reverse-geocoder --replicas=2
```

## Troubleshooting

### Service Not Responding
1. Check pod status: `kubectl get pods -l app=reverse-geocoder`
2. Check logs: `kubectl logs -l app=reverse-geocoder`
3. Verify service: `kubectl get service reverse-geocoder`

### Geocoding Failures
1. Check service logs for database loading issues
2. Verify coordinates are within supported regions (14 countries)
3. Test with known coordinates from major cities

### Node Labels Not Updating
1. Verify geocoder service is running and accessible
2. Check geolocation monitoring logs: `journalctl -u k3s-geolocation-monitor -f`
3. Test manual API calls from node to verify connectivity

## Development

### Local Testing
```bash
# Run locally (requires Java 17+)
mvn clean compile exec:java -Dexec.mainClass="me.bechberger.k3s.geocoder.GeocoderServer"

# Test local instance
curl 'http://localhost:8090/health'
curl 'http://localhost:8090/api/reverse-geocode?lat=51.5074&lon=-0.1278'

# Run comprehensive tests
mvn test
```

### Building
```bash
# Compile and run tests
mvn clean test

# Package application
mvn clean package

# Build Docker image
docker build -t reverse-geocoder:latest .
```
