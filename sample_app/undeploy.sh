#!/bin/bash

# Undeploy script for the server info application
# This script removes the application from the K3s cluster

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

log_info "Removing server info application from Kubernetes..."

# Check if kubectl is available
if ! command -v kubectl &> /dev/null; then
    log_error "kubectl is not installed or not in PATH"
    log_info "On the K3s server, you can use: sudo k3s kubectl"
    exit 1
fi

# Check if we can connect to the cluster
if ! kubectl cluster-info &> /dev/null; then
    log_error "Cannot connect to Kubernetes cluster"
    exit 1
fi

# Delete the deployment and services (ignore if not found)
log_info "Deleting deployment and services..."
kubectl delete deployment server-info-server --ignore-not-found=true
kubectl delete service server-info-server server-info-server-nodeport --ignore-not-found=true

# Wait for pods to be terminated
log_info "Waiting for pods to be terminated..."

# Monitor pod deletion with regular status checks
TIMEOUT=60
ELAPSED=0
INTERVAL=5

while [ $ELAPSED -lt $TIMEOUT ]; do
    REMAINING_PODS=$(kubectl get pods -l app=server-info-server --no-headers 2>/dev/null | wc -l)
    
    if [ "$REMAINING_PODS" -eq 0 ]; then
        log_success "All pods terminated successfully"
        break
    fi
    
    log_info "Still waiting... ($REMAINING_PODS pods remaining, ${ELAPSED}s elapsed)"
    kubectl get pods -l app=server-info-server --no-headers 2>/dev/null || true
    
    sleep $INTERVAL
    ELAPSED=$((ELAPSED + INTERVAL))
done

# Final check after timeout
if [ $ELAPSED -ge $TIMEOUT ]; then
    REMAINING_PODS=$(kubectl get pods -l app=server-info-server --no-headers 2>/dev/null | wc -l)
    if [ "$REMAINING_PODS" -gt 0 ]; then
        log_warn "Timeout reached, $REMAINING_PODS pods may still be terminating"
        kubectl get pods -l app=server-info-server
    fi
fi

# Verify cleanup
REMAINING_PODS=$(kubectl get pods -l app=server-info-server --no-headers 2>/dev/null | wc -l)
if [ "$REMAINING_PODS" -eq 0 ]; then
    log_success "Application removed successfully"
else
    log_warn "$REMAINING_PODS pods still exist (may still be terminating)"
    kubectl get pods -l app=server-info-server
fi

# Optional: Remove the Docker image from local registry
read -p "Do you want to remove the Docker image as well? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    if docker images server-info-server:latest | grep -q latest; then
        log_info "Removing Docker image..."
        docker rmi server-info-server:latest || {
            log_warn "Failed to remove Docker image"
        }
        log_success "Docker image removed"
    else
        log_info "Docker image not found locally"
    fi
fi

echo ""
log_success "Cleanup completed!"
log_info "To redeploy the application, run './deploy.sh'"
