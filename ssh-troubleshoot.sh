#!/bin/bash

# SSH Setup Troubleshooter for K3s on Phone
# This script diagnoses and fixes common SSH setup issues

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

# Function to detect SSH service name
detect_ssh_service_name() {
    local ssh_service=""
    
    # Check for available SSH service names
    if systemctl list-unit-files 2>/dev/null | grep -q "^ssh\.service"; then
        ssh_service="ssh"
    elif systemctl list-unit-files 2>/dev/null | grep -q "^sshd\.service"; then
        ssh_service="sshd"
    else
        # Try to detect by checking if service files exist
        if [ -f "/lib/systemd/system/ssh.service" ] || [ -f "/etc/systemd/system/ssh.service" ]; then
            ssh_service="ssh"
        elif [ -f "/lib/systemd/system/sshd.service" ] || [ -f "/etc/systemd/system/sshd.service" ]; then
            ssh_service="sshd"
        fi
    fi
    
    echo "$ssh_service"
}

# Function to check SSH installation
check_ssh_installation() {
    log_step "Checking SSH installation..."
    
    # Check if OpenSSH server is installed
    if dpkg -l | grep -q openssh-server; then
        log "✓ OpenSSH server is installed"
    else
        log_error "✗ OpenSSH server is not installed"
        echo "  Install with: sudo apt-get install -y openssh-server"
        return 1
    fi
    
    # Check SSH configuration file
    if [ -f "/etc/ssh/sshd_config" ]; then
        log "✓ SSH configuration file exists"
    else
        log_error "✗ SSH configuration file missing"
        return 1
    fi
    
    return 0
}

# Function to check SSH service status
check_ssh_service_status() {
    log_step "Checking SSH service status..."
    
    local ssh_service
    ssh_service=$(detect_ssh_service_name)
    
    if [ -z "$ssh_service" ]; then
        log_error "✗ Could not detect SSH service name"
        echo "  This usually means OpenSSH server is not properly installed"
        return 1
    fi
    
    log "✓ Detected SSH service name: $ssh_service"
    
    # Check if service is active
    if systemctl is-active "$ssh_service" &>/dev/null; then
        log "✓ SSH service is running"
    else
        log_error "✗ SSH service is not running"
        echo "  Start with: sudo systemctl start $ssh_service"
    fi
    
    # Check if service is enabled
    if systemctl is-enabled "$ssh_service" &>/dev/null; then
        log "✓ SSH service is enabled (will start on boot)"
    else
        log_warn "⚠ SSH service is not enabled"
        echo "  Enable with: sudo systemctl enable $ssh_service"
    fi
    
    # Show service status
    echo ""
    echo "=== SSH Service Status ==="
    systemctl status "$ssh_service" --no-pager || true
    
    return 0
}

# Function to check SSH configuration
check_ssh_configuration() {
    log_step "Checking SSH configuration..."
    
    if [ ! -f "/etc/ssh/sshd_config" ]; then
        log_error "SSH configuration file not found"
        return 1
    fi
    
    # Check PermitRootLogin setting
    local root_login_setting
    root_login_setting=$(grep "^PermitRootLogin" /etc/ssh/sshd_config | head -1 | awk '{print $2}' || echo "not_set")
    
    case "$root_login_setting" in
        "yes")
            log "✓ Root login is enabled"
            ;;
        "no")
            log_warn "⚠ Root login is disabled"
            echo "  Enable with: sudo sed -i 's/^PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config"
            ;;
        "not_set")
            log_warn "⚠ Root login setting not configured"
            echo "  Add setting: echo 'PermitRootLogin yes' | sudo tee -a /etc/ssh/sshd_config"
            ;;
        *)
            log_warn "⚠ Root login setting: $root_login_setting"
            ;;
    esac
    
    # Check if port 22 is configured
    local ssh_port
    ssh_port=$(grep "^Port" /etc/ssh/sshd_config | head -1 | awk '{print $2}' || echo "22")
    log "SSH port configured: $ssh_port"
    
    return 0
}

# Function to check network connectivity
check_ssh_network() {
    log_step "Checking SSH network connectivity..."
    
    # Check if SSH port is listening
    if ss -tlnp | grep -q ":22 "; then
        log "✓ SSH is listening on port 22"
    else
        log_error "✗ SSH is not listening on port 22"
        echo "  Check if SSH service is running"
    fi
    
    # Check firewall status
    if command -v ufw &>/dev/null; then
        local ufw_status
        ufw_status=$(sudo ufw status | head -1)
        log "UFW firewall status: $ufw_status"
        
        if echo "$ufw_status" | grep -q "active"; then
            if sudo ufw status | grep -q "22/tcp"; then
                log "✓ SSH port 22 is allowed in UFW"
            else
                log_warn "⚠ SSH port 22 may be blocked by UFW"
                echo "  Allow SSH: sudo ufw allow ssh"
            fi
        fi
    fi
    
    return 0
}

# Function to fix SSH issues
fix_ssh_issues() {
    log_step "Attempting to fix SSH issues..."
    
    local ssh_service
    ssh_service=$(detect_ssh_service_name)
    
    if [ -z "$ssh_service" ]; then
        log_error "Cannot fix SSH - service name not detected"
        echo "  Try reinstalling: sudo apt-get install --reinstall openssh-server"
        return 1
    fi
    
    log "Using SSH service name: $ssh_service"
    
    # Try to restart the service
    log "Restarting SSH service..."
    if sudo systemctl restart "$ssh_service"; then
        log "✓ SSH service restarted successfully"
    else
        log_error "✗ Failed to restart SSH service"
        echo "  Check logs: sudo journalctl -u $ssh_service"
        return 1
    fi
    
    # Try to enable the service
    log "Enabling SSH service..."
    if sudo systemctl enable "$ssh_service" 2>/dev/null; then
        log "✓ SSH service enabled successfully"
    else
        log_warn "⚠ Failed to enable SSH service"
        echo "  Try manually: sudo systemctl enable $ssh_service"
    fi
    
    return 0
}

# Function to show SSH troubleshooting info
show_troubleshooting_info() {
    echo ""
    echo "=== SSH Troubleshooting Guide ==="
    echo ""
    echo "Common SSH issues and solutions:"
    echo ""
    echo "1. 'Refusing to operate on alias name' error:"
    echo "   - This happens when SSH service has multiple names"
    echo "   - Solution: Use correct service name (ssh vs sshd)"
    echo "   - Fix: This script automatically detects the correct name"
    echo ""
    echo "2. SSH service not starting:"
    echo "   - Check configuration: sudo sshd -T"
    echo "   - Check logs: sudo journalctl -u ssh (or sshd)"
    echo "   - Reinstall: sudo apt-get install --reinstall openssh-server"
    echo ""
    echo "3. Connection refused:"
    echo "   - Check if service is running: systemctl status ssh"
    echo "   - Check firewall: sudo ufw status"
    echo "   - Check network: ss -tlnp | grep :22"
    echo ""
    echo "4. Permission denied:"
    echo "   - Check PermitRootLogin in /etc/ssh/sshd_config"
    echo "   - Check user authentication settings"
    echo "   - Verify passwords are set correctly"
    echo ""
    echo "Manual commands:"
    echo "  sudo systemctl restart ssh     # Restart SSH (Debian/Ubuntu)"
    echo "  sudo systemctl restart sshd    # Restart SSH (RHEL/CentOS)"
    echo "  sudo systemctl enable ssh      # Enable on boot"
    echo "  sudo systemctl status ssh      # Check status"
    echo "  sudo journalctl -u ssh -f      # View live logs"
    echo ""
}

# Main execution
echo -e "${BLUE}[SSH TROUBLESHOOTER]${NC} Diagnosing SSH setup issues..."
echo ""

# Run checks
check_ssh_installation
echo ""
check_ssh_service_status  
echo ""
check_ssh_configuration
echo ""
check_ssh_network

echo ""
echo "Would you like to:"
echo "1. Try to fix SSH issues automatically"
echo "2. Show troubleshooting guide"
echo "3. Exit"
echo ""
read -p "Choose an option (1-3): " -n 1 -r
echo ""

case $REPLY in
    1)
        echo ""
        fix_ssh_issues
        ;;
    2)
        show_troubleshooting_info
        ;;
    3)
        echo "Exiting..."
        exit 0
        ;;
    *)
        echo "Invalid option"
        exit 1
        ;;
esac

echo ""
log "SSH troubleshooting complete!"
echo ""
echo "If you still have issues:"
echo "• Check SSH logs: sudo journalctl -u ssh"
echo "• Test SSH config: sudo sshd -T"
echo "• Reinstall SSH: sudo apt-get install --reinstall openssh-server"
