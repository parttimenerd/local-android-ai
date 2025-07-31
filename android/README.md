# K3s Phone Server Android App

⚠️ **EARLY PROTOTYPE - USE AT YOUR OWN RISK** ⚠️

Android application providing web server with location, orientation, and AI services for K3s integration.

## Features

- **Web Server**: HTTP API on port 8005
- **Location Services**: GPS location via `/location`
- **Orientation**: Device compass via `/orientation`  
- **AI Vision**: Image analysis via `/ai/analyze` and `/ai/capture`
- **Camera**: Image capture via `/capture`
- **AI Availability**: Check capabilities via `/has-ai`

## API Endpoints

### Server Status
```
GET /status
```
Returns server information and health status.

### AI Availability Check
```
GET /has-ai
```
Returns AI capability information:
```json
{
  "available": true,
  "capabilities": {
    "vision": true,
    "language": false,
    "features": ["image_classification", "object_detection"]
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

### AI Image Analysis
```
POST /ai/analyze
Content-Type: application/json

{
  "task": "describe this image",
  "imageBase64": "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAA..."
}
```

### AI Camera Capture
```
POST /ai/capture
Content-Type: application/json

{
  "task": "describe your surroundings"
}
```

### Camera Capture
```
POST /capture
Content-Type: application/json

{
  "camera": "back"
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