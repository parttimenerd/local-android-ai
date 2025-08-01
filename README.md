# K3s on Phone

⚠️ **EARLY PROTOTYPE - USE AT YOUR OWN RISK** ⚠️

**This is an experimental proof-of-concept project. None of the features are guaranteed to work. Use entirely at your own risk. Only intended for exploration.**

Kubernetes cluster deployment on Android devices using Debian in KVM via Android Linux Terminal.

## Overview

⚠️ **EXPERIMENTAL PROJECT**: This is a rough prototype for educational and experimental purposes only. Features may not work as expected, may break without notice, and are only suitable for exploration.

Deploy a multi-node K3s cluster across Android phones using:
- K3s lightweight Kubernetes distribution
- Tailscale mesh VPN for secure networking  
- Java sample application demonstrating load balancing
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

### Worker Node Setup  
```bash
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/setup.sh | bash -s -- phone-02 -t YOUR_TAILSCALE_KEY -k K3S_TOKEN -u https://phone-01:6443
```

### Deploy Sample Application

#### Option 1: Using Local Registry (Recommended for Multi-Node)
```bash
# Set up local Docker registry
./setup-registry.sh setup

# Build and push application image
cd sample_app
./build.sh
../setup-registry.sh push server-info-server:latest

# Deploy application
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

## Registry Management

The project includes a comprehensive Docker registry management system for easy image distribution across K3s nodes.

### Registry Setup
```bash
# Set up local Docker registry
./setup-registry.sh setup

# Check registry status
./setup-registry.sh status

# View registry information
./setup-registry.sh info
```

### Image Management
```bash
# List all images in registry
./setup-registry.sh list

# Push a local image to registry
./setup-registry.sh push my-app:latest

# Pull an image from registry
./setup-registry.sh pull my-app:latest

# Delete an image from registry
./setup-registry.sh delete my-app:latest
```

### Registry Operations
```bash
# Start/stop registry
./setup-registry.sh start
./setup-registry.sh stop
./setup-registry.sh restart

# View registry logs
./setup-registry.sh logs

# Clean up unused data
./setup-registry.sh cleanup

# Completely remove registry
./setup-registry.sh remove
```

### K3s Integration
```bash
# Configure K3s nodes to use registry
./setup-registry.sh configure-k3s

# Registry automatically configures:
# - Master node with registries.yaml
# - Agent nodes via SSH (if accessible)
# - Insecure registry settings for local development
```

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

**Script Comparison:**
- `reset.sh`: Removes ALL nodes and apps, resets to server-only cluster
- `clean.sh`: Removes only NotReady/unreachable nodes, keeps working cluster
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

## Monitoring and Operations

### Cluster Status
```bash
# Node information
kubectl get nodes -o wide
kubectl top nodes

# Pod status across namespaces  
kubectl get pods --all-namespaces
kubectl top pods --all-namespaces

# Service discovery
kubectl get services --all-namespaces
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

- [x] test it with one phone as the basic k3s client
    - the computer being the host
- [ ] deploy the application to the cluster with replication
- [ ] add another phone and improve deploy scripts
- [ ] test and fix phone app
- [ ] test and fix advanced features of phone app
- [ ] test cluster without a computer, just two phones
- [ ] write blog post
