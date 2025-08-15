# K3s Phone Server - Android AI App

Android application with MediaPipe AI inference, object detection, and device sensors for K3s cluster integration.

## Features

### AI Inference
- **LLM Support**: Gemma 3n E2B IT, DeepSeek-R1 Distill Qwen 1.5B, Llama 3.2 (1B/3B), TinyLlama 1.1B
- **Object Detection**: MediaPipe EfficientDet Lite 2
- **Model Management**: Download, test, performance metrics (tokens/second)
- **Streaming**: Real-time token streaming with cancellation support

### Device Integration
- **Location**: GPS with accuracy, altitude, bearing metadata
- **Camera**: Front/rear with zoom, base64 encoding
- **Sensors**: Compass orientation (azimuth, pitch, roll)
- **Permissions**: Runtime request handling

### REST API (Port 8005)
- JSON responses with CORS support
- Error handling with HTTP status codes
- Built-in documentation at `/help`

## API Endpoints

### AI Services
```http
POST /ai/text
{
  "prompt": "text",
  "model": "gemma-3n-e2b-it", 
  "maxTokens": 150,
  "temperature": 0.7,
  "topK": 40,
  "topP": 0.95
}
```

```http
POST /ai/object_detection
{
  "side": "rear|front",
  "threshold": 0.6,
  "maxResults": 5,
  "returnImage": false
}
```

```http
GET /ai/models
POST /ai/models/download {"modelName": "model-name"}
POST /ai/models/test {"modelName": "model-name", "prompt": "text"}
```

### Device & System
```http
GET /location       # GPS: lat, lng, alt, accuracy, bearing
GET /orientation    # Compass: azimuth, pitch, roll, accuracy  
GET /capture?side=rear&zoom=2.0  # Camera capture, base64 JPEG ⚠️ App must be visible
GET /status         # Server status, features, permissions
GET /help           # API documentation
```

⚠️ **Camera Privacy Notice**: Camera capture requires the Android app to be visible due to Android OS privacy restrictions. This ensures users are aware when the camera is being accessed. Location and other endpoints work in the background.

## Technical Details

### AI Models
- **Gemma 3n E2B IT**: 2B parameters, instruction-tuned
- **DeepSeek-R1 Distill**: 1.5B reasoning model with thinking capability
- **Llama 3.2**: 1B/3B instruction models
- **TinyLlama**: 1.1B chat model
- **Backend Support**: CPU, GPU, NNAPI backends
- **Memory Requirements**: 1-4GB depending on model

### MediaPipe Integration
- **Object Detection**: EfficientDet Lite 2 with 80 COCO classes
- **LLM Inference**: Native MediaPipe LLM with streaming support
- **Performance**: Real-time token generation with metrics
- **Cancellation**: Coroutine-based with proper cleanup

### Development
- **Kotlin**: Android-native implementation
- **Coroutines**: Async operations with proper cancellation
- **Camera2 API**: Advanced camera features
- **Permissions**: Location, camera runtime requests
- **Error Handling**: Comprehensive exception management

## Build & Install

```bash
# Build APK
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Monitor logs
adb logcat -s "K3sPhoneServer" "*AI*" "*Camera*" "*Location*"
```

## Integration

Designed for K3s cluster nodes running on Android devices. Provides REST API for cluster applications to access AI inference, device sensors, and camera functionality.

```http
GET /health
# Simple health check endpoint
```

```http
GET /help
# Complete API documentation with examples
```

## 🛠️ Build Instructions

### Prerequisites
- **Android Studio** Arctic Fox or newer
- **Android SDK** API level 24+ (Android 7.0+)
- **Device Requirements**: 3GB+ RAM for AI features
- **Permissions**: Camera, Location, Internet

### Building the App
```bash
# Clone the repository
git clone <repository-url>
cd android

# Build debug APK
./gradlew assembleDebug

# Install on connected device  
./gradlew installDebug

# Build release APK
./gradlew assembleRelease
```

### Debug Information
The `/status` endpoint provides comprehensive debug information including:
- Current AI model status and memory usage
- Permission status for all features
- Available device memory and requirements
- Feature availability and configuration

## License

Apache 2.0