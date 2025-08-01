#!/bin/bash

# K3s on Phone Setup Script
# This script sets up a Kubernetes cluster using K3s on Android phones
# running Debian in KVM hypervisor via the Android Linux Terminal app.
#
# One-line installation:
# curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/refs/heads/main/setup.sh | bash -s -- HOSTNAME -t TAILSCALE_KEY

set -e

# Script version
VERSION="1.0.0"

# Default values
VERBOSE=false
HOSTNAME=""
TAILSCALE_AUTH_KEY=""
K3S_TOKEN=""
K3S_URL=""
CLEANUP_MODE=false
REMOVE_FROM_TAILSCALE=false
LOCAL_MODE=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Exit codes
EXIT_SUCCESS=0
EXIT_INVALID_ARGS=1
EXIT_MISSING_DEPS=2
EXIT_INSTALL_FAILED=3
EXIT_CONFIG_FAILED=4

# Logging functions
log() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_verbose() {
    if [ "$VERBOSE" = true ]; then
        echo -e "${YELLOW}[VERBOSE]${NC} $1"
    fi
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# Help function
show_help() {
    cat << EOF
K3s on Phone Setup Script v${VERSION}

Sets up a Kubernetes cluster using K3s on Android phones running Debian
in KVM hypervisor via the Android Linux Terminal app.

USAGE:
    ./setup.sh HOSTNAME [OPTIONS]
    ./setup.sh --local [OPTIONS]
    ./setup.sh cleanup [OPTIONS]

ARGUMENTS:
    HOSTNAME                    Set the hostname for this node (not allowed with --local)
    cleanup                     Remove not-ready nodes from cluster

OPTIONS:
    -t, --tailscale-key KEY     Tailscale authentication key
                                Get one at: https://login.tailscale.com/admin/machines/new-linux
    -k, --k3s-token TOKEN       K3s node token (must be used with -u)
    -u, --k3s-url URL           K3s server URL (must be used with -k)
    --local                     Local mode: skip hostname, password, Tailscale, Docker setup (but checks Tailscale is running)
    -v, --verbose               Enable verbose output
    -h, --help                  Show this help message
    --version                   Show version information
    --remove-from-tailscale     Also remove nodes from Tailscale VPN (cleanup only)

EXAMPLES:
    # Setup as K3s server (master node)
    ./setup.sh my-phone-01 -t tskey-auth-xxxxx

    # Setup as K3s agent (worker node)
    ./setup.sh my-phone-02 -t tskey-auth-xxxxx -k mynodetoken -u https://my-phone-01:6443

    # Setup agent with auto-generated hostname
    ./setup.sh phone-%d -t tskey-auth-xxxxx -k mynodetoken -u https://my-phone-01:6443

    # Local server setup: computer/server as K3s master
    ./setup.sh --local

    # Local mode: join existing cluster
    ./setup.sh --local -k mynodetoken -u https://existing-server:6443

    # Clean up not-ready nodes
    ./setup.sh cleanup -v

    # Clean up not-ready nodes and remove from Tailscale
    ./setup.sh cleanup --remove-from-tailscale -v

DESCRIPTION:
    This script will:
    1. Set the hostname for the device
    2. Install Docker following official Debian installation guide
    3. Configure SSH server with root access
    4. Install and configure Tailscale for secure networking
    5. Install K3s either as server (master) or agent (worker)

    Cleanup mode will:
    1. Identify nodes in NotReady state
    2. Remove them from the K3s cluster
    3. Optionally remove them from Tailscale VPN

    Local mode will:
    1. Skip hostname, password, Tailscale, and Docker setup
    2. Only install and configure K3s
    3. Suitable for existing systems with prerequisites already installed
    4. If -t flag is provided, it will be included in generated agent commands

NOTES:
    - This script requires sudo privileges
    - Root SSH password will be set to 'root' for simplicity
    - The Android Linux Terminal app is experimental and may be unstable
    - Ensure you have developer mode enabled on your Android device
    - Cleanup mode must be run from the K3s server (master) node

EOF
}

show_version() {
    echo "K3s on Phone Setup Script v${VERSION}"
}

# Utility functions
check_root() {
    if [ "$EUID" -eq 0 ]; then
        log_error "Please run this script as a regular user with sudo privileges, not as root"
        exit $EXIT_INVALID_ARGS
    fi
}

check_sudo() {
    if ! command -v sudo &> /dev/null; then
        log_error "sudo is required but not installed"
        exit $EXIT_MISSING_DEPS
    fi
    
    # Test sudo access
    if ! sudo -n true 2>/dev/null; then
        log "Testing sudo access..."
        if ! sudo true; then
            log_error "sudo access required but not available"
            exit $EXIT_MISSING_DEPS
        fi
    fi
}

check_internet() {
    log_verbose "Checking internet connectivity..."
    if ! ping -c 1 8.8.8.8 &> /dev/null; then
        log_error "Internet connection required but not available"
        exit $EXIT_MISSING_DEPS
    fi
}

# Installation functions
install_docker() {
    log_step "Installing Docker..."
    
    # Check if Docker is already installed
    if command -v docker &> /dev/null; then
        log "Docker is already installed, skipping installation"
        return 0
    fi
    
    log_verbose "Updating package list"
    sudo apt-get update -qq || {
        log_error "Failed to update package list"
        exit $EXIT_INSTALL_FAILED
    }
    
    log_verbose "Installing prerequisites"
    sudo apt-get install -y ca-certificates curl || {
        log_error "Failed to install prerequisites"
        exit $EXIT_INSTALL_FAILED
    }
    
    log_verbose "Setting up Docker GPG key"
    sudo install -m 0755 -d /etc/apt/keyrings
    sudo curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc || {
        log_error "Failed to download Docker GPG key"
        exit $EXIT_INSTALL_FAILED
    }
    sudo chmod a+r /etc/apt/keyrings/docker.asc
    
    log_verbose "Adding Docker repository"
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian \
      $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
      sudo tee /etc/apt/sources.list.d/docker.list > /dev/null || {
        log_error "Failed to add Docker repository"
        exit $EXIT_INSTALL_FAILED
    }
    
    log_verbose "Installing Docker packages"
    sudo apt-get update -qq || {
        log_error "Failed to update package list after adding Docker repository"
        exit $EXIT_INSTALL_FAILED
    }
    
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin || {
        log_error "Failed to install Docker packages"
        exit $EXIT_INSTALL_FAILED
    }
    
    log_verbose "Testing Docker installation"
    if sudo docker run --rm hello-world > /dev/null 2>&1; then
        log "Docker installed and tested successfully"
    else
        log_error "Docker test failed"
        exit $EXIT_INSTALL_FAILED
    fi
    
    # Add current user to docker group
    log_verbose "Adding user to docker group"
    sudo usermod -aG docker "$USER" || log_warn "Failed to add user to docker group"
}

setup_ssh() {
    log_step "Setting up SSH server..."
    
    log_verbose "Installing OpenSSH server"
    sudo apt-get install -y openssh-server || {
        log_error "Failed to install OpenSSH server"
        exit $EXIT_INSTALL_FAILED
    }
    
    log_verbose "Configuring SSH"
    # Backup original config if it exists and we haven't backed it up yet
    if [ -f /etc/ssh/sshd_config ] && [ ! -f /etc/ssh/sshd_config.backup ]; then
        sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.backup
    fi
    
    # Enable root login (configure as needed for security)
    if grep -q "^#PermitRootLogin" /etc/ssh/sshd_config; then
        sudo sed -i 's/^#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
    elif grep -q "^PermitRootLogin" /etc/ssh/sshd_config; then
        sudo sed -i 's/^PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
    else
        echo "PermitRootLogin yes" | sudo tee -a /etc/ssh/sshd_config > /dev/null
    fi
    
    log_verbose "Setting root password to 'root'"
    echo "root:root" | sudo chpasswd || {
        log_error "Failed to set root password"
        exit $EXIT_CONFIG_FAILED
    }
    
    log_verbose "Starting and enabling SSH service"
    sudo systemctl restart sshd || {
        log_error "Failed to restart SSH service"
        exit $EXIT_CONFIG_FAILED
    }
    sudo systemctl enable sshd || log_warn "Failed to enable SSH service"
    
    log "SSH server configured successfully"
    log_warn "Root SSH login enabled with password 'root' - consider changing for security"
}

setup_tailscale() {
    log_step "Installing and configuring Tailscale..."
    
    # Install Tailscale if not already installed
    if ! command -v tailscale &> /dev/null; then
        log_verbose "Installing Tailscale"
        curl -fsSL https://tailscale.com/install.sh | sh || {
            log_error "Failed to install Tailscale"
            exit $EXIT_INSTALL_FAILED
        }
    else
        log "Tailscale is already installed"
    fi
    
    # Check if already configured and running
    if sudo tailscale status --json &> /dev/null; then
        log "Tailscale is already configured and running"
        return 0
    fi
    
    if [ -n "$TAILSCALE_AUTH_KEY" ]; then
        log_verbose "Connecting to Tailscale with auth key"
        sudo tailscale up --auth-key="$TAILSCALE_AUTH_KEY" --hostname="$HOSTNAME" || {
            log_error "Failed to connect to Tailscale"
            exit $EXIT_CONFIG_FAILED
        }
    else
        log "Tailscale installed. Run 'sudo tailscale up --auth-key=YOUR_KEY' to connect manually"
    fi
    
    log_verbose "Enabling Tailscale service"
    sudo systemctl enable tailscaled || log_warn "Failed to enable Tailscale service"
    
    log "Tailscale setup completed"
}

check_tailscale_local_mode() {
    log_step "Checking Tailscale status in local mode..."
    
    # Check if Tailscale is installed
    if ! command -v tailscale &> /dev/null; then
        log_error "Tailscale is not installed!"
        echo ""
        echo "Please install Tailscale first:"
        echo "  curl -fsSL https://tailscale.com/install.sh | sh"
        echo ""
        echo "Then authenticate with your Tailscale account:"
        echo "  sudo tailscale up"
        echo ""
        exit $EXIT_MISSING_DEPS
    fi
    
    # Check if Tailscale is running and authenticated
    if ! sudo tailscale status --json &> /dev/null; then
        log_error "Tailscale is installed but not running or not authenticated!"
        echo ""
        echo "Please ensure Tailscale is set up and running:"
        echo "  sudo tailscale up"
        echo ""
        echo "If you haven't authenticated yet, you'll be prompted to visit a URL to authenticate."
        echo ""
        exit $EXIT_CONFIG_FAILED
    fi
    
    # Get Tailscale status
    local tailscale_ip=$(tailscale ip -4 2>/dev/null || echo "unknown")
    local tailscale_hostname=$(tailscale status --json 2>/dev/null | grep -o '"Name":"[^"]*"' | head -1 | cut -d'"' -f4 2>/dev/null || echo "unknown")
    
    log "✅ Tailscale is running"
    log_verbose "Tailscale IP: $tailscale_ip"
    log_verbose "Tailscale hostname: $tailscale_hostname"
}

set_hostname() {
    log_step "Setting hostname to: $HOSTNAME"
    
    # Get current hostname
    local current_hostname=$(hostname)
    
    if [ "$current_hostname" = "$HOSTNAME" ]; then
        log "Hostname is already set correctly"
        return 0
    fi
    
    # Try to use hostnamectl if available (systemd systems)
    if command -v hostnamectl &> /dev/null; then
        log_verbose "Using hostnamectl to set hostname"
        sudo hostnamectl set-hostname "$HOSTNAME" || {
            log_error "Failed to set hostname using hostnamectl"
            exit $EXIT_CONFIG_FAILED
        }
    else
        # Fallback to manual hostname setting
        log_verbose "Using manual hostname configuration"
        
        log_verbose "Updating /etc/hostname"
        echo "$HOSTNAME" | sudo tee /etc/hostname > /dev/null || {
            log_error "Failed to update /etc/hostname"
            exit $EXIT_CONFIG_FAILED
        }
        
        log_verbose "Setting hostname for current session"
        sudo hostname "$HOSTNAME" || {
            log_error "Failed to set hostname for current session"
            exit $EXIT_CONFIG_FAILED
        }
    fi
    
    log_verbose "Updating /etc/hosts"
    # Update or add the hostname entry
    if grep -q "127.0.1.1" /etc/hosts; then
        sudo sed -i "s/127.0.1.1.*/127.0.1.1\t$HOSTNAME/" /etc/hosts
    else
        echo -e "127.0.1.1\t$HOSTNAME" | sudo tee -a /etc/hosts > /dev/null
    fi
    
    # Verify the hostname was set correctly
    local new_hostname=$(hostname)
    if [ "$new_hostname" = "$HOSTNAME" ]; then
        log "Hostname set successfully to: $new_hostname"
    else
        log_warn "Hostname may not have been set correctly (current: $new_hostname, expected: $HOSTNAME)"
        log_warn "A reboot may be required for the hostname change to fully take effect"
    fi
}

install_k3s_server() {
    log_step "Installing K3s as server (master node)..."
    
    # Check if K3s is already installed
    if command -v k3s &> /dev/null; then
        log "K3s is already installed, checking configuration..."
        
        # Check if this is running as a server by looking for server process
        if sudo systemctl is-active --quiet k3s 2>/dev/null; then
            log "K3s server service is already running"
            
            # Server is running, token file should exist - read it directly
            if sudo test -f /var/lib/rancher/k3s/server/node-token; then
                show_agent_setup_info
                return 0
            else
                log_error "K3s server service is running but token file not found"
                log_error "Server may have failed to initialize properly"
                return 1
            fi
        elif sudo systemctl is-active --quiet k3s-agent 2>/dev/null; then
            log_error "K3s is already installed and running as an agent (worker node)"
            log_error "Cannot install server on a node that's already configured as an agent"
            log_error "Use cleanup mode or reinstall K3s to change the node type"
            return 1
        else
            log "K3s is installed but not running, will start as server"
        fi
    fi
    
    log_verbose "Downloading and installing K3s server"
    curl -sfL https://get.k3s.io | sh - || {
        log_error "Failed to install K3s server"
        exit $EXIT_INSTALL_FAILED
    }
    
    log_verbose "Waiting for K3s to be ready..."
    local retries=30
    while [ $retries -gt 0 ]; do
        if sudo k3s kubectl get nodes &> /dev/null; then
            break
        fi
        sleep 2
        retries=$((retries - 1))
    done
    
    if [ $retries -eq 0 ]; then
        log_error "K3s server failed to start properly"
        exit $EXIT_INSTALL_FAILED
    fi
    
    log "K3s server installed successfully!"
    show_agent_setup_info
}

install_k3s_agent() {
    log_step "Installing K3s as agent (worker node)..."
    
    # Check if K3s is already installed
    if command -v k3s &> /dev/null; then
        log "K3s is already installed, checking configuration..."
        
        # Check if this is running as an agent
        if sudo systemctl is-active --quiet k3s-agent 2>/dev/null; then
            log "K3s agent service is already running"
            return 0
        elif sudo systemctl is-active --quiet k3s 2>/dev/null; then
            log_error "K3s is already installed and running as a server (master node)"
            log_error "Cannot install agent on a node that's already configured as a server"
            log_error "Use cleanup mode or reinstall K3s to change the node type"
            return 1
        else
            log "K3s is installed but not running, will start as agent"
        fi
    fi
    
    log_verbose "Downloading and installing K3s agent with server URL: $K3S_URL"
    curl -sfL https://get.k3s.io | K3S_URL="$K3S_URL" K3S_TOKEN="$K3S_TOKEN" sh - || {
        log_error "Failed to install K3s agent"
        exit $EXIT_INSTALL_FAILED
    }
    
    log "K3s agent installed successfully!"
    log "This node should now be connected to the cluster at: $K3S_URL"
}

show_agent_setup_info() {
    if ! sudo test -f /var/lib/rancher/k3s/server/node-token; then
        log_warn "K3s node token file not found, cannot show agent setup commands"
        return 1
    fi
    
    local token
    token=$(sudo cat /var/lib/rancher/k3s/server/node-token 2>/dev/null)
    if [ -z "$token" ]; then
        log_warn "Failed to read K3s node token"
        return 1
    fi
    
    local server_url="https://$HOSTNAME:6443"
    
    # Handle Tailscale auth key parameter based on context
    local tailscale_flag=""
    if [ "$LOCAL_MODE" = true ]; then
        # In local mode, only include -t if it was explicitly provided
        if [ -n "$TAILSCALE_AUTH_KEY" ]; then
            tailscale_flag=" -t $TAILSCALE_AUTH_KEY"
        fi
    else
        # In non-local mode, always include -t (with placeholder if not provided)
        local tailscale_key_param="${TAILSCALE_AUTH_KEY:-YOUR_TAILSCALE_AUTH_KEY}"
        tailscale_flag=" -t $tailscale_key_param"
    fi
    
    echo ""
    log "=============================================="
    log "K3s Server Setup Complete!"
    log "=============================================="
    echo ""
    log "K3s Token (for agent nodes): $token"
    log "Server URL: $server_url"
    echo ""
    log "To get the token manually anytime:"
    echo "sudo cat /var/lib/rancher/k3s/server/node-token"
    echo ""
    log "To add agent nodes, use one of the following methods:"
    echo ""
    echo "Option 1 - One-line setup with auto-generated hostname:"
    echo ""
    echo "curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/refs/heads/main/setup.sh | bash -s -- phone-%d$tailscale_flag -k $token -u $server_url"
    echo ""
    echo "Option 2 - One-line setup with manual hostname:"
    echo ""
    echo "curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/refs/heads/main/setup.sh | bash -s -- AGENT_HOSTNAME$tailscale_flag -k $token -u $server_url"
    echo ""
    echo "Option 3 - Download and run manually:"
    echo ""
    echo "curl -sfL https://raw.githubusercontent.com/parttimenerd/k3s-on-phone/refs/heads/main/setup.sh > setup.sh"
    echo "chmod +x setup.sh"
    echo "./setup.sh AGENT_HOSTNAME$tailscale_flag -k $token -u $server_url"
    echo ""
    log "Option 1 auto-generates unique hostnames. For manual setup (Options 2-3), replace AGENT_HOSTNAME with the desired hostname for each agent node"
    if [ "$LOCAL_MODE" = false ] && [ -z "$TAILSCALE_AUTH_KEY" ]; then
        log "Replace YOUR_TAILSCALE_AUTH_KEY with your actual Tailscale auth key"
    fi
    echo ""
}

# Cleanup functions
cleanup_not_ready_nodes() {
    log_step "Cleaning up not-ready nodes from K3s cluster..."
    
    # Check if we have kubectl access
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl not found. This command must be run from the K3s server node."
        exit $EXIT_MISSING_DEPS
    fi
    
    # Check if we can connect to the cluster
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to K3s cluster. Ensure you're running this on the server node."
        exit $EXIT_MISSING_DEPS
    fi
    
    # Get not-ready nodes
    log_verbose "Checking for not-ready nodes..."
    local not_ready_nodes
    not_ready_nodes=$(kubectl get nodes --no-headers | grep "NotReady" | awk '{print $1}' || echo "")
    
    if [ -z "$not_ready_nodes" ]; then
        log "No not-ready nodes found in the cluster"
        return 0
    fi
    
    log "Found not-ready nodes:"
    echo "$not_ready_nodes" | while read -r node; do
        if [ -n "$node" ]; then
            log "  - $node"
        fi
    done
    
    # Confirm deletion
    echo ""
    read -p "Do you want to remove these nodes from the cluster? (y/N): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log "Cleanup cancelled by user"
        return 0
    fi
    
    # Remove each not-ready node
    echo "$not_ready_nodes" | while read -r node; do
        if [ -n "$node" ]; then
            log_verbose "Draining node: $node"
            kubectl drain "$node" --ignore-daemonsets --delete-emptydir-data --force --timeout=60s || {
                log_warn "Failed to drain node $node, continuing with deletion"
            }
            
            log_verbose "Deleting node: $node"
            kubectl delete node "$node" || {
                log_error "Failed to delete node $node"
                continue
            }
            
            log "Removed node: $node"
            
            # Remove from Tailscale if requested
            if [ "$REMOVE_FROM_TAILSCALE" = true ]; then
                remove_from_tailscale "$node"
            fi
        fi
    done
    
    log "Cleanup completed"
}

remove_from_tailscale() {
    local node_name="$1"
    
    if [ -z "$node_name" ]; then
        log_warn "No node name provided for Tailscale removal"
        return 1
    fi
    
    log_verbose "Attempting to remove $node_name from Tailscale..."
    
    # Check if tailscale command is available
    if ! command -v tailscale &> /dev/null; then
        log_warn "Tailscale command not found, skipping Tailscale cleanup for $node_name"
        return 1
    fi
    
    # List Tailscale devices and try to find the node
    local tailscale_status
    tailscale_status=$(sudo tailscale status --json 2>/dev/null || echo "")
    
    if [ -z "$tailscale_status" ]; then
        log_warn "Could not get Tailscale status, skipping removal of $node_name"
        return 1
    fi
    
    # Try to find the device ID for the node (this is a simplified approach)
    # In a real scenario, you might need more sophisticated matching
    log_warn "Automatic Tailscale device removal not implemented yet"
    log_warn "Please manually remove $node_name from your Tailscale admin console:"
    log_warn "https://login.tailscale.com/admin/machines"
    
    return 0
}

# Validation functions
validate_hostname() {
    # In local mode, hostname is prohibited
    if [ "$LOCAL_MODE" = true ] && [ -n "$HOSTNAME" ]; then
        log_error "Hostname argument is not allowed in --local mode"
        return 1
    fi
    
    # In local mode without hostname, this is valid
    if [ "$LOCAL_MODE" = true ] && [ -z "$HOSTNAME" ]; then
        return 0
    fi
    
    if [ -z "$HOSTNAME" ]; then
        log_error "Hostname is required"
        return 1
    fi
    
    # Check for auto-hostname pattern and expand it
    if echo "$HOSTNAME" | grep -q '%d'; then
        log_verbose "Auto-hostname pattern detected, expanding %d with timestamp"
        local timestamp_b64
        timestamp_b64=$(echo "$(date +%s)" | base64 | tr -d '=' | tr '/' '-' | cut -c1-8)
        HOSTNAME=$(echo "$HOSTNAME" | sed "s/%d/$timestamp_b64/g")
        log_verbose "Expanded hostname: $HOSTNAME"
    fi
    
    # Basic hostname validation
    if ! echo "$HOSTNAME" | grep -qE '^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$'; then
        log_error "Invalid hostname format. Use only letters, numbers, and hyphens"
        return 1
    fi
}

validate_k3s_params() {
    # K3S_TOKEN and K3S_URL must be used together
    if [ -n "$K3S_TOKEN" ] && [ -z "$K3S_URL" ]; then
        log_error "K3S_TOKEN requires K3S_URL to be set"
        return 1
    fi
    
    if [ -n "$K3S_URL" ] && [ -z "$K3S_TOKEN" ]; then
        log_error "K3S_URL requires K3S_TOKEN to be set"
        return 1
    fi
    
    # Validate URL format if provided
    if [ -n "$K3S_URL" ]; then
        if ! echo "$K3S_URL" | grep -qE '^https?://'; then
            log_error "K3S_URL must start with http:// or https://"
            return 1
        fi
    fi
}

validate_tailscale_key() {
    # Validate Tailscale auth key format if provided
    if [ -n "$TAILSCALE_AUTH_KEY" ]; then
        if ! echo "$TAILSCALE_AUTH_KEY" | grep -qE '^tskey-auth-'; then
            log_error "Invalid Tailscale auth key format. Tailscale keys must start with 'tskey-auth-'"
            log_error "Get a valid key at: https://login.tailscale.com/admin/machines/new-linux"
            return 1
        fi
    fi
}

validate_local_params() {
    if [ "$LOCAL_MODE" = true ]; then
        # In local mode, use current hostname
        HOSTNAME=$(hostname)
        log_verbose "Local mode: using current hostname: $HOSTNAME"
        
        # Local mode without K3s parameters requires server setup or agent parameters
        if [ -z "$K3S_TOKEN" ] && [ -z "$K3S_URL" ]; then
            log_verbose "Local mode: will setup K3s server (no agent parameters provided)"
        elif [ -n "$K3S_TOKEN" ] && [ -n "$K3S_URL" ]; then
            log_verbose "Local mode: will setup K3s agent"
        else
            log_error "Local mode: either provide both -k and -u for agent mode, or neither for server mode"
            return 1
        fi
    fi
    
    return 0
}

# Argument parsing
parse_arguments() {
    if [ $# -eq 0 ]; then
        show_help
        exit $EXIT_SUCCESS
    fi
    
    # Check for help/version flags first
    if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
        show_help
        exit $EXIT_SUCCESS
    fi
    
    if [ "$1" = "--version" ]; then
        show_version
        exit $EXIT_SUCCESS
    fi
    
    # Check if first argument is 'cleanup'
    if [ "$1" = "cleanup" ]; then
        CLEANUP_MODE=true
        shift
        
        # Parse cleanup-specific options
        while [[ $# -gt 0 ]]; do
            case $1 in
                --remove-from-tailscale)
                    REMOVE_FROM_TAILSCALE=true
                    shift
                    ;;
                -v|--verbose)
                    VERBOSE=true
                    shift
                    ;;
                -h|--help)
                    show_help
                    exit $EXIT_SUCCESS
                    ;;
                --version)
                    show_version
                    exit $EXIT_SUCCESS
                    ;;
                *)
                    log_error "Unknown cleanup option: $1"
                    echo ""
                    show_help
                    exit $EXIT_INVALID_ARGS
                    ;;
            esac
        done
        return 0
    fi
    
    # Check if first argument is '--local'
    if [ "$1" = "--local" ]; then
        LOCAL_MODE=true
        shift
        
        # Parse remaining options for local mode
        while [[ $# -gt 0 ]]; do
            case $1 in
                -t|--tailscale-key)
                    TAILSCALE_AUTH_KEY="$2"
                    shift 2
                    ;;
                -k|--k3s-token)
                    K3S_TOKEN="$2"
                    shift 2
                    ;;
                -u|--k3s-url)
                    K3S_URL="$2"
                    shift 2
                    ;;
                -v|--verbose)
                    VERBOSE=true
                    shift
                    ;;
                -h|--help)
                    show_help
                    exit $EXIT_SUCCESS
                    ;;
                --version)
                    show_version
                    exit $EXIT_SUCCESS
                    ;;
                *)
                    log_error "Unknown option for --local mode: $1"
                    echo ""
                    show_help
                    exit $EXIT_INVALID_ARGS
                    ;;
            esac
        done
        return 0
    fi
    
    # Regular hostname-based setup
    HOSTNAME="$1"
    shift
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            -t|--tailscale-key)
                TAILSCALE_AUTH_KEY="$2"
                shift 2
                ;;
            -k|--k3s-token)
                K3S_TOKEN="$2"
                shift 2
                ;;
            -u|--k3s-url)
                K3S_URL="$2"
                shift 2
                ;;
            --local)
                LOCAL_MODE=true
                shift
                ;;
            -v|--verbose)
                VERBOSE=true
                shift
                ;;
            -h|--help)
                show_help
                exit $EXIT_SUCCESS
                ;;
            --version)
                show_version
                exit $EXIT_SUCCESS
                ;;
            *)
                log_error "Unknown option: $1"
                echo ""
                show_help
                exit $EXIT_INVALID_ARGS
                ;;
        esac
    done
}

# Main function
main() {
    # Parse command line arguments
    parse_arguments "$@"
    
    # Handle cleanup mode
    if [ "$CLEANUP_MODE" = true ]; then
        log "=============================================="
        log "K3s on Phone Cleanup v${VERSION}"
        log "=============================================="
        log "Mode: Cleanup not-ready nodes"
        log "Remove from Tailscale: $REMOVE_FROM_TAILSCALE"
        log "Verbose: $VERBOSE"
        log "=============================================="
        
        if [ "$VERBOSE" = true ]; then
            log_verbose "Verbose mode enabled - showing detailed output"
        fi
        
        echo ""
        
        # Pre-flight checks for cleanup
        check_root
        check_sudo
        
        # Run cleanup
        cleanup_not_ready_nodes
        
        echo ""
        log "=============================================="
        log "Cleanup completed!"
        log "=============================================="
        echo ""
        return 0
    fi
    
    # Regular setup mode - validate arguments
    validate_hostname || exit $EXIT_INVALID_ARGS
    validate_k3s_params || exit $EXIT_INVALID_ARGS
    validate_tailscale_key || exit $EXIT_INVALID_ARGS
    validate_local_params || exit $EXIT_INVALID_ARGS
    
    # Pre-flight checks
    check_root
    check_sudo
    check_internet
    
    # Show configuration
    log "=============================================="
    log "K3s on Phone Setup v${VERSION}"
    log "=============================================="
    log "Hostname: $HOSTNAME"
    if [ "$LOCAL_MODE" = true ]; then
        log "Mode: Local (minimal setup)"
    fi
    if [ "$LOCAL_MODE" = false ]; then
        log "Tailscale Auth Key: ${TAILSCALE_AUTH_KEY:+***provided***}"
    fi
    if [ -n "$K3S_TOKEN" ] && [ -n "$K3S_URL" ]; then
        log "K3s Mode: Agent (Worker Node)"
        log "K3s Server URL: $K3S_URL"
        log "K3s Token: ***provided***"
    else
        log "K3s Mode: Server (Master Node)"
    fi
    log "Verbose: $VERBOSE"
    log "=============================================="
    
    if [ "$VERBOSE" = true ]; then
        log_verbose "Verbose mode enabled - showing detailed output"
    fi
    
    echo ""
    
    # Main installation steps
    if [ "$LOCAL_MODE" = false ]; then
        set_hostname
        install_docker
        setup_ssh
        setup_tailscale
    else
        log "Local mode: skipping hostname, Docker, SSH, and Tailscale setup"
        # But we still need to check that Tailscale is available
        check_tailscale_local_mode
    fi
    
    # Install K3s (server or agent based on parameters)
    if [ -n "$K3S_TOKEN" ] && [ -n "$K3S_URL" ]; then
        install_k3s_agent
    else
        install_k3s_server
    fi
    
    echo ""
    log "=============================================="
    log "Setup completed successfully!"
    log "=============================================="
    log "You may need to reboot for all changes to take effect."
    log "Docker group membership requires logout/login or reboot to take effect."
    echo ""
}

# Run main function with all arguments
main "$@"
