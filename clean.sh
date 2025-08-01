#!/bin/bash

# K3s Cluster Clean Script
# This script removes unreachable "phone-..." devices from Tailscale VPN
# and NotReady nodes from the K3s cluster.

set -e

# Script version
VERSION="1.0.0"

# Default values
VERBOSE=false
FORCE=false
TAILSCALE_API_KEY=""
DRY_RUN=false

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
K3s Cluster Clean Script v${VERSION}

Removes unreachable "phone-..." devices from Tailscale VPN and NotReady nodes
from the K3s cluster. Helps maintain a clean cluster by removing dead nodes.

USAGE:
    ./clean.sh [OPTIONS]

OPTIONS:
    -t, --tailscale-key KEY     Tailscale API key for VPN device removal
                                Get one at: https://login.tailscale.com/admin/settings/keys
    -f, --force                 Skip confirmation prompts
    --dry-run                   Show what would be removed without making changes
    -v, --verbose               Enable verbose output
    -h, --help                  Show this help message
    --version                   Show version information

EXAMPLES:
    # Clean NotReady K3s nodes only
    ./clean.sh

    # Clean both K3s nodes and Tailscale devices
    ./clean.sh -t tskey-api-xxxxx

    # Dry run to see what would be cleaned
    ./clean.sh -t tskey-api-xxxxx --dry-run

    # Force clean without confirmation
    ./clean.sh -t tskey-api-xxxxx --force

DESCRIPTION:
    This script will:
    1. Check for NotReady nodes in the K3s cluster
    2. Check for unreachable "phone-..." devices in Tailscale (if API key provided)
    3. Remove NotReady nodes from K3s cluster
    4. Remove unreachable phone devices from Tailscale VPN
    5. Provide summary of cleaned resources

NOTES:
    - This script must be run from the K3s server (master) node
    - Tailscale API key is different from auth key (get from admin settings)
    - Only removes devices with "phone-" prefix from Tailscale
    - Use --dry-run to preview changes before applying

EOF
}

show_version() {
    echo "K3s Cluster Clean Script v${VERSION}"
}

check_server_node() {
    log_step "Verifying this is the K3s server node..."
    
    # Check if kubectl is available
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl not found. This script must be run from the K3s server node."
        exit $EXIT_MISSING_DEPS
    fi
    
    # Check if we can connect to the cluster
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to K3s cluster. Ensure you're running this on the server node."
        exit $EXIT_MISSING_DEPS
    fi
    
    log "✅ Confirmed running on K3s server node"
}

get_not_ready_nodes() {
    log_step "Checking for NotReady nodes in K3s cluster..."
    
    local not_ready_nodes
    not_ready_nodes=$(kubectl get nodes --no-headers | grep "NotReady" | awk '{print $1}' || echo "")
    
    local count
    count=$(echo "$not_ready_nodes" | grep -v '^$' | wc -l || echo "0")
    
    if [ "$count" -gt 0 ]; then
        log "Found $count NotReady nodes:"
        echo "$not_ready_nodes" | while read -r node; do
            if [ -n "$node" ]; then
                log "  - $node"
            fi
        done
    else
        log "No NotReady nodes found in cluster"
    fi
    
    echo "$not_ready_nodes" > /tmp/clean_not_ready_nodes
    echo "$count" > /tmp/clean_not_ready_count
}

get_unreachable_tailscale_devices() {
    if [ -z "$TAILSCALE_API_KEY" ]; then
        log_verbose "No Tailscale API key provided, skipping VPN device check"
        echo "0" > /tmp/clean_tailscale_count
        echo "" > /tmp/clean_tailscale_devices
        return 0
    fi
    
    log_step "Checking for unreachable 'phone-...' devices in Tailscale..."
    
    # Check if curl is available
    if ! command -v curl &> /dev/null; then
        log_error "curl is required for Tailscale API access"
        exit $EXIT_MISSING_DEPS
    fi
    
    # Get devices from Tailscale API
    local api_response
    api_response=$(curl -s -H "Authorization: Bearer $TAILSCALE_API_KEY" \
                        "https://api.tailscale.com/api/v2/tailnet/-/devices" || echo "")
    
    if [ -z "$api_response" ]; then
        log_error "Failed to get device list from Tailscale API"
        log_error "Check your API key and network connectivity"
        exit $EXIT_MISSING_DEPS
    fi
    
    # Check if jq is available for JSON parsing
    if ! command -v jq &> /dev/null; then
        log_warn "jq not available, using basic JSON parsing"
        # Simple grep-based parsing for phone devices
        local phone_devices
        phone_devices=$(echo "$api_response" | grep -o '"name":"phone-[^"]*"' | cut -d'"' -f4 || echo "")
    else
        # Use jq for proper JSON parsing
        local phone_devices
        phone_devices=$(echo "$api_response" | jq -r '.devices[]? | select(.name | startswith("phone-")) | .name' 2>/dev/null || echo "")
    fi
    
    # Check which phone devices are unreachable
    local unreachable_devices=""
    local count=0
    
    if [ -n "$phone_devices" ]; then
        echo "$phone_devices" | while read -r device; do
            if [ -n "$device" ]; then
                log_verbose "Checking connectivity to $device..."
                if ! ping -c 1 -W 2 "$device" &> /dev/null; then
                    echo "$device" >> /tmp/clean_unreachable_devices_tmp
                fi
            fi
        done
        
        if [ -f /tmp/clean_unreachable_devices_tmp ]; then
            unreachable_devices=$(cat /tmp/clean_unreachable_devices_tmp)
            count=$(cat /tmp/clean_unreachable_devices_tmp | wc -l)
            rm -f /tmp/clean_unreachable_devices_tmp
        fi
    fi
    
    if [ "$count" -gt 0 ]; then
        log "Found $count unreachable phone devices:"
        echo "$unreachable_devices" | while read -r device; do
            if [ -n "$device" ]; then
                log "  - $device"
            fi
        done
    else
        log "All phone devices are reachable or no phone devices found"
    fi
    
    echo "$unreachable_devices" > /tmp/clean_tailscale_devices
    echo "$count" > /tmp/clean_tailscale_count
}

confirm_cleanup() {
    local k3s_count
    local tailscale_count
    k3s_count=$(cat /tmp/clean_not_ready_count 2>/dev/null || echo "0")
    tailscale_count=$(cat /tmp/clean_tailscale_count 2>/dev/null || echo "0")
    
    if [ "$k3s_count" -eq 0 ] && [ "$tailscale_count" -eq 0 ]; then
        log "No cleanup needed - all nodes are ready and reachable"
        return 1
    fi
    
    if [ "$DRY_RUN" = true ]; then
        log_step "DRY RUN - Would perform the following cleanup:"
        if [ "$k3s_count" -gt 0 ]; then
            log "Would remove $k3s_count NotReady K3s nodes"
        fi
        if [ "$tailscale_count" -gt 0 ]; then
            log "Would remove $tailscale_count unreachable Tailscale devices"
        fi
        return 1
    fi
    
    if [ "$FORCE" = true ]; then
        log "Force mode enabled, proceeding with cleanup"
        return 0
    fi
    
    echo ""
    log_warn "⚠️  CLEANUP WARNING ⚠️"
    log_warn "This will remove:"
    if [ "$k3s_count" -gt 0 ]; then
        log_warn "  • $k3s_count NotReady nodes from K3s cluster"
    fi
    if [ "$tailscale_count" -gt 0 ]; then
        log_warn "  • $tailscale_count unreachable devices from Tailscale VPN"
    fi
    echo ""
    
    read -p "Continue with cleanup? (y/N): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log "Cleanup cancelled by user"
        return 1
    fi
    
    return 0
}

clean_k3s_nodes() {
    local not_ready_nodes
    not_ready_nodes=$(cat /tmp/clean_not_ready_nodes 2>/dev/null || echo "")
    
    if [ -z "$not_ready_nodes" ] || [ "$(echo "$not_ready_nodes" | grep -v '^$' | wc -l)" -eq 0 ]; then
        return 0
    fi
    
    log_step "Removing NotReady nodes from K3s cluster..."
    
    echo "$not_ready_nodes" | while read -r node; do
        if [ -n "$node" ]; then
            log_verbose "Draining node: $node"
            kubectl drain "$node" --ignore-daemonsets --delete-emptydir-data --force --timeout=30s || {
                log_warn "Failed to drain node $node, continuing with deletion"
            }
            
            log_verbose "Deleting node: $node"
            kubectl delete node "$node" || {
                log_warn "Failed to delete node $node"
                continue
            }
            
            log "Removed K3s node: $node"
        fi
    done
}

clean_tailscale_devices() {
    if [ -z "$TAILSCALE_API_KEY" ]; then
        return 0
    fi
    
    local unreachable_devices
    unreachable_devices=$(cat /tmp/clean_tailscale_devices 2>/dev/null || echo "")
    
    if [ -z "$unreachable_devices" ] || [ "$(echo "$unreachable_devices" | grep -v '^$' | wc -l)" -eq 0 ]; then
        return 0
    fi
    
    log_step "Removing unreachable devices from Tailscale..."
    
    # First, get device details with IDs
    local api_response
    api_response=$(curl -s -H "Authorization: Bearer $TAILSCALE_API_KEY" \
                        "https://api.tailscale.com/api/v2/tailnet/-/devices" || echo "")
    
    echo "$unreachable_devices" | while read -r device; do
        if [ -n "$device" ]; then
            log_verbose "Getting device ID for: $device"
            
            # Extract device ID
            local device_id
            if command -v jq &> /dev/null; then
                device_id=$(echo "$api_response" | jq -r ".devices[]? | select(.name == \"$device\") | .id" 2>/dev/null || echo "")
            else
                # Basic parsing without jq (less reliable)
                device_id=$(echo "$api_response" | grep -B5 -A5 "\"name\":\"$device\"" | grep -o '"id":"[^"]*"' | cut -d'"' -f4 || echo "")
            fi
            
            if [ -n "$device_id" ]; then
                log_verbose "Removing device: $device (ID: $device_id)"
                if curl -s -X DELETE \
                       -H "Authorization: Bearer $TAILSCALE_API_KEY" \
                       "https://api.tailscale.com/api/v2/device/$device_id" > /dev/null; then
                    log "Removed Tailscale device: $device"
                else
                    log_warn "Failed to remove Tailscale device: $device"
                fi
            else
                log_warn "Could not find device ID for: $device"
            fi
        fi
    done
}

show_cleanup_summary() {
    local k3s_count
    local tailscale_count
    k3s_count=$(cat /tmp/clean_not_ready_count 2>/dev/null || echo "0")
    tailscale_count=$(cat /tmp/clean_tailscale_count 2>/dev/null || echo "0")
    
    log_step "Cleanup completed!"
    echo ""
    
    log "Summary:"
    log "  K3s nodes removed: $k3s_count"
    log "  Tailscale devices removed: $tailscale_count"
    
    # Show current cluster status
    local current_nodes
    current_nodes=$(kubectl get nodes --no-headers 2>/dev/null | wc -l || echo "0")
    log "  Current K3s nodes: $current_nodes"
    
    echo ""
    log "✅ Cluster cleanup completed successfully"
    echo ""
}

cleanup_temp_files() {
    rm -f /tmp/clean_not_ready_nodes /tmp/clean_not_ready_count
    rm -f /tmp/clean_tailscale_devices /tmp/clean_tailscale_count
    rm -f /tmp/clean_unreachable_devices_tmp
}

# Argument parsing
parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -t|--tailscale-key)
                TAILSCALE_API_KEY="$2"
                shift 2
                ;;
            -f|--force)
                FORCE=true
                shift
                ;;
            --dry-run)
                DRY_RUN=true
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
    
    # Show header
    log "=============================================="
    log "K3s Cluster Clean Script v${VERSION}"
    log "=============================================="
    log "Force mode: $FORCE"
    log "Dry run: $DRY_RUN"
    log "Tailscale cleanup: $([ -n "$TAILSCALE_API_KEY" ] && echo "enabled" || echo "disabled")"
    log "Verbose: $VERBOSE"
    log "=============================================="
    
    if [ "$VERBOSE" = true ]; then
        log_verbose "Verbose mode enabled - showing detailed output"
    fi
    
    echo ""
    
    # Pre-flight checks
    check_server_node
    
    # Gather information
    get_not_ready_nodes
    get_unreachable_tailscale_devices
    
    # Confirm and perform cleanup
    if confirm_cleanup; then
        echo ""
        clean_k3s_nodes
        clean_tailscale_devices
        show_cleanup_summary
    fi
    
    # Cleanup temp files
    cleanup_temp_files
    
    log "=============================================="
    log "Clean completed!"
    log "=============================================="
    echo ""
}

# Trap to clean up temp files on exit
trap cleanup_temp_files EXIT

# Run main function with all arguments
main "$@"
