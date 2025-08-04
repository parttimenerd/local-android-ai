# Reverse Geocoder Service

A standalone reverse geocoding service using local GeoNames data for converting GPS coordinates to city names. Designed for high-performance, offline operation in K3s clusters.

## Overview

The reverse geocoder service provides a REST API for converting GPS coordinates (latitude/longitude) into human-readable location names (cities). It operates entirely offline using a comprehensive local GeoNames database, eliminating external dependencies and ensuring consistent performance.

## Architecture

- **Standalone Service**: Runs independently on the cluster master node
- **High Availability**: Deployed as a Kubernetes service accessible from all cluster nodes  
- **Local-Only Resolution**: Uses comprehensive GeoNames database with 30,632+ cities
- **Zero External Dependencies**: No internet required for geocoding operations
- **Comprehensive Testing**: 20+ unit tests with parametric testing for German and French cities

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

The service includes comprehensive offline coverage with 30,632+ cities from 14 countries:

- **Germany (DE)**: 6,830 cities including Berlin, Munich, Hamburg, Cologne, Frankfurt
- **France (FR)**: 8,726 cities including Paris, Lyon, Marseille, Nice, Strasbourg  
- **United Kingdom (GB)**: 3,806 cities including London, Manchester, Birmingham
- **United States (US)**: 1,998 cities including New York, Los Angeles, Chicago
- **Italy (IT)**: 309 major cities and towns
- **Spain (ES)**: 2,854 cities and settlements
- **Netherlands (NL)**: 1,192 cities including Amsterdam, Rotterdam
- **Belgium (BE)**: 1,730 cities including Brussels, Antwerp
- **Austria (AT)**: 187 cities including Vienna, Salzburg
- **Switzerland (CH)**: 1,107 cities including Zurich, Geneva, Bern
- **Denmark (DK)**: 421 cities including Copenhagen
- **Sweden (SE)**: 805 cities including Stockholm, Gothenburg
- **Norway (NO)**: 508 cities including Oslo, Bergen
- **Finland (FI)**: 159 cities including Helsinki

## Deployment

### Build and Deploy
```bash
# Build the Docker image
./build.sh

# Deploy to Kubernetes
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
