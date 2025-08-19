# K3s Single Node Cluster for Android Phones

## TL;DR - Quick Setup

Complete K3s cluster setup and HTTPBin deployment in 3 commands:

```bash
# 1. Setup K3s cluster on Android phone (standard mode)
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/single-node-cluster/setup.sh | bash -s -- -t YOUR_TAILSCALE_KEY -h phone-01

# 2. Deploy HTTPBin from remote machine (note the secret key from step 1 output)
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/single-node-cluster/httpbin.sh | bash -s -- deploy -u https://YOUR_PHONE.tailXXXX.ts.net -k YOUR_SECRET_KEY

# 3. Test HTTPBin endpoints
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/get
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/headers
curl -X POST http://YOUR_PHONE.tailXXXX.ts.net:30080/post -d '{"test":"data"}'
```

**Network Topology**: Phone (K3s + Tailscale funnel) ← HTTPS:443,6443 + HTTP:30080 → Remote machines
**Access**: kubeconfig server (HTTPS), K3s API (HTTPS), HTTPBin (HTTP on :30080)
**Security**: Authenticated kubeconfig download, app API local-only

---

This directory contains scripts for setting up a standalone K3s cluster on*Required for standard mode, not needed for local mode

### Secret Key Management

The setup script supports automatic secret key generation and management:

- **Auto-generation**: If no `-k` flag provided, generates a 24-character secure key
- **Persistent storage**: Saves key to `~/.k3s/secret-key` with 600 permissions
- **Automatic reuse**: Subsequent runs automatically use stored key
- **Manual override**: Use `-k` flag to specify custom key
- **Full display**: Shows complete secret key for easy copying

Example output:
```
[INFO] ✅ Generated secret key: GKsd1nkCaWKSb5FE6oLAEs64
[INFO] 📁 Stored in: /home/user/.k3s/secret-key
[INFO] Secret key: GKsd1nkCaWKSb5FE6oLAEs64
[INFO] Secret file: ~/.k3s/secret-key
```

### Setup Modes

1. **Standard Mode**: New tailnet node with full setup
   - Requires: `-t TAILSCALE_KEY -h HOSTNAME`
   - Installs Tailscale, sets hostname, installs K3s

2. **Local Mode (`--local`)**: Existing tailnet member
   - Requires: None (uses current hostname)
   - Skips Tailscale auth and hostname changes

3. **No K3s Mode (`--no-k3s-setup`)**: Use existing K3s cluster
   - Requires: Existing K3s installation
   - Validates cluster health and configures access

### What it does

1. **Docker Installation** - Installs Docker from official repository
2. **SSH Setup** - Configures OpenSSH server with security settings
3. **Hostname Configuration** - Sets hostname (supports `phone-%d` pattern) *[Standard mode]*
4. **Tailscale Setup** - Installs Tailscale and configures funnel *[Standard mode]*
5. **K3s Installation** - Single-node cluster with phone labels *[Unless --no-k3s-setup]*
6. **Port Forwarding** - Android app accessibility via localhost *[With --android]*
7. **Kubeconfig Server** - Authenticated config download service

### Generated Endpoints

After setup, the following endpoints are available:

- **K3s API**: `https://phone-XX.tailXXXX.ts.net:6443`
- **Kubeconfig**: `https://phone-XX.tailXXXX.ts.net:8443/kubeconfig?key=SECRET`
- **Cluster Apps**: `https://phone-XX.tailXXXX.ts.net:8087` (for NodePort services)
- **Android App**: `https://phone-XX.tailXXXX.ts.net:8005` (with --android)
- **Android App (local)**: `http://localhost:8005` (always available locally)h Tailscale funnel access for remote management.

## Overview

The single-node setup creates a complete Kubernetes cluster on a single Android phone that can be accessed remotely via Tailscale funnel, while keeping the Android app API secure and only accessible locally.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android Phone                            │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐ │
│  │ Android App │  │ K3s Cluster  │  │ Tailscale Funnel    │ │
│  │ (Port 8005) │  │ (Port 6443)  │  │ (External Access)   │ │
│  └─────────────┘  └──────────────┘  └─────────────────────┘ │
│         │                 │                    │            │
│         │                 │                    │            │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐ │
│  │Port Forward │  │ Kubeconfig   │  │ Public Endpoints:   │ │
│  │localhost:   │  │ Server       │  │ • K3s API :6443     │ │
│  │8005         │  │ (Port 8443)  │  │ • Kubeconfig :8443  │ │
│  └─────────────┘  └──────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                 Remote Machine                              │
│  ┌─────────────┐                                           │
│  │ httbin.sh   │ ──────► Tailscale Funnel                  │
│  │ (kubectl)   │         https://phone-XX.tailXXX.ts.net  │
│  └─────────────┘                                           │
└─────────────────────────────────────────────────────────────┘
```

## Features

- **Single-node K3s cluster** - Complete Kubernetes on one phone
- **Tailscale funnel** - Secure external access via Tailscale
- **Auto-generated secrets** - Automatic secret key generation and persistent storage
- **Local & standard modes** - Flexible setup for existing tailnets or new nodes
- **Optional K3s setup** - Skip K3s installation for existing clusters (--no-k3s-setup)
- **Android app protection** - App API only accessible locally
- **Remote deployment** - Deploy apps from any machine
- **Authenticated kubeconfig** - Secure config download with secret key
- **Port forwarding** - Local access to Android app functionality
- **Multi-port funnel** - K3s API (6443), kubeconfig (8443), cluster apps (8087)

## Quick Start

### Prerequisites

- **Android device** with root access and Termux installed
- **Internet connectivity**: `ping github.com` should work
- **Tailscale account** and authentication key (for standard mode)
- **kubectl** installed on remote machines for cluster management

### One-Line Installation

```bash
# Basic setup with Tailscale key and hostname
ping -c 1 github.com && curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/single-node-cluster/setup.sh | bash -s -- -t YOUR_TAILSCALE_KEY -h phone-01

# Local mode (existing tailnet)
ping -c 1 github.com && curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/single-node-cluster/setup.sh | bash -s -- --local
```

The `ping` command ensures internet connectivity before running the installation.

### Manual Installation

### 1. Setup on Android Phone

```bash
# Clone repository
git clone https://github.com/parttimenerd/k3s-on-phone.git
cd k3s-on-phone/single-node-cluster

# Basic setup with auto-generated secret key
./setup.sh -t tskey-auth-XXXXXXXXX -h phone-01

# OR: Setup with custom secret key
./setup.sh -t tskey-auth-XXXXXXXXX -h phone-01 -k mySecretKey123

# OR: Local mode (existing tailnet)
./setup.sh --local

# OR: Use existing K3s cluster
./setup.sh --local --no-k3s-setup
```

After setup, note the generated secret key and funnel domain from the output:
```
[INFO] ✅ Generated secret key: GKsd1nkCaWKSb5FE6oLAEs64
[INFO] � Kubeconfig: https://thinkstation.taile645ec.ts.net/kubeconfig?key=GKsd1nkCaWKSb5FE6oLAEs64
[INFO] 🧪 Test cluster access:
[INFO]    curl -s "https://thinkstation.taile645ec.ts.net/kubeconfig?key=GKsd1nkCaWKSb5FE6oLAEs64" -o /tmp/kubeconfig.yaml && kubectl --kubeconfig=/tmp/kubeconfig.yaml get nodes
```

### 2. Access Cluster Remotely

Use the exact command provided by the setup script:

```bash
# Download kubeconfig and test kubectl access
curl -s "https://YOUR_PHONE.tailXXXX.ts.net/kubeconfig?key=YOUR_SECRET_KEY" -o /tmp/kubeconfig.yaml && kubectl --kubeconfig=/tmp/kubeconfig.yaml get nodes

# Example with actual values:
curl -s "https://thinkstation.taile645ec.ts.net/kubeconfig?key=GKsd1nkCaWKSb5FE6oLAEs64" -o /tmp/kubeconfig.yaml && kubectl --kubeconfig=/tmp/kubeconfig.yaml get nodes
```

Expected output:
```
NAME           STATUS   ROLES                  AGE   VERSION
thinkstation   Ready    control-plane,master   1h    v1.33.3+k3s1
```

### 3. Deploy Applications

```bash
# Set kubeconfig for session
export KUBECONFIG=/tmp/kubeconfig.yaml

# Deploy a test application
kubectl create deployment nginx --image=nginx
kubectl expose deployment nginx --port=80 --type=NodePort

# Check deployment
kubectl get pods,svc

# Access via funnel (if port is in 30000-32767 range)
kubectl get svc nginx -o jsonpath='{.spec.ports[0].nodePort}'
# Then access: https://YOUR_PHONE.tailXXXX.ts.net:NODE_PORT
```

## Setup Script (`setup.sh`)

Sets up a complete single-node K3s cluster on the Android phone with flexible configuration options.

### Usage

```bash
# Standard mode (new tailnet node)
./setup.sh -t TAILSCALE_KEY -h HOSTNAME [-k SECRET_KEY] [OPTIONS]

# Local mode (existing tailnet)
./setup.sh --local [-k SECRET_KEY] [OPTIONS]
```

### Parameters

| Parameter | Required | Description | Example |
|-----------|----------|-------------|---------|
| `-t, --tailscale-key` | Yes* | Tailscale authentication key | `tskey-auth-XXXXXXXXX` |
| `-h, --hostname` | Yes* | Hostname for the node | `phone-01` or `phone-%d` |
| `-k, --secret-key` | No | Secret key for kubeconfig access | `mySecretKey123` |
| `--local` | No | Local mode: use existing tailnet | |
| `--android` | No | Enable Android app port forwarding | |
| `--no-k3s-setup` | No | Skip K3s installation (use existing) | |
| `--verbose` | No | Enable verbose logging | |
| `--dry-run` | No | Show what would be done | |

*Required for standard mode, not needed for local mode
| `--dry-run` | Show what would be done | |

### What it does

1. **Docker Installation** - Installs Docker from official repository
2. **SSH Setup** - Configures OpenSSH server with security settings
3. **Hostname Configuration** - Sets hostname (supports `phone-%d` pattern)
4. **Tailscale Setup** - Installs Tailscale and configures funnel
5. **K3s Installation** - Single-node cluster with phone labels
6. **Port Forwarding** - Android app accessibility via localhost
7. **Kubeconfig Server** - Authenticated config download service

### Generated Endpoints

After setup, the following endpoints are available:

- **K3s API**: `https://phone-XX.tailXXXX.ts.net:6443`
- **Kubeconfig**: `https://phone-XX.tailXXXX.ts.net:8443/kubeconfig?key=SECRET`
- **Android App (local)**: `http://localhost:8005` (always available locally)

### Setup Examples

```bash
# Basic setup with auto-generated secret
./setup.sh -t tskey-auth-XXXXXXXXX -h phone-01

# Custom secret key
./setup.sh -t tskey-auth-XXXXXXXXX -h phone-01 -k myCustomSecret123

# Random hostname pattern
./setup.sh -t tskey-auth-XXXXXXXXX -h phone-%d

# Local mode (existing tailnet)
./setup.sh --local

# Local mode with Android forwarding
./setup.sh --local --android

# Use existing K3s cluster
./setup.sh --local --no-k3s-setup

# Dry run to see what would happen
./setup.sh --local --dry-run --verbose
```

## HTTPBin Deployment (`httpbin.sh`)

Production-ready HTTPBin deployment script with automatic funnel configuration and comprehensive testing.

### Quick Deploy

```bash
# One-line deployment from remote machine
curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/single-node-cluster/httpbin.sh | bash -s -- deploy -u https://YOUR_PHONE.tailXXXX.ts.net -k YOUR_SECRET_KEY
```

### Usage

```bash
./httpbin.sh COMMAND -u FUNNEL_URL -k SECRET_KEY [OPTIONS]
```

**Commands**: `deploy`, `undeploy`, `status`, `test`

### Features

- **Automatic deployment cleanup** - Removes existing HTTPBin before redeploy
- **Funnel port management** - Ensures ports 443, 6443, 30080 are configured
- **Deployment testing** - Validates HTTPBin responds correctly via funnel
- **NodePort service** - HTTPBin accessible on port 30080 (HTTP protocol)
- **Kubernetes integration** - Standard K8s resources with proper labels

### Network Configuration

- **External access**: `http://phone.tailXXXX.ts.net:30080` (HTTP on port 30080)
- **Internal access**: `http://httpbin.default.svc.cluster.local:80`
- **NodePort mapping**: Container port 80 → NodePort 30080 → Tailscale funnel
- **Protocol**: HTTP (Tailscale terminates TLS externally)

### Examples

```bash
# Deploy HTTPBin
./httpbin.sh deploy -u https://phone-01.tailXXXX.ts.net -k mySecretKey123

# Check deployment status
./httpbin.sh status -u https://phone-01.tailXXXX.ts.net -k mySecretKey123

# Test all endpoints
./httpbin.sh test -u https://phone-01.tailXXXX.ts.net -k mySecretKey123

# Remove deployment
./httpbin.sh undeploy -u https://phone-01.tailXXXX.ts.net -k mySecretKey123
```

### HTTPBin Endpoints

Once deployed, HTTPBin provides comprehensive HTTP testing endpoints:

```bash
# Basic requests
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/get
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/headers
curl -X POST http://YOUR_PHONE.tailXXXX.ts.net:30080/post -d '{"test":"data"}'

# Status codes
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/status/200
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/status/404

# HTTP methods
curl -X PUT http://YOUR_PHONE.tailXXXX.ts.net:30080/put -d '{"key":"value"}'
curl -X DELETE http://YOUR_PHONE.tailXXXX.ts.net:30080/delete

# Response formats
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/json
curl http://YOUR_PHONE.tailXXXX.ts.net:30080/xml
```

## Remote kubectl Access

After setup, you can use kubectl from any machine to manage the cluster:

### Quick Setup

Use the exact command provided by the setup script output:

```bash
# Download kubeconfig and test access (replace with your actual URL and key)
curl -s "https://thinkstation.taile645ec.ts.net/kubeconfig?key=GKsd1nkCaWKSb5FE6oLAEs64" -o /tmp/kubeconfig.yaml && kubectl --kubeconfig=/tmp/kubeconfig.yaml get nodes
```

### Persistent Setup

```bash
# Download kubeconfig
curl -s "https://YOUR_PHONE.tailXXXX.ts.net/kubeconfig?key=YOUR_SECRET_KEY" -o ~/.kube/config-phone

# Set as default kubeconfig
export KUBECONFIG=~/.kube/config-phone

# Or use specific kubeconfig for commands
kubectl --kubeconfig=~/.kube/config-phone get nodes
```

### Common kubectl Commands

```bash
# View cluster info
kubectl cluster-info
kubectl get nodes -o wide

# Deploy applications
kubectl create deployment nginx --image=nginx
kubectl expose deployment nginx --port=80 --type=NodePort

# Monitor resources
kubectl get pods,svc,deployments -A
kubectl top nodes
kubectl top pods

# Access applications
kubectl get svc nginx -o jsonpath='{.spec.ports[0].nodePort}'
# Then access: https://YOUR_PHONE.tailXXXX.ts.net:NODE_PORT

# Cleanup
kubectl delete deployment nginx
kubectl delete service nginx
```

### Kubeconfig Details

The downloaded kubeconfig includes:
- **Server URL**: Points to your phone's funnel domain (e.g., `https://phone-01.taile645ec.ts.net:6443`)
- **TLS Configuration**: `insecure-skip-tls-verify: true` for certificate domain mismatch handling
- **Authentication**: Client certificates for cluster access
- **Context**: Pre-configured context pointing to your single-node cluster

## Security

### Protected Resources

- **Android App API** - Only accessible via localhost (not exposed via funnel)
- **Kubeconfig Server** - Requires secret key authentication
- **K3s API** - Standard Kubernetes RBAC authentication

### Network Topology

```
Internet ──► Tailscale Funnel ──► Phone
             │
             ├─ :6443 (K3s API) ✅ Public
             ├─ :8443 (Kubeconfig) ✅ Public + Auth
             ├─ :8087 (Cluster Apps) ✅ Public
             └─ :8005 (Android App) ✅ Public (with --android) / ❌ Local Only (default)
```

**Port Details:**
- **6443**: K3s API server - Standard Kubernetes API access
- **8443**: Kubeconfig server - Authenticated download endpoint
- **8087**: Cluster applications - NodePort services and custom apps
- **8005**: Android app - Optional external access with `--android` flag

### Authentication Flow

1. Client requests kubeconfig: `GET /kubeconfig?key=SECRET`
2. Server validates secret key
3. Server returns kubeconfig with funnel URL
4. Client uses kubeconfig to access K3s API

## Troubleshooting

### Secret Key Issues

```bash
# Check if secret key file exists
ls -la ~/.k3s/secret-key

# View stored secret key
cat ~/.k3s/secret-key

# Regenerate secret key (remove file and run setup again)
rm ~/.k3s/secret-key
./setup.sh --local --dry-run  # Will generate new key

# Use custom secret key
./setup.sh --local -k "your-custom-secret-key"
```

### Setup Issues

```bash
# Check Tailscale status
tailscale status

# Check funnel status
tailscale funnel status

# Check K3s status
sudo systemctl status k3s

# Check services
sudo systemctl status kubeconfig-server
sudo systemctl status k3s-app-forwarder

# View logs
sudo journalctl -u k3s -f
sudo journalctl -u kubeconfig-server -f
```

### Deployment Issues

```bash
# Test cluster connection
kubectl cluster-info

# Check node status
kubectl get nodes -o wide

# Check deployments
kubectl get deployments,pods,services -A

# View pod logs
kubectl logs -f deployment/httpbin
```

### Network Issues

```bash
# Test kubeconfig download
curl "https://phone-01.tailXXXX.ts.net:8443/kubeconfig?key=SECRET"

# Test funnel connectivity
curl -k "https://phone-01.tailXXXX.ts.net:6443/version"

# Check Android app (local only)
curl "http://localhost:8005/status"
```

## Advanced Usage

### Custom Applications

Create your own deployment manifests and apply them:

```bash
# Download kubeconfig first
curl "https://phone-01.tailXXXX.ts.net:8443/kubeconfig?key=SECRET" -o kubeconfig.yaml

# Set environment
export KUBECONFIG=kubeconfig.yaml

# Deploy custom application
kubectl apply -f my-app.yaml
```

### Multiple Clusters

Set up multiple phones and manage them separately:

```bash
# Setup phone-01
./setup.sh -t tskey1 -h phone-01 -k secret1

# Setup phone-02  
./setup.sh -t tskey2 -h phone-02 -k secret2

# Deploy HTTPBin to phone-01
./httpbin.sh deploy -u https://phone-01.tailXXXX.ts.net -k secret1

# Deploy HTTPBin to phone-02
./httpbin.sh deploy -u https://phone-02.tailXXXX.ts.net -k secret2
```

### Monitoring

Add monitoring stack to your cluster:

```bash
# Deploy simple monitoring
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml

# Access via port-forward
kubectl port-forward -n kubernetes-dashboard service/kubernetes-dashboard 8080:443
```

## Complete Workflow Example

Here's a complete example showing the full process from setup to deployment:

### On Android Phone:
```bash
# 1. Clone and setup (auto-generated secret)
git clone https://github.com/parttimenerd/k3s-on-phone.git
cd k3s-on-phone/single-node-cluster
./setup.sh --local

# Note the output showing your secret and funnel URL:
# [INFO] ✅ Generated secret key: GKsd1nkCaWKSb5FE6oLAEs64
# [INFO] 📁 Stored in: /home/user/.k3s/secret-key
# [INFO] 🌐 K3s API accessible: https://phone-01.tail12345.ts.net:6443
# [INFO] 📄 Kubeconfig: https://phone-01.tail12345.ts.net:8443/kubeconfig?key=GKsd1nkCaWKSb5FE6oLAEs64
```

### On Remote Machine:
```bash
# 2. Deploy HTTPBin using the URLs from setup output
./httpbin.sh deploy -u https://phone-01.tail12345.ts.net -k GKsd1nkCaWKSb5FE6oLAEs64

# 3. Access your deployed HTTPBin
curl http://phone-01.tail12345.ts.net:30080/get

# 4. Or use kubectl directly
curl "https://phone-01.tail12345.ts.net:8443/kubeconfig?key=GKsd1nkCaWKSb5FE6oLAEs64" -o kubeconfig.yaml
export KUBECONFIG=kubeconfig.yaml
kubectl get pods
```

## Requirements

### Phone Setup
- Android phone with Linux terminal (Termux, UserLAnd, etc.)
- Root access or privileged container
- Network connectivity
- Sufficient storage (2GB+ recommended)

### Remote Machine
- kubectl installed
- curl available
- Network access to Tailscale funnel

## Files

- `setup.sh` - Main setup script for the phone
- `httpbin.sh` - HTTPBin deployment script for remote machines
- `README.md` - This documentation

## License

Same as the main k3s-on-phone project.
