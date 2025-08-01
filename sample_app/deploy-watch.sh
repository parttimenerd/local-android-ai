#!/bin/bash

# Deploy Watch script for the server info application
# This script monitors for image changes and new agent nodes, then automatically redeploys

set -e

# Determine script directory and change to it
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_watch() { echo -e "${BLUE}[WATCH]${NC} $1"; }

# Configuration
WATCH_INTERVAL=10  # seconds
IMAGE_CHECK_INTERVAL=30  # seconds
NODE_CHECK_INTERVAL=60   # seconds

# State tracking
LAST_IMAGE_ID=""
LAST_AGENT_NODE_COUNT=0
LAST_DEPLOYMENT_TIME=0
DEPLOYMENT_IN_PROGRESS=false

# Show usage information
show_usage() {
    cat << EOF
Usage: $0 [options]

Deploy Watch - Monitor and auto-deploy on changes

OPTIONS:
    -i, --interval SECONDS      Watch interval in seconds (default: $WATCH_INTERVAL)
    --image-interval SECONDS    Image check interval (default: $IMAGE_CHECK_INTERVAL)
    --node-interval SECONDS     Node check interval (default: $NODE_CHECK_INTERVAL)
    -h, --help                  Show this help message

EXAMPLES:
    $0                          # Start watching with default settings
    $0 -i 5                     # Check every 5 seconds
    $0 --image-interval 60      # Check images every 60 seconds

MONITORING:
    • Docker image changes (rebuild detection)
    • New agent nodes joining the cluster
    • Registry image updates
    • Deployment health status

CONTROLS:
    Ctrl+C                      Stop watching
    
EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -i|--interval)
            WATCH_INTERVAL="$2"
            shift 2
            ;;
        --image-interval)
            IMAGE_CHECK_INTERVAL="$2"
            shift 2
            ;;
        --node-interval)
            NODE_CHECK_INTERVAL="$2"
            shift 2
            ;;
        -h|--help)
            show_usage
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
done

# Validate intervals
if ! [[ "$WATCH_INTERVAL" =~ ^[0-9]+$ ]] || [ "$WATCH_INTERVAL" -lt 1 ]; then
    log_error "Watch interval must be a positive integer"
    exit 1
fi

if ! [[ "$IMAGE_CHECK_INTERVAL" =~ ^[0-9]+$ ]] || [ "$IMAGE_CHECK_INTERVAL" -lt 1 ]; then
    log_error "Image check interval must be a positive integer"
    exit 1
fi

if ! [[ "$NODE_CHECK_INTERVAL" =~ ^[0-9]+$ ]] || [ "$NODE_CHECK_INTERVAL" -lt 1 ]; then
    log_error "Node check interval must be a positive integer"
    exit 1
fi

# Check dependencies
check_dependencies() {
    local missing=()
    
    if ! command -v kubectl &>/dev/null; then
        missing+=("kubectl")
    fi
    
    if ! command -v docker &>/dev/null; then
        missing+=("docker")
    fi
    
    if [ ! -f "./deploy.sh" ]; then
        missing+=("deploy.sh")
    fi
    
    if [ ! -f "../registry.sh" ]; then
        missing+=("../registry.sh")
    fi
    
    if [ ${#missing[@]} -gt 0 ]; then
        log_error "Missing required dependencies: ${missing[*]}"
        exit 1
    fi
}

# Get current image ID from Docker
get_local_image_id() {
    docker images server-info-server:latest --format "{{.ID}}" 2>/dev/null || echo ""
}

# Get current image ID from registry
get_registry_image_id() {
    if [ -f "../registry.sh" ]; then
        # Get image digest from registry
        ../registry.sh list 2>/dev/null | grep "server-info-server" | head -n1 | awk '{print $3}' || echo ""
    else
        echo ""
    fi
}

# Get current agent node count
get_agent_node_count() {
    kubectl get nodes --no-headers 2>/dev/null | grep -v "control-plane\|master" | wc -l || echo "0"
}

# Check if deployment is healthy
is_deployment_healthy() {
    local desired_replicas available_replicas ready_replicas
    
    desired_replicas=$(kubectl get deployment server-info-server -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "0")
    available_replicas=$(kubectl get deployment server-info-server -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "0")
    ready_replicas=$(kubectl get deployment server-info-server -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
    
    [ "$desired_replicas" -eq "$available_replicas" ] && [ "$desired_replicas" -eq "$ready_replicas" ] && [ "$desired_replicas" -gt 0 ]
}

# Trigger deployment
trigger_deployment() {
    local reason="$1"
    local current_time
    current_time=$(date +%s)
    
    if [ "$DEPLOYMENT_IN_PROGRESS" = true ]; then
        log_warn "Deployment already in progress, skipping..."
        return
    fi
    
    # Cooldown check - avoid rapid re-deployments
    if [ $((current_time - LAST_DEPLOYMENT_TIME)) -lt 30 ]; then
        log_warn "Deployment cooldown active, skipping..."
        return
    fi
    
    log_success "🚀 Triggering deployment: $reason"
    DEPLOYMENT_IN_PROGRESS=true
    LAST_DEPLOYMENT_TIME=$current_time
    
    # Run deployment in background to avoid blocking watch loop
    (
        log_info "Starting deployment..."
        if REPLICAS="$LAST_AGENT_NODE_COUNT" ./deploy.sh; then
            log_success "✅ Deployment completed successfully"
        else
            log_error "❌ Deployment failed"
        fi
        DEPLOYMENT_IN_PROGRESS=false
    ) &
    
    # Wait a bit to avoid immediate re-triggering
    sleep 5
}

# Initialize state
initialize_state() {
    log_info "Initializing watch state..."
    
    LAST_IMAGE_ID=$(get_local_image_id)
    LAST_AGENT_NODE_COUNT=$(get_agent_node_count)
    LAST_DEPLOYMENT_TIME=$(date +%s)
    
    log_info "Initial state:"
    log_info "  • Local image ID: ${LAST_IMAGE_ID:-'none'}"
    log_info "  • Agent nodes: $LAST_AGENT_NODE_COUNT"
    log_info "  • Watch interval: ${WATCH_INTERVAL}s"
    log_info "  • Image check interval: ${IMAGE_CHECK_INTERVAL}s"
    log_info "  • Node check interval: ${NODE_CHECK_INTERVAL}s"
}

# Main watch loop
watch_loop() {
    local cycle_count=0
    local last_image_check=0
    local last_node_check=0
    local current_time
    
    log_watch "🔍 Starting watch loop..."
    echo "Press Ctrl+C to stop watching"
    echo ""
    
    while true; do
        current_time=$(date +%s)
        cycle_count=$((cycle_count + 1))
        
        # Check for image changes
        if [ $((current_time - last_image_check)) -ge "$IMAGE_CHECK_INTERVAL" ]; then
            current_image_id=$(get_local_image_id)
            
            if [ -n "$current_image_id" ] && [ "$current_image_id" != "$LAST_IMAGE_ID" ]; then
                log_watch "📦 Image change detected: $LAST_IMAGE_ID → $current_image_id"
                LAST_IMAGE_ID="$current_image_id"
                trigger_deployment "Image updated"
            fi
            
            last_image_check=$current_time
        fi
        
        # Check for new agent nodes
        if [ $((current_time - last_node_check)) -ge "$NODE_CHECK_INTERVAL" ]; then
            current_agent_count=$(get_agent_node_count)
            
            if [ "$current_agent_count" -ne "$LAST_AGENT_NODE_COUNT" ]; then
                log_watch "🔗 Agent node count changed: $LAST_AGENT_NODE_COUNT → $current_agent_count"
                LAST_AGENT_NODE_COUNT="$current_agent_count"
                trigger_deployment "Agent nodes changed"
            fi
            
            last_node_check=$current_time
        fi
        
        # Check deployment health every few cycles
        if [ $((cycle_count % 6)) -eq 0 ]; then
            if kubectl get deployment server-info-server &>/dev/null; then
                if ! is_deployment_healthy; then
                    log_warn "🏥 Deployment appears unhealthy, checking..."
                    
                    # Wait a bit and check again
                    sleep 10
                    if ! is_deployment_healthy; then
                        log_warn "Deployment still unhealthy, triggering redeploy..."
                        trigger_deployment "Health check failed"
                    fi
                fi
            else
                log_warn "📋 No deployment found, triggering initial deploy..."
                trigger_deployment "Initial deployment"
            fi
        fi
        
        # Status update every 30 seconds
        if [ $((cycle_count % 3)) -eq 0 ]; then
            current_agent_count=$(get_agent_node_count)
            if kubectl get deployment server-info-server &>/dev/null; then
                replicas=$(kubectl get deployment server-info-server -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
                log_watch "💓 Status: $replicas pods running on $current_agent_count agent nodes"
            else
                log_watch "💓 Status: No deployment found, $current_agent_count agent nodes available"
            fi
        fi
        
        sleep "$WATCH_INTERVAL"
    done
}

# Cleanup function
cleanup() {
    log_warn "🛑 Stopping watch mode..."
    
    # Kill any background deployment processes
    if [ "$DEPLOYMENT_IN_PROGRESS" = true ]; then
        log_info "Waiting for deployment to complete..."
        wait
    fi
    
    log_success "Watch mode stopped"
    exit 0
}

# Set up signal handlers
trap cleanup SIGINT SIGTERM

# Main execution
main() {
    log_info "🚀 Deploy Watch - Automatic deployment monitor"
    log_info "Working directory: $SCRIPT_DIR"
    echo ""
    
    # Check dependencies
    check_dependencies
    
    # Check cluster connectivity
    if ! kubectl cluster-info &>/dev/null; then
        log_error "Cannot connect to Kubernetes cluster"
        log_info "Make sure you have proper kubeconfig or run from the K3s server node"
        exit 1
    fi
    
    # Initialize state
    initialize_state
    echo ""
    
    # Start watch loop
    watch_loop
}

# Run main function
main "$@"
