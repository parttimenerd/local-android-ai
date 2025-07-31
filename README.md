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
```bash
cd sample_app
./build.sh && ./deploy.sh && ./test.sh
```

### Android Phone Server App

Optional Android app providing additional services like GPS location, device orientation, AI vision, and camera capture via HTTP API on port 8005.

See [android/README.md](android/README.md) for installation and usage details.

## Detailed Setup

### Prerequisites Installation

1. **Android Linux Terminal**: Install from Play Store
2. **Debian Environment**: Set up within the terminal app
3. **Basic Tools**: Install curl, wget, git within Debian
4. **Tailscale Account**: Register at tailscale.com

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

# Clean cluster setup
./setup.sh cleanup --remove-tailscale

# Uninstall K3s
sudo /usr/local/bin/k3s-uninstall.sh      # Server
sudo /usr/local/bin/k3s-agent-uninstall.sh # Agent nodes
```

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
- [ ] deploy the application to the cluster with replication
- [ ] add another phone and improve deploy scripts
- [ ] test and fix phone app
- [ ] test and fix advanced features of phone app
- [ ] test cluster without a computer, just two phones
- [ ] write blog post
