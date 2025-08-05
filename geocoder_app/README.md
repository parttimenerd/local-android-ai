# Reverse Geocoder Service

High-performance reverse geocoding using local GeoNames data. Converts GPS coordinates to city names offline.

## Features

- **Offline Operation**: 30,632+ cities from 15 countries, no external dependencies
- **Fast Startup**: Germany+France in 355ms, background loading for others
- **Build-time Optimization**: Data cached during Maven build, instant container startup  
- **K3s Integration**: Automatic deployment with cluster location monitoring

## API

### Health Check
```
GET /health
```

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
GET /api/reverse-geocode?lat={latitude}&lon={longitude}
```

**Response:**
```json
{
  "location": "Berlin, DE",
  "method": "geonames", 
  "coordinates": {"latitude": 52.5200, "longitude": 13.4050}
}
```

## Coverage

30,632+ cities from 15 countries including Germany (6,830), France (8,726), UK (3,806), US (1,998), and 11 others.

## Quick Start

```bash
# Build and deploy
./build.sh && ./deploy.sh

# Test
kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- \
  curl 'http://reverse-geocoder.default.svc.cluster.local:8090/health'
```

## Configuration

- **Port**: 8090, **NodePort**: 30090
- **Resources**: 1.5Gi memory, 500m CPU
- **Performance**: 355ms startup (DE+FR), 388MB container
- **JVM**: G1GC, 1024m heap, string deduplication

## Testing

```bash
# Berlin
curl 'http://reverse-geocoder:8090/api/reverse-geocode?lat=52.5200&lon=13.4050'

# Paris  
curl 'http://reverse-geocoder:8090/api/reverse-geocode?lat=48.8566&lon=2.3522'

# Run unit tests
mvn test
```

## Monitoring

```bash
# Status
kubectl get deployment,pods,service -l app=reverse-geocoder

# Logs
kubectl logs -l app=reverse-geocoder -f

# Scale
kubectl scale deployment reverse-geocoder --replicas=2
```
