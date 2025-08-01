#!/bin/bash

# K3s Cluster Reset Script
# This script resets the K3s cluster by removing all agent nodes, applications, and pods,
# leaving only the server (master) node running.

set -e

# Script version
VERSION="1.0.0"

# Default values
VERBOSE=false
FORCE=false
REMOVE_FROM_TAILSCALE=false

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
K3s Cluster Reset Script v${VERSION}

Resets the K3s cluster by removing all agent nodes, applications, and pods,
leaving only the server (master) node running in a clean state.

USAGE:
    ./reset.sh [OPTIONS]

OPTIONS:
    -f, --force                 Skip confirmation prompts
    --remove-from-tailscale     Also remove agent nodes from Tailscale VPN
    -v, --verbose               Enable verbose output
    -h, --help                  Show this help message
    --version                   Show version information

EXAMPLES:
    # Interactive reset with confirmation prompts
    ./reset.sh

    # Force reset without confirmation prompts
    ./reset.sh --force

    # Reset and remove nodes from Tailscale
    ./reset.sh --remove-from-tailscale

    # Verbose reset with detailed output
    ./reset.sh --verbose --force

DESCRIPTION:
    This script will:
    1. Check that it's running on the K3s server (master) node
    2. Remove all applications and workloads from all namespaces
    3. Drain and remove all agent (worker) nodes from the cluster
    4. Optionally remove agent nodes from Tailscale VPN
    5. Clean up any remaining resources
    6. Leave only the server node in a clean state

NOTES:
    - This script must be run from the K3s server (master) node
    - All applications and data will be permanently lost
    - Agent nodes will need to be rejoined manually after reset
    - The server node will remain running and accessible
    - Use with caution in production environments

EOF
}

show_version() {
    echo "K3s Cluster Reset Script v${VERSION}"
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
    
    # Check if K3s server service is running
    if ! sudo systemctl is-active --quiet k3s 2>/dev/null; then
        log_error "K3s server service is not running. This script must be run from the server node."
        exit $EXIT_MISSING_DEPS
    fi
    
    log "✅ Confirmed running on K3s server node"
}

get_cluster_info() {
    log_step "Getting cluster information..."
    
    # Get server node name
    local server_node
    server_node=$(hostname)
    
    # Get all nodes
    local all_nodes
    all_nodes=$(kubectl get nodes --no-headers | awk '{print $1}' || echo "")
    
    # Get agent nodes (exclude server node)
    local agent_nodes
    agent_nodes=$(echo "$all_nodes" | grep -v "^$server_node$" || echo "")
    
    # Count nodes
    local total_nodes
    local agent_count
    total_nodes=$(echo "$all_nodes" | grep -v '^$' | wc -l || echo "0")
    agent_count=$(echo "$agent_nodes" | grep -v '^$' | wc -l || echo "0")
    
    log "Server node: $server_node"
    log "Total nodes: $total_nodes"
    log "Agent nodes: $agent_count"
    
    if [ "$agent_count" -gt 0 ]; then
        log "Agent nodes to be removed:"
        echo "$agent_nodes" | while read -r node; do
            if [ -n "$node" ]; then
                log "  - $node"
            fi
        done
    else
        log "No agent nodes found in cluster"
    fi
    
    # Get applications count
    local app_count
    app_count=$(kubectl get pods --all-namespaces --no-headers 2>/dev/null | grep -v "kube-system\|kube-public\|kube-node-lease" | wc -l || echo "0")
    log "Application pods: $app_count"
    
    # Store values for later use
    echo "$server_node" > /tmp/reset_server_node
    echo "$agent_nodes" > /tmp/reset_agent_nodes
    echo "$app_count" > /tmp/reset_app_count
}

confirm_reset() {
    if [ "$FORCE" = true ]; then
        log "Force mode enabled, skipping confirmation"
        return 0
    fi
    
    echo ""
    log_warn "⚠️  CLUSTER RESET WARNING ⚠️"
    log_warn "This will permanently:"
    log_warn "  • Remove ALL applications and pods"
    log_warn "  • Remove ALL agent (worker) nodes from the cluster"
    if [ "$REMOVE_FROM_TAILSCALE" = true ]; then
        log_warn "  • Remove agent nodes from Tailscale VPN"
    fi
    log_warn "  • Reset the cluster to server-only state"
    echo ""
    log_warn "This action CANNOT be undone!"
    echo ""
    
    read -p "Are you sure you want to reset the cluster? (type 'yes' to confirm): " -r
    echo ""
    if [ "$REPLY" != "yes" ]; then
        log "Cluster reset cancelled by user"
        exit $EXIT_SUCCESS
    fi
    
    log "Proceeding with cluster reset..."
}

remove_applications() {
    log_step "Removing all applications and workloads..."
    
    # Get app count
    local app_count
    app_count=$(cat /tmp/reset_app_count 2>/dev/null || echo "0")
    
    if [ "$app_count" -eq 0 ]; then
        log "No application pods found, skipping application cleanup"
        return 0
    fi
    
    # Remove all non-system namespaces and their resources
    log_verbose "Getting non-system namespaces..."
    local user_namespaces
    user_namespaces=$(kubectl get namespaces --no-headers | grep -v "kube-system\|kube-public\|kube-node-lease\|default" | awk '{print $1}' || echo "")
    
    if [ -n "$user_namespaces" ]; then
        log "Removing user namespaces and their resources..."
        echo "$user_namespaces" | while read -r ns; do
            if [ -n "$ns" ]; then
                log_verbose "Deleting namespace: $ns"
                kubectl delete namespace "$ns" --timeout=60s || log_warn "Failed to delete namespace $ns"
            fi
        done
    fi
    
    # Clean up default namespace
    log_verbose "Cleaning up default namespace..."
    kubectl delete all --all -n default --timeout=60s || log_warn "Failed to clean default namespace"
    
    # Remove any remaining pods in system namespaces that aren't critical
    log_verbose "Checking for any remaining non-critical pods..."
    local remaining_pods
    remaining_pods=$(kubectl get pods --all-namespaces --no-headers | grep -v "kube-system" | wc -l || echo "0")
    
    if [ "$remaining_pods" -gt 0 ]; then
        log_warn "Found $remaining_pods remaining pods, attempting cleanup..."
        kubectl get pods --all-namespaces --no-headers | grep -v "kube-system" | while read -r line; do
            if [ -n "$line" ]; then
                local ns pod
                ns=$(echo "$line" | awk '{print $1}')
                pod=$(echo "$line" | awk '{print $2}')
                log_verbose "Force deleting pod: $ns/$pod"
                kubectl delete pod "$pod" -n "$ns" --force --grace-period=0 || true
            fi
        done
    fi
    
    log "✅ Applications and workloads removed"
}

remove_agent_nodes() {
    log_step "Removing agent nodes from cluster..."
    
    local agent_nodes
    agent_nodes=$(cat /tmp/reset_agent_nodes 2>/dev/null || echo "")
    
    if [ -z "$agent_nodes" ] || [ "$(echo "$agent_nodes" | grep -v '^$' | wc -l)" -eq 0 ]; then
        log "No agent nodes found, skipping node removal"
        return 0
    fi
    
    echo "$agent_nodes" | while read -r node; do
        if [ -n "$node" ]; then
            log_verbose "Processing node: $node"
            
            # Drain the node
            log_verbose "Draining node: $node"
            kubectl drain "$node" --ignore-daemonsets --delete-emptydir-data --force --timeout=60s || {
                log_warn "Failed to drain node $node, continuing with deletion"
            }
            
            # Delete the node
            log_verbose "Deleting node: $node"
            kubectl delete node "$node" || {
                log_warn "Failed to delete node $node"
                continue
            }
            
            log "Removed agent node: $node"
            
            # Remove from Tailscale if requested
            if [ "$REMOVE_FROM_TAILSCALE" = true ]; then
                remove_from_tailscale "$node"
            fi
        fi
    done
    
    log "✅ Agent nodes removed from cluster"
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

cleanup_resources() {
    log_step "Cleaning up remaining cluster resources..."
    
    # Remove any persistent volumes that might be left
    log_verbose "Checking for persistent volumes..."
    local pv_count
    pv_count=$(kubectl get pv --no-headers 2>/dev/null | wc -l || echo "0")
    
    if [ "$pv_count" -gt 0 ]; then
        log_verbose "Found $pv_count persistent volumes, cleaning up..."
        kubectl delete pv --all --timeout=30s || log_warn "Failed to delete some persistent volumes"
    fi
    
    # Remove any persistent volume claims
    log_verbose "Checking for persistent volume claims..."
    kubectl delete pvc --all --all-namespaces --timeout=30s || log_warn "Failed to delete some persistent volume claims"
    
    # Clean up any remaining secrets (except default ones)
    log_verbose "Cleaning up user secrets..."
    kubectl get secrets --all-namespaces --no-headers | grep -v "default-token\|kubernetes.io/service-account-token" | while read -r line; do
        if [ -n "$line" ]; then
            local ns secret
            ns=$(echo "$line" | awk '{print $1}')
            secret=$(echo "$line" | awk '{print $2}')
            # Skip system namespaces
            if ! echo "$ns" | grep -qE "kube-system|kube-public|kube-node-lease"; then
                log_verbose "Deleting secret: $ns/$secret"
                kubectl delete secret "$secret" -n "$ns" || true
            fi
        fi
    done
    
    # Clean up any remaining configmaps (except default ones)
    log_verbose "Cleaning up user configmaps..."
    kubectl get configmaps --all-namespaces --no-headers | while read -r line; do
        if [ -n "$line" ]; then
            local ns cm
            ns=$(echo "$line" | awk '{print $1}')
            cm=$(echo "$line" | awk '{print $2}')
            # Skip system namespaces and default configmaps
            if ! echo "$ns" | grep -qE "kube-system|kube-public|kube-node-lease" && ! echo "$cm" | grep -q "kube-root-ca.crt"; then
                log_verbose "Deleting configmap: $ns/$cm"
                kubectl delete configmap "$cm" -n "$ns" || true
            fi
        fi
    done
    
    log "✅ Cluster resources cleaned up"
}

show_final_status() {
    log_step "Cluster reset completed!"
    echo ""
    
    # Show final cluster status
    local server_node
    server_node=$(cat /tmp/reset_server_node 2>/dev/null || hostname)
    
    log "Final cluster status:"
    log "  Server node: $server_node"
    
    # Check node count
    local final_node_count
    final_node_count=$(kubectl get nodes --no-headers 2>/dev/null | wc -l || echo "0")
    log "  Active nodes: $final_node_count"
    
    # Check pod count
    local final_pod_count
    final_pod_count=$(kubectl get pods --all-namespaces --no-headers 2>/dev/null | grep -v "kube-system" | wc -l || echo "0")
    log "  Application pods: $final_pod_count"
    
    echo ""
    log "✅ Cluster successfully reset to server-only state"
    log "The K3s server is ready to accept new agent nodes"
    echo ""
    
    # Show how to add new nodes
    if [ -f "add_nodes.md" ]; then
        log "To add new agent nodes, refer to: add_nodes.md"
    else
        log "To get agent setup commands, run the setup script again or check the server token:"
        log "  sudo cat /var/lib/rancher/k3s/server/node-token"
    fi
    
    echo ""
}

cleanup_temp_files() {
    # Clean up temporary files
    rm -f /tmp/reset_server_node /tmp/reset_agent_nodes /tmp/reset_app_count
}

# Argument parsing
parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -f|--force)
                FORCE=true
                shift
                ;;
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
    log "K3s Cluster Reset Script v${VERSION}"
    log "=============================================="
    log "Force mode: $FORCE"
    log "Remove from Tailscale: $REMOVE_FROM_TAILSCALE"
    log "Verbose: $VERBOSE"
    log "=============================================="
    
    if [ "$VERBOSE" = true ]; then
        log_verbose "Verbose mode enabled - showing detailed output"
    fi
    
    echo ""
    
    # Pre-flight checks
    check_server_node
    
    # Get cluster information
    get_cluster_info
    
    # Confirm the reset operation
    confirm_reset
    
    echo ""
    
    # Perform the reset
    remove_applications
    remove_agent_nodes
    cleanup_resources
    
    # Show final status
    show_final_status
    
    # Cleanup
    cleanup_temp_files
    
    log "=============================================="
    log "Reset completed successfully!"
    log "=============================================="
    echo ""
}

# Trap to clean up temp files on exit
trap cleanup_temp_files EXIT

# Run main function with all arguments
main "$@"
