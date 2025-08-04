# Sample Kubernetes Application

⚠️ **EXPERIMENTAL PROTOTYPE - USE AT YOUR OWN RISK** ⚠️

**This is experimental prototype s# If using from K3s cluster, ensure port forwarding in Android Linux Terminal:
# Use the Linux Terminal app UI to forward port 8005tware. Features may not work as expected, may fail unpredictably, or may not function at all. Use entirely at your own risk.**

Java HTTP server demonstrating K3s deployment and load balancing on mobile devices.

## Overview

⚠️ **PROTOTYPE WARNING**: All features described are experimental and may not work reliably.

Lightweight Java application providing:
- System information endpoint (`/health`)
- SAP UI5 dashboard (`/dashboard`) with real-time monitoring
- Phone integration with location, orientation, camera, and AI features
- Auto-scaling deployment across cluster nodes
- LoadBalancer service with Tailscale networking

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
- **`/api/phone`**: Android phone location/orientation/camera/AI data (if available)
- **`/api/phone/capture`**: Direct camera capture from connected Android phone

### Dashboard Features
The `/dashboard` endpoint provides a comprehensive monitoring interface featuring:
- **Real-time system monitoring**: Memory usage, CPU load, uptime tracking
- **Interactive phone location map**: Live GPS tracking with orientation markers using reverse-geocoder service
- **Live camera feed**: Real-time camera capture from connected Android device
- **AI-powered descriptions**: Automatic scene analysis and description updates
- **Auto-refresh**: System data every 10 seconds, AI descriptions every minute
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

# Verify AI availability
curl http://localhost:8005/has-ai

# Test phone API endpoints
curl http://localhost:8005/location
curl http://localhost:8005/orientation
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
