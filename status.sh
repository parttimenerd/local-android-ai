#!/bin/bash

# K3s Cluster Status Script
# This script provides a comprehensive overview of the K3s cluster status
# including nodes, pods, services, and applications.

set -e

# Script version
VERSION="1.0.0"

# Default values
VERBOSE=false
WATCH_MODE=false
REFRESH_INTERVAL=5
NAMESPACE=""
SHOW_SYSTEM=false
SHOW_OBJECT_DETECTION=false
SHOW_LLM=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Logging functions
log() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_header() {
    echo -e "${BOLD}${CYAN}$1${NC}"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# Help function
show_help() {
    cat << EOF
K3s Cluster Status Script v${VERSION}

Provides a comprehensive overview of the K3s cluster status including
nodes, pods, services, and applications.

USAGE:
    ./status.sh [OPTIONS]

OPTIONS:
    -n, --namespace NAMESPACE   Show resources in specific namespace (default: all)
    -s, --system               Include system namespaces (kube-system, etc.)
    -w, --watch                Watch mode - continuously refresh status
    -i, --interval SECONDS     Refresh interval for watch mode (default: 5)
    -v, --verbose              Enable verbose output
    --object-detection         Include object detection from phone cameras
    --llm                      Include LLM scene descriptions from phone rear cameras
    -h, --help                 Show this help message
    --version                  Show version information

EXAMPLES:
    # Show basic cluster status
    ./status.sh

    # Show verbose output with node locations
    ./status.sh -v

    # Show status with object detection from phone cameras
    ./status.sh --object-detection

    # Show status with LLM scene descriptions from phone cameras
    ./status.sh --llm

    # Show status for specific namespace
    ./status.sh -n default

    # Show all resources including system namespaces
    ./status.sh -s

    # Watch mode with 10 second refresh and object detection
    ./status.sh -w -i 10 --object-detection

    # Full monitoring: verbose + object detection + watch mode
    ./status.sh -v -s --object-detection -w

DESCRIPTION:
    This script displays:
    1. Cluster information and version
    2. Node status and resource usage
    3. Node locations with GPS coordinates and reverse geocoded cities (verbose mode)
    4. Pod status across namespaces
    5. Service endpoints
    6. Application deployments and statefulsets
    7. Recent cluster events
    8. Object detection from phone cameras (with --object-detection flag)

NOTES:
    - This script requires kubectl access to the K3s cluster
    - Must be run from a node with cluster access (typically the server node)
    - Use Ctrl+C to exit watch mode

EOF
}

show_version() {
    echo "K3s Cluster Status Script v${VERSION}"
}

# Check if kubectl is available and cluster is accessible
check_cluster_access() {
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl not found. Please run this from the K3s server node."
        exit 1
    fi
    
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to K3s cluster. Ensure you're running this on a node with cluster access."
        exit 1
    fi
}

# Get cluster information
show_cluster_info() {
    log_header "=== CLUSTER INFORMATION ==="
    echo ""
    
    # Cluster version
    local k8s_version=$(kubectl version --short 2>/dev/null | grep "Server Version" | awk '{print $3}' || echo "unknown")
    echo "Kubernetes Version: $k8s_version"
    
    # Cluster endpoint
    local cluster_endpoint=$(kubectl cluster-info | grep "control plane" | awk '{print $6}' | tr -d '\033[0m' || echo "unknown")
    echo "Cluster Endpoint: $cluster_endpoint"
    
    # Number of nodes
    local node_count=$(kubectl get nodes --no-headers 2>/dev/null | wc -l || echo "0")
    echo "Total Nodes: $node_count"
    
    echo ""
}

# Show node status
show_nodes() {
    log_header "=== NODE STATUS ==="
    echo ""
    
    # Node overview
    kubectl get nodes -o wide 2>/dev/null || {
        log_error "Failed to get node information"
        return 1
    }
    
    echo ""
    
    # Node resource usage (if metrics available)
    if kubectl top nodes &>/dev/null; then
        echo "Node Resource Usage:"
        kubectl top nodes 2>/dev/null || log_warn "Node metrics not available"
        echo ""
    fi
}

# Show pod status
show_pods() {
    log_header "=== POD STATUS ==="
    echo ""
    
    local namespace_flag=""
    if [ -n "$NAMESPACE" ]; then
        namespace_flag="-n $NAMESPACE"
        echo "Namespace: $NAMESPACE"
        echo ""
    elif [ "$SHOW_SYSTEM" = false ]; then
        namespace_flag="--all-namespaces"
        echo "Showing user namespaces (use -s to include system namespaces)"
        echo ""
    else
        namespace_flag="--all-namespaces"
        echo "Showing all namespaces"
        echo ""
    fi
    
    # Pod status overview
    if [ "$SHOW_SYSTEM" = false ] && [ -z "$NAMESPACE" ]; then
        # Filter out system namespaces
        kubectl get pods --all-namespaces --no-headers 2>/dev/null | \
        grep -v -E "^(kube-system|kube-public|kube-node-lease|local-path-storage)" | \
        if read -r line; then
            echo "NAMESPACE       NAME                                READY   STATUS    RESTARTS   AGE"
            echo "$line"
            cat
        else
            echo "No user pods found"
            return 0
        fi
    else
        kubectl get pods $namespace_flag -o wide 2>/dev/null || {
            log_error "Failed to get pod information"
            return 1
        }
    fi
    
    echo ""
    
    # Pod resource usage (if metrics available)
    if kubectl top pods $namespace_flag &>/dev/null; then
        echo "Pod Resource Usage:"
        kubectl top pods $namespace_flag 2>/dev/null || log_warn "Pod metrics not available"
        echo ""
    fi
}

# Show services
show_services() {
    log_header "=== SERVICES ==="
    echo ""
    
    local namespace_flag=""
    if [ -n "$NAMESPACE" ]; then
        namespace_flag="-n $NAMESPACE"
    elif [ "$SHOW_SYSTEM" = false ]; then
        namespace_flag="--all-namespaces"
        kubectl get services --all-namespaces --no-headers 2>/dev/null | \
        grep -v -E "^(kube-system|kube-public|kube-node-lease)" | \
        if read -r line; then
            echo "NAMESPACE   NAME               TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE"
            echo "$line"
            cat
        else
            echo "No user services found"
            return 0
        fi
        echo ""
        return 0
    else
        namespace_flag="--all-namespaces"
    fi
    
    kubectl get services $namespace_flag 2>/dev/null || {
        log_error "Failed to get service information"
        return 1
    }
    
    echo ""
}

# Show deployments and statefulsets
show_workloads() {
    log_header "=== WORKLOADS ==="
    echo ""
    
    local namespace_flag=""
    if [ -n "$NAMESPACE" ]; then
        namespace_flag="-n $NAMESPACE"
    elif [ "$SHOW_SYSTEM" = false ]; then
        namespace_flag="--all-namespaces"
    else
        namespace_flag="--all-namespaces"
    fi
    
    # Deployments
    echo "Deployments:"
    if [ "$SHOW_SYSTEM" = false ] && [ -z "$NAMESPACE" ]; then
        kubectl get deployments --all-namespaces --no-headers 2>/dev/null | \
        grep -v -E "^(kube-system|kube-public|kube-node-lease)" | \
        if read -r line; then
            echo "NAMESPACE   NAME                    READY   UP-TO-DATE   AVAILABLE   AGE"
            echo "$line"
            cat
        else
            echo "No user deployments found"
        fi
    else
        kubectl get deployments $namespace_flag 2>/dev/null || echo "No deployments found"
    fi
    
    echo ""
    
    # StatefulSets
    echo "StatefulSets:"
    if [ "$SHOW_SYSTEM" = false ] && [ -z "$NAMESPACE" ]; then
        kubectl get statefulsets --all-namespaces --no-headers 2>/dev/null | \
        grep -v -E "^(kube-system|kube-public|kube-node-lease)" | \
        if read -r line; then
            echo "NAMESPACE   NAME      READY   AGE"
            echo "$line"
            cat
        else
            echo "No user statefulsets found"
        fi
    else
        kubectl get statefulsets $namespace_flag 2>/dev/null || echo "No statefulsets found"
    fi
    
    echo ""
    
    # DaemonSets (usually system, but show if requested)
    if [ "$SHOW_SYSTEM" = true ] || [ -n "$NAMESPACE" ]; then
        echo "DaemonSets:"
        kubectl get daemonsets $namespace_flag 2>/dev/null || echo "No daemonsets found"
        echo ""
    fi
}

# Show persistent volumes and claims
show_storage() {
    log_header "=== STORAGE ==="
    echo ""
    
    # Persistent Volumes
    echo "Persistent Volumes:"
    kubectl get pv 2>/dev/null || echo "No persistent volumes found"
    echo ""
    
    # Persistent Volume Claims
    echo "Persistent Volume Claims:"
    local namespace_flag=""
    if [ -n "$NAMESPACE" ]; then
        namespace_flag="-n $NAMESPACE"
    elif [ "$SHOW_SYSTEM" = false ]; then
        namespace_flag="--all-namespaces"
        kubectl get pvc --all-namespaces --no-headers 2>/dev/null | \
        grep -v -E "^(kube-system|kube-public|kube-node-lease)" | \
        if read -r line; then
            echo "NAMESPACE   NAME        STATUS   VOLUME    CAPACITY   ACCESS MODES   STORAGECLASS   AGE"
            echo "$line"
            cat
        else
            echo "No user persistent volume claims found"
        fi
        echo ""
        return 0
    else
        namespace_flag="--all-namespaces"
    fi
    
    kubectl get pvc $namespace_flag 2>/dev/null || echo "No persistent volume claims found"
    echo ""
}

# Show ingress resources
show_ingress() {
    log_header "=== INGRESS ==="
    echo ""
    
    local namespace_flag=""
    if [ -n "$NAMESPACE" ]; then
        namespace_flag="-n $NAMESPACE"
    elif [ "$SHOW_SYSTEM" = false ]; then
        namespace_flag="--all-namespaces"
        kubectl get ingress --all-namespaces --no-headers 2>/dev/null | \
        grep -v -E "^(kube-system|kube-public|kube-node-lease)" | \
        if read -r line; then
            echo "NAMESPACE   NAME      CLASS    HOSTS     ADDRESS   PORTS   AGE"
            echo "$line"
            cat
        else
            echo "No user ingress resources found"
        fi
        echo ""
        return 0
    else
        namespace_flag="--all-namespaces"
    fi
    
    kubectl get ingress $namespace_flag 2>/dev/null || echo "No ingress resources found"
    echo ""
}

# Show events (recent)
show_events() {
    if [ "$VERBOSE" = true ]; then
        log_header "=== RECENT EVENTS ==="
        echo ""
        
        local namespace_flag=""
        if [ -n "$NAMESPACE" ]; then
            namespace_flag="-n $NAMESPACE"
        else
            namespace_flag="--all-namespaces"
        fi
        
        # Show events from last 10 minutes
        kubectl get events $namespace_flag --sort-by='.lastTimestamp' 2>/dev/null | tail -20 || {
            echo "No recent events found"
        }
        echo ""
    fi
}

# Show node locations
show_locations() {
    if [ "$VERBOSE" = true ]; then
        log_header "📍 Node Locations"
        echo ""
        
        # Get all nodes with phone labels
        local phone_nodes
        phone_nodes=$(kubectl get nodes -o json 2>/dev/null | jq -r '.items[] | select(.metadata.labels["device-type"] == "phone") | .metadata.name' 2>/dev/null || echo "")
        
        if [ -z "$phone_nodes" ]; then
            echo "No phone nodes found in cluster"
            echo ""
            return
        fi
        
        # Table header
        printf "%-20s %-12s %-13s %-10s %-20s %-15s\n" "NODE" "LATITUDE" "LONGITUDE" "ALTITUDE" "CITY" "LAST UPDATED"
        printf "%-20s %-12s %-13s %-10s %-20s %-15s\n" "----" "--------" "---------" "--------" "----" "------------"
        
        # Get location data for each phone node
        echo "$phone_nodes" | while read -r node; do
            if [ -n "$node" ]; then
                local node_info
                node_info=$(kubectl get node "$node" -o json 2>/dev/null || echo "")
                
                if [ -n "$node_info" ]; then
                    local latitude longitude altitude city updated
                    latitude=$(echo "$node_info" | jq -r '.metadata.labels["phone.location/latitude"] // "N/A"' 2>/dev/null || echo "N/A")
                    longitude=$(echo "$node_info" | jq -r '.metadata.labels["phone.location/longitude"] // "N/A"' 2>/dev/null || echo "N/A")
                    altitude=$(echo "$node_info" | jq -r '.metadata.labels["phone.location/altitude"] // "N/A"' 2>/dev/null || echo "N/A")
                    city=$(echo "$node_info" | jq -r '.metadata.labels["phone.location/city"] // "Unknown"' 2>/dev/null || echo "Unknown")
                    updated=$(echo "$node_info" | jq -r '.metadata.labels["phone.location/updated"] // "Never"' 2>/dev/null || echo "Never")
                    
                    # Format coordinates
                    if [ "$latitude" != "N/A" ] && [ "$longitude" != "N/A" ]; then
                        latitude=$(printf "%.6f" "$latitude" 2>/dev/null || echo "$latitude")
                        longitude=$(printf "%.6f" "$longitude" 2>/dev/null || echo "$longitude")
                    fi
                    
                    # Format altitude
                    if [ "$altitude" != "N/A" ]; then
                        altitude=$(printf "%.1fm" "$altitude" 2>/dev/null || echo "$altitude")
                    fi
                    
                    # Truncate long city names
                    if [ ${#city} -gt 18 ]; then
                        city="${city:0:15}..."
                    fi
                    
                    # Format timestamp
                    if [ "$updated" != "Never" ] && [ "$updated" != "N/A" ]; then
                        updated=$(date -d "$updated" "+%H:%M:%S" 2>/dev/null || echo "$updated")
                    fi
                    
                    printf "%-20s %-12s %-13s %-10s %-20s %-15s\n" "$node" "$latitude" "$longitude" "$altitude" "$city" "$updated"
                fi
            fi
        done
        echo ""
    fi
}

# Show object detection results
show_object_detection() {
    if [ "$SHOW_OBJECT_DETECTION" = true ]; then
        log_header "📹 Object Detection (Front & Rear Cameras)"
        echo ""
        
        # Get all nodes with phone labels
        local phone_nodes
        phone_nodes=$(kubectl get nodes -o json 2>/dev/null | jq -r '.items[] | select(.metadata.labels["device-type"] == "phone") | .metadata.name' 2>/dev/null || echo "")
        
        if [ -z "$phone_nodes" ]; then
            echo "No phone nodes found in cluster"
            echo ""
            return
        fi
        
        # Table header
        printf "%-20s %-10s %-50s %-15s\n" "NODE" "CAMERA" "DETECTED OBJECTS" "TIMESTAMP"
        printf "%-20s %-10s %-50s %-15s\n" "----" "------" "----------------" "---------"
        
        # Get object detection data for each phone node
        echo "$phone_nodes" | while read -r node; do
            if [ -n "$node" ]; then
                # Check if node has forwarded port or direct access
                local api_base=""
                if command -v curl >/dev/null 2>&1; then
                    # Try localhost first (if port forwarding is set up)
                    if curl -s --connect-timeout 2 "http://localhost:8005/status" >/dev/null 2>&1; then
                        api_base="http://localhost:8005"
                    else
                        # Try direct node access
                        api_base="http://$node:8005"
                    fi
                    
                    # Get front camera detection
                    local front_result
                    front_result=$(curl -s --connect-timeout 5 --max-time 10 "$api_base/object-detection?camera=front" 2>/dev/null || echo "")
                    if [ -n "$front_result" ]; then
                        local front_objects front_timestamp
                        front_objects=$(echo "$front_result" | jq -r '[.objects[]?.class] | join(", ")' 2>/dev/null || echo "Error")
                        front_timestamp=$(echo "$front_result" | jq -r '.timestamp' 2>/dev/null | xargs -I {} date -d {} "+%H:%M:%S" 2>/dev/null || echo "Unknown")
                        
                        if [ -z "$front_objects" ] || [ "$front_objects" = "null" ]; then
                            front_objects="No objects detected"
                        fi
                        
                        # Truncate long object lists
                        if [ ${#front_objects} -gt 48 ]; then
                            front_objects="${front_objects:0:45}..."
                        fi
                        
                        printf "%-20s %-10s %-50s %-15s\n" "$node" "Front" "$front_objects" "$front_timestamp"
                    else
                        printf "%-20s %-10s %-50s %-15s\n" "$node" "Front" "API unavailable" "---"
                    fi
                    
                    # Get rear camera detection
                    local rear_result
                    rear_result=$(curl -s --connect-timeout 5 --max-time 10 "$api_base/object-detection?camera=rear" 2>/dev/null || echo "")
                    if [ -n "$rear_result" ]; then
                        local rear_objects rear_timestamp
                        rear_objects=$(echo "$rear_result" | jq -r '[.objects[]?.class] | join(", ")' 2>/dev/null || echo "Error")
                        rear_timestamp=$(echo "$rear_result" | jq -r '.timestamp' 2>/dev/null | xargs -I {} date -d {} "+%H:%M:%S" 2>/dev/null || echo "Unknown")
                        
                        if [ -z "$rear_objects" ] || [ "$rear_objects" = "null" ]; then
                            rear_objects="No objects detected"
                        fi
                        
                        # Truncate long object lists
                        if [ ${#rear_objects} -gt 48 ]; then
                            rear_objects="${rear_objects:0:45}..."
                        fi
                        
                        printf "%-20s %-10s %-50s %-15s\n" "$node" "Rear" "$rear_objects" "$rear_timestamp"
                    else
                        printf "%-20s %-10s %-50s %-15s\n" "$node" "Rear" "API unavailable" "---"
                    fi
                else
                    printf "%-20s %-10s %-50s %-15s\n" "$node" "Front" "curl not available" "---"
                    printf "%-20s %-10s %-50s %-15s\n" "$node" "Rear" "curl not available" "---"
                fi
            fi
        done
        echo ""
    fi
}

# Show LLM scene descriptions
show_llm_descriptions() {
    if [ "$SHOW_LLM" = true ]; then
        log_header "🤖 LLM Scene Descriptions (Rear Cameras)"
        echo ""
        
        # Get all nodes with phone labels
        local phone_nodes
        phone_nodes=$(kubectl get nodes -o json 2>/dev/null | jq -r '.items[] | select(.metadata.labels["device-type"] == "phone") | .metadata.name' 2>/dev/null || echo "")
        
        if [ -z "$phone_nodes" ]; then
            echo "No phone nodes found in cluster"
            echo ""
            return
        fi
        
        # Table header
        printf "%-20s %-70s %-15s\n" "NODE" "SCENE DESCRIPTION" "TIMESTAMP"
        printf "%-20s %-70s %-15s\n" "----" "-----------------" "---------"
        
        # Get LLM descriptions for each phone node (rear camera only)
        echo "$phone_nodes" | while read -r node; do
            if [ -n "$node" ]; then
                # Check if node has forwarded port or direct access
                local api_base=""
                if command -v curl >/dev/null 2>&1; then
                    # Try localhost first (if port forwarding is set up)
                    if curl -s --connect-timeout 2 "http://localhost:8005/status" >/dev/null 2>&1; then
                        api_base="http://localhost:8005"
                    else
                        # Try direct node access
                        api_base="http://$node:8005"
                    fi
                    
                    # Make AI text request with rear camera
                    local ai_request='{"text":"Describe the scene in one sentence","model":"","temperature":0.7,"topK":40,"captureConfig":{"camera":"rear"}}'
                    local ai_result
                    ai_result=$(curl -s --connect-timeout 10 --max-time 30 -X POST -H "Content-Type: application/json" -d "$ai_request" "$api_base/ai/text" 2>/dev/null || echo "")
                    
                    if [ -n "$ai_result" ]; then
                        local description timestamp
                        description=$(echo "$ai_result" | jq -r '.response // .text // "No description available"' 2>/dev/null || echo "Parse error")
                        timestamp=$(date "+%H:%M:%S")
                        
                        # Clean up description - remove quotes and newlines
                        description=$(echo "$description" | tr -d '"' | tr '\n' ' ' | sed 's/  */ /g' | xargs)
                        
                        # Truncate long descriptions
                        if [ ${#description} -gt 68 ]; then
                            description="${description:0:65}..."
                        fi
                        
                        printf "%-20s %-70s %-15s\n" "$node" "$description" "$timestamp"
                    else
                        printf "%-20s %-70s %-15s\n" "$node" "AI API unavailable" "---"
                    fi
                else
                    printf "%-20s %-70s %-15s\n" "$node" "curl not available" "---"
                fi
            fi
        done
        echo ""
    fi
}

# Main status function
show_status() {
    if [ "$WATCH_MODE" = false ]; then
        echo "K3s Cluster Status - $(date)"
        echo "========================================"
        echo ""
    fi
    
    show_cluster_info
    show_nodes
    show_locations
    show_pods
    show_services
    show_workloads
    show_events
    show_object_detection
    show_llm_descriptions
    
    if [ "$WATCH_MODE" = true ]; then
        echo ""
        echo "Last updated: $(date) | Press Ctrl+C to exit"
        echo "========================================"
    fi
}

# Clear screen for watch mode
clear_screen() {
    if [ "$WATCH_MODE" = true ]; then
        clear
    fi
}

# Parse arguments
parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -n|--namespace)
                NAMESPACE="$2"
                shift 2
                ;;
            -s|--system)
                SHOW_SYSTEM=true
                shift
                ;;
            -w|--watch)
                WATCH_MODE=true
                shift
                ;;
            -i|--interval)
                REFRESH_INTERVAL="$2"
                shift 2
                ;;
            -v|--verbose)
                VERBOSE=true
                shift
                ;;
            --object-detection)
                SHOW_OBJECT_DETECTION=true
                shift
                ;;
            --llm)
                SHOW_LLM=true
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            --version)
                show_version
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                echo ""
                show_help
                exit 1
                ;;
        esac
    done
}

# Main function
main() {
    parse_arguments "$@"
    
    # Check cluster access
    check_cluster_access
    
    if [ "$WATCH_MODE" = true ]; then
        # Watch mode
        while true; do
            clear_screen
            show_status
            sleep "$REFRESH_INTERVAL"
        done
    else
        # Single run
        show_status
    fi
}

# Handle Ctrl+C gracefully in watch mode
trap 'echo -e "\n\nExiting..."; exit 0' INT

# Run main function
main "$@"
