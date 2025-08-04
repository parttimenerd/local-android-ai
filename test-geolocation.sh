#!/bin/bash

# Test Geolocation Monitoring Script
# Tests the phone app API and demonstrates geolocation label management

set -e

# Configuration
PHONE_API_URL="http://localhost:8005"
GEOLOCATION_ENDPOINT="$PHONE_API_URL/location"
NODE_NAME=$(hostname)
LABEL_PREFIX="phone.location"

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

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# Help function
show_help() {
    cat << EOF
Geolocation Monitoring Test Script

Tests the phone app geolocation API and demonstrates node label management.

USAGE:
    ./test-geolocation.sh [COMMAND]

COMMANDS:
    test-api                Test phone app API connectivity
    test-labels             Test node label operations
    show-current            Show current node geolocation labels
    show-service            Show geolocation service status
    monitor                 Run one-time location check and update
    simulate LAT LON [ALT]  Simulate location update with given coordinates
    clear                   Clear all geolocation labels from node
    help                    Show this help message

EXAMPLES:
    # Test if phone app API is working
    ./test-geolocation.sh test-api

    # Show current location labels
    ./test-geolocation.sh show-current

    # Run a single location check
    ./test-geolocation.sh monitor

    # Simulate being at specific coordinates
    ./test-geolocation.sh simulate 51.5074 -0.1278 100

    # Clear all location labels
    ./test-geolocation.sh clear

EOF
}

# Function to test phone app API
test_api() {
    log_step "Testing phone app API connectivity..."
    
    log "Attempting to connect to: $GEOLOCATION_ENDPOINT"
    
    local response
    if response=$(curl -s --connect-timeout 5 --max-time 10 "$GEOLOCATION_ENDPOINT" 2>/dev/null); then
        if [ -n "$response" ]; then
            log "✅ Phone app API is responding"
            echo "Response: $response"
            
            # Try to parse coordinates
            local latitude longitude
            latitude=$(echo "$response" | grep -o '"latitude"[[:space:]]*:[[:space:]]*[^,}]*' | sed 's/.*:[[:space:]]*//' | tr -d '"')
            longitude=$(echo "$response" | grep -o '"longitude"[[:space:]]*:[[:space:]]*[^,}]*' | sed 's/.*:[[:space:]]*//' | tr -d '"')
            
            if [[ "$latitude" =~ ^-?[0-9]+\.?[0-9]*$ ]] && [[ "$longitude" =~ ^-?[0-9]+\.?[0-9]*$ ]]; then
                log "✅ Valid coordinates found: $latitude, $longitude"
            else
                log_warn "⚠️ Response received but coordinates not valid"
            fi
        else
            log_warn "⚠️ Empty response from phone app API"
        fi
    else
        log_error "❌ Cannot connect to phone app API"
        log_error "Make sure:"
        log_error "  1. Android phone app is running"
        log_error "  2. Port 8005 is accessible"
        log_error "  3. Location permissions are granted"
    fi
}

# Function to test label operations
test_labels() {
    log_step "Testing node label operations..."
    
    local test_lat="51.5074"
    local test_lon="-0.1278"
    local test_alt="100"
    
    log "Testing label setting with coordinates: $test_lat, $test_lon, ${test_alt}m"
    
    # Set test labels
    if kubectl label node "$NODE_NAME" "$LABEL_PREFIX/latitude=$test_lat" --overwrite >/dev/null 2>&1 && \
       kubectl label node "$NODE_NAME" "$LABEL_PREFIX/longitude=$test_lon" --overwrite >/dev/null 2>&1 && \
       kubectl label node "$NODE_NAME" "$LABEL_PREFIX/altitude=$test_alt" --overwrite >/dev/null 2>&1; then
        log "✅ Successfully set test labels"
        
        # Read back labels
        local read_lat read_lon read_alt
        read_lat=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/latitude']}" 2>/dev/null || echo "")
        read_lon=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/longitude']}" 2>/dev/null || echo "")
        read_alt=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/altitude']}" 2>/dev/null || echo "")
        
        if [ "$read_lat" = "$test_lat" ] && [ "$read_lon" = "$test_lon" ] && [ "$read_alt" = "$test_alt" ]; then
            log "✅ Labels correctly read back: $read_lat, $read_lon, ${read_alt}m"
        else
            log_warn "⚠️ Label readback mismatch: expected $test_lat,$test_lon,$test_alt, got $read_lat,$read_lon,$read_alt"
        fi
        
        # Clean up test labels
        kubectl label node "$NODE_NAME" "$LABEL_PREFIX/latitude-" >/dev/null 2>&1 || true
        kubectl label node "$NODE_NAME" "$LABEL_PREFIX/longitude-" >/dev/null 2>&1 || true
        kubectl label node "$NODE_NAME" "$LABEL_PREFIX/altitude-" >/dev/null 2>&1 || true
        log "Cleaned up test labels"
        
    else
        log_error "❌ Failed to set node labels"
        log_error "Make sure kubectl is configured and you have permissions"
    fi
}

# Function to show current labels
show_current() {
    log_step "Current geolocation labels for node: $NODE_NAME"
    
    local latitude longitude altitude updated
    latitude=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/latitude']}" 2>/dev/null || echo "")
    longitude=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/longitude']}" 2>/dev/null || echo "")
    altitude=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/altitude']}" 2>/dev/null || echo "")
    updated=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/updated']}" 2>/dev/null || echo "")
    
    if [ -n "$latitude" ] && [ -n "$longitude" ]; then
        log "Latitude: $latitude"
        log "Longitude: $longitude"
        if [ -n "$altitude" ]; then
            log "Altitude: $altitude meters"
        fi
        if [ -n "$updated" ]; then
            log "Last Updated: $updated"
        fi
        
        # Show approximate location (if we have internet)
        if command -v curl >/dev/null 2>&1; then
            log "Approximate location lookup..."
            local location_info
            location_info=$(curl -s "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$latitude&longitude=$longitude&localityLanguage=en" 2>/dev/null | grep -o '"city":"[^"]*"' | cut -d'"' -f4 2>/dev/null || echo "")
            if [ -n "$location_info" ]; then
                log "Nearest city: $location_info"
            fi
        fi
    else
        log_warn "No geolocation labels found on this node"
    fi
}

# Function to show service status
show_service() {
    log_step "Geolocation monitoring service status..."
    
    if command -v systemctl >/dev/null 2>&1; then
        if systemctl is-active --quiet k3s-geolocation-monitor.service 2>/dev/null; then
            log "✅ Service is running"
            
            # Show recent logs
            log "Recent log entries:"
            journalctl -u k3s-geolocation-monitor --since "5 minutes ago" --no-pager -n 5 2>/dev/null || log_warn "Cannot access service logs"
        elif systemctl is-enabled --quiet k3s-geolocation-monitor.service 2>/dev/null; then
            log_warn "⚠️ Service is enabled but not running"
        else
            log_error "❌ Service is not installed or enabled"
        fi
    else
        log_warn "systemctl not available - cannot check service status"
    fi
}

# Function to run one-time monitoring
monitor() {
    log_step "Running one-time geolocation check..."
    
    # Get current location from phone
    local new_location
    local response
    if response=$(curl -s --connect-timeout 5 --max-time 10 "$GEOLOCATION_ENDPOINT" 2>/dev/null); then
        if [ -n "$response" ]; then
            log "Phone app response: $response"
            
            # Parse coordinates
            local latitude longitude altitude
            latitude=$(echo "$response" | grep -o '"latitude"[[:space:]]*:[[:space:]]*[^,}]*' | sed 's/.*:[[:space:]]*//' | tr -d '"')
            longitude=$(echo "$response" | grep -o '"longitude"[[:space:]]*:[[:space:]]*[^,}]*' | sed 's/.*:[[:space:]]*//' | tr -d '"')
            altitude=$(echo "$response" | grep -o '"altitude"[[:space:]]*:[[:space:]]*[^,}]*' | sed 's/.*:[[:space:]]*//' | tr -d '"')
            
            if [[ "$latitude" =~ ^-?[0-9]+\.?[0-9]*$ ]] && [[ "$longitude" =~ ^-?[0-9]+\.?[0-9]*$ ]]; then
                # Use altitude if available, otherwise use 0
                if [[ "$altitude" =~ ^-?[0-9]+\.?[0-9]*$ ]]; then
                    new_location="$latitude,$longitude,$altitude"
                    log "Parsed location: $latitude, $longitude, ${altitude}m"
                else
                    new_location="$latitude,$longitude,0"
                    log "Parsed location: $latitude, $longitude (no altitude)"
                fi
                
                # Get current labels
                local current_lat current_lon current_alt
                current_lat=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/latitude']}" 2>/dev/null || echo "")
                current_lon=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/longitude']}" 2>/dev/null || echo "")
                current_alt=$(kubectl get node "$NODE_NAME" -o jsonpath="{.metadata.labels['$LABEL_PREFIX/altitude']}" 2>/dev/null || echo "")
                
                local current_location=""
                if [ -n "$current_lat" ] && [ -n "$current_lon" ]; then
                    if [ -n "$current_alt" ]; then
                        current_location="$current_lat,$current_lon,$current_alt"
                        log "Current labels: $current_lat, $current_lon, ${current_alt}m"
                    else
                        current_location="$current_lat,$current_lon,0"
                        log "Current labels: $current_lat, $current_lon (no altitude)"
                    fi
                fi
                
                # Update labels
                local lat_part lon_part alt_part
                lat_part=$(echo "$new_location" | cut -d',' -f1)
                lon_part=$(echo "$new_location" | cut -d',' -f2)
                alt_part=$(echo "$new_location" | cut -d',' -f3)
                
                if kubectl label node "$NODE_NAME" "$LABEL_PREFIX/latitude=$lat_part" --overwrite >/dev/null 2>&1 && \
                   kubectl label node "$NODE_NAME" "$LABEL_PREFIX/longitude=$lon_part" --overwrite >/dev/null 2>&1 && \
                   kubectl label node "$NODE_NAME" "$LABEL_PREFIX/altitude=$alt_part" --overwrite >/dev/null 2>&1; then
                    
                    local timestamp
                    timestamp=$(date '+%Y-%m-%dT%H:%M:%SZ')
                    kubectl label node "$NODE_NAME" "$LABEL_PREFIX/updated=$timestamp" --overwrite >/dev/null 2>&1
                    
                    log "✅ Updated node labels successfully"
                    if [[ "$altitude" =~ ^-?[0-9]+\.?[0-9]*$ ]]; then
                        log "New coordinates: $lat_part, $lon_part, ${alt_part}m"
                    else
                        log "New coordinates: $lat_part, $lon_part (no altitude)"
                    fi
                else
                    log_error "❌ Failed to update node labels"
                fi
            else
                log_error "❌ Invalid coordinates in response"
            fi
        else
            log_error "❌ Empty response from phone app"
        fi
    else
        log_error "❌ Cannot connect to phone app API"
    fi
}

# Function to simulate location
simulate() {
    local lat="$1"
    local lon="$2"
    local alt="${3:-0}"  # Default altitude to 0 if not provided
    
    if [ -z "$lat" ] || [ -z "$lon" ]; then
        log_error "Usage: simulate LATITUDE LONGITUDE [ALTITUDE]"
        return 1
    fi
    
    log_step "Simulating location update..."
    log "Setting coordinates: $lat, $lon, ${alt}m"
    
    if kubectl label node "$NODE_NAME" "$LABEL_PREFIX/latitude=$lat" --overwrite >/dev/null 2>&1 && \
       kubectl label node "$NODE_NAME" "$LABEL_PREFIX/longitude=$lon" --overwrite >/dev/null 2>&1 && \
       kubectl label node "$NODE_NAME" "$LABEL_PREFIX/altitude=$alt" --overwrite >/dev/null 2>&1; then
        
        local timestamp
        timestamp=$(date '+%Y-%m-%dT%H:%M:%SZ')
        kubectl label node "$NODE_NAME" "$LABEL_PREFIX/updated=$timestamp" --overwrite >/dev/null 2>&1
        
        log "✅ Simulated location set successfully"
        show_current
    else
        log_error "❌ Failed to set simulated location"
    fi
}

# Function to clear all location labels
clear_labels() {
    log_step "Clearing all geolocation labels..."
    
    kubectl label node "$NODE_NAME" "$LABEL_PREFIX/latitude-" >/dev/null 2>&1 || true
    kubectl label node "$NODE_NAME" "$LABEL_PREFIX/longitude-" >/dev/null 2>&1 || true
    kubectl label node "$NODE_NAME" "$LABEL_PREFIX/altitude-" >/dev/null 2>&1 || true
    kubectl label node "$NODE_NAME" "$LABEL_PREFIX/updated-" >/dev/null 2>&1 || true
    
    log "✅ Cleared all geolocation labels"
}

# Main execution
case "${1:-help}" in
    test-api)
        test_api
        ;;
    test-labels)
        test_labels
        ;;
    show-current)
        show_current
        ;;
    show-service)
        show_service
        ;;
    monitor)
        monitor
        ;;
    simulate)
        simulate "$2" "$3" "$4"
        ;;
    clear)
        clear_labels
        ;;
    help)
        show_help
        ;;
    *)
        log_error "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac
