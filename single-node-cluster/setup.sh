#!/usr/bin/env bash
set -euo pipefail

# Setup script for K3s single-node cluster with automatic Tailscale funnel configuration
# Configures kubeconfig server on main domain to avoid TLS issues
# Usage: ./setup.sh -t TAILSCALE_KEY -h HOSTNAME -k SECRET_KEY
#
# This script sets up:
# - Docker and SSH as in the main setup
# - Hostname configuration 
# - Tailscale with funnel for external access
# - K3s in single-node mode (no agents)
# - Port forwarding for Android app access
# - Secure kubeconfig server with authentication
# - App API protection (not accessible via funnel)

SCRIPT_VERSION="1.0.0"
SCRIPT_NAME="$(basename "$0")"

# Default values
TAILSCALE_KEY=""
HOSTNAME=""
SECRET_KEY=""
VERBOSE=false
DRY_RUN=false
LOCAL_MODE=false
ANDROID_FORWARDING=false
NO_K3S_SETUP=false

# Exit codes
EXIT_SUCCESS=0
EXIT_INVALID_ARGS=1
EXIT_INSTALL_FAILED=2
EXIT_CONFIG_FAILED=3
EXIT_NETWORK_FAILED=4
EXIT_AUTH_FAILED=5

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log() {
    echo -e "${GREEN}[INFO]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

log_verbose() {
    if [ "$VERBOSE" = true ]; then
        echo -e "${YELLOW}[VERBOSE]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
    fi
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

log_step() {
    echo -e "\n${BLUE}==== $1 ====${NC}" >&2
}

show_help() {
    cat << EOF
K3s Single Node Cluster Setup v${SCRIPT_VERSION}

Creates a standalone K3s cluster on Android phones with Tailscale funnel access.

USAGE:
    $SCRIPT_NAME -t TAILSCALE_KEY -h HOSTNAME [-k SECRET_KEY] [OPTIONS]
    $SCRIPT_NAME --local [-k SECRET_KEY] [OPTIONS]

REQUIRED PARAMETERS:
    -t, --tailscale-key KEY    Tailscale authentication key (not needed in local mode)
    -h, --hostname HOSTNAME    Hostname for the node (not changed in local mode)

OPTIONAL PARAMETERS:
    -k, --secret-key KEY       Secret key for kubeconfig access (auto-generated if not provided)

OPTIONS:
    --local                    Local mode: skip hostname/Tailscale setup (use existing tailnet)
    --android                  Enable Android app port forwarding (8005)
    --no-k3s-setup            Skip K3s installation (requires existing K3s cluster)
    --verbose                  Enable verbose logging
    --dry-run                  Show what would be done without executing
    --help                     Show this help message

EXAMPLES:
    # Basic setup (new tailnet node)
    $SCRIPT_NAME -t tskey-auth-xxx -h phone-01 -k mySecretKey123

    # Basic setup with auto-generated secret key
    $SCRIPT_NAME -t tskey-auth-xxx -h phone-01

    # Local mode (existing tailnet node)
    $SCRIPT_NAME --local -k mySecretKey123

    # Local mode with auto-generated secret key
    $SCRIPT_NAME --local

    # With Android app forwarding
    $SCRIPT_NAME --local -k mySecretKey123 --android

    # Skip K3s setup (use existing cluster)
    $SCRIPT_NAME --local -k mySecretKey123 --no-k3s-setup

    # Use existing K3s with Android forwarding
    $SCRIPT_NAME --local -k mySecretKey123 --no-k3s-setup --android

    # With auto-generated hostname and Android forwarding
    $SCRIPT_NAME -t tskey-auth-xxx -h phone-%d -k mySecretKey123 --android

    # Verbose local mode with Android
    $SCRIPT_NAME --local -k mySecretKey123 --android --verbose

FEATURES:
    • Docker and SSH configuration
    • Hostname setup with phone-%d pattern support (standard mode)
    • Tailscale installation with funnel configuration (standard mode)
    • Tailscale funnel setup for existing tailnet (local mode)
    • K3s single-node cluster installation or existing cluster validation
    • Auto-generated secret keys with persistent storage (~/.k3s/secret-key)
    • Optional Android app port forwarding (8005) with --android flag
    • Secure kubeconfig server with authentication
    • NodePort services exposed via funnel (port 30080)
    • App API protection (not exposed via funnel)

LOCAL MODE:
    • Skips hostname changes (uses current hostname)
    • Skips Tailscale installation/authentication
    • Requires existing Tailscale connection
    • Only configures funnel for existing tailnet
    • Ideal for phones already in a tailnet

SECURITY:
    • Kubeconfig server requires SECRET_KEY authentication
    • App API (port 8005) only accessible locally
    • Tailscale funnel exposes K3s API (6443), kubeconfig server (main domain), and NodePort services (30080)

NETWORK TOPOLOGY:
    Phone (K3s + App) <-- Local --> Port Forwarding
                      <-- Tailscale Funnel --> External Access

TROUBLESHOOTING:
    • Kubeconfig server logs: sudo journalctl -u kubeconfig-server -f
    • Kubeconfig server log file: /var/log/kubeconfig-server.log
    • K3s logs: sudo journalctl -u k3s -f
    • Tailscale logs: sudo journalctl -u tailscaled -f
    • Tailscale funnel status: tailscale funnel status
    • Test kubeconfig server locally: curl http://localhost:8443/status
    • Test via funnel: curl -k https://hostname.domain.ts.net/status

FILES:
    • Secret key storage: ~/.k3s/secret-key
    • K3s config: /etc/rancher/k3s/k3s.yaml
    • Kubeconfig server: /usr/local/bin/kubeconfig-server
                      
EOF
}

# Parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -t|--tailscale-key)
                TAILSCALE_KEY="$2"
                shift 2
                ;;
            -h|--hostname)
                HOSTNAME="$2"
                shift 2
                ;;
            -k|--secret-key)
                SECRET_KEY="$2"
                shift 2
                ;;
            --local)
                LOCAL_MODE=true
                shift
                ;;
            --android)
                ANDROID_FORWARDING=true
                shift
                ;;
            --no-k3s-setup)
                NO_K3S_SETUP=true
                shift
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            --help)
                show_help
                exit $EXIT_SUCCESS
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                exit $EXIT_INVALID_ARGS
                ;;
        esac
    done

    # Validate required parameters
    if [ -z "$SECRET_KEY" ]; then
        # Check if secret key exists in file
        local secret_file="$HOME/.k3s/secret-key"
        if [ -f "$secret_file" ] && [ -s "$secret_file" ]; then
            SECRET_KEY=$(cat "$secret_file")
            log "Using existing secret key from: $secret_file"
            log "Secret key: $SECRET_KEY"
        else
            # Auto-generate secret key if not provided
            log "Secret key not provided, generating one..."
            SECRET_KEY=$(openssl rand -base64 32 | tr -d "=+/" | cut -c1-24)
            
            # Create .k3s directory if it doesn't exist
            mkdir -p ~/.k3s
            
            # Store secret key in file
            echo "$SECRET_KEY" > "$secret_file"
            chmod 600 "$secret_file"
            
            log "✅ Generated secret key: $SECRET_KEY"
            log "📁 Stored in: $secret_file"
            log "💡 Use this key for future runs: --secret-key $SECRET_KEY"
        fi
    fi

    if [ "$LOCAL_MODE" = false ]; then
        # Standard mode requires Tailscale key and hostname
        if [ -z "$TAILSCALE_KEY" ]; then
            log_error "Tailscale key is required (-t/--tailscale-key) unless using --local mode"
            exit $EXIT_INVALID_ARGS
        fi

        if [ -z "$HOSTNAME" ]; then
            log_error "Hostname is required (-h/--hostname) unless using --local mode"
            exit $EXIT_INVALID_ARGS
        fi

        # Generate hostname if pattern is used
        if [[ "$HOSTNAME" == *"%d"* ]]; then
            local random_num
            random_num=$(shuf -i 10-99 -n 1)
            HOSTNAME=$(printf "$HOSTNAME" "$random_num")
            log "Generated hostname: $HOSTNAME"
        fi
    else
        # Local mode: use current hostname and check Tailscale status
        HOSTNAME=$(hostname)
        log "Local mode: using current hostname: $HOSTNAME"
        
        if ! command -v tailscale &> /dev/null; then
            log_error "Local mode requires Tailscale to be installed"
            exit $EXIT_INVALID_ARGS
        fi
        
        if ! tailscale status &> /dev/null; then
            log_error "Local mode requires Tailscale to be running and authenticated"
            log_error "Please run 'sudo tailscale up' first"
            exit $EXIT_INVALID_ARGS
        fi
    fi
}

# Detect if running on Android/Termux
is_android() {
    # Check for Android-specific environment
    [ -n "$ANDROID_ROOT" ] || [ -n "$ANDROID_DATA" ] || [ -d "/system/bin" ] || [ "$(whoami)" = "droid" ]
}

# Get appropriate user:group for chown operations
get_user_group() {
    local user
    user=$(whoami)
    
    if is_android; then
        # On Android/Termux, just use the user without group
        echo "$user"
    else
        # On regular Linux, use user:group format
        echo "$user:$user"
    fi
}

# Check if running with sufficient privileges
check_privileges() {
    if [ "$EUID" -eq 0 ]; then
        log_error "This script should not be run as root"
        log_error "It will request sudo privileges when needed"
        exit $EXIT_INVALID_ARGS
    fi

    if ! command -v sudo &> /dev/null; then
        log_error "sudo is required but not installed"
        exit $EXIT_INSTALL_FAILED
    fi
}

# Install Docker (reuse logic from main setup.sh)
install_docker() {
    log_step "Installing Docker..."

    if command -v docker &> /dev/null; then
        log "Docker is already installed"
        local docker_version=$(docker --version | cut -d' ' -f3 | cut -d',' -f1)
        log_verbose "Docker version: $docker_version"
        
        # Check if Docker service is running
        if sudo systemctl is-active --quiet docker; then
            log "Docker service is running"
        else
            log "Starting Docker service..."
            sudo systemctl start docker
            sudo systemctl enable docker
        fi
        return 0
    fi

    log "Installing Docker from official repository..."

    # Install prerequisites
    sudo apt-get update
    sudo apt-get install -y apt-transport-https ca-certificates curl gnupg lsb-release

    # Add Docker's official GPG key
    curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

    # Add Docker repository
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

    # Install Docker
    sudo apt-get update
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io

    # Add user to docker group
    sudo usermod -aG docker "$USER"

    # Start and enable Docker
    sudo systemctl start docker
    sudo systemctl enable docker

    log "✅ Docker installed successfully"
    log "Note: You may need to log out and back in for Docker group membership to take effect"
}

# Setup SSH (reuse logic from main setup.sh)
setup_ssh() {
    log_step "Setting up SSH..."

    # Install SSH server if not present
    if ! command -v sshd &> /dev/null; then
        log "Installing OpenSSH server..."
        sudo apt-get update
        sudo apt-get install -y openssh-server
    fi

    # Start and enable SSH service
    sudo systemctl start ssh
    sudo systemctl enable ssh

    # Configure SSH for better security
    local ssh_config="/etc/ssh/sshd_config"
    local ssh_config_backup="/etc/ssh/sshd_config.backup"

    if [ ! -f "$ssh_config_backup" ]; then
        log_verbose "Backing up SSH configuration"
        sudo cp "$ssh_config" "$ssh_config_backup"
    fi

    # Configure SSH settings
    log_verbose "Configuring SSH security settings"
    {
        echo "# K3s Phone Setup - Security Configuration"
        echo "PermitRootLogin no"
        echo "PasswordAuthentication yes"
        echo "PubkeyAuthentication yes"
        echo "Port 22"
        echo "MaxAuthTries 3"
        echo "ClientAliveInterval 300"
        echo "ClientAliveCountMax 2"
    } | sudo tee -a "$ssh_config" > /dev/null

    # Restart SSH service
    sudo systemctl restart ssh

    log "✅ SSH setup completed"
}

# Set hostname
set_hostname() {
    log_step "Setting hostname to: $HOSTNAME"

    local current_hostname=$(hostname)
    if [ "$current_hostname" = "$HOSTNAME" ]; then
        log "Hostname is already set correctly"
        return 0
    fi

    # Set hostname using hostnamectl if available
    if command -v hostnamectl &> /dev/null; then
        log_verbose "Using hostnamectl to set hostname"
        sudo hostnamectl set-hostname "$HOSTNAME"
    else
        # Fallback method
        log_verbose "Using manual hostname configuration"
        echo "$HOSTNAME" | sudo tee /etc/hostname > /dev/null
        sudo hostname "$HOSTNAME"
    fi

    # Update /etc/hosts
    log_verbose "Updating /etc/hosts"
    sudo sed -i "s/127.0.1.1.*/127.0.1.1\t$HOSTNAME/" /etc/hosts

    log "✅ Hostname set to: $HOSTNAME"
}

# Install and configure Tailscale
setup_tailscale() {
    log_step "Setting up Tailscale with funnel..."

    # Uninstall existing Tailscale
    if command -v tailscale &> /dev/null; then
        log "Uninstalling existing Tailscale..."
        sudo tailscale down || true
        sudo apt-get remove -y tailscale || true
        sudo rm -f /usr/bin/tailscale /usr/sbin/tailscaled || true
    fi

    # Install Tailscale
    log "Installing Tailscale..."
    curl -fsSL https://tailscale.com/install.sh | sh

    # Authenticate with Tailscale
    log "Authenticating with Tailscale..."
    sudo tailscale up --authkey="$TAILSCALE_KEY" --hostname="$HOSTNAME"

    # Wait for Tailscale to be ready
    log_verbose "Waiting for Tailscale to be ready..."
    local retries=0
    while [ $retries -lt 30 ]; do
        if tailscale status &> /dev/null; then
            break
        fi
        sleep 2
        retries=$((retries + 1))
    done

    if [ $retries -eq 30 ]; then
        log_error "Tailscale failed to start properly"
        exit $EXIT_NETWORK_FAILED
    fi

    # Get Tailscale IP
    local tailscale_ip=$(tailscale ip -4)
    log "Tailscale IP: $tailscale_ip"

    # Setup funnel for K3s API (port 6443) and kubeconfig server (port 8443)
    log "Setting up Tailscale funnel..."
    
    # Reset funnel configuration to start fresh
    log_verbose "Resetting funnel configuration..."
    sudo tailscale funnel reset 2>/dev/null || true
    
    # Configure funnel for kubeconfig server on main domain (port 443)
    # This avoids TLS issues with specific ports
    log_verbose "Setting up funnel for kubeconfig server..."
    if sudo tailscale funnel --bg http://localhost:8443 2>/dev/null; then
        log "✅ Kubeconfig server accessible via: https://${HOSTNAME}.tailxxxx.ts.net/"
    else
        log_warn "Failed to setup funnel for kubeconfig server"
    fi
    
    # Note: K3s API funnel will be set up after K3s is installed to avoid port conflicts
    
    # Configure additional ports if needed
    if [ "$ANDROID_FORWARDING" = true ]; then
        log_verbose "Setting up funnel for Android app..."
        if sudo tailscale funnel --https=8005 --bg http://localhost:8005 2>/dev/null; then
            log "✅ Android app accessible via: https://${HOSTNAME}.tailxxxx.ts.net:8005"
        else
            log_warn "Failed to setup funnel for Android app, but continuing..."
        fi
    fi

    log "✅ Tailscale setup completed with funnel"
    log "External access: https://${HOSTNAME}.tailxxxx.ts.net"
}

# Setup Tailscale funnel in local mode (existing tailnet)
setup_tailscale_local() {
    log_step "Setting up Tailscale funnel (local mode)..."

    # Verify Tailscale is connected
    local tailscale_status
    if ! tailscale_status=$(tailscale status 2>&1); then
        log_error "Tailscale is not running or authenticated"
        log_error "Status: $tailscale_status"
        exit $EXIT_NETWORK_FAILED
    fi

    log "✅ Tailscale is already authenticated"
    
    # Get current Tailscale IP
    local tailscale_ip
    tailscale_ip=$(tailscale ip -4)
    log "Tailscale IP: $tailscale_ip"

    # Check if funnel is already configured
    local funnel_status
    if funnel_status=$(tailscale funnel status 2>/dev/null); then
        log_verbose "Current funnel status:"
        echo "$funnel_status" | while IFS= read -r line; do log_verbose "  $line"; done
    fi

    # Setup funnel for kubeconfig server and optional Android app
    log "Setting up Tailscale funnel..."
    
    # Reset funnel configuration to start fresh
    log_verbose "Resetting funnel configuration..."
    sudo tailscale funnel reset 2>/dev/null || true
    
    # Configure funnel for kubeconfig server on main domain (port 443)
    # This avoids TLS issues with specific ports
    log_verbose "Setting up funnel for kubeconfig server..."
    if sudo tailscale funnel --bg http://localhost:8443 2>/dev/null; then
        log_verbose "✅ Funnel enabled for kubeconfig server (main domain)"
    else
        log_warn "Failed to enable funnel for kubeconfig server, trying to continue..."
    fi
    
    # Note: K3s API funnel will be set up after K3s is installed to avoid port conflicts
    
    # Optionally enable funnel for Android app
    if [ "$ANDROID_FORWARDING" = true ]; then
        log_verbose "Setting up funnel for Android app..."
        if sudo tailscale funnel --https=8005 --bg http://localhost:8005 2>/dev/null; then
            log_verbose "✅ Funnel enabled for port 8005 (Android app)"
        else
            log_warn "Failed to enable funnel for port 8005 (Android app), trying to continue..."
        fi
    fi

    # Get the actual funnel domain
    local funnel_domain
    if funnel_domain=$(tailscale funnel status 2>/dev/null | grep "https://" | head -1 | awk '{print $1}' | sed 's|https://||' | sed 's|:.*||'); then
        if [ -n "$funnel_domain" ]; then
            log "✅ Tailscale funnel configured"
            log "External access: https://$funnel_domain"
        else
            log_warn "Funnel may not be fully configured - check with: tailscale funnel status"
        fi
    else
        log_warn "Could not determine funnel domain - check with: tailscale funnel status"
    fi
}

# Setup funnel for K3s API after K3s is running (to avoid port conflicts)
setup_k3s_funnel() {
    log_verbose "Setting up funnel for K3s API..."
    
    # Use HTTP-to-HTTPS proxying to avoid 502 Bad Gateway errors
    # This proxies HTTPS external traffic to HTTP backend (K3s handles TLS termination)
    if sudo tailscale funnel --https=6443 --bg http://localhost:6443 2>/dev/null; then
        log_verbose "✅ Funnel enabled for K3s API (port 6443) using HTTP backend"
    else
        log_warn "HTTP-to-HTTPS funnel failed, trying HTTPS-to-HTTPS..."
        # Reset the 6443 funnel and try HTTPS mode as fallback
        sudo tailscale funnel --https=6443 off 2>/dev/null || true
        
        if sudo tailscale funnel --https=6443 --bg https://localhost:6443 2>/dev/null; then
            log_verbose "✅ Funnel enabled for K3s API using HTTPS backend (port 6443)"
        else
            log_warn "Failed to enable funnel for K3s API in any mode"
        fi
    fi
    
    # Setup funnel for NodePort services (port 30080)
    log_verbose "Setting up funnel for NodePort services..."
    if sudo tailscale funnel --https=30080 --bg http://localhost:30080 2>/dev/null; then
        log_verbose "✅ Funnel enabled for NodePort services (port 30080)"
        log "📡 NodePort services accessible via: https://${HOSTNAME}.tailxxxx.ts.net:30080"
        return 0
    else
        log_warn "Failed to enable funnel for NodePort services, trying to continue..."
        return 1
    fi
}

# Install K3s in single-node mode
install_k3s() {
    log_step "Installing K3s in single-node mode..."

    # Uninstall existing K3s
    if command -v k3s &> /dev/null; then
        log "Uninstalling existing K3s..."
        if [ -f /usr/local/bin/k3s-uninstall.sh ]; then
            sudo /usr/local/bin/k3s-uninstall.sh
        fi
    fi

    # Install K3s server (single-node)
    log "Installing K3s server..."
    
    # Get Tailscale hostname for TLS SAN
    local tailscale_hostname=$(tailscale status --json 2>/dev/null | python3 -c "
import json, sys
try:
    status = json.load(sys.stdin)
    print(status['Self']['DNSName'].rstrip('.'))
except:
    print('${HOSTNAME}.tailxxxx.ts.net')
" 2>/dev/null || echo "${HOSTNAME}.tailxxxx.ts.net")
    
    log_verbose "Adding TLS SAN for Tailscale domain: $tailscale_hostname"
    
    # Install certificates and curl for Debian systems
    if command -v apt-get &> /dev/null; then
        log_verbose "Updating certificates for Debian/Ubuntu..."
        
        # Wait for dpkg lock to be released
        local lock_retries=0
        while [ $lock_retries -lt 30 ]; do
            if sudo fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || sudo fuser /var/lib/dpkg/lock >/dev/null 2>&1; then
                log_verbose "Waiting for dpkg lock to be released... (attempt $((lock_retries + 1))/30)"
                sleep 2
                lock_retries=$((lock_retries + 1))
            else
                break
            fi
        done
        
        if [ $lock_retries -eq 30 ]; then
            log_warn "dpkg lock timeout, trying to continue anyway..."
        fi
        
        # Try to update and install packages with retries
        local apt_retries=0
        while [ $apt_retries -lt 3 ]; do
            if sudo apt-get update -qq 2>/dev/null; then
                break
            else
                log_verbose "apt-get update failed, retrying... (attempt $((apt_retries + 1))/3)"
                sleep 5
                apt_retries=$((apt_retries + 1))
            fi
        done
        
        # Install essential packages, skip if they fail
        sudo apt-get install -y ca-certificates curl wget 2>/dev/null || {
            log_warn "Failed to install some packages, trying individual installation..."
            sudo apt-get install -y ca-certificates 2>/dev/null || true
            sudo apt-get install -y curl 2>/dev/null || true
            sudo apt-get install -y wget 2>/dev/null || true
        }
    fi
    
    # Use wget as fallback if curl has certificate issues
    log_verbose "Downloading K3s installer..."
    if ! curl -sfL https://get.k3s.io -o /tmp/k3s-install.sh 2>/dev/null; then
        log_verbose "curl failed, trying wget..."
        if ! wget -q https://get.k3s.io -O /tmp/k3s-install.sh 2>/dev/null; then
            log_verbose "wget failed, trying curl with insecure flag..."
            curl -sfLk https://get.k3s.io -o /tmp/k3s-install.sh
        fi
    fi
    
    # Make installer executable and run it
    chmod +x /tmp/k3s-install.sh
    sudo /tmp/k3s-install.sh server \
        --node-name="$HOSTNAME" \
        --node-label="device-type=phone" \
        --node-label="cluster-mode=single-node" \
        --disable=traefik \
        --write-kubeconfig-mode=644 \
        --tls-san="$tailscale_hostname"
    
    # Clean up installer
    rm -f /tmp/k3s-install.sh

    # Wait for K3s to be ready
    log_verbose "Waiting for K3s to be ready..."
    local retries=0
    while [ $retries -lt 60 ]; do
        if sudo k3s kubectl get nodes &> /dev/null; then
            break
        fi
        sleep 5
        retries=$((retries + 1))
    done

    if [ $retries -eq 60 ]; then
        log_error "K3s failed to start properly"
        exit $EXIT_INSTALL_FAILED
    fi

    # Make kubectl available for regular user
    mkdir -p ~/.kube
    sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
    sudo chown "$(get_user_group)" ~/.kube/config

    # Set KUBECONFIG environment variable
    echo 'export KUBECONFIG=~/.kube/config' >> ~/.bashrc

    log "✅ K3s single-node cluster installed successfully"
}

# Check if K3s is already running (for --no-k3s-setup mode)
check_k3s_running() {
    log_step "Checking existing K3s cluster..."

    # Check if K3s command exists
    if ! command -v k3s &> /dev/null; then
        log_error "K3s is not installed (required for --no-k3s-setup mode)"
        log_error "Either install K3s first or remove --no-k3s-setup flag"
        exit $EXIT_INSTALL_FAILED
    fi

    # Check if K3s service is running
    if ! sudo systemctl is-active --quiet k3s; then
        log_error "K3s service is not running"
        log_error "Start it with: sudo systemctl start k3s"
        exit $EXIT_INSTALL_FAILED
    fi

    # Test if K3s cluster is responsive
    log_verbose "Testing K3s cluster connectivity..."
    local retries=0
    while [ $retries -lt 30 ]; do
        if sudo k3s kubectl get nodes &> /dev/null; then
            break
        fi
        sleep 2
        retries=$((retries + 1))
    done

    if [ $retries -eq 30 ]; then
        log_error "K3s cluster is not responding"
        log_error "Check cluster status with: sudo k3s kubectl get nodes"
        exit $EXIT_INSTALL_FAILED
    fi

    # Ensure kubeconfig is available for regular user
    if [ ! -f ~/.kube/config ] || [ ! -s ~/.kube/config ]; then
        log "Setting up kubeconfig for current user..."
        mkdir -p ~/.kube
        sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
        sudo chown "$(get_user_group)" ~/.kube/config
    fi

    # Get cluster info
    local node_count
    node_count=$(sudo k3s kubectl get nodes --no-headers 2>/dev/null | wc -l)
    local cluster_version
    cluster_version=$(sudo k3s kubectl version --short --client 2>/dev/null | grep Client | cut -d' ' -f3 || echo "unknown")
    
    log "✅ K3s cluster is running"
    log "   Nodes: $node_count"
    log "   Version: $cluster_version"
    
    # Set KUBECONFIG environment variable if not already set
    if ! grep -q "export KUBECONFIG=~/.kube/config" ~/.bashrc 2>/dev/null; then
        echo 'export KUBECONFIG=~/.kube/config' >> ~/.bashrc
    fi
}

# Setup port forwarding for Android app
setup_port_forwarding() {
    log_step "Setting up port forwarding for Android app..."

    # Find Android app IP (usually on the local network)
    local app_ip=""
    
    # Try to detect Android app by scanning common ports
    log_verbose "Scanning for Android app on port 8005..."
    
    # Check localhost first
    if curl -s --connect-timeout 2 http://localhost:8005/status &> /dev/null; then
        app_ip="localhost"
        log "Found Android app on localhost:8005"
    else
        # Scan local network
        local network=$(ip route | grep "$(ip route | awk '/default/ { print $5 }')" | grep -oE '192\.168\.[0-9]+\.[0-9]+|10\.[0-9]+\.[0-9]+\.[0-9]+|172\.([1-2][0-9]|3[0-1])\.[0-9]+\.[0-9]+' | head -1 | sed 's/\.[0-9]*$//')
        
        if [ -n "$network" ]; then
            log_verbose "Scanning network: ${network}.0/24"
            for i in {100..200}; do
                if curl -s --connect-timeout 1 "http://${network}.${i}:8005/status" &> /dev/null; then
                    app_ip="${network}.${i}"
                    log "Found Android app on ${app_ip}:8005"
                    break
                fi
            done
        fi
    fi

    if [ -z "$app_ip" ]; then
        log_warn "Android app not found. Port forwarding will be configured for localhost"
        app_ip="localhost"
    fi

    # Create systemd service for port forwarding
    log "Creating port forwarding service..."
    sudo tee /etc/systemd/system/k3s-app-forwarder.service > /dev/null << EOF
[Unit]
Description=K3s Android App Port Forwarder
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/socat TCP-LISTEN:8005,fork,reuseaddr TCP:${app_ip}:8005
Restart=always
RestartSec=5
User=nobody

[Install]
WantedBy=multi-user.target
EOF

    # Install socat if not present
    if ! command -v socat &> /dev/null; then
        log "Installing socat for port forwarding..."
        sudo apt-get update
        sudo apt-get install -y socat
    fi

    # Start and enable the forwarding service
    sudo systemctl daemon-reload
    sudo systemctl enable k3s-app-forwarder.service
    sudo systemctl start k3s-app-forwarder.service

    log "✅ Port forwarding setup completed"
    log "Android app accessible at: http://localhost:8005"
}

# Create kubeconfig server with authentication
setup_kubeconfig_server() {
    log_step "Setting up authenticated kubeconfig server..."

    # Install PyYAML if not present
    if ! python3 -c "import yaml" 2>/dev/null; then
        log "Installing PyYAML for kubeconfig server..."
        sudo apt-get update
        sudo apt-get install -y python3-yaml
    fi

    # Create the kubeconfig server script
    sudo tee /usr/local/bin/kubeconfig-server > /dev/null << EOF
#!/usr/bin/env python3
import http.server
import socketserver
import urllib.parse
import os
import sys
import logging
import json
import yaml
import subprocess
import re
from datetime import datetime

PORT = 8443
SECRET_KEY_FILE = "/etc/k3s/secret-key"
try:
    with open(SECRET_KEY_FILE, 'r') as f:
        SECRET_KEY = f.read().strip()
    logging.info(f"Loaded secret key from {SECRET_KEY_FILE}")
except FileNotFoundError:
    SECRET_KEY = "$SECRET_KEY"  # Fallback to hardcoded key
    logging.warning(f"Secret key file {SECRET_KEY_FILE} not found, using hardcoded key")
KUBECONFIG_PATH = "/etc/rancher/k3s/k3s.yaml"

# Configure logging with more detail
logging.basicConfig(
    level=logging.INFO, 
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler('/var/log/kubeconfig-server.log')
    ]
)

class KubeconfigHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        """Override to use our logging setup"""
        logging.info(f"{self.client_address[0]} - {format % args}")
    
    def get_funnel_domain(self):
        """Get the actual funnel domain from tailscale status"""
        import subprocess
        import re
        
        try:
            # Get funnel status
            result = subprocess.run(['tailscale', 'funnel', 'status'], 
                                  capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                # Look for the main domain (shortest URL without port) first
                lines = result.stdout.split('\n')
                for line in lines:
                    # Look for main domain entry without port
                    match = re.search(r'https://([a-zA-Z0-9.-]+\.ts\.net)[^:]', line)
                    if match:
                        domain = match.group(1)
                        logging.info(f"Detected main funnel domain: {domain}")
                        return domain
                
                # Fallback: look for any funnel domain and strip port
                for line in lines:
                    match = re.search(r'https://([a-zA-Z0-9.-]+\.ts\.net)', line)
                    if match:
                        domain = match.group(1)
                        logging.info(f"Detected funnel domain (fallback): {domain}")
                        return domain
        except Exception as e:
            logging.warning(f"Failed to detect funnel domain: {e}")
        
        # Fallback to hostname-based guess
        hostname = os.uname().nodename
        fallback = f"{hostname}.tailxxxx.ts.net"
        logging.warning(f"Using fallback funnel domain: {fallback}")
        return fallback
    
    def do_GET(self):
        client_ip = self.client_address[0]
        parsed_path = urllib.parse.urlparse(self.path)
        query_params = urllib.parse.parse_qs(parsed_path.query)
        
        logging.info(f"Request from {client_ip}: {self.command} {parsed_path.path}")
        logging.debug(f"Query params: {query_params}")
        logging.debug(f"Headers: {dict(self.headers)}")
        
        if parsed_path.path == "/kubeconfig":
            # Check authentication
            if "key" not in query_params:
                logging.warning(f"Missing key parameter from {client_ip}")
                self.send_error_response(400, "Missing key parameter")
                return
                
            if query_params["key"][0] != SECRET_KEY:
                logging.warning(f"Invalid key from {client_ip}: {query_params['key'][0][:8]}...")
                self.send_error_response(403, "Invalid authentication key")
                return
            
            # Serve kubeconfig
            try:
                with open(KUBECONFIG_PATH, 'r') as f:
                    kubeconfig_content = f.read()
                
                # Get the actual funnel domain dynamically
                hostname = os.uname().nodename
                funnel_domain = self.get_funnel_domain()
                
                # Replace localhost with actual funnel domain and modify for TLS
                import yaml
                config = yaml.safe_load(kubeconfig_content)
                
                # Update cluster server URL
                config['clusters'][0]['cluster']['server'] = f"https://{funnel_domain}:6443"
                
                # Add insecure-skip-tls-verify to handle certificate domain mismatch
                config['clusters'][0]['cluster']['insecure-skip-tls-verify'] = True
                
                # Remove certificate-authority-data since we're skipping TLS verify
                if 'certificate-authority-data' in config['clusters'][0]['cluster']:
                    del config['clusters'][0]['cluster']['certificate-authority-data']
                
                kubeconfig_content = yaml.dump(config)
                
                logging.info(f"Served kubeconfig to {client_ip} (size: {len(kubeconfig_content)} bytes)")
                self.send_response(200)
                self.send_header('Content-type', 'application/x-yaml')
                self.send_header('Content-Disposition', f'attachment; filename="{hostname}-kubeconfig.yaml"')
                self.send_header('Cache-Control', 'no-cache')
                self.end_headers()
                self.wfile.write(kubeconfig_content.encode())
                
            except Exception as e:
                logging.error(f"Error serving kubeconfig to {client_ip}: {e}")
                self.send_error_response(500, f"Server error: {str(e)}")
        
        elif parsed_path.path == "/status":
            # Status endpoint (no auth required)
            logging.info(f"Status check from {client_ip}")
            self.send_json_response({
                "status": "running",
                "hostname": os.uname().nodename,
                "service": "kubeconfig-server",
                "version": "1.0.0",
                "port": PORT,
                "timestamp": datetime.now().isoformat(),
                "endpoints": {
                    "/kubeconfig": "GET kubeconfig (requires ?key=SECRET)",
                    "/status": "GET server status",
                    "/location": "GET device location (requires ?key=SECRET)",
                    "/orientation": "GET device orientation (requires ?key=SECRET)",
                    "/ports": "GET available funnel ports (requires ?key=SECRET)",
                    "/ports/open": "POST open new funnel port (requires ?key=SECRET&port=PORT)",
                    "/ports/close": "POST close funnel port (requires ?key=SECRET&port=PORT)"
                }
            })
        
        elif parsed_path.path == "/location":
            # Location endpoint (requires auth)
            if not self.check_auth(query_params, client_ip):
                return
            
            location_data = self.get_location_data()
            logging.info(f"Served location data to {client_ip}")
            self.send_json_response(location_data)
        
        elif parsed_path.path == "/orientation":
            # Orientation endpoint (requires auth)
            if not self.check_auth(query_params, client_ip):
                return
            
            orientation_data = self.get_orientation_data()
            logging.info(f"Served orientation data to {client_ip}")
            self.send_json_response(orientation_data)
        
        elif parsed_path.path == "/ports":
            # Ports list endpoint (requires auth)
            if not self.check_auth(query_params, client_ip):
                return
            
            ports_data = self.get_ports_data()
            logging.info(f"Served ports data to {client_ip}")
            self.send_json_response(ports_data)
        
        elif parsed_path.path == "/health":
            # Health check endpoint
            logging.debug(f"Health check from {client_ip}")
            self.send_response(200)
            self.send_header('Content-type', 'text/plain')
            self.end_headers()
            self.wfile.write(b'OK')
        
        elif parsed_path.path.startswith("/httpbin"):
            # HTTPBin proxy endpoint (requires auth)
            if not self.check_auth(query_params, client_ip):
                return
            
            # Proxy requests to local HTTPBin service
            self.proxy_httpbin_request(parsed_path.path, query_params, client_ip)
            
        else:
            logging.warning(f"404 - Unknown path from {client_ip}: {parsed_path.path}")
            self.send_error_response(404, "Not found. Available endpoints: /kubeconfig, /status, /location, /orientation, /ports, /health, /httpbin")
    
    def proxy_httpbin_request(self, path, query_params, client_ip):
        """Proxy requests to HTTPBin service"""
        try:
            # Remove /httpbin prefix and preserve the rest of the path
            httpbin_path = path[8:]  # Remove "/httpbin"
            if not httpbin_path:
                httpbin_path = "/"
            
            # Construct HTTPBin URL
            httpbin_url = f"http://localhost:30080{httpbin_path}"
            
            # Add query parameters if any (excluding the auth key)
            filtered_params = {k: v for k, v in query_params.items() if k != "key"}
            if filtered_params:
                query_string = "&".join([f"{k}={v[0]}" for k, v in filtered_params.items()])
                httpbin_url += f"?{query_string}"
            
            logging.info(f"Proxying HTTPBin request from {client_ip}: {httpbin_url}")
            
            # Make request to HTTPBin
            import urllib.request
            with urllib.request.urlopen(httpbin_url, timeout=10) as response:
                content = response.read()
                content_type = response.headers.get('Content-Type', 'application/json')
                
                self.send_response(200)
                self.send_header('Content-type', content_type)
                self.send_header('Cache-Control', 'no-cache')
                self.end_headers()
                self.wfile.write(content)
                
                logging.info(f"HTTPBin proxy successful for {client_ip}")
                
        except Exception as e:
            logging.error(f"HTTPBin proxy error for {client_ip}: {e}")
            self.send_error_response(500, f"HTTPBin proxy error: {str(e)}")
    
    def do_POST(self):
        """Handle POST requests for port management"""
        client_ip = self.client_address[0]
        parsed_path = urllib.parse.urlparse(self.path)
        query_params = urllib.parse.parse_qs(parsed_path.query)
        
        logging.info(f"POST request from {client_ip}: {parsed_path.path}")
        
        if parsed_path.path == "/ports/open":
            # Open new funnel port (requires auth)
            if not self.check_auth(query_params, client_ip):
                return
            
            if "port" not in query_params:
                self.send_error_response(400, "Missing port parameter")
                return
            
            try:
                port = int(query_params["port"][0])
                result = self.open_funnel_port(port)
                logging.info(f"Port {port} open request from {client_ip}: {result['success']}")
                self.send_json_response(result)
            except ValueError:
                self.send_error_response(400, "Invalid port number")
            except Exception as e:
                logging.error(f"Error opening port from {client_ip}: {e}")
                self.send_error_response(500, f"Server error: {str(e)}")
        
        elif parsed_path.path == "/ports/close":
            # Close funnel port (requires auth)
            if not self.check_auth(query_params, client_ip):
                return
            
            if "port" not in query_params:
                self.send_error_response(400, "Missing port parameter")
                return
            
            try:
                port = int(query_params["port"][0])
                result = self.close_funnel_port(port)
                logging.info(f"Port {port} close request from {client_ip}: {result['success']}")
                self.send_json_response(result)
            except ValueError:
                self.send_error_response(400, "Invalid port number")
            except Exception as e:
                logging.error(f"Error closing port from {client_ip}: {e}")
                self.send_error_response(500, f"Server error: {str(e)}")
        
        else:
            logging.warning(f"404 - Unknown POST path from {client_ip}: {parsed_path.path}")
            self.send_error_response(404, "Not found. Available POST endpoints: /ports/open, /ports/close")
    
    def check_auth(self, query_params, client_ip):
        """Check authentication for protected endpoints"""
        if "key" not in query_params:
            logging.warning(f"Missing key parameter from {client_ip}")
            self.send_error_response(400, "Missing key parameter")
            return False
            
        if query_params["key"][0] != SECRET_KEY:
            logging.warning(f"Invalid key from {client_ip}: {query_params['key'][0][:8]}...")
            self.send_error_response(403, "Invalid authentication key")
            return False
        
        return True
    
    def get_location_data(self):
        """Get device location data if available"""
        try:
            # Try to get location from Android app if available
            location_response = subprocess.run(['curl', '-s', '--connect-timeout', '2', 'http://localhost:8005/location'], 
                                             capture_output=True, text=True, timeout=5)
            if location_response.returncode == 0:
                import json
                location_data = json.loads(location_response.stdout)
                return {
                    "available": True,
                    "source": "android_app",
                    "timestamp": datetime.now().isoformat(),
                    "data": location_data
                }
        except Exception as e:
            logging.debug(f"Failed to get location from Android app: {e}")
        
        # Fallback: Try to get rough location from IP geolocation
        try:
            ip_response = subprocess.run(['curl', '-s', '--connect-timeout', '3', 'http://ip-api.com/json'], 
                                       capture_output=True, text=True, timeout=5)
            if ip_response.returncode == 0:
                import json
                ip_data = json.loads(ip_response.stdout)
                return {
                    "available": True,
                    "source": "ip_geolocation",
                    "timestamp": datetime.now().isoformat(),
                    "data": {
                        "latitude": ip_data.get("lat"),
                        "longitude": ip_data.get("lon"),
                        "city": ip_data.get("city"),
                        "country": ip_data.get("country"),
                        "accuracy": "city_level"
                    }
                }
        except Exception as e:
            logging.debug(f"Failed to get IP geolocation: {e}")
        
        return {
            "available": False,
            "source": "none",
            "timestamp": datetime.now().isoformat(),
            "message": "No location data available"
        }
    
    def get_orientation_data(self):
        """Get device orientation data if available"""
        try:
            # Try to get orientation from Android app if available
            orientation_response = subprocess.run(['curl', '-s', '--connect-timeout', '2', 'http://localhost:8005/orientation'], 
                                                capture_output=True, text=True, timeout=5)
            if orientation_response.returncode == 0:
                import json
                orientation_data = json.loads(orientation_response.stdout)
                return {
                    "available": True,
                    "source": "android_app",
                    "timestamp": datetime.now().isoformat(),
                    "data": orientation_data
                }
        except Exception as e:
            logging.debug(f"Failed to get orientation from Android app: {e}")
        
        return {
            "available": False,
            "source": "none",
            "timestamp": datetime.now().isoformat(),
            "message": "No orientation data available"
        }
    
    def get_ports_data(self):
        """Get current funnel ports status"""
        try:
            result = subprocess.run(['tailscale', 'funnel', 'status'], 
                                  capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                # Parse funnel status output
                lines = result.stdout.split('\n')
                ports = []
                current_domain = None
                
                for line in lines:
                    line = line.strip()
                    if line.startswith('https://'):
                        # Extract domain and port
                        if ':' in line:
                            domain_port = line.replace('https://', '').split()[0]
                            if ':' in domain_port:
                                domain, port = domain_port.split(':')
                                ports.append({
                                    "port": int(port),
                                    "domain": domain,
                                    "url": f"https://{domain_port}",
                                    "status": "active"
                                })
                            else:
                                current_domain = domain_port
                                ports.append({
                                    "port": 443,
                                    "domain": domain_port,
                                    "url": f"https://{domain_port}",
                                    "status": "active"
                                })
                
                return {
                    "available": True,
                    "timestamp": datetime.now().isoformat(),
                    "ports": ports,
                    "total_ports": len(ports)
                }
        except Exception as e:
            logging.error(f"Failed to get funnel status: {e}")
        
        return {
            "available": False,
            "timestamp": datetime.now().isoformat(),
            "message": "Failed to get funnel status",
            "ports": []
        }
    
    def open_funnel_port(self, port):
        """Open a new funnel port"""
        try:
            # Determine protocol based on port - all funnel ports use HTTPS externally
            if port == 443:
                cmd = ['sudo', 'tailscale', 'funnel', '--bg', f'http://localhost:8443']
            elif port == 80:
                cmd = ['sudo', 'tailscale', 'funnel', '--https=80', '--bg', f'http://localhost:80']
            else:
                cmd = ['sudo', 'tailscale', 'funnel', f'--https={port}', '--bg', f'http://localhost:{port}']
            
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
            
            if result.returncode == 0:
                return {
                    "success": True,
                    "port": port,
                    "message": f"Funnel opened for port {port}",
                    "timestamp": datetime.now().isoformat()
                }
            else:
                return {
                    "success": False,
                    "port": port,
                    "message": f"Failed to open funnel for port {port}: {result.stderr}",
                    "timestamp": datetime.now().isoformat()
                }
        except Exception as e:
            return {
                "success": False,
                "port": port,
                "message": f"Error opening funnel for port {port}: {str(e)}",
                "timestamp": datetime.now().isoformat()
            }
    
    def close_funnel_port(self, port):
        """Close a funnel port"""
        try:
            # Note: Tailscale doesn't have a direct way to close individual ports
            # We would need to reset and reconfigure, which is complex
            # For now, return a message indicating this limitation
            return {
                "success": False,
                "port": port,
                "message": f"Port closure not implemented - use 'tailscale funnel reset' and reconfigure manually",
                "timestamp": datetime.now().isoformat(),
                "note": "Individual port closure requires funnel reset and reconfiguration"
            }
        except Exception as e:
            return {
                "success": False,
                "port": port,
                "message": f"Error closing funnel for port {port}: {str(e)}",
                "timestamp": datetime.now().isoformat()
            }
    
    def send_json_response(self, data):
        """Send JSON response"""
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        self.wfile.write(json.dumps(data, indent=2).encode())
    
    def send_error_response(self, code, message):
        """Send JSON error response"""
        self.send_response(code)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        error_data = {
            "error": True,
            "code": code,
            "message": message,
            "timestamp": datetime.now().isoformat()
        }
        self.wfile.write(json.dumps(error_data).encode())

class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    """Handle requests in separate threads"""
    allow_reuse_address = True

if __name__ == "__main__":
    try:
        # Create log directory if it doesn't exist
        os.makedirs('/var/log', exist_ok=True)
        
        logging.info(f"Starting kubeconfig server on port {PORT}")
        logging.info(f"Secret key configured: {SECRET_KEY[:8]}...")
        logging.info(f"Kubeconfig path: {KUBECONFIG_PATH}")
        
        with ThreadedTCPServer(("", PORT), KubeconfigHandler) as httpd:
            logging.info(f"Kubeconfig server ready - listening on 0.0.0.0:{PORT}")
            logging.info(f"Local access: http://localhost:{PORT}/kubeconfig?key=SECRET")
            logging.info(f"Status endpoint: http://localhost:{PORT}/status")
            
            try:
                httpd.serve_forever()
            except KeyboardInterrupt:
                logging.info("Received interrupt signal")
            finally:
                logging.info("Server shutting down")
                
    except Exception as e:
        logging.error(f"Failed to start server: {e}")
        sys.exit(1)
EOF

    # Make script executable
    sudo chmod +x /usr/local/bin/kubeconfig-server

    # Copy secret key to system location for the server
    sudo mkdir -p /etc/k3s
    echo "$SECRET_KEY" | sudo tee /etc/k3s/secret-key > /dev/null
    sudo chmod 600 /etc/k3s/secret-key

    # Create systemd service
    sudo tee /etc/systemd/system/kubeconfig-server.service > /dev/null << EOF
[Unit]
Description=K3s Kubeconfig Server
After=network.target k3s.service
Requires=k3s.service

[Service]
Type=simple
ExecStart=/usr/local/bin/kubeconfig-server
Restart=always
RestartSec=5
User=root
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

    # Start and enable the service
    sudo systemctl daemon-reload
    sudo systemctl enable kubeconfig-server.service
    sudo systemctl start kubeconfig-server.service

    # Verify service started correctly
    if ! sudo systemctl is-active kubeconfig-server.service >/dev/null 2>&1; then
        log_error "Kubeconfig server failed to start"
        log "Check logs with: sudo journalctl -u kubeconfig-server -f"
        exit 1
    fi

    log "✅ Kubeconfig server setup completed"
    log "Access: https://${HOSTNAME}.tailxxxx.ts.net/kubeconfig?key=${SECRET_KEY}"
    log_verbose "Server logs: sudo journalctl -u kubeconfig-server -f"
    log_verbose "Log file: /var/log/kubeconfig-server.log"
}

# Test funnel connectivity and kubectl access
test_funnel_connectivity() {
    local funnel_domain="$1"
    log_verbose "Testing funnel connectivity for: $funnel_domain"
    
    # Test kubeconfig server
    local kubeconfig_url="https://$funnel_domain/status"
    log_verbose "Testing kubeconfig server: $kubeconfig_url"
    
    if curl -s --connect-timeout 10 --max-time 15 "$kubeconfig_url" >/dev/null 2>&1; then
        log "✅ Kubeconfig server is accessible via funnel"
    else
        log_warn "❌ Kubeconfig server not accessible via funnel"
        log_warn "   URL tested: $kubeconfig_url"
        log_warn "   Check: tailscale funnel status"
        return 1
    fi
    
    # Test K3s API endpoint
    local k3s_url="https://$funnel_domain:6443/version"
    log_verbose "Testing K3s API: $k3s_url"
    
    if curl -k -s --connect-timeout 10 --max-time 15 "$k3s_url" >/dev/null 2>&1; then
        log "✅ K3s API is accessible via funnel"
        
        # Test actual kubectl connectivity
        if test_kubectl_via_funnel "$funnel_domain"; then
            log "✅ kubectl access confirmed via funnel"
            return 0
        else
            log_warn "❌ kubectl access failed via funnel"
            return 1
        fi
    else
        log_warn "❌ K3s API not accessible via funnel"
        log_warn "   URL tested: $k3s_url"
        log_warn "   Check: sudo systemctl status k3s"
        return 1
    fi
}

# Test kubectl access via funnel
test_kubectl_via_funnel() {
    local funnel_domain="$1"
    local temp_kubeconfig="/tmp/test-kubeconfig-$$.yaml"
    
    log_verbose "Testing kubectl access via funnel..."
    
    # Download kubeconfig
    local kubeconfig_url="https://$funnel_domain/kubeconfig?key=${SECRET_KEY}"
    if ! curl -s --connect-timeout 15 --max-time 30 "$kubeconfig_url" -o "$temp_kubeconfig" 2>/dev/null; then
        log_verbose "Failed to download kubeconfig for testing"
        return 1
    fi
    
    # Test kubectl
    if kubectl --kubeconfig="$temp_kubeconfig" cluster-info --request-timeout=15s >/dev/null 2>&1; then
        local node_count
        node_count=$(kubectl --kubeconfig="$temp_kubeconfig" get nodes --no-headers 2>/dev/null | wc -l)
        log_verbose "kubectl test successful - cluster has $node_count nodes"
        rm -f "$temp_kubeconfig"
        return 0
    else
        log_verbose "kubectl test failed"
        rm -f "$temp_kubeconfig"
        return 1
    fi
}

# Main setup function
main() {
    log_step "K3s Single Node Cluster Setup v${SCRIPT_VERSION}"
    
    if [ "$LOCAL_MODE" = true ]; then
        log "Mode: Local (existing tailnet)"
    else
        log "Mode: Standard (new tailnet node)"
    fi
    
    if [ "$NO_K3S_SETUP" = true ]; then
        log "K3s: Use existing cluster"
    else
        log "K3s: Install new cluster"
    fi
    
    log "Target hostname: $HOSTNAME"
    log "Secret key: $SECRET_KEY"
    log "Secret file: ~/.k3s/secret-key"
    
    if [ "$DRY_RUN" = true ]; then
        log "DRY RUN MODE - No changes will be made"
        return 0
    fi

    check_privileges
    install_docker
    setup_ssh
    
    if [ "$LOCAL_MODE" = false ]; then
        set_hostname
        setup_tailscale
    else
        setup_tailscale_local
    fi
    
    if [ "$NO_K3S_SETUP" = true ]; then
        check_k3s_running
    else
        install_k3s
    fi
    
    # Set up K3s API funnel after K3s is running (to avoid port conflicts)
    setup_k3s_funnel
    
    if [ "$ANDROID_FORWARDING" = true ]; then
        setup_port_forwarding
    fi
    
    setup_kubeconfig_server

    log_step "Setup Complete!"
    echo
    log "✅ K3s single-node cluster is ready"
    
    if [ "$ANDROID_FORWARDING" = true ]; then
        log "📱 Android app accessible locally: http://localhost:8005"
    else
        log "📱 Android app forwarding: disabled (use --android to enable)"
    fi
    
    if [ "$LOCAL_MODE" = true ]; then
        # In local mode, get the actual funnel domain
        local funnel_domain funnel_full_url
        log_verbose "Detecting funnel URL..."
        
        # Try multiple methods to get funnel domain
        # Look for the main domain (shortest URL without port) first
        if funnel_full_url=$(tailscale funnel status 2>/dev/null | grep -E "https://[^[:space:]]+\.ts\.net[^:]" | head -1); then
            # Extract domain from the main domain entry (without port)
            funnel_domain=$(echo "$funnel_full_url" | sed -E 's/.*https:\/\/([^[:space:]]+\.ts\.net).*/\1/' | sed 's/(.*$//')
            log_verbose "Found main domain entry: $funnel_full_url"
            log_verbose "Extracted main domain: $funnel_domain"
        elif funnel_full_url=$(tailscale funnel status 2>/dev/null | grep -E "https://[^[:space:]]+" | head -1); then
            # Fallback: extract domain from any funnel entry and remove port if present
            funnel_domain=$(echo "$funnel_full_url" | sed -E 's/.*https:\/\/([^[:space:]]+).*/\1/' | sed 's/:.*$//' | sed 's/(.*$//')
            log_verbose "Fallback funnel entry: $funnel_full_url"
            log_verbose "Extracted domain (port removed): $funnel_domain"
        fi
        
        # Fallback: try to get from tailscale status
        if [ -z "$funnel_domain" ] || [ "$funnel_domain" = "#" ]; then
            log_verbose "Trying alternative method to get funnel domain..."
            local ts_status
            # Try JSON method first
            if ts_status=$(tailscale status --json 2>/dev/null) && command -v jq >/dev/null 2>&1; then
                funnel_domain=$(echo "$ts_status" | jq -r '.Self.DNSName // empty' | sed 's/\.$//')
            # Try parsing text output
            elif ts_status=$(tailscale status 2>/dev/null | grep "$(hostname)" | head -1); then
                funnel_domain=$(echo "$ts_status" | awk '{print $2}' | sed 's/\.$//')
            # Last resort: try any line with our hostname
            elif ts_status=$(tailscale status 2>/dev/null | grep -E "\.ts\.net" | head -1); then
                funnel_domain=$(echo "$ts_status" | awk '{print $1}' | sed 's/\.$//')
            fi
            log_verbose "Alternative extraction result: $funnel_domain"
        fi
        
        # Final fallback: construct from hostname
        if [ -z "$funnel_domain" ] || [ "$funnel_domain" = "#" ]; then
            funnel_domain="${HOSTNAME}.ts.net"
            log_warn "Could not detect funnel domain, using fallback: $funnel_domain"
        fi
        
        if [ -n "$funnel_domain" ] && [ "$funnel_domain" != "#" ]; then
            log "🌐 K3s API accessible: https://$funnel_domain:6443"
            log "📄 Kubeconfig: https://$funnel_domain/kubeconfig?key=${SECRET_KEY}"
            log "🚢 NodePort services: https://$funnel_domain:30080"
            if [ "$ANDROID_FORWARDING" = true ]; then
                log "📱 Android app via funnel: https://$funnel_domain:8005"
            fi
            
            # Test funnel connectivity
            test_funnel_connectivity "$funnel_domain"
            
            # Add example command for testing
            echo
            log "🧪 Test cluster access:"
            log "   curl -s \"https://$funnel_domain/kubeconfig?key=${SECRET_KEY}\" -o /tmp/kubeconfig.yaml && kubectl --kubeconfig=/tmp/kubeconfig.yaml get nodes"
            
            # Add HTTPBin deployment example
            echo
            log "🐳 Deploy HTTPBin for testing:"
            log "   ./httpbin.sh deploy -u https://$funnel_domain/kubeconfig -k ${SECRET_KEY}"
            log "   ./httpbin.sh test -u https://$funnel_domain/kubeconfig -k ${SECRET_KEY}"
            
            # Add API endpoints information
            echo
            log "🔌 Extended API endpoints:"
            log "   Device location: curl -s \"https://$funnel_domain/location?key=${SECRET_KEY}\""
            log "   Device orientation: curl -s \"https://$funnel_domain/orientation?key=${SECRET_KEY}\""
            log "   Funnel ports status: curl -s \"https://$funnel_domain/ports?key=${SECRET_KEY}\""
            log "   Open funnel port: curl -X POST \"https://$funnel_domain/ports/open?key=${SECRET_KEY}&port=8080\""
        else
            log "🌐 K3s API accessible: https://${HOSTNAME}.<domain>.ts.net:6443"
            log "📄 Kubeconfig: https://${HOSTNAME}.<domain>.ts.net/kubeconfig?key=${SECRET_KEY}"
            log "🚢 NodePort services: https://${HOSTNAME}.<domain>.ts.net:30080"
            if [ "$ANDROID_FORWARDING" = true ]; then
                log "📱 Android app via funnel: https://${HOSTNAME}.<domain>.ts.net:8005"
            fi
            log_warn "Run 'tailscale funnel status' to get the exact domain"
        fi
    else
        # Standard mode: try to get the actual funnel domain
        local funnel_domain
        log_verbose "Detecting funnel URL in standard mode..."
        
        # Try to get actual domain from funnel status
        local funnel_status
        if funnel_status=$(tailscale funnel status 2>/dev/null); then
            funnel_domain=$(echo "$funnel_status" | grep -E "https://[^[:space:]]+" | head -1 | sed -E 's/.*https:\/\/([^[:space:]]+).*/\1/' | sed 's/(.*$//')
            log_verbose "Detected funnel domain: $funnel_domain"
        fi
        
        if [ -n "$funnel_domain" ] && [ "$funnel_domain" != "#" ]; then
            log "🌐 K3s API accessible: https://$funnel_domain:6443"
            log "📄 Kubeconfig: https://$funnel_domain/kubeconfig?key=${SECRET_KEY}"
            log "🚢 NodePort services: https://$funnel_domain:30080"
            if [ "$ANDROID_FORWARDING" = true ]; then
                log "📱 Android app via funnel: https://$funnel_domain:8005"
            fi
            
            # Test funnel connectivity
            test_funnel_connectivity "$funnel_domain"
            
            # Add example command for testing
            echo
            log "🧪 Test cluster access:"
            log "   curl -s \"https://$funnel_domain/kubeconfig?key=${SECRET_KEY}\" -o /tmp/kubeconfig.yaml && kubectl --kubeconfig=/tmp/kubeconfig.yaml get nodes"
        else
            log "🌐 K3s API accessible: https://${HOSTNAME}.tailxxxx.ts.net:6443"
            log "📄 Kubeconfig: https://${HOSTNAME}.tailxxxx.ts.net/kubeconfig?key=${SECRET_KEY}"
            log "🚢 NodePort services: https://${HOSTNAME}.tailxxxx.ts.net:30080"
            if [ "$ANDROID_FORWARDING" = true ]; then
                log "📱 Android app via funnel: https://${HOSTNAME}.tailxxxx.ts.net:8005"
            fi
            log_warn "Could not detect exact funnel domain. Check with: tailscale funnel status"
        fi
    fi
    
    echo
    log "📋 Logs and Troubleshooting:"
    log "   • Kubeconfig server logs: sudo journalctl -u kubeconfig-server -f"
    log "   • Kubeconfig server log file: /var/log/kubeconfig-server.log"
    log "   • K3s logs: sudo journalctl -u k3s -f"
    log "   • Tailscale logs: sudo journalctl -u tailscaled -f"
    log "   • Tailscale funnel status: tailscale funnel status"
    echo
    log "Next steps:"
    log "1. Deploy HTTPBin for testing:"
    if [ -n "$funnel_domain" ] && [ "$funnel_domain" != "#" ]; then
        log "   curl -sSL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/single-node-cluster/httpbin.sh | bash -s -- --host \"$funnel_domain\" --key \"${SECRET_KEY}\""
    else
        log "   curl -sSL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/main/single-node-cluster/httpbin.sh | bash -s -- --host \"${HOSTNAME}.tailxxxx.ts.net\" --key \"${SECRET_KEY}\""
    fi
    log "2. Access your cluster remotely using the kubeconfig URL"
    log "3. Monitor cluster status via Tailscale funnel"
    
    if [ "$LOCAL_MODE" = true ]; then
        log "4. Check funnel status: tailscale funnel status"
    fi
    echo
}


parse_args "$@"
main
