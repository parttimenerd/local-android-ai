#!/bin/bash

# Scale script for the server-info-server application
# This script scales the deployment to a specified number of replicas

set -e

# Determine script directory and change to it
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Logging functions
log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }

# Deployment name
DEPLOYMENT_NAME="server-info-server"

# Show usage information
show_usage() {
    echo "Usage: $0 <replicas> [options]"
    echo ""
    echo "Scale the server-info-server deployment to the specified number of replicas."
    echo ""
    echo "Arguments:"
    echo "  <replicas>    Number of replicas to scale to (1-50)"
    echo ""
    echo "Options:"
    echo "  -w, --wait    Wait for scaling to complete before exiting"
    echo "  -t, --test    Run a quick test after scaling"
    echo "  -h, --help    Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 3              # Scale to 3 replicas"
    echo "  $0 5 --wait       # Scale to 5 replicas and wait for completion"
    echo "  $0 2 --wait --test # Scale to 2 replicas, wait, and test"
    echo ""
    echo "Notes:"
    echo "  • Multiple pods can run on the same agent node"
    echo "  • Pods will only be scheduled on agent nodes (not master/control-plane)"
    echo "  • Use 'kubectl get nodes' to see available nodes"
    echo ""
}

# Parse command line arguments
REPLICAS=""
WAIT_FOR_SCALING=false
RUN_TEST=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -w|--wait)
            WAIT_FOR_SCALING=true
            shift
            ;;
        -t|--test)
            RUN_TEST=true
            shift
            ;;
        -h|--help)
            show_usage
            exit 0
            ;;
        -*)
            log_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
        *)
            if [ -z "$REPLICAS" ]; then
                REPLICAS="$1"
            else
                log_error "Too many arguments"
                show_usage
                exit 1
            fi
            shift
            ;;
    esac
done

# Validate arguments
if [ -z "$REPLICAS" ]; then
    log_error "Missing required argument: <replicas>"
    show_usage
    exit 1
fi

# Validate replica count
if ! [[ "$REPLICAS" =~ ^[0-9]+$ ]]; then
    log_error "Replicas must be a positive integer"
    exit 1
fi

if [ "$REPLICAS" -lt 1 ] || [ "$REPLICAS" -gt 20 ]; then
    log_error "Replicas must be between 1 and 20"
    exit 1
fi

log_info "Scaling server-info-server to $REPLICAS replicas..."

# Check if kubectl is available
if ! command -v kubectl &> /dev/null; then
    log_error "kubectl is not installed or not in PATH"
    log_info "On the K3s server, you can use: sudo k3s kubectl"
    exit 1
fi

# Check if we can connect to the cluster
if ! kubectl cluster-info &> /dev/null; then
    log_error "Cannot connect to Kubernetes cluster"
    log_info "Make sure you have proper kubeconfig or run from the K3s server node"
    exit 1
fi

# Get agent node information
AGENT_NODE_COUNT=$(kubectl get nodes --no-headers | grep -v "control-plane\|master" | wc -l)
log_info "Found $AGENT_NODE_COUNT agent nodes in the cluster"

if [ "$AGENT_NODE_COUNT" -eq 0 ]; then
    log_warn "No agent nodes found. Pods may be scheduled on master nodes."
    log_info "Check node labels with: kubectl get nodes --show-labels"
fi

# Check if the deployment exists
if ! kubectl get deployment "$DEPLOYMENT_NAME" &> /dev/null; then
    log_error "$DEPLOYMENT_NAME deployment not found"
    log_info "Run './deploy.sh' first to deploy the application"
    exit 1
fi

# Get current replica count
CURRENT_REPLICAS=$(kubectl get deployment "$DEPLOYMENT_NAME" -o jsonpath='{.spec.replicas}')
log_info "Current replicas: $CURRENT_REPLICAS"

if [ "$CURRENT_REPLICAS" -eq "$REPLICAS" ]; then
    log_info "Deployment is already scaled to $REPLICAS replicas"
    exit 0
fi

# Show scaling information
if [ "$AGENT_NODE_COUNT" -gt 0 ]; then
    PODS_PER_NODE=$((REPLICAS / AGENT_NODE_COUNT))
    REMAINDER=$((REPLICAS % AGENT_NODE_COUNT))
    
    if [ "$PODS_PER_NODE" -gt 0 ]; then
        log_info "This will result in approximately $PODS_PER_NODE pods per agent node"
        if [ "$REMAINDER" -gt 0 ]; then
            log_info "With $REMAINDER additional pods on some nodes"
        fi
    else
        log_info "This will result in $REPLICAS pods distributed across $AGENT_NODE_COUNT agent nodes"
    fi
fi

# Scale the deployment
log_info "Scaling deployment from $CURRENT_REPLICAS to $REPLICAS replicas..."
kubectl scale deployment "$DEPLOYMENT_NAME" --replicas="$REPLICAS" || {
    log_error "Failed to scale deployment"
    exit 1
}

log_success "Scaling command issued successfully"

# Wait for scaling to complete if requested
if [ "$WAIT_FOR_SCALING" = true ]; then
    log_info "Waiting for scaling to complete..."
    
    TIMEOUT=120
    ELAPSED=0
    INTERVAL=5
    
    while [ $ELAPSED -lt $TIMEOUT ]; do
        READY_REPLICAS=$(kubectl get deployment "$DEPLOYMENT_NAME" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
        AVAILABLE_REPLICAS=$(kubectl get deployment "$DEPLOYMENT_NAME" -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "0")
        
        if [ "$READY_REPLICAS" -eq "$REPLICAS" ] && [ "$AVAILABLE_REPLICAS" -eq "$REPLICAS" ]; then
            log_success "Scaling completed successfully!"
            break
        fi
        
        log_info "Still scaling... ($READY_REPLICAS/$REPLICAS ready, ${ELAPSED}s elapsed)"
        kubectl get pods -l app="$DEPLOYMENT_NAME" --no-headers 2>/dev/null || true
        
        # Check for problematic pods
        PROBLEM_PODS=$(kubectl get pods -l app="$DEPLOYMENT_NAME" --no-headers 2>/dev/null | grep -E "(Error|CrashLoopBackOff|ImagePullBackOff|ErrImagePull)" || echo "")
        if [ -n "$PROBLEM_PODS" ]; then
            log_warn "Found problematic pods during scaling:"
            echo "$PROBLEM_PODS"
        fi
        
        sleep $INTERVAL
        ELAPSED=$((ELAPSED + INTERVAL))
    done
    
    # Final check after timeout
    if [ $ELAPSED -ge $TIMEOUT ]; then
        READY_REPLICAS=$(kubectl get deployment "$DEPLOYMENT_NAME" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
        if [ "$READY_REPLICAS" -ne "$REPLICAS" ]; then
            log_warn "Scaling did not complete within timeout"
            log_info "Current status: $READY_REPLICAS/$REPLICAS replicas ready"
            kubectl get pods -l app="$DEPLOYMENT_NAME"
        fi
    fi
fi

# Show final status
log_info "Final deployment status:"
kubectl get deployment "$DEPLOYMENT_NAME"
kubectl get pods -l app="$DEPLOYMENT_NAME" -o wide

# Run test if requested
if [ "$RUN_TEST" = true ]; then
    if [ -f "./test.sh" ]; then
        echo ""
        log_info "Running application test..."
        ./test.sh
    else
        log_warn "test.sh not found in current directory"
    fi
fi

echo ""
log_success "Scaling operation completed!"
echo ""
log_info "Useful commands:"
echo "  Check status:   kubectl get deployment $DEPLOYMENT_NAME"
echo "  View pods:      kubectl get pods -l app=$DEPLOYMENT_NAME"
echo "  View logs:      kubectl logs -l app=$DEPLOYMENT_NAME"
echo "  Test app:       ./test.sh"
echo ""
