#!/bin/bash

# Scale script for the hostname server application
# This script scales the deployment to a specified number of replicas

set -e

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

# Show usage information
show_usage() {
    echo "Usage: $0 <replicas> [options]"
    echo ""
    echo "Scale the hostname server deployment to the specified number of replicas."
    echo ""
    echo "Arguments:"
    echo "  <replicas>    Number of replicas to scale to (1-20)"
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

log_info "Scaling hostname server to $REPLICAS replicas..."

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

# Check if the deployment exists
if ! kubectl get deployment hostname-server &> /dev/null; then
    log_error "hostname-server deployment not found"
    log_info "Run './deploy.sh' first to deploy the application"
    exit 1
fi

# Get current replica count
CURRENT_REPLICAS=$(kubectl get deployment hostname-server -o jsonpath='{.spec.replicas}')
log_info "Current replicas: $CURRENT_REPLICAS"

if [ "$CURRENT_REPLICAS" -eq "$REPLICAS" ]; then
    log_info "Deployment is already scaled to $REPLICAS replicas"
    exit 0
fi

# Scale the deployment
log_info "Scaling deployment from $CURRENT_REPLICAS to $REPLICAS replicas..."
kubectl scale deployment hostname-server --replicas="$REPLICAS" || {
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
        READY_REPLICAS=$(kubectl get deployment hostname-server -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
        AVAILABLE_REPLICAS=$(kubectl get deployment hostname-server -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "0")
        
        if [ "$READY_REPLICAS" -eq "$REPLICAS" ] && [ "$AVAILABLE_REPLICAS" -eq "$REPLICAS" ]; then
            log_success "Scaling completed successfully!"
            break
        fi
        
        log_info "Still scaling... ($READY_REPLICAS/$REPLICAS ready, ${ELAPSED}s elapsed)"
        kubectl get pods -l app=hostname-server --no-headers 2>/dev/null || true
        
        # Check for problematic pods
        PROBLEM_PODS=$(kubectl get pods -l app=hostname-server --no-headers 2>/dev/null | grep -E "(Error|CrashLoopBackOff|ImagePullBackOff|ErrImagePull)" || echo "")
        if [ -n "$PROBLEM_PODS" ]; then
            log_warn "Found problematic pods during scaling:"
            echo "$PROBLEM_PODS"
        fi
        
        sleep $INTERVAL
        ELAPSED=$((ELAPSED + INTERVAL))
    done
    
    # Final check after timeout
    if [ $ELAPSED -ge $TIMEOUT ]; then
        READY_REPLICAS=$(kubectl get deployment hostname-server -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
        if [ "$READY_REPLICAS" -ne "$REPLICAS" ]; then
            log_warn "Scaling did not complete within timeout"
            log_info "Current status: $READY_REPLICAS/$REPLICAS replicas ready"
            kubectl get pods -l app=hostname-server
        fi
    fi
fi

# Show final status
log_info "Final deployment status:"
kubectl get deployment hostname-server
kubectl get pods -l app=hostname-server -o wide

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
echo "  Check status:   kubectl get deployment hostname-server"
echo "  View pods:      kubectl get pods -l app=hostname-server"
echo "  View logs:      kubectl logs -l app=hostname-server"
echo "  Test app:       ./test.sh"
echo ""
