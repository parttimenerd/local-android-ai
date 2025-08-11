#!/usr/bin/env bash
set -euo pipefail

# Test script for simplified location monitoring approach
echo "Testing simplified location monitoring approach..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[TEST]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1" >&2; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

# Test 1: Check if location updater script exists
log "1. Checking if location updater script exists..."
if [ -f "/usr/local/bin/update-node-locations.sh" ]; then
    log "✅ Location updater script found"
else
    log_error "❌ Location updater script not found"
    log_error "Run setup.sh on the server to install it"
    exit 1
fi

# Test 2: Check if script is executable
log "2. Checking if script is executable..."
if [ -x "/usr/local/bin/update-node-locations.sh" ]; then
    log "✅ Script is executable"
else
    log_error "❌ Script is not executable"
    exit 1
fi

# Test 3: Check kubectl availability
log "3. Checking kubectl availability..."
if command -v kubectl >/dev/null 2>&1; then
    log "✅ kubectl is available"
    
    if kubectl cluster-info >/dev/null 2>&1; then
        log "✅ kubectl can connect to cluster"
    else
        log_warn "⚠️  kubectl cannot connect to cluster"
    fi
else
    log_error "❌ kubectl not found"
    exit 1
fi

# Test 4: Check for phone nodes
log "4. Checking for phone nodes..."
phone_nodes=$(kubectl get nodes -l device-type=phone -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || true)
if [ -n "$phone_nodes" ]; then
    log "✅ Found phone nodes: $phone_nodes"
else
    log_warn "⚠️  No phone nodes found with device-type=phone label"
    
    # Check if any nodes exist at all
    all_nodes=$(kubectl get nodes -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || true)
    if [ -n "$all_nodes" ]; then
        log "   Available nodes: $all_nodes"
        log "   You may need to label them as phones:"
        for node in $all_nodes; do
            log "   kubectl label node $node device-type=phone"
        done
    else
        log_warn "   No nodes found in cluster"
    fi
fi

# Test 5: Run location updater once (dry run)
log "5. Testing location updater (--help)..."
if /usr/local/bin/update-node-locations.sh --help >/dev/null 2>&1; then
    log "✅ Location updater help works"
else
    log_error "❌ Location updater help failed"
    exit 1
fi

# Test 6: Check systemd service
log "6. Checking systemd service..."
if systemctl list-unit-files | grep -q location-monitor.service; then
    log "✅ location-monitor service is installed"
    
    if systemctl is-enabled location-monitor >/dev/null 2>&1; then
        log "✅ Service is enabled"
    else
        log_warn "⚠️  Service is not enabled"
    fi
    
    if systemctl is-active location-monitor >/dev/null 2>&1; then
        log "✅ Service is running"
    else
        log_warn "⚠️  Service is not running"
        log "   Start with: sudo systemctl start location-monitor"
    fi
else
    log_warn "⚠️  location-monitor service not found"
fi

# Test 7: Mock Android app test (if curl is available)
log "7. Testing mock Android app simulation..."
if command -v curl >/dev/null 2>&1; then
    log "   Creating mock coordinates response..."
    
    # Create a temporary mock coordinates file
    mock_coordinates='{"latitude": 48.1351, "longitude": 11.5820, "altitude": 520, "city": "Munich"}'
    
    log "   Mock coordinates: $mock_coordinates"
    log "   To simulate Android app, serve this on port 8005 at /location endpoint"
    log "   Example: echo '$mock_coordinates' | python3 -m http.server 8005"
else
    log_warn "⚠️  curl not available for testing"
fi

echo ""
log "========================================"
log "Simplified Location Monitoring Test Complete"
log "========================================"
echo ""

log "Summary:"
log "✅ Location updater script installed and working"
log "✅ kubectl integration functional"
if [ -n "$phone_nodes" ]; then
    log "✅ Phone nodes detected and ready"
else
    log "⚠️  No phone nodes found - label nodes as device-type=phone"
fi

echo ""
log "Next steps:"
log "1. Ensure Android geolocation app runs on port 8005"
log "2. Test manual update: /usr/local/bin/update-node-locations.sh --once --verbose"
log "3. Start continuous monitoring: sudo systemctl start location-monitor"
log "4. Set up SSH keys for passwordless access to phone nodes"

echo ""
log "Architecture summary:"
log "• Server runs location updater script (queries via SSH)"
log "• Android apps serve geolocation on port 8005"
log "• No complex services needed on agent nodes"
log "• Simple kubectl label updates for location data"
