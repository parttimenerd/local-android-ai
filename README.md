# K3s on Phone

⚠️ **EARLY PROTOTYPE - USE AT YOUR OWN RISK** ⚠️

**This is an experimental proof-of-concept project. None of the features are guaranteed to work. Use entirely at your own risk. Only intended for exploration.**

Kubernetes cluster deployment on Android devices using Debian in KVM via Android Linux Terminal.

## Overview

⚠️ **EXPERIMENTAL PROJECT**: This is a rough prototype for educational and experimental purposes only. Features may not work as expected, may break without notice, and are only suitable for exploration.

Deploy a multi-node K3s cluster across Android phones using:
- K3s lightweight Kubernetes distribution
- Tailscale mesh VPN for secure networking  
- Java sample application with cluster location mapping and city information
- Automated Docker registry setup and image distribution
- Real-time GPS tracking and geographic visualization
- Automated scripts for setup and deployment


### System Requirements

- Android devices with developer mode enabled
- Android Linux Terminal app with Debian installed
- Tailscale account for VPN networking
- 4GB+ RAM, 64GB+ storage per device
- Stable network connectivity

**Known Issues:**
- If network connectivity fails during setup, restarting and reinstalling Debian on your phone is often needed
- This is a known limitation of the experimental Android Linux Terminal app

## Quick Start

### Master Node Setup
```bash
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/setup.sh | bash -s -- phone-01 -t YOUR_TAILSCALE_KEY
```

*Automatically sets up Docker registry, K3s server, and geolocation monitoring.*

### Worker Node Setup  
```bash
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/setup.sh | bash -s -- phone-02 -t YOUR_TAILSCALE_KEY -k K3S_TOKEN -u https://phone-01:6443
```

*Automatically configures Docker registry access and joins cluster with geolocation monitoring.*

### Deploy Sample Application

#### Option 1: Using Local Registry (Recommended for Multi-Node)
```bash
# Registry is automatically set up during K3s installation

# Build and push application image
cd sample_app
./build.sh
../registry.sh push server-info-server:latest

# Deploy application with cluster map features
./deploy.sh && ./test.sh
```

#### Option 2: SSH Image Distribution
```bash
# Set up SSH keys for image distribution
ssh-copy-id root@<agent-node-ip>  # password: root

# Build and distribute images (will prompt for distribution method)
cd sample_app
./build.sh

# Deploy application
./deploy.sh && ./test.sh
```

#### Option 3: Manual Build on Each Node
```bash
# On each node, build the image locally
cd sample_app
./build.sh

# Deploy from master node
./deploy.sh && ./test.sh
```

## New Features

### 🗺️ Cluster Location Mapping with City Information
- **Interactive map dashboard** showing all nodes with real-time locations
- **City information**: Automatic reverse geocoding adds city names to node labels
- **Visual markers**: Current node (blue arrow) vs other nodes (muted gray phone icons)
- **Geographic overview**: See your distributed K3s cluster on an actual world map

### 🐳 Automatic Docker Registry Integration  
- **Zero-config setup**: Registry automatically configured during K3s installation
- **Seamless distribution**: Images pushed once, available on all nodes
- **Insecure registry handling**: Automatic Docker daemon configuration for local development
- **Dynamic addressing**: Registry location automatically detected for server/agent nodes



## Registry Management

The project includes a comprehensive Docker registry management system for easy image distribution across K3s nodes. **Registry setup is now automatic during K3s installation.**

### Registry Setup
```bash
# Registry is automatically set up during ./setup.sh
# Manual operations (if needed):

# Check registry status
./registry.sh status

# View registry information
./registry.sh info
```

### Image Management
```bash
# List all images in registry
./registry.sh list

# Push a local image to registry
./registry.sh push my-app:latest

# Pull an image from registry
./registry.sh pull my-app:latest

# Delete an image from registry
./registry.sh delete my-app:latest
```

### Registry Operations
```bash
# Start/stop registry
./registry.sh start
./registry.sh stop
./registry.sh restart

# View registry logs
./registry.sh logs

# Clean up unused data
./registry.sh cleanup

# Completely remove registry
./registry.sh remove
```

### K3s Integration
Registry integration is **automatic during setup**:
- Master node: Local registry on port 5000
- Agent nodes: Configured to use master's registry
- Docker daemon: Automatically configured for insecure local registry
- No manual configuration needed

### Reset Cluster to Clean State
```bash
# Interactive reset with confirmation
./reset.sh

# Force reset without confirmation
./reset.sh --force

# Reset and remove nodes from Tailscale
./reset.sh --remove-from-tailscale --force
```

### Clean Dead Nodes and Devices
```bash
# Remove NotReady K3s nodes only
./clean.sh

# Remove NotReady nodes + unreachable "phone-..." from Tailscale VPN
./clean.sh -t tskey-api-xxxxx

# Dry run to preview cleanup
./clean.sh -t tskey-api-xxxxx --dry-run
```

### Delete Specific Resources
```bash
# Delete a specific pod
./delete.sh pod my-app-pod

# Delete all pods in a namespace (with confirmation)
./delete.sh pod --all -n my-namespace

# Preview what would be deleted (recommended)
./delete.sh deployment my-app --dry-run

# Force delete without confirmation
./delete.sh service my-service --force

# Delete complete application stack
./delete.sh app my-application
```

**Script Comparison:**
- `reset.sh`: Removes ALL nodes and apps, resets to server-only cluster
- `clean.sh`: Removes only NotReady/unreachable nodes, keeps working cluster
- `delete.sh`: Safely delete specific resources (pods, deployments, services, nodes)
- `setup.sh cleanup`: Legacy cleanup for NotReady nodes only

### Android Phone Server App

Optional Android app providing additional services like GPS location, device orientation, AI vision, and camera capture via HTTP API on port 8005.

See [android/README.md](android/README.md) for installation and usage details.

## Detailed Setup

### Prerequisites Installation

1. **Android Linux Terminal**: Install from Play Store
2. **Debian Environment**: Set up within the terminal app
3. **Basic Tools**: Install curl, wget, git within Debian
4. **Tailscale Account**: Register at tailscale.com
5. **Tailscale Auth Key**: Create one at https://login.tailscale.com/admin/machines/new-linux

### Master Node Configuration

```bash
# Full setup with all options
./setup.sh phone-master \
  --tailscale-key YOUR_KEY \
  --k3s-args "--disable traefik --disable servicelb" \
  --cleanup-on-error
```

### Worker Node Configuration

```bash
# Join existing cluster
./setup.sh phone-worker \
  --tailscale-key YOUR_KEY \
  --k3s-token $(cat /var/lib/rancher/k3s/server/node-token) \
  --k3s-url https://master-tailscale-ip:6443
```

### Local Mode Setup

For local development or when running on systems with existing prerequisites:

```bash
# Local server setup: computer/server as K3s master
./setup.sh --local

# Local mode: join existing cluster
./setup.sh --local -k mynodetoken -u https://existing-server:6443

# Local mode with Tailscale auth key (will install Tailscale if needed)
./setup.sh --local -t tskey-auth-xxxxx
```

**Getting the K3s Token:**
After setting up a local server, get the token for worker nodes:
```bash
sudo cat /var/lib/rancher/k3s/server/node-token
```

**Notes**: 
- In normal mode, the root SSH password is set to 'root' for simplicity. This is **NOT done in `--local` mode** for security reasons.
- Local mode will **check that Tailscale is running** and prompt you to install/configure it if needed, but won't modify your existing Tailscale setup.
- Use local mode when setting up a computer/server as the K3s master that phones will connect to.
- **Hostname argument is prohibited in local mode** - the current system hostname will always be used.
- **With `-t` flag**: Tailscale will be installed automatically if not present.
- **Token display**: The setup script will show the K3s token and commands to add worker nodes.

## Application Management

### Sample App Operations

```bash
cd sample_app

```bash
# Build and deploy
./build.sh && ./deploy.sh

# Scale with validation
./scale.sh 3 --wait --test

# Monitor deployment
kubectl get pods -l app=server-info-server -w

# Test load balancing
./test.sh --iterations 10

# Clean removal
./undeploy.sh
```

### Custom Applications

The sample app demonstrates:
- Java HTTP server with system information endpoint
- Kubernetes deployment with anti-affinity rules
- LoadBalancer service with Tailscale integration
- Horizontal scaling across cluster nodes
- Health checks and readiness probes
- **Interactive cluster location map with city information**
- **Real-time geographic visualization of node distribution**

### Cluster Location Mapping

The sample application includes comprehensive location features:

**Interactive Map Dashboard**: Access at `http://your-app:8080/dashboard.html`
- 🗺️ World map showing all cluster nodes in real-time
- 📍 Current node: Blue marker with directional arrow
- 📱 Other nodes: Muted gray markers with phone icons  
- 🏙️ City information: Popup shows city names and coordinates

## Reverse Geocoder Service

A standalone, high-performance reverse geocoding service provides city name resolution for all cluster nodes:

### Features
- **Local GeoNames Database**: 30,632+ cities from 14 countries
- **Zero External Dependencies**: Complete offline operation  
- **High Performance**: Fast local database lookups
- **Comprehensive Testing**: 20+ unit tests with parametric validation
- **UTF-8 Safe**: Proper handling of international characters

### Deployment
```bash
cd geocoder_app
./build.sh && ./deploy.sh

# Test the service
kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- \
  curl 'http://reverse-geocoder.default.svc.cluster.local:8090/health'
```

### API Usage
```bash
# Get city for coordinates
curl 'http://reverse-geocoder.default.svc.cluster.local:8090/api/reverse-geocode?lat=52.5200&lon=13.4050'

# Returns: {"location":"Berlin, DE","method":"geonames","coordinates":{"latitude":52.520000,"longitude":13.405000}}
```

See [geocoder_app/README.md](geocoder_app/README.md) for complete documentation.

**REST API for Applications**:

```bash
# Get all node locations with city information
curl http://your-app:8080/api/cluster/locations

# Get phone nodes only
curl http://your-app:8080/api/cluster/locations?phone-only=true
```

**API Response Format** (now includes city information):
```json
{
  "cluster": "k3s-phone",
  "timestamp": 1691234567890,
  "nodeCount": 3,
  "nodes": [
    {
      "name": "phone-01",
      "ip": "192.168.1.100",
      "latitude": 51.5074,
      "longitude": -0.1278,
      "altitude": 100.0,
      "deviceType": "phone",
      "cityName": "London, GB",
      "lastUpdated": "2025-08-01T10:30:00Z"
    }
  ]
}
```

This enables building status maps and location-aware dashboards that show the real-time geographic distribution of your K3s cluster nodes with human-readable city information.

### Phone Node Targeting

Agent nodes are automatically labeled as `device-type=phone` to enable phone-specific deployments:

```bash
# Check node labels
kubectl get nodes --show-labels

# View phone nodes only
kubectl get nodes -l device-type=phone

# View phone nodes with location and city
kubectl get nodes -l device-type=phone -o custom-columns=NAME:.metadata.name,LATITUDE:.metadata.labels.phone\.location/latitude,LONGITUDE:.metadata.labels.phone\.location/longitude,CITY:.metadata.labels.phone\.location/city

# Deploy to phone nodes only (automatic with sample app)
kubectl apply -f k8s/deployment.yaml
```

**Deployment Configuration:**
```yaml
nodeSelector:
  device-type: phone
```

**Geolocation Monitoring:**
Agent nodes include a service that monitors the phone app's geolocation API every 20 seconds and automatically updates node labels:
- `phone.location/latitude`
- `phone.location/longitude` 
- `phone.location/altitude`
- `phone.location/city` (e.g., "London, GB")
- `phone.location/city-updated` (timestamp)
- `phone.location/updated`

This ensures applications only run on phone devices, not on server/desktop nodes that might join the cluster.

**Manual Labeling (if needed):**
```bash
# Label existing nodes as phones
kubectl label node my-phone-node device-type=phone

# Remove phone label
kubectl label node my-phone-node device-type-

# Label server/desktop nodes differently
kubectl label node my-server-node device-type=server
```

## Monitoring and Operations

### Cluster Status
```bash
# Node information
kubectl get nodes -o wide
kubectl top nodes

# Node information with location and city
kubectl get nodes -o custom-columns=NAME:.metadata.name,STATUS:.status.conditions[-1].type,DEVICE:.metadata.labels.device-type,LAT:.metadata.labels.phone\.location/latitude,LON:.metadata.labels.phone\.location/longitude,CITY:.metadata.labels.phone\.location/city

# Pod status across namespaces  
kubectl get pods --all-namespaces
kubectl top pods --all-namespaces

# Service discovery
kubectl get services --all-namespaces
```

### Geolocation Monitoring
```bash
# Check geolocation service on agent nodes
sudo systemctl status k3s-geolocation-monitor

# View geolocation logs
sudo journalctl -u k3s-geolocation-monitor -f

# Test phone app API
curl -s http://localhost:8005/location

# Check phone location labels with city information
kubectl get nodes -l device-type=phone -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.phone\.location/latitude}{"\t"}{.metadata.labels.phone\.location/longitude}{"\t"}{.metadata.labels.phone\.location/altitude}{"\t"}{.metadata.labels.phone\.location/city}{"\n"}{end}'
```

### Testing & Diagnostics
```bash
# Interactive cluster map testing  
cd sample_app
./test-cluster-map.sh

# Comprehensive geolocation testing
./test-geolocation.sh help

# Quick diagnostics
./test-geolocation.sh test-api     # Test phone app connection
./test-geolocation.sh show-current # Show current location labels
./test-geolocation.sh monitor      # Run one-time location update
./test-geolocation.sh simulate 51.5074 -0.1278 100  # Test with coordinates

# Service status checks
./test-geolocation.sh show-service # Check systemd service
./test-geolocation.sh test-labels  # Test label operations
```

### Network Verification
```bash
# Tailscale connectivity
tailscale status
tailscale ping other-node

# K3s networking
kubectl get endpoints
kubectl describe service service-name
```

### Log Analysis
```bash
# Application logs
kubectl logs -l app=server-info-server -f --tail=100

# System logs
journalctl -u k3s -f
journalctl -u tailscaled -f
```

## Troubleshooting

### Network Connectivity Issues
- **Debian Network Problems**: If setup fails with network connectivity errors (unable to reach github.com or download packages), restart and reinstall Debian in the Android Linux Terminal app
- **This is a known issue**: The Android Linux Terminal app's Debian environment can lose network connectivity and requires a fresh installation to resolve
- **Check Basic Connectivity**: `ping github.com` and `ping google.com` should work before running setup

### Node Join Issues
- **Check Tailscale**: `tailscale status` shows all nodes
- **Verify Token**: K3s token matches server
- **Network Access**: Port 6443 accessible between nodes
- **Time Sync**: System clocks synchronized

### Application Deployment Problems
- **Image Pull**: `kubectl describe pods` shows image pull status
- **Resource Limits**: Check memory/CPU availability with `kubectl top nodes`
- **Networking**: Verify service endpoints and pod IPs
- **Permissions**: Ensure proper RBAC configuration

### Performance Issues
- **Resource Monitoring**: Use `kubectl top` commands regularly
- **Thermal Throttling**: Monitor device temperature
- **Storage Space**: Check available disk space
- **Network Latency**: Test connectivity between nodes

### Recovery Procedures

**Restart Services:**
```bash
# K3s service restart
sudo systemctl restart k3s

# Tailscale reconnection  
sudo tailscale down && sudo tailscale up
```

**Complete Cleanup:**
```bash
# Remove applications
./sample_app/undeploy.sh

# Clean dead/unreachable nodes only
./clean.sh -t tskey-api-xxxxx --force

# Reset cluster to clean state (removes all agent nodes and apps)
./reset.sh --force

# Uninstall K3s completely
sudo /usr/local/bin/k3s-uninstall.sh      # Server
sudo /usr/local/bin/k3s-agent-uninstall.sh # Agent nodes
```

**Cleanup Script Comparison:**
- `./clean.sh`: Removes NotReady K3s nodes and unreachable phone devices from Tailscale
- `./reset.sh`: Removes ALL agent nodes and applications, resets to server-only state
- `./setup.sh cleanup`: Legacy method, removes only NotReady K3s nodes
- K3s uninstall scripts: Completely removes K3s from the system

**Remove Multiple Devices from Tailscale:**
```bash
# Remove devices by hostname prefix using Tailscale API
./remove_from_vpn.sh <TAILSCALE_API_KEY> <HOSTNAME_PREFIX>

# Example: Remove all devices starting with "phone-"
./remove_from_vpn.sh tskey-api-xxxxx phone-


# Example: Remove all test devices
./remove_from_vpn.sh tskey-api-xxxxx test-
```

*Note: Requires a Tailscale API key (not auth key) from your Tailscale admin console.*

## License

This project is licensed under the Apache 2.0 License.

### Third-Party Licenses

#### Gemma AI Model
The Android app includes the Gemma language model for advanced AI capabilities. The Gemma model is provided under and subject to the **Gemma Terms of Use** found at [ai.google.dev/gemma/terms](https://ai.google.dev/gemma/terms).

**Important**: By using the Android app with AI features, you agree to comply with the Gemma Terms of Use. Please review the license carefully before use.

#### Other Components
- K3s: Apache 2.0 License
- Tailscale (with headscale support being planned)  
- TensorFlow Lite models: Apache 2.0 License
- MediaPipe: Apache 2.0 License

# Roadmap

- [ ] test it with one phone as the basic k3s client
    - the computer being the host
- [x] deploy the application to the cluster with replication
- [ ] add another phone and improve deploy scripts
- [ ] test and fix phone app
- [x] use phone app to automatically update location labels in node
- [ ] test and fix advanced features of phone app
- [x] create a small webapp in Java that shows the location of all nodes on a map
- [ ] update phone app to use tokens and all to download gemma model directly
- [ ] test cluster without a computer, just two phones
- [ ] write blog post
