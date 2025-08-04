#!/bin/bash

# K3s Resource Deletion Script
# This script helps delete specific resources from the K3s cluster
# Supports deleting pods, deployments, services, namespaces, and nodes

set -e

# Script version
VERSION="1.0.0"

# Default values
VERBOSE=false
FORCE=false
DRY_RUN=false
ALL_NAMESPACES=false
NAMESPACE="default"

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
EXIT_NOT_FOUND=3

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

log_dry_run() {
    echo -e "${BLUE}[DRY-RUN]${NC} $1"
}

# Help function
show_help() {
    cat << EOF
K3s Resource Deletion Script v${VERSION}

Safely delete various Kubernetes resources from your K3s cluster.

USAGE:
    ./delete.sh RESOURCE_TYPE RESOURCE_NAME [OPTIONS]
    ./delete.sh RESOURCE_TYPE --all [OPTIONS]

RESOURCE TYPES:
    pod, pods                   Delete specific pods
    deployment, deploy          Delete deployments
    service, svc                Delete services
    namespace, ns               Delete namespaces
    node                        Delete and drain nodes
    configmap, cm               Delete configmaps
    secret                      Delete secrets
    app                         Delete complete application (deployment + service + configmap)

ARGUMENTS:
    RESOURCE_NAME               Name of the resource to delete
    --all                       Delete all resources of the specified type

OPTIONS:
    -n, --namespace NAMESPACE   Kubernetes namespace (default: default)
    -A, --all-namespaces        Apply to all namespaces
    -f, --force                 Skip confirmation prompts
    --dry-run                   Show what would be deleted without actually deleting
    -v, --verbose               Enable verbose output
    -h, --help                  Show this help message
    --version                   Show version information

EXAMPLES:
    # Delete a specific pod
    ./delete.sh pod my-app-pod

    # Delete all pods in default namespace
    ./delete.sh pod --all

    # Delete a deployment with confirmation
    ./delete.sh deployment my-app

    # Delete all services in a specific namespace
    ./delete.sh service --all -n my-namespace

    # Delete all pods across all namespaces (with dry-run)
    ./delete.sh pod --all -A --dry-run

    # Force delete a stuck pod
    ./delete.sh pod stuck-pod -f

    # Delete complete application (deployment + service + configmap)
    ./delete.sh app my-application

    # Drain and remove a node from cluster
    ./delete.sh node worker-node-1

    # Delete namespace and all resources within it
    ./delete.sh namespace my-project

SAFETY FEATURES:
    - Confirmation prompts for destructive operations
    - Dry-run mode to preview changes
    - Graceful draining for node deletions
    - Protection against deleting system resources
    - Clear logging of all operations

NOTES:
    - System namespaces (kube-system, kube-public, etc.) are protected
    - Node deletion includes draining to safely move workloads
    - Use --force to skip confirmations (use with caution)
    - Always test with --dry-run first for bulk operations

EOF
}

show_version() {
    echo "K3s Resource Deletion Script v${VERSION}"
}

# Function to check dependencies
check_dependencies() {
    if ! command -v kubectl &>/dev/null; then
        log_error "kubectl not found. This script requires kubectl to be installed and configured."
        exit $EXIT_MISSING_DEPS
    fi
    
    # Check if we can connect to the cluster
    if ! kubectl cluster-info &>/dev/null; then
        log_error "Cannot connect to K3s cluster. Ensure kubectl is properly configured."
        exit $EXIT_MISSING_DEPS
    fi
    
    log_verbose "✅ Dependencies checked successfully"
}

# Function to confirm deletion
confirm_deletion() {
    local resource_type="$1"
    local resource_name="$2"
    local namespace_info="$3"
    
    if [ "$FORCE" = true ]; then
        log_verbose "Force mode enabled, skipping confirmation"
        return 0
    fi
    
    echo ""
    log_warn "⚠️  DELETION CONFIRMATION ⚠️"
    log_warn "Resource Type: $resource_type"
    log_warn "Resource Name: $resource_name"
    log_warn "Namespace: $namespace_info"
    echo ""
    log_warn "This action CANNOT be undone!"
    echo ""
    
    read -p "Are you sure you want to delete this resource? (y/N): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log "Deletion cancelled by user"
        exit $EXIT_SUCCESS
    fi
    
    log "Proceeding with deletion..."
}

# Function to check if resource exists
resource_exists() {
    local resource_type="$1"
    local resource_name="$2"
    local namespace="$3"
    
    if [ "$namespace" = "all-namespaces" ]; then
        kubectl get "$resource_type" "$resource_name" --all-namespaces &>/dev/null
    else
        kubectl get "$resource_type" "$resource_name" -n "$namespace" &>/dev/null
    fi
}

# Function to list resources
list_resources() {
    local resource_type="$1"
    local namespace="$2"
    
    log_step "Listing available $resource_type resources..."
    
    if [ "$namespace" = "all-namespaces" ]; then
        kubectl get "$resource_type" --all-namespaces
    else
        kubectl get "$resource_type" -n "$namespace"
    fi
}

# Function to delete pod
delete_pod() {
    local pod_name="$1"
    local namespace="$2"
    
    if [ "$pod_name" = "--all" ]; then
        if [ "$ALL_NAMESPACES" = true ]; then
            confirm_deletion "pods" "ALL" "all namespaces"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all pods in all namespaces"
                kubectl get pods --all-namespaces
            else
                log_step "Deleting all pods in all namespaces..."
                kubectl delete pods --all --all-namespaces --timeout=60s
            fi
        else
            confirm_deletion "pods" "ALL" "$namespace"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all pods in namespace: $namespace"
                kubectl get pods -n "$namespace"
            else
                log_step "Deleting all pods in namespace: $namespace..."
                kubectl delete pods --all -n "$namespace" --timeout=60s
            fi
        fi
    else
        if ! resource_exists "pod" "$pod_name" "$namespace"; then
            log_error "Pod '$pod_name' not found in namespace '$namespace'"
            list_resources "pods" "$namespace"
            exit $EXIT_NOT_FOUND
        fi
        
        confirm_deletion "pod" "$pod_name" "$namespace"
        if [ "$DRY_RUN" = true ]; then
            log_dry_run "Would delete pod: $pod_name in namespace: $namespace"
        else
            log_step "Deleting pod: $pod_name..."
            kubectl delete pod "$pod_name" -n "$namespace" --timeout=60s
        fi
    fi
    
    log "✅ Pod deletion completed"
}

# Function to delete deployment
delete_deployment() {
    local deployment_name="$1"
    local namespace="$2"
    
    if [ "$deployment_name" = "--all" ]; then
        if [ "$ALL_NAMESPACES" = true ]; then
            confirm_deletion "deployments" "ALL" "all namespaces"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all deployments in all namespaces"
                kubectl get deployments --all-namespaces
            else
                log_step "Deleting all deployments in all namespaces..."
                kubectl delete deployments --all --all-namespaces --timeout=120s
            fi
        else
            confirm_deletion "deployments" "ALL" "$namespace"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all deployments in namespace: $namespace"
                kubectl get deployments -n "$namespace"
            else
                log_step "Deleting all deployments in namespace: $namespace..."
                kubectl delete deployments --all -n "$namespace" --timeout=120s
            fi
        fi
    else
        if ! resource_exists "deployment" "$deployment_name" "$namespace"; then
            log_error "Deployment '$deployment_name' not found in namespace '$namespace'"
            list_resources "deployments" "$namespace"
            exit $EXIT_NOT_FOUND
        fi
        
        confirm_deletion "deployment" "$deployment_name" "$namespace"
        if [ "$DRY_RUN" = true ]; then
            log_dry_run "Would delete deployment: $deployment_name in namespace: $namespace"
        else
            log_step "Deleting deployment: $deployment_name..."
            kubectl delete deployment "$deployment_name" -n "$namespace" --timeout=120s
        fi
    fi
    
    log "✅ Deployment deletion completed"
}

# Function to delete service
delete_service() {
    local service_name="$1"
    local namespace="$2"
    
    if [ "$service_name" = "--all" ]; then
        if [ "$ALL_NAMESPACES" = true ]; then
            confirm_deletion "services" "ALL" "all namespaces"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all services in all namespaces"
                kubectl get services --all-namespaces
            else
                log_step "Deleting all services in all namespaces..."
                # Exclude default kubernetes service
                kubectl get services --all-namespaces --no-headers | grep -v "kubernetes.*ClusterIP.*443/TCP" | while read -r line; do
                    if [ -n "$line" ]; then
                        local svc_ns svc_name
                        svc_ns=$(echo "$line" | awk '{print $1}')
                        svc_name=$(echo "$line" | awk '{print $2}')
                        kubectl delete service "$svc_name" -n "$svc_ns" || true
                    fi
                done
            fi
        else
            confirm_deletion "services" "ALL" "$namespace"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all services in namespace: $namespace"
                kubectl get services -n "$namespace"
            else
                log_step "Deleting all services in namespace: $namespace..."
                kubectl delete services --all -n "$namespace" --timeout=60s
            fi
        fi
    else
        if ! resource_exists "service" "$service_name" "$namespace"; then
            log_error "Service '$service_name' not found in namespace '$namespace'"
            list_resources "services" "$namespace"
            exit $EXIT_NOT_FOUND
        fi
        
        confirm_deletion "service" "$service_name" "$namespace"
        if [ "$DRY_RUN" = true ]; then
            log_dry_run "Would delete service: $service_name in namespace: $namespace"
        else
            log_step "Deleting service: $service_name..."
            kubectl delete service "$service_name" -n "$namespace" --timeout=60s
        fi
    fi
    
    log "✅ Service deletion completed"
}

# Function to delete namespace
delete_namespace() {
    local namespace_name="$1"
    
    # Protect system namespaces
    case "$namespace_name" in
        kube-system|kube-public|kube-node-lease|default)
            log_error "Cannot delete system namespace: $namespace_name"
            exit $EXIT_INVALID_ARGS
            ;;
    esac
    
    if ! resource_exists "namespace" "$namespace_name" ""; then
        log_error "Namespace '$namespace_name' not found"
        list_resources "namespaces" ""
        exit $EXIT_NOT_FOUND
    fi
    
    confirm_deletion "namespace" "$namespace_name" "cluster-wide"
    if [ "$DRY_RUN" = true ]; then
        log_dry_run "Would delete namespace: $namespace_name and all resources within it"
        kubectl get all -n "$namespace_name"
    else
        log_step "Deleting namespace: $namespace_name..."
        kubectl delete namespace "$namespace_name" --timeout=300s
    fi
    
    log "✅ Namespace deletion completed"
}

# Function to delete node
delete_node() {
    local node_name="$1"
    
    if ! resource_exists "node" "$node_name" ""; then
        log_error "Node '$node_name' not found"
        list_resources "nodes" ""
        exit $EXIT_NOT_FOUND
    fi
    
    confirm_deletion "node" "$node_name" "cluster-wide"
    if [ "$DRY_RUN" = true ]; then
        log_dry_run "Would drain and delete node: $node_name"
        kubectl get node "$node_name" -o wide
    else
        log_step "Draining node: $node_name..."
        kubectl drain "$node_name" --ignore-daemonsets --delete-emptydir-data --force --timeout=300s || {
            log_warn "Failed to drain node cleanly, proceeding with deletion"
        }
        
        log_step "Deleting node: $node_name..."
        kubectl delete node "$node_name"
    fi
    
    log "✅ Node deletion completed"
}

# Function to delete configmap
delete_configmap() {
    local configmap_name="$1"
    local namespace="$2"
    
    if [ "$configmap_name" = "--all" ]; then
        if [ "$ALL_NAMESPACES" = true ]; then
            confirm_deletion "configmaps" "ALL" "all namespaces"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all configmaps in all namespaces"
                kubectl get configmaps --all-namespaces
            else
                log_step "Deleting all configmaps in all namespaces..."
                # Exclude system configmaps
                kubectl get configmaps --all-namespaces --no-headers | grep -v "kube-root-ca.crt" | while read -r line; do
                    if [ -n "$line" ]; then
                        local cm_ns cm_name
                        cm_ns=$(echo "$line" | awk '{print $1}')
                        cm_name=$(echo "$line" | awk '{print $2}')
                        if ! echo "$cm_ns" | grep -qE "kube-system|kube-public|kube-node-lease"; then
                            kubectl delete configmap "$cm_name" -n "$cm_ns" || true
                        fi
                    fi
                done
            fi
        else
            confirm_deletion "configmaps" "ALL" "$namespace"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all configmaps in namespace: $namespace"
                kubectl get configmaps -n "$namespace"
            else
                log_step "Deleting all configmaps in namespace: $namespace..."
                kubectl delete configmaps --all -n "$namespace" --timeout=60s
            fi
        fi
    else
        if ! resource_exists "configmap" "$configmap_name" "$namespace"; then
            log_error "ConfigMap '$configmap_name' not found in namespace '$namespace'"
            list_resources "configmaps" "$namespace"
            exit $EXIT_NOT_FOUND
        fi
        
        confirm_deletion "configmap" "$configmap_name" "$namespace"
        if [ "$DRY_RUN" = true ]; then
            log_dry_run "Would delete configmap: $configmap_name in namespace: $namespace"
        else
            log_step "Deleting configmap: $configmap_name..."
            kubectl delete configmap "$configmap_name" -n "$namespace" --timeout=60s
        fi
    fi
    
    log "✅ ConfigMap deletion completed"
}

# Function to delete secret
delete_secret() {
    local secret_name="$1"
    local namespace="$2"
    
    if [ "$secret_name" = "--all" ]; then
        if [ "$ALL_NAMESPACES" = true ]; then
            confirm_deletion "secrets" "ALL (excluding system secrets)" "all namespaces"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all user secrets in all namespaces"
                kubectl get secrets --all-namespaces | grep -v "default-token\|kubernetes.io/service-account-token"
            else
                log_step "Deleting all user secrets in all namespaces..."
                # Exclude system secrets
                kubectl get secrets --all-namespaces --no-headers | grep -v "default-token\|kubernetes.io/service-account-token" | while read -r line; do
                    if [ -n "$line" ]; then
                        local secret_ns secret_name_var
                        secret_ns=$(echo "$line" | awk '{print $1}')
                        secret_name_var=$(echo "$line" | awk '{print $2}')
                        if ! echo "$secret_ns" | grep -qE "kube-system|kube-public|kube-node-lease"; then
                            kubectl delete secret "$secret_name_var" -n "$secret_ns" || true
                        fi
                    fi
                done
            fi
        else
            confirm_deletion "secrets" "ALL (excluding system secrets)" "$namespace"
            if [ "$DRY_RUN" = true ]; then
                log_dry_run "Would delete all user secrets in namespace: $namespace"
                kubectl get secrets -n "$namespace" | grep -v "default-token\|kubernetes.io/service-account-token"
            else
                log_step "Deleting all user secrets in namespace: $namespace..."
                kubectl get secrets -n "$namespace" --no-headers | grep -v "default-token\|kubernetes.io/service-account-token" | while read -r line; do
                    if [ -n "$line" ]; then
                        local secret_name_var
                        secret_name_var=$(echo "$line" | awk '{print $1}')
                        kubectl delete secret "$secret_name_var" -n "$namespace" || true
                    fi
                done
            fi
        fi
    else
        # Protect system secrets
        if echo "$secret_name" | grep -qE "default-token|kubernetes.io/service-account-token"; then
            log_error "Cannot delete system secret: $secret_name"
            exit $EXIT_INVALID_ARGS
        fi
        
        if ! resource_exists "secret" "$secret_name" "$namespace"; then
            log_error "Secret '$secret_name' not found in namespace '$namespace'"
            list_resources "secrets" "$namespace"
            exit $EXIT_NOT_FOUND
        fi
        
        confirm_deletion "secret" "$secret_name" "$namespace"
        if [ "$DRY_RUN" = true ]; then
            log_dry_run "Would delete secret: $secret_name in namespace: $namespace"
        else
            log_step "Deleting secret: $secret_name..."
            kubectl delete secret "$secret_name" -n "$namespace" --timeout=60s
        fi
    fi
    
    log "✅ Secret deletion completed"
}

# Function to delete complete application
delete_app() {
    local app_name="$1"
    local namespace="$2"
    
    confirm_deletion "application (deployment + service + configmap)" "$app_name" "$namespace"
    
    if [ "$DRY_RUN" = true ]; then
        log_dry_run "Would delete application components for: $app_name"
        echo "Components that would be deleted:"
        kubectl get deployment "$app_name" -n "$namespace" 2>/dev/null || echo "  No deployment found"
        kubectl get service "$app_name" -n "$namespace" 2>/dev/null || echo "  No service found"
        kubectl get configmap "$app_name-config" -n "$namespace" 2>/dev/null || echo "  No configmap found"
    else
        log_step "Deleting application: $app_name..."
        
        # Delete deployment
        if kubectl get deployment "$app_name" -n "$namespace" &>/dev/null; then
            log_verbose "Deleting deployment: $app_name"
            kubectl delete deployment "$app_name" -n "$namespace" --timeout=120s || true
        fi
        
        # Delete service
        if kubectl get service "$app_name" -n "$namespace" &>/dev/null; then
            log_verbose "Deleting service: $app_name"
            kubectl delete service "$app_name" -n "$namespace" --timeout=60s || true
        fi
        
        # Delete configmap (try common patterns)
        for cm_pattern in "$app_name-config" "$app_name-configmap" "$app_name"; do
            if kubectl get configmap "$cm_pattern" -n "$namespace" &>/dev/null; then
                log_verbose "Deleting configmap: $cm_pattern"
                kubectl delete configmap "$cm_pattern" -n "$namespace" --timeout=60s || true
            fi
        done
        
        # Delete secrets (try common patterns)
        for secret_pattern in "$app_name-secret" "$app_name-secrets" "$app_name"; do
            if kubectl get secret "$secret_pattern" -n "$namespace" &>/dev/null; then
                log_verbose "Deleting secret: $secret_pattern"
                kubectl delete secret "$secret_pattern" -n "$namespace" --timeout=60s || true
            fi
        done
    fi
    
    log "✅ Application deletion completed"
}

# Parse command line arguments
parse_arguments() {
    RESOURCE_TYPE=""
    RESOURCE_NAME=""
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            -n|--namespace)
                NAMESPACE="$2"
                shift 2
                ;;
            -A|--all-namespaces)
                ALL_NAMESPACES=true
                NAMESPACE="all-namespaces"
                shift
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
            pod|pods|deployment|deploy|service|svc|namespace|ns|node|configmap|cm|secret|app)
                if [ -z "$RESOURCE_TYPE" ]; then
                    RESOURCE_TYPE="$1"
                    shift
                else
                    log_error "Multiple resource types specified"
                    exit $EXIT_INVALID_ARGS
                fi
                ;;
            --all)
                RESOURCE_NAME="--all"
                shift
                ;;
            *)
                if [ -z "$RESOURCE_TYPE" ]; then
                    log_error "Resource type must be specified first"
                    echo ""
                    show_help
                    exit $EXIT_INVALID_ARGS
                elif [ -z "$RESOURCE_NAME" ]; then
                    RESOURCE_NAME="$1"
                    shift
                else
                    log_error "Unknown option: $1"
                    echo ""
                    show_help
                    exit $EXIT_INVALID_ARGS
                fi
                ;;
        esac
    done
    
    # Validate required arguments
    if [ -z "$RESOURCE_TYPE" ]; then
        log_error "Resource type is required"
        echo ""
        show_help
        exit $EXIT_INVALID_ARGS
    fi
    
    if [ -z "$RESOURCE_NAME" ]; then
        log_error "Resource name or --all is required"
        echo ""
        show_help
        exit $EXIT_INVALID_ARGS
    fi
}

# Main function
main() {
    # Parse command line arguments
    parse_arguments "$@"
    
    # Show header
    log "=============================================="
    log "K3s Resource Deletion Script v${VERSION}"
    log "=============================================="
    log "Resource Type: $RESOURCE_TYPE"
    log "Resource Name: $RESOURCE_NAME"
    log "Namespace: $NAMESPACE"
    log "Force mode: $FORCE"
    log "Dry run: $DRY_RUN"
    log "Verbose: $VERBOSE"
    log "=============================================="
    
    if [ "$VERBOSE" = true ]; then
        log_verbose "Verbose mode enabled - showing detailed output"
    fi
    
    if [ "$DRY_RUN" = true ]; then
        log_warn "DRY RUN MODE - No actual deletions will be performed"
    fi
    
    echo ""
    
    # Check dependencies
    check_dependencies
    
    # Execute deletion based on resource type
    case "$RESOURCE_TYPE" in
        pod|pods)
            delete_pod "$RESOURCE_NAME" "$NAMESPACE"
            ;;
        deployment|deploy)
            delete_deployment "$RESOURCE_NAME" "$NAMESPACE"
            ;;
        service|svc)
            delete_service "$RESOURCE_NAME" "$NAMESPACE"
            ;;
        namespace|ns)
            if [ "$RESOURCE_NAME" = "--all" ]; then
                log_error "Cannot delete all namespaces - too dangerous"
                exit $EXIT_INVALID_ARGS
            fi
            delete_namespace "$RESOURCE_NAME"
            ;;
        node)
            if [ "$RESOURCE_NAME" = "--all" ]; then
                log_error "Cannot delete all nodes - would destroy cluster"
                exit $EXIT_INVALID_ARGS
            fi
            delete_node "$RESOURCE_NAME"
            ;;
        configmap|cm)
            delete_configmap "$RESOURCE_NAME" "$NAMESPACE"
            ;;
        secret)
            delete_secret "$RESOURCE_NAME" "$NAMESPACE"
            ;;
        app)
            if [ "$RESOURCE_NAME" = "--all" ]; then
                log_error "Cannot delete all applications - use specific resource types instead"
                exit $EXIT_INVALID_ARGS
            fi
            delete_app "$RESOURCE_NAME" "$NAMESPACE"
            ;;
        *)
            log_error "Unknown resource type: $RESOURCE_TYPE"
            exit $EXIT_INVALID_ARGS
            ;;
    esac
    
    echo ""
    log "=============================================="
    log "Deletion operation completed successfully!"
    log "=============================================="
}

# Run main function with all arguments
main "$@"
