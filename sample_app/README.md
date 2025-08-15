# Sample Kubernetes Application

Spring Boot application demonstrating K3s deployment with location mapping and AI integration.

## Features

- **Health API** (`/health`) with node details and system metrics
- **Dashboard** (`/dashboard`) with cluster monitoring and map visualization  
- **Location Integration** using Android GPS and reverse geocoding
- **Object Detection** via MediaPipe EfficientDet Lite 2
- **Auto-scaling** deployment across cluster nodes
- **LoadBalancer** service with Tailscale networking

## Object Detection
- MediaPipe EfficientDet Lite 2 integration
- JSON response with objects, bounding boxes, confidence scores
- Optional base64 image return
- API: `/api/phone/capture` with detection enabled

## System Information
- Real-time metrics (CPU, memory, network)
- Node identification and cluster status
- GPS coordinates from Android devices
- City names via geocoder service
- AI capability detection

## Prerequisites

### K3s Cluster Setup
- K3s cluster with Tailscale VPN configured (headscale support planned)
- kubectl access from master node
- Docker/Podman for image building

## Quick Start

### Prerequisites
- K3s cluster with reverse-geocoder service deployed (for location features)
- Docker registry configured for multi-node deployments

### Build and Deploy
```bash
# Build Docker image
./build.sh

# Deploy to cluster
./deploy.sh

# Check status
kubectl get pods,svc -l app=server-info-server
```

### Multi-Node Image Distribution
For clusters with multiple nodes, the build script can automatically distribute images via SSH:

```bash
# Setup SSH access to agent nodes first
ssh-copy-id root@<agent-ip>  # Password: root

# Build will offer to distribute to agent nodes automatically
./build.sh

# Or distribute manually after building
./distribute-image.sh

# Or distribute a specific image
./distribute-image.sh my-app:v1.0.0
```

### Deployment
```bash
# Deploy and check status
./deploy.sh
kubectl get pods,svc -l app=server-info-server

# Access: http://<EXTERNAL-IP>:8080
```

## Manual Deployment

```bash
kubectl apply -f k8s/
kubectl wait --for=condition=available --timeout=300s deployment/server-info-server
```

## Files Structure

```
sample_app/
├── src/main/java/          # Java application source
├── k8s/                    # Kubernetes manifests
├── Dockerfile              # Container build
├── build.sh deploy.sh      # Build/deploy scripts
├── scale.sh test.sh        # Scaling/testing utilities
└── undeploy.sh            # Cleanup script
```

## Application Details

### Endpoints
- **`/`**: Basic server information
- **`/health`**: Detailed system health data  
- **`/dashboard`**: SAP UI5 monitoring interface with Android phone integration
- **`/api/system`**: Additional system metrics
- **`/api/phone`**: Android phone location/orientation/object detection data (if available)
- **`/api/phone/capture`**: Direct camera capture with object detection from connected Android phone ⚠️ **Requires Android app visible**

⚠️ **Camera Privacy Requirement**: Camera capture endpoints require the K3s Phone Server Android app to be visible due to Android OS privacy restrictions. This is a built-in security feature to ensure users are aware when the camera is being used.

### Enhanced API Endpoints

#### GET `/api/phone?refreshAI=true&includeImage=true`
- **Purpose**: Get phone data with fresh object detection
- **Parameters**:
  - `refreshAI=true`: Triggers new object detection (faster than LLM)
  - `includeImage=true`: Includes base64 image in object detection response
- **Response**: JSON with location, orientation, and object detection data
- **Performance**: Object detection typically completes in <1 second

#### POST `/api/phone/capture`
- **Purpose**: Direct camera capture with object detection ⚠️ **Requires Android app visible**
- **Request Body**: JSON with object detection parameters
- **Response**: JSON with detected objects, bounding boxes, and optional image
- **Uses**: MediaPipe EfficientDet Lite 2 for fast on-device processing
- **Privacy**: Android app must be visible due to OS camera privacy restrictions

### Dashboard Features
The `/dashboard` endpoint provides a comprehensive monitoring interface featuring:
- **Real-time system monitoring**: Memory usage, CPU load, uptime tracking
- **Interactive phone location map**: Live GPS tracking with orientation markers using reverse-geocoder service
- **Live object detection**: Real-time object detection from connected Android device camera
- **Fast AI processing**: MediaPipe-based object detection with <1 second response times
- **Auto-refresh**: System data every 10 seconds, object detection on demand
- **Responsive design**: SAP UI5 components with mobile-friendly interface
- **City location display**: Automatic city resolution using local GeoNames database

### Health Response
```json
{
  "status": "healthy",
  "hostname": "server-info-server-abc123",
  "timestamp": "2025-07-30T15:35:54Z",
  "uptime_ms": 21418,
  "memory": { "used_mb": 24.78, "total_mb": 2000.00, "usage_percent": 1.24 },
  "system": { 
    "os_name": "Linux", 
    "os_version": "Garden Linux 1877.1",
    "os_description": "Garden Linux 1877.1 (SAP optimized)",
    "jvm_version": "24+36", 
    "jvm_vendor": "Eclipse Adoptium",
    "cpu_load_avg": 22.94 
  }
}
```

### Configuration
- Node anti-affinity deployment with auto-scaling
- LoadBalancer service via Tailscale
- Base image: `ghcr.io/gardenlinux/gardenlinux/bare-sapmachine:1877.1`
- Reverse geocoding integration with standalone geocoder service

## Service Dependencies

### Reverse Geocoder Service
The application integrates with the reverse-geocoder service for location features:

```bash
# Deploy geocoder service first (from geocoder_app directory)
cd ../geocoder_app
./build.sh && ./deploy.sh

# Then deploy the main application
cd ../sample_app
./build.sh && ./deploy.sh
```

The application automatically discovers the geocoder service at:
`http://reverse-geocoder.default.svc.cluster.local:8090`

## Usage Examples

### Object Detection Integration
```bash
# Test object detection through sample app
curl "http://localhost:8080/api/phone?refreshAI=true&includeImage=true"

# Direct camera capture with object detection
curl -X POST http://localhost:8080/api/phone/capture \
  -H "Content-Type: application/json" \
  -d '{
    "side": "rear",
    "threshold": 0.6,
    "maxResults": 10,
    "returnImage": true
  }'

# Fast object detection without image (for dashboards)
curl "http://localhost:8080/api/phone?refreshAI=true&includeImage=false"
```

### Sample Object Detection Response
```json
{
  "available": true,
  "timestamp": 1692720000000,
  "capabilities": {
    "ai": true,
    "camera": true,
    "location": true
  },
  "location": {
    "latitude": 37.7749,
    "longitude": -122.4194,
    "accuracy": 5.0
  },
  "objectDetection": {
    "success": true,
    "objects": [
      {
        "category": "person",
        "score": 0.92,
        "boundingBox": {
          "left": 0.15,
          "top": 0.20,
          "width": 0.35,
          "height": 0.65
        }
      },
      {
        "category": "car",
        "score": 0.87,
        "boundingBox": {
          "left": 0.60,
          "top": 0.45,
          "width": 0.30,
          "height": 0.25
        }
      }
    ],
    "inferenceTime": 850,
    "threshold": 0.6,
    "imageMetadata": {
      "width": 1920,
      "height": 1080,
      "camera": "rear"
    }
  }
}
```

### Load Balancing Test
```bash
for i in {1..10}; do curl -s http://<SERVICE-IP>:8080 | jq .hostname; done
```

### Scaling
```bash
./scale.sh 3 --wait --test    # Scale and test
kubectl scale deployment server-info-server --replicas=3
```

### Logs
```bash
kubectl logs -l app=server-info-server --tail=50 -f
```

## Troubleshooting

### Phone Integration Issues
```bash
# Check if Android phone server is running
curl http://localhost:8005/status

# If using from K3s cluster, ensure port forwarding in Android Linux Terminal:
# socat TCP-LISTEN:8005,fork TCP:localhost:8005 &

# Verify AI and object detection availability
curl http://localhost:8005/ai/models
curl http://localhost:8005/capabilities

# Test phone API endpoints
curl http://localhost:8005/location
curl http://localhost:8005/orientation

# Test object detection endpoint directly
curl -X POST http://localhost:8005/ai/object_detection \
  -H "Content-Type: application/json" \
  -d '{"side":"rear","threshold":0.5,"maxResults":5,"returnImage":false}'

# Test sample app integration
curl "http://localhost:8080/api/phone?refreshAI=true&includeImage=false"
```

### Pods Issues
```bash
kubectl describe pods -l app=server-info-server
kubectl top nodes
```

### Service Issues  
```bash
kubectl get service server-info-server -o wide
sudo tailscale status
```

### Image Issues
```bash
kubectl describe pods -l app=server-info-server | grep -A 5 Events
sudo docker pull ghcr.io/gardenlinux/gardenlinux/bare-sapmachine:1877.1
```

## Automated Troubleshooting

### Quick Fix Script
For common deployment issues, use the automated troubleshooting script:
```bash
./fix-deployment.sh
```

This script automatically detects and fixes:
- ✅ LoadBalancer + hostNetwork port conflicts
- ✅ Pod scheduling failures  
- ✅ Image availability issues across nodes
- ✅ Service configuration problems

### Common Issues and Solutions

#### 1. Pods Stuck in Pending State
**Symptom:** `0/N nodes are available: N node(s) didn't have free ports`
**Cause:** LoadBalancer service conflicts with hostNetwork deployment
**Auto-fix:** Run `./fix-deployment.sh` or manually delete LoadBalancer service
```bash
sudo k3s kubectl delete service server-info-server
```

#### 2. Image Pull Errors  
**Symptom:** `ErrImageNeverPull` on some nodes
**Cause:** Image only built on one node
**Solution:** Use automatic SSH distribution or build on each node:
```bash
# Option 1: Setup SSH and let build script distribute automatically
ssh-copy-id root@<agent-ip>  # Password: root
./build.sh  # Will offer to distribute

# Option 2: Use standalone distribution script
./distribute-image.sh

# Option 3: Manual distribution
scp /tmp/image.tar root@<agent-ip>:/tmp/
ssh root@<agent-ip> 'sudo k3s ctr images import /tmp/image.tar'

# Option 4: Build on each node separately
# On each K3s node:
./build.sh
```

#### 3. Port Conflicts
**Symptom:** Service binding failures
**Check:** `sudo k3s kubectl get svc -A | grep 8080`
**Fix:** Delete conflicting services or change ports

#### 4. hostNetwork vs LoadBalancer
- **hostNetwork: true** → App accessible on `<node-ip>:8080` directly
- **LoadBalancer service** → Creates proxy pods that also bind to ports
- **Don't mix both** → Causes port conflicts and scheduling failures

## Customization

**Update code:** Edit `src/main/java/.../*.java`, then `./build.sh && ./deploy.sh`

**Adjust resources:** Modify `k8s/deployment.yaml` resource limits

**Scaling strategy:** Replace node anti-affinity in `k8s/deployment.yaml`
  
## License

Apache 2.0, Copyright 2017 - 2025 SAP SE or an SAP affiliate company and contributors.
