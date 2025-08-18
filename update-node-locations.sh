#!/usr/bin/env bash
set -euo pipefail

# Simple Node Location Updater
# Queries geolocation from Android apps via direct HTTP API and updates node labels
#
# Usage: ./update-node-locations.sh [--interval SECONDS] [--once]

SCRIPT_VERSION="1.0.0"
DEFAULT_INTERVAL=30
DEFAULT_GEO_PORT=8005
INTERVAL=${INTERVAL:-$DEFAULT_INTERVAL}
RUN_ONCE=false
VERBOSE=false

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

log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

show_help() {
    cat << EOF
Node Location Updater v${SCRIPT_VERSION}

Queries geolocation from Android phone nodes via direct HTTP API and updates Kubernetes node labels.

USAGE:
    $0 [OPTIONS]

OPTIONS:
    --interval SECONDS      Update interval in seconds (default: $DEFAULT_INTERVAL)
    --once                  Run once and exit (don't loop)
    --port PORT            Android app geolocation port (default: $DEFAULT_GEO_PORT)
    --verbose              Enable verbose logging
    --help                 Show this help

EXAMPLES:
    $0                     # Run with default 30s interval
    $0 --interval 60       # Run with 60s interval
    $0 --once             # Run once and exit
    $0 --verbose --once   # Run once with verbose output

REQUIREMENTS:
    - kubectl must be available and configured
    - Direct network access to phone nodes on port $DEFAULT_GEO_PORT
    - Android geolocation app running on port $DEFAULT_GEO_PORT

EOF
}

# Parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --interval)
                INTERVAL="$2"
                shift 2
                ;;
            --once)
                RUN_ONCE=true
                shift
                ;;
            --port)
                DEFAULT_GEO_PORT="$2"
                shift 2
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --help)
                show_help
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done
}

# Check if kubectl is available and working
check_kubectl() {
    if ! command -v kubectl >/dev/null 2>&1; then
        log_error "kubectl not found. Please install kubectl and configure access to the cluster."
        exit 1
    fi
    
    if ! kubectl cluster-info >/dev/null 2>&1; then
        log_error "kubectl cannot connect to cluster. Please check your kubeconfig."
        exit 1
    fi
    
    log_verbose "kubectl is available and cluster is accessible"
}

# Get list of phone nodes
get_phone_nodes() {
    # Get all nodes labeled with device-type=phone
    local nodes
    nodes=$(kubectl get nodes -l device-type=phone -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || true)
    
    if [ -z "$nodes" ]; then
        log_warn "No phone nodes found. Make sure nodes are labeled with device-type=phone during installation"
        log_verbose "Phone nodes should have device-type=phone label applied automatically during K3s agent setup"
        return 1
    fi
    
    echo "$nodes"
}

# Query geolocation from a node via direct HTTP API access
query_node_location() {
    local node="$1"
    local port="$2"
    
    log_verbose "Querying location from node: $node (port: $port)"
    
    # Get the node's IP address from Kubernetes
    local node_ip
    node_ip=$(kubectl get node "$node" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || true)
    
    if [ -z "$node_ip" ]; then
        log_verbose "Could not get IP address for node $node"
        return 1
    fi
    
    log_verbose "Using node IP: $node_ip"
    
    # Try to get location data directly from the Android app via HTTP
    local location_data
    location_data=$(curl -s --connect-timeout 3 --max-time 5 "http://$node_ip:$port/location" 2>/dev/null || true)
    
    if [ -z "$location_data" ]; then
        log_verbose "No location data from $node_ip:$port (app may not be running or not accessible)"
        return 1
    fi
    
    # Parse JSON response (basic parsing without jq dependency)
    local latitude longitude altitude city
    latitude=$(echo "$location_data" | grep -o '"latitude":[^,}]*' | cut -d':' -f2 | tr -d ' "' || true)
    longitude=$(echo "$location_data" | grep -o '"longitude":[^,}]*' | cut -d':' -f2 | tr -d ' "' || true)
    altitude=$(echo "$location_data" | grep -o '"altitude":[^,}]*' | cut -d':' -f2 | tr -d ' "' || true)
    city=$(echo "$location_data" | grep -o '"city":"[^"]*"' | cut -d':' -f2 | tr -d '"' || true)
    
    if [ -z "$latitude" ] || [ -z "$longitude" ]; then
        log_warn "Invalid location data from $node_ip:$port: $location_data"
        return 1
    fi
    
    log_verbose "Retrieved coordinates from $node: lat=$latitude, lng=$longitude, alt=$altitude, city=$city"
    echo "$latitude,$longitude,$altitude,$city"
}

# Update node labels with location data
update_node_labels() {
    local node="$1"
    local location_data="$2"
    
    IFS=',' read -r latitude longitude altitude city <<< "$location_data"
    
    log_verbose "Updating labels for node $node..."
    
    # Build label update command
    local labels=()
    labels+=("phone.location/latitude=$latitude")
    labels+=("phone.location/longitude=$longitude")
    labels+=("phone.location/updated=$(date -u +%Y-%m-%dT%H:%M:%SZ)")
    labels+=("phone.location/status=active")
    
    if [ -n "$altitude" ] && [ "$altitude" != "null" ]; then
        labels+=("phone.location/altitude=$altitude")
    fi
    
    if [ -n "$city" ] && [ "$city" != "null" ]; then
        # Replace spaces and special chars for k8s label compatibility
        local city_clean
        city_clean=$(echo "$city" | sed 's/[^a-zA-Z0-9-]/_/g' | sed 's/__*/_/g' | sed 's/^_\|_$//g')
        labels+=("phone.location/city=$city_clean")
    fi
    
    # Also ensure device-type=phone label is set
    labels+=("device-type=phone")
    labels+=("node-role.kubernetes.io/phone=true")
    
    # Apply all labels at once
    log_verbose "Applying ${#labels[@]} labels to node $node"
    if kubectl label node "$node" "${labels[@]}" --overwrite >/dev/null 2>&1; then
        log "✅ Updated location for $node: lat=$latitude, lng=$longitude"
        if [ -n "$city" ] && [ "$city" != "null" ]; then
            log "   City: $city"
        fi
        return 0
    else
        log_error "Failed to update labels for node $node"
        return 1
    fi
}

# Update locations for all phone nodes
update_all_locations() {
    local nodes
    if ! nodes=$(get_phone_nodes); then
        return 1
    fi
    
    local total_nodes=0
    local success_count=0
    
    for node in $nodes; do
        total_nodes=$((total_nodes + 1))
        
        log_verbose "Processing node: $node"
        
        local location_data
        if location_data=$(query_node_location "$node" "$DEFAULT_GEO_PORT"); then
            if update_node_labels "$node" "$location_data"; then
                success_count=$((success_count + 1))
            fi
        else
            log_warn "Could not retrieve location from $node"
        fi
    done
    
    log "Processed $total_nodes nodes, $success_count successful updates"
    
    if [ $success_count -eq 0 ] && [ $total_nodes -gt 0 ]; then
        log_warn "No successful location updates. Check that:"
        log_warn "  1. SSH access to phone nodes is working"
        log_warn "  2. Android geolocation app is running on port $DEFAULT_GEO_PORT"
        log_warn "  3. App is serving location data at /location endpoint"
        return 1
    fi
    
    return 0
}

# Main function
main() {
    parse_args "$@"
    
    log "Node Location Updater v${SCRIPT_VERSION} starting..."
    log "Update interval: ${INTERVAL}s, Port: $DEFAULT_GEO_PORT, Run once: $RUN_ONCE"
    
    # Check prerequisites
    check_kubectl
    
    if [ "$RUN_ONCE" = true ]; then
        log "Running single location update..."
        update_all_locations
        exit $?
    fi
    
    # Continuous mode
    log "Starting continuous location monitoring (press Ctrl+C to stop)..."
    
    # Trap for graceful shutdown
    trap 'log "Shutting down location updater..."; exit 0' INT TERM
    
    while true; do
        update_all_locations || log_warn "Update cycle failed, continuing..."
        
        log_verbose "Waiting ${INTERVAL}s until next update..."
        sleep "$INTERVAL"
    done
}

# Run main function if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
