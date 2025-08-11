# K3s Phone Server Android App - Simplified Version

⚠️ **SIMPLIFIED FOR STABILITY** ⚠️

Android application providing web server with location and orientation services for K3s integration. **AI functionality has been removed** for improved stability and reduced complexity.

## Features

- **Web Server**: HTTP API on ports 8005 and 8080
- **Location Services**: GPS location via `/location`
- **Orientation**: Device compass via `/orientation`  
- **Health Checks**: Server status via `/status` and `/health`

## Removed Features (For Stability)

- ~~**AI Vision**: Image analysis (removed)~~
- ~~**AI Camera**: Image capture and analysis (removed)~~
- ~~**Camera**: Basic image capture (removed)~~
- ~~**Large AI models**: No more 42MB downloads (removed)~~

## API Endpoints

### Server Status
```
GET /status
```
Returns server information and features:
```json
{
  "status": "running",
  "server": "K3s Phone Server", 
  "version": "1.0.0-simplified",
  "features": {
    "location": true,
    "orientation": true,
    "ai": false
  }
}
```

### Health Check
```
GET /health
```
Returns service health status:
```json
{
  "status": "healthy",
  "services": {
    "location": "available",
    "orientation": "available"
  }
}
```

### Location Services
```
GET /location
```
Returns current GPS location:
```json
{
  "latitude": 37.7749,
  "longitude": -122.4194,
  "altitude": 50.0,
  "accuracy": 10.0,
  "timestamp": 1641234567890,
  "provider": "gps"
}
```

### Orientation/Compass
```
GET /orientation
```
Returns device orientation:
```json
{
  "azimuth": 45.0,
  "pitch": 10.0,
  "roll": 5.0,
  "accuracy": "HIGH",
  "timestamp": 1641234567890
}
```

## Installation

1. Download APK from [releases](../../releases)
2. Enable "Install from unknown sources" in Android settings
3. Install APK and grant permissions (location, camera, storage)
4. **Port Forwarding**: If using Android Linux Terminal app, ensure port 8005 is forwarded using the Linux Terminal app UI

AI models (~42MB) download automatically on first launch.

## Building

```bash
cd android
./gradlew assembleDebug
# APK will be in app/build/outputs/apk/debug/
```

## Basic Usage

```bash
# Check AI availability
curl http://PHONE_IP:8005/has-ai

# Get location and orientation
curl http://PHONE_IP:8005/location
curl http://PHONE_IP:8005/orientation

# AI image analysis
curl -X POST http://PHONE_IP:8005/ai/capture \
  -H "Content-Type: application/json" \
  -d '{"task": "describe your surroundings"}'
```

## License

Apache 2.0