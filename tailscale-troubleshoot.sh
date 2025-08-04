#!/bin/bash

# Tailscale Setup Troubleshooter
# This script diagnoses and fixes common Tailscale installation and authentication issues

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log() {
    echo -e "${GREEN}[INFO]${NC} $1"
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

# Function to check network connectivity
check_network() {
    log_step "Checking network connectivity..."
    
    # Check general internet connectivity
    if ping -c 1 8.8.8.8 &>/dev/null; then
        log "✓ Internet connectivity OK"
    else
        log_error "✗ No internet connectivity"
        echo "  Please check your network connection"
        return 1
    fi
    
    # Check DNS resolution
    if nslookup tailscale.com &>/dev/null; then
        log "✓ DNS resolution OK"
    else
        log_warn "✗ DNS resolution issues"
        echo "  Try using different DNS servers (8.8.8.8, 1.1.1.1)"
    fi
    
    # Check Tailscale domain accessibility
    if curl -s --connect-timeout 5 https://tailscale.com/install.sh | head -1 | grep -q "#!/"; then
        log "✓ Tailscale install script accessible"
    else
        log_error "✗ Cannot access Tailscale install script"
        echo "  This could be a firewall or proxy issue"
        return 1
    fi
}

# Function to check system requirements
check_system_requirements() {
    log_step "Checking system requirements..."
    
    # Check if running as root or with sudo access
    if [ "$EUID" -eq 0 ]; then
        log "✓ Running as root"
    elif sudo -n true 2>/dev/null; then
        log "✓ Sudo access available"
    else
        log_error "✗ No root or sudo access"
        echo "  Tailscale requires root/sudo privileges"
        return 1
    fi
    
    # Check OS support
    if [ -f /etc/os-release ]; then
        source /etc/os-release
        log "✓ OS: $PRETTY_NAME"
        
        # Check for supported distributions
        case "$ID" in
            ubuntu|debian|fedora|centos|rhel|opensuse*)
                log "✓ Supported OS distribution"
                ;;
            *)
                log_warn "⚠ Unusual OS distribution: $ID"
                echo "  Tailscale may not officially support this distribution"
                ;;
        esac
    else
        log_warn "⚠ Cannot determine OS version"
    fi
    
    # Check architecture
    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64|amd64)
            log "✓ Architecture: $ARCH (supported)"
            ;;
        aarch64|arm64)
            log "✓ Architecture: $ARCH (ARM64 - supported)"
            ;;
        armv7l|armhf)
            log "✓ Architecture: $ARCH (ARM32 - supported)"
            ;;
        *)
            log_warn "⚠ Unusual architecture: $ARCH"
            echo "  Please check Tailscale's architecture support"
            ;;
    esac
}

# Function to install Tailscale with better error handling
install_tailscale_enhanced() {
    log_step "Installing Tailscale with enhanced error handling..."
    
    if command -v tailscale &>/dev/null; then
        log "✓ Tailscale already installed"
        tailscale version
        return 0
    fi
    
    log "Downloading and installing Tailscale..."
    
    # Try the official install script first
    if curl -fsSL https://tailscale.com/install.sh | sudo sh; then
        log "✓ Tailscale installed successfully via official installer"
        return 0
    else
        log_warn "Official installer failed, trying package manager..."
    fi
    
    # Fallback to package manager
    if command -v apt-get &>/dev/null; then
        log "Trying APT package manager..."
        curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/jammy.noarmor.gpg | sudo tee /usr/share/keyrings/tailscale-archive-keyring.gpg >/dev/null
        curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/jammy.tailscale-keyring.list | sudo tee /etc/apt/sources.list.d/tailscale.list
        sudo apt-get update
        sudo apt-get install -y tailscale
    elif command -v yum &>/dev/null; then
        log "Trying YUM package manager..."
        sudo yum install -y yum-utils
        sudo yum-config-manager --add-repo https://pkgs.tailscale.com/stable/centos/7/tailscale.repo
        sudo yum install -y tailscale
    elif command -v zypper &>/dev/null; then
        log "Trying Zypper package manager..."
        sudo zypper addrepo https://pkgs.tailscale.com/stable/opensuse/tumbleweed/tailscale.repo
        sudo zypper refresh
        sudo zypper install -y tailscale
    else
        log_error "No supported package manager found"
        echo ""
        echo "Manual installation options:"
        echo "1. Download binary directly from https://tailscale.com/download"
        echo "2. Use your distribution's package manager"
        echo "3. Build from source: https://github.com/tailscale/tailscale"
        return 1
    fi
    
    # Verify installation
    if command -v tailscale &>/dev/null; then
        log "✓ Tailscale installed successfully"
        tailscale version
    else
        log_error "Installation completed but tailscale command not found"
        return 1
    fi
}

# Function to start Tailscale daemon
start_tailscale_daemon() {
    log_step "Starting Tailscale daemon..."
    
    # Check if systemd is available
    if command -v systemctl &>/dev/null; then
        log "Using systemctl to manage Tailscale daemon..."
        
        # Enable and start the service
        if sudo systemctl enable tailscaled; then
            log "✓ Tailscale daemon enabled"
        else
            log_warn "Failed to enable Tailscale daemon"
        fi
        
        if sudo systemctl start tailscaled; then
            log "✓ Tailscale daemon started"
        else
            log_error "Failed to start Tailscale daemon"
            echo "Check daemon status with: sudo systemctl status tailscaled"
            echo "Check logs with: sudo journalctl -u tailscaled"
            return 1
        fi
        
        # Wait a moment for daemon to initialize
        sleep 2
        
        # Check daemon status
        if sudo systemctl is-active tailscaled &>/dev/null; then
            log "✓ Tailscale daemon is running"
        else
            log_error "Tailscale daemon failed to start properly"
            sudo systemctl status tailscaled
            return 1
        fi
    else
        log_warn "systemctl not available, trying to start daemon manually..."
        if sudo tailscaled --state=/var/lib/tailscale/tailscaled.state & then
            log "✓ Tailscale daemon started manually"
            sleep 2
        else
            log_error "Failed to start Tailscale daemon manually"
            return 1
        fi
    fi
}

# Function to authenticate with Tailscale
authenticate_tailscale() {
    log_step "Authenticating with Tailscale..."
    
    # Check if already authenticated
    if sudo tailscale status --json &>/dev/null; then
        log "✓ Already authenticated with Tailscale"
        sudo tailscale status
        return 0
    fi
    
    echo ""
    echo "Tailscale authentication options:"
    echo "1. Use an auth key (automated)"
    echo "2. Interactive authentication (manual)"
    echo ""
    read -p "Do you have a Tailscale auth key? (y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo ""
        echo "Get an auth key from: https://login.tailscale.com/admin/settings/keys"
        echo ""
        read -p "Enter your Tailscale auth key: " -s AUTH_KEY
        echo ""
        
        if [ -n "$AUTH_KEY" ]; then
            read -p "Enter hostname for this device (optional): " HOSTNAME
            
            if [ -n "$HOSTNAME" ]; then
                log "Authenticating with auth key and hostname..."
                sudo tailscale up --auth-key="$AUTH_KEY" --hostname="$HOSTNAME"
            else
                log "Authenticating with auth key..."
                sudo tailscale up --auth-key="$AUTH_KEY"
            fi
        else
            log_error "No auth key provided"
            return 1
        fi
    else
        log "Starting interactive authentication..."
        echo ""
        echo "This will open a browser or show you a URL to visit for authentication."
        read -p "Press Enter to continue..."
        
        sudo tailscale up
    fi
    
    # Verify authentication
    if sudo tailscale status --json &>/dev/null; then
        log "✓ Successfully authenticated with Tailscale"
        echo ""
        sudo tailscale status
    else
        log_error "Authentication failed"
        return 1
    fi
}

# Function to run diagnostics
run_diagnostics() {
    log_step "Running Tailscale diagnostics..."
    
    echo ""
    echo "=== Tailscale Status ==="
    if command -v tailscale &>/dev/null; then
        if sudo tailscale status &>/dev/null; then
            sudo tailscale status
        else
            echo "Tailscale not authenticated or daemon not running"
        fi
    else
        echo "Tailscale not installed"
    fi
    
    echo ""
    echo "=== Tailscale Version ==="
    if command -v tailscale &>/dev/null; then
        tailscale version
    else
        echo "Tailscale not installed"
    fi
    
    echo ""
    echo "=== Daemon Status ==="
    if command -v systemctl &>/dev/null; then
        sudo systemctl status tailscaled --no-pager || true
    else
        echo "systemctl not available"
    fi
    
    echo ""
    echo "=== Network Information ==="
    echo "IP addresses:"
    ip addr show | grep -E "inet " | head -5
    
    echo ""
    echo "Routes:"
    ip route | head -5
    
    echo ""
    echo "=== Firewall Status ==="
    if command -v ufw &>/dev/null; then
        sudo ufw status
    elif command -v firewalld &>/dev/null; then
        sudo firewall-cmd --state 2>/dev/null || true
    elif command -v iptables &>/dev/null; then
        echo "iptables rules count: $(sudo iptables -L | wc -l)"
    else
        echo "No common firewall detected"
    fi
}

# Main execution
echo -e "${BLUE}[TAILSCALE TROUBLESHOOTER]${NC} Diagnosing Tailscale setup issues..."
echo ""

# Run checks
if ! check_network; then
    echo ""
    echo "❌ Network connectivity issues detected. Please fix network problems first."
    exit 1
fi

echo ""
if ! check_system_requirements; then
    echo ""
    echo "❌ System requirements not met. Please address the issues above."
    exit 1
fi

echo ""
echo "Would you like to:"
echo "1. Install/reinstall Tailscale"
echo "2. Start Tailscale daemon"
echo "3. Authenticate with Tailscale" 
echo "4. Run diagnostics only"
echo "5. Full setup (install + start + authenticate)"
echo ""
read -p "Choose an option (1-5): " -n 1 -r
echo ""

case $REPLY in
    1)
        install_tailscale_enhanced
        ;;
    2)
        start_tailscale_daemon
        ;;
    3)
        authenticate_tailscale
        ;;
    4)
        run_diagnostics
        ;;
    5)
        install_tailscale_enhanced && \
        start_tailscale_daemon && \
        authenticate_tailscale
        ;;
    *)
        echo "Invalid option"
        exit 1
        ;;
esac

echo ""
echo "=== Final Status ==="
run_diagnostics

echo ""
log "Troubleshooting complete!"
echo ""
echo "If you still have issues:"
echo "• Check Tailscale documentation: https://tailscale.com/kb/"
echo "• Contact Tailscale support: https://tailscale.com/contact/"
echo "• Check your network/firewall settings"
echo "• Verify your auth key hasn't expired"
