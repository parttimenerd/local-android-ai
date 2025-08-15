# K3s on Phone - Android Kubernetes with AI

⚠️ **EXPERIMENTAL PROJECT** ⚠️

Kubernetes cluster deployment on Android devices with on-device AI inference.

## 📱 Download Android App

[![Latest Release](https://img.shields.io/github/v/release/parttimenerd/k3s-on-phone?label=Latest%20Release)](https://github.com/parttimenerd/k3s-on-phone/releases/latest)

**Direct APK Downloads:**
- **[📱 Debug APK](https://github.com/parttimenerd/k3s-on-phone/releases/latest/download/app-debug.apk)** - Development build with debugging enabled
- **[🚀 Release APK](https://github.com/parttimenerd/k3s-on-phone/releases/latest/download/app-release-unsigned.apk)** - Production build (optimized)

**Installation:**
1. Enable "Install from unknown sources" in Android Settings → Security
2. Download and install the APK file
3. Grant location and camera permissions when prompted
4. Verify the app shows "Server running on port 8005"

**⚠️ Important:** Start the Android app BEFORE setting up Kubernetes nodes!

## Project Overview

- **K3s cluster nodes** running in Android Linux containers
- **Android app** with MediaPipe LLM inference and object detection
- **REST API server** on port 8005 with AI, camera, and location endpoints
- **Location tracking** for automatic node labeling
- **Sample applications** including geocoder and cluster dashboard
- **Unified CLI interface** with command-specific help and discovery

## Android App Features

- **LLM Inference**: Gemma, DeepSeek-R1, Llama 3.2, TinyLlama models via MediaPipe
- **Object Detection**: MediaPipe EfficientDet Lite 2
- **Location Services**: GPS tracking with accuracy metadata
- **Camera Integration**: Front/rear camera with zoom and base64 encoding
- **Device Sensors**: Compass orientation and device data
- **Model Management**: Download, test, and manage AI models with performance metrics

## CLI Management Features

- **Unified Command Interface**: All operations through `./setup.sh` with consistent syntax
- **Command-Specific Help**: Detailed help for each command with examples and options
- **Tab Completion**: Project-specific autocompletion for commands, options, and arguments (bash/zsh)
- **Parallel Network Discovery**: Fast server scanning with concurrent connections
- **Endpoint Validation**: Automatic testing of server capabilities during discovery
- **Interactive Guidance**: Clear error messages and troubleshooting suggestions
- **Auto-completion Installation**: Automatically offers tab completion after successful operations
- **Legacy Compatibility**: Backward compatibility with existing script workflows

## Quick Start

### Requirements
- Android devices with developer mode
- Android Linux Terminal with Debian
- Tailscale account
- 4GB+ RAM, 64GB+ storage

⚠️ **[Download and install K3s Phone Server Android app](https://github.com/parttimenerd/k3s-on-phone/releases/latest) before setup**

### Master Node
```bash
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/setup.sh | bash -s -- phone-01 -t YOUR_TAILSCALE_KEY
```

### Worker Node
```bash
# 1. Download and install K3s Phone Server Android app from:
#    https://github.com/parttimenerd/k3s-on-phone/releases/latest
# 2. START the app and ensure it's running on port 8005
# 3. Run agent setup:
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/setup.sh | bash -s -- phone-02 -t YOUR_TAILSCALE_KEY -k K3S_TOKEN -u https://phone-01:6443
```

## Tab Completion

Install project-specific tab completion for enhanced productivity:

```bash
# Install completion for current shell (bash/zsh) - user level only
./install-completion.sh

# Or install manually:
# For bash:
source k3s-completion.sh

# For zsh:
source _k3s_setup

# Uninstall if needed:
./install-completion.sh uninstall
```

**Features:**
- **Project-specific**: Only activates for K3s Phone Setup scripts, won't conflict with other projects
- **Command completion**: `./setup.sh sca<TAB>` → `./setup.sh scan-for-server`
- **Option completion**: `./setup.sh scan-for-server --<TAB>` → shows all options
- **Smart suggestions**: Subnet patterns, hostnames, Kubernetes namespaces
- **Context-aware**: Different options per command
- **User-level**: No sudo required, installs only for current user
- **Auto-installation**: Automatically offers to install after successful commands

**Examples:**
```bash
./setup.sh <TAB><TAB>              # Show all commands
./setup.sh scan-for-server <TAB>   # Show scan options and subnet suggestions  
./setup.sh phone-<TAB>             # Complete to phone-01, phone-02, etc.
./setup.sh status -n <TAB>         # Show available Kubernetes namespaces
```

**Note**: The completion system is designed to be project-specific. It only activates for setup.sh files containing K3s Phone Setup code, preventing conflicts with other projects that might have their own setup.sh files.

## API Endpoints (Port 8005)

### AI Services
- `POST /ai/text` - LLM text generation with streaming
- `POST /ai/object_detection` - MediaPipe object detection
- `GET /ai/models` - Available models with memory requirements
- `POST /ai/models/test` - Model testing with performance metrics

### Device Integration  
- `GET /location` - GPS coordinates with accuracy
- `GET /orientation` - Compass data (azimuth, pitch, roll)
- `GET /capture` - Camera capture with zoom support
- `GET /status` - Server status and capabilities
- `GET /help` - Available endpoints and documentation

## Server Discovery

Server discovery with parallel scanning for network discovery:

### Automatic Scanning
```bash
# Scan for K3s Phone Servers with parallel processing
./setup.sh scan-for-server 192.168.179.0/24

# Scan default subnet (192.168.179.0/24)
./setup.sh scan-for-server

# Scan with verbose endpoint testing
./setup.sh scan-for-server 192.168.1.0/24 --verbose
```

### Discovery Features
- **Parallel Scanning**: High-performance batch processing (20 concurrent scans)
- **Early Termination**: Stops at first responsive server found
- **Endpoint Testing**: Validates `/status`, `/location`, `/orientation`, `/help` endpoints
- **Health Verification**: Confirms server responds with "K3s Phone Server" identification
- **Network Latency**: Reports average ping times for discovered servers
- **Capability Detection**: Lists available AI models and device features

### Integration with Setup
Server discovery is automatically used during cluster setup:
- Worker nodes automatically scan for master servers
- Health monitoring uses the same discovery logic
- Port forwarding adapts to server changes automatically

## Sample Applications

### Geocoder Service
Reverse geocoding service using GeoNames data:
```bash
cd geocoder_app && ./deploy.sh
```

### Sample App
Basic Spring Boot application demonstrating cluster API usage:
```bash
cd sample_app && ./deploy.sh
```

## Cluster Management

```bash
# Get help for any command
./setup.sh --help                             # Show all available commands
./setup.sh COMMAND --help                     # Get command-specific help

# Server discovery and scanning
./setup.sh scan-for-server 192.168.179.0/24  # Parallel subnet scan
./setup.sh scan-for-server                    # Scan default subnet

# Cluster status with locations and object detection
./setup.sh status -v --object-detection -w

# Interactive dashboard
./setup.sh dashboard

# Clean unreachable nodes  
./setup.sh clean

# Reset cluster
./setup.sh reset --force
```

## Monitoring & Dashboard

- **Location tracking**: GPS coordinates with reverse geocoding
- **Object detection**: Real-time camera analysis from all nodes  
- **Resource monitoring**: Node and pod usage
- **Live updates**: Auto-refresh every 5-20 seconds
- **Interactive map**: OpenStreetMap visualization

## 🗺️ Location Monitoring

**Simplified SSH-based approach** for reliable location tracking:

- ✅ **Server-side monitoring**: Centralized location collection
- ✅ **Direct API calls**: HTTP requests to `$NODE_NAME:8005/location`
- ✅ **Automatic labeling**: Node labels updated with GPS + city data
- ✅ **No complex services**: Simple SSH + kubectl approach

## 📁 Project Structure

```
k3s-on-phone/
├── android/                    # AI-enabled Android app
│   ├── app/src/main/kotlin/    # Kotlin source code (AI, camera, server)
│   └── mediapipe/              # MediaPipe AI components
├── geocoder_app/               # Reverse geocoding service
├── sample_app/                 # Example K3s application  
├── setup.sh                    # Main installation script
├── status.sh                   # Enhanced status with locations/object detection
├── dashboard.sh                # Interactive web dashboard
└── *.sh                        # Utility scripts
```

## 🔧 Troubleshooting

### Network Connectivity
- **Debian Issues**: If setup fails, restart and reinstall Debian in Android Linux Terminal
- **Basic Tests**: `ping github.com` and `ping 8.8.8.8` should work

### Common Issues
- **Node Join**: Check Tailscale status and K3s token
- **App Connection**: Ensure Android app is running on port 8005
- **Location Updates**: Verify SSH keys between server and agents

### Tab Completion Issues
- **Test Synchronization**: Run `./test-completions.sh` to verify completion files are synchronized
- **Reload Shell**: After installation, restart your shell or run `source ~/.bashrc` (bash) or `source ~/.zshrc` (zsh)
- **Wrong Completions**: The system only activates for K3s Phone Setup scripts - other setup.sh files get default completion

## 📄 License

Apache 2.0 License - Educational/experimental use only.

```bash
# Show comprehensive help and command overview
./setup.sh --help

# Get command-specific help
./setup.sh scan-for-server --help    # Detailed scanning options and examples
./setup.sh my-phone-01 --help        # Node setup help and configuration
./setup.sh clean --help              # Cleanup options and safety features
./setup.sh status --help             # Status and monitoring options
./setup.sh reset --help              # Reset options with safety warnings

# Server discovery and network scanning
./setup.sh scan-for-server                   # Scan default subnet (192.168.179.0/24)
./setup.sh scan-for-server 192.168.1.0/24    # Scan specific subnet with parallel processing
./setup.sh scan-for-server --verbose       # Verbose scanning with endpoint testing

# Port forwarding setup
./setup.sh setup-port                        # Setup port forwarding to discovered server

# Cluster status and diagnostics
./setup.sh status                    # Basic cluster overview
./setup.sh status -n default         # Status for specific namespace
./setup.sh status -w                 # Watch mode with live updates
./setup.sh status -s                 # Include system namespaces

# Clean up dead/unreachable nodes
./setup.sh clean                     # Remove NotReady K3s nodes only
./setup.sh clean -t api-key          # Also remove from Tailscale VPN
./setup.sh clean --dry-run           # Preview what would be cleaned

# Reset cluster (destructive)
./setup.sh reset                     # Interactive reset with confirmation
./setup.sh reset --force             # Force reset without confirmation
./setup.sh reset --remove-from-tailscale  # Also remove from Tailscale

# Test simplified location monitoring
./setup.sh test-location             # Test SSH-based location system
```

### Direct Script Access (Alternative)

All scripts remain available for direct use:
```bash
./status.sh -v                       # Verbose cluster status
./clean.sh -t api-key --force        # Force cleanup with Tailscale
./reset.sh --remove-from-tailscale   # Reset and clean Tailscale
./test-simplified-location.sh        # Test location monitoring
```

### Specialized Scripts

```bash
# Registry management (not integrated - specialized tool)
./registry.sh status                 # Check registry status
./registry.sh list                   # List registry images
./registry.sh push image:tag         # Push image to registry

# Advanced operations
./delete.sh pod my-app --dry-run     # Safe resource deletion
./remove_from_vpn.sh api-key prefix  # Bulk Tailscale removal
./update-node-locations.sh --once    # Manual location update
```

### Location Monitoring Management

```bash
# Check location monitoring service
sudo systemctl status location-monitor

# View location monitoring logs
sudo journalctl -u location-monitor -f

# Manual location update (run from server)
sudo /usr/local/bin/update-node-locations.sh --once --verbose

# Test location monitoring setup
./setup.sh test-location

# View node location labels
kubectl get nodes -l device-type=phone -o json | jq '.items[].metadata.labels | with_entries(select(.key | startswith("phone.location")))'
```



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
# Interactive reset with confirmation (using integrated command)
./setup.sh reset

# Force reset without confirmation
./setup.sh reset --force

# Reset and remove nodes from Tailscale
./setup.sh reset --remove-from-tailscale --force

# Alternative: Direct script access
./reset.sh --force
```

### Clean Dead Nodes and Devices
```bash
# Remove NotReady K3s nodes only (using integrated command)
./setup.sh clean

# Remove NotReady nodes + unreachable "phone-..." from Tailscale VPN
./setup.sh clean -t tskey-api-xxxxx

# Dry run to preview cleanup
./setup.sh clean -t tskey-api-xxxxx --dry-run

# Alternative: Direct script access
./clean.sh -t tskey-api-xxxxx
```

### Check Cluster Status
```bash
# Show comprehensive cluster status (using integrated command)
./setup.sh status

# Show status for specific namespace
./setup.sh status -n default

# Watch mode with continuous refresh
./setup.sh status -w

# Alternative: Direct script access
./status.sh -s
```

### Test Location Monitoring
```bash
# Test the simplified location monitoring system
./setup.sh test-location

# Alternative: Direct script access
./test-simplified-location.sh
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

**Script Integration:**
- `./setup.sh reset`: Integrated cluster reset (removes ALL nodes and apps)
- `./setup.sh clean`: Integrated cleanup (removes only NotReady/unreachable nodes)
- `./setup.sh status`: Integrated cluster status and diagnostics
- `./setup.sh test-location`: Integrated location monitoring testing
- `./delete.sh`: Safely delete specific resources (pods, deployments, services, nodes)
- Direct script access still available: `./reset.sh`, `./clean.sh`, `./status.sh`, etc.

### Android Phone Server App

Simplified Android app providing GPS location and device orientation services via HTTP API on port 8005.

**Key Features:**
- GPS location data for K3s node labeling
- Device orientation/compass data
- Simple, stable implementation without AI complexity
- Lightweight with minimal dependencies

See [android/README.md](android/README.md) and [android/SIMPLIFIED_API.md](android/SIMPLIFIED_API.md) for installation and usage details.

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

**Simplified Location Monitoring:**
The server node runs a monitoring script that queries all phone nodes via SSH every 30 seconds and automatically updates node labels:
- `phone.location/latitude`
- `phone.location/longitude` 
- `phone.location/altitude`
- `phone.location/city` (e.g., "London, GB")
- `phone.location/updated` (timestamp)
- `phone.location/status` (active/inactive)

**How it works:**
- **Server-side script**: `/usr/local/bin/update-node-locations.sh`
- **SSH querying**: Direct connection to phone Android apps on port 8005
- **No complex authentication**: Uses simple SSH keys and kubectl commands
- **Systemd service**: `location-monitor.service` for continuous monitoring

**Prerequisites:**
```bash
# Set up SSH keys (run on server)
ssh-keygen -t rsa -b 4096 -f ~/.ssh/id_rsa
ssh-copy-id user@phone-hostname

# Test SSH connectivity
ssh phone-hostname "echo 'SSH working'"

# Test Android app
curl http://phone-hostname:8005/location
```

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

### Simplified Location Monitoring
```bash
# Check location monitoring service (server node)
sudo systemctl status location-monitor

# View location monitoring logs
sudo journalctl -u location-monitor -f

# Manual location update (server node)
sudo /usr/local/bin/update-node-locations.sh --once --verbose

# Test phone app API directly
curl -s http://phone-hostname:8005/location

# Check phone location labels with city information
kubectl get nodes -l device-type=phone -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.phone\.location/latitude}{"\t"}{.metadata.labels.phone\.location/longitude}{"\t"}{.metadata.labels.phone\.location/altitude}{"\t"}{.metadata.labels.phone\.location/city}{"\n"}{end}'

# Test location monitoring script
./test-simplified-location.sh

# SSH connectivity test
ssh phone-hostname "curl -s http://localhost:8005/location"
```

### Testing & Diagnostics
```bash
# Test simplified location monitoring
./test-simplified-location.sh

# Quick location monitoring diagnostics
sudo systemctl status location-monitor
sudo /usr/local/bin/update-node-locations.sh --once --verbose

# Test SSH connectivity to phone nodes
ssh phone-hostname "curl -s http://localhost:8005/location"

# Check node labels
kubectl get nodes -l device-type=phone -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.phone\.location/latitude}{"\t"}{.metadata.labels.phone\.location/longitude}{"\t"}{.metadata.labels.phone\.location/city}{"\n"}{end}'
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

### Quick Troubleshooting with Integrated Commands

The unified command interface makes troubleshooting easier:

```bash
# Discover available K3s Phone Servers
./setup.sh scan-for-server            # Find servers on default subnet
./setup.sh scan-for-server 192.168.1.0/24  # Scan specific network

# Get comprehensive cluster overview
./setup.sh status -v

# Check if nodes are responding
./setup.sh status -w           # Watch mode shows real-time updates

# Check location monitoring system
./setup.sh test-location       # Test SSH-based location system
sudo systemctl status location-monitor

# Clean up problematic nodes
./setup.sh clean --dry-run     # Preview what would be cleaned
./setup.sh clean -t api-key    # Clean NotReady nodes + Tailscale

# If cluster is completely broken
./setup.sh reset --force       # Nuclear option: reset everything
```

**First Steps for Any Issue:**
0. `./setup.sh --help` - Get overview of all available commands
1. `./setup.sh scan-for-server` - Discover available K3s Phone Servers
2. `./setup.sh status` - Get cluster overview
3. `kubectl get nodes` - Check node status  
4. `tailscale status` - Verify VPN connectivity
5. `./setup.sh clean --dry-run` - Check for dead nodes

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

# Clean dead/unreachable nodes only (integrated command)
./setup.sh clean -t tskey-api-xxxxx --force

# Reset cluster to clean state (removes all agent nodes and apps)
./setup.sh reset --force

# Uninstall K3s completely
sudo /usr/local/bin/k3s-uninstall.sh      # Server
sudo /usr/local/bin/k3s-agent-uninstall.sh # Agent nodes
```

**Integrated Cleanup Commands:**
- `./setup.sh clean`: Removes NotReady K3s nodes and unreachable phone devices from Tailscale
- `./setup.sh reset`: Removes ALL agent nodes and applications, resets to server-only state
- `./setup.sh status`: Shows comprehensive cluster status and diagnostics
- Direct script access: `./clean.sh`, `./reset.sh`, `./status.sh` also available
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

This project is licensed under the Apache 2.0 License and has no relationship to the k3s project, other than using it.

### Third-Party Licenses

The simplified Android app now uses minimal dependencies:

#### Core Components
- K3s: Apache 2.0 License
- Tailscale (with headscale support being planned)


## Roadmap

- [ ] test it with one phone as the basic k3s client
    - the computer being the host
- [x] deploy the application to the cluster with replication
- [ ] add another phone and improve deploy scripts
- [x] test and fix phone app
  - [x] start with simple app that just gives location (✅ Completed - AI removed)
  - [x] add taking photos (Optional - may add back later)
  - [x] add LLM stuff (Optional - may add back later)
- [x] use phone app to automatically update location labels in node
- [x] **simplified location monitoring with SSH-based approach**
- [x] **unified script interface - all commands integrated into setup.sh**
- [x] test and fix advanced features of phone app
- [x] create a small webapp in Java that shows the location of all nodes on a map
- [x] update phone app to use tokens and all to download gemma model directly
- [ ] write blog post


now
- [x] fix app (test that basic LLM works)
- [ ] setup on agent
- [ ] location label updating
- [ ] dashboard.sh
- [ ] status.sh
- [ ] sample appö- [ ] with second phone