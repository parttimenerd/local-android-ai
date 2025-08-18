#!/bin/bash

# Deploy script for the server info application
# This script deploys the application to the K3s cluster

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

log_info "Deploying server info application to Kubernetes..."
log_info "Working directory: $SCRIPT_DIR"

# Function to check and setup registry if needed
check_and_setup_registry() {
    log_info "Checking if local registry is available..."
    
    local registry_script="../registry.sh"
    if [ ! -f "$registry_script" ]; then
        log_error "Registry management script not found: $registry_script"
        return 1
    fi
    
    # Check if registry is running
    if ! "$registry_script" status | grep -q "Registry is running"; then
        log_warn "Local registry not running. Setting up registry..."
        
        if "$registry_script" setup; then
            log_success "Registry setup completed"
        else
            log_error "Failed to setup registry"
            return 1
        fi
    else
        log_info "✅ Local registry is already running"
    fi
    
    # Ensure image is in registry
    log_info "Checking if server-info-server image is in registry..."
    if ! "$registry_script" list | grep -q "server-info-server"; then
        log_warn "server-info-server image not found in registry"
        log_info "Building and pushing image to registry..."
        
        # Build the image if it doesn't exist locally
        if ! docker images server-info-server:latest --format "table {{.Repository}}:{{.Tag}}" | grep -q "server-info-server:latest"; then
            log_info "Building local image first..."
            if [ -f "./build.sh" ]; then
                ./build.sh
            else
                log_error "build.sh not found. Please build the image first."
                return 1
            fi
        fi
        
        # Push to registry
        if "$registry_script" push server-info-server:latest; then
            log_success "Image pushed to registry"
        else
            log_error "Failed to push image to registry"
            return 1
        fi
    else
        log_info "✅ server-info-server image found in registry"
    fi
}

# Function to cleanup previous deployment
cleanup_previous_deployment() {
    log_info "Checking for existing deployment..."
    
    # Check if deployment exists
    if kubectl get deployment server-info-server &> /dev/null; then
        log_warn "Found existing deployment: server-info-server"
        
        # Ask for confirmation
        read -p "Do you want to remove the existing deployment before deploying? [y/N]: " -n 1 -r
        echo
        
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            log_info "Removing existing deployment and services..."
            
            # Remove deployment
            kubectl delete deployment server-info-server --ignore-not-found=true || true
            
            # Remove services
            kubectl delete service server-info-server --ignore-not-found=true || true
            kubectl delete service server-info-server-nodeport --ignore-not-found=true || true
            
            # Wait for pods to terminate
            log_info "Waiting for pods to terminate..."
            kubectl wait --for=delete pods -l app=server-info-server --timeout=60s || true
            
            log_success "Previous deployment cleaned up"
        else
            log_info "Keeping existing deployment. This may cause conflicts."
        fi
    else
        log_info "No existing deployment found"
    fi
}

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

# Cleanup previous deployment if it exists
cleanup_previous_deployment

# Check and setup registry if needed
check_and_setup_registry

# Get registry address early for consistent use
REGISTRY_ADDRESS=""
if [ -f "../registry.sh" ]; then
    REGISTRY_ADDRESS=$(../registry.sh address 2>/dev/null || echo "localhost:5000")
    log_info "Using registry address: $REGISTRY_ADDRESS"
else
    REGISTRY_ADDRESS="localhost:5000"
    log_warn "Registry script not found, using default: $REGISTRY_ADDRESS"
fi

# Get number of phone nodes for replica scaling (nodes with names starting with "phone-")
PHONE_NODE_COUNT=$(kubectl get nodes --no-headers | grep "^phone-" | wc -l)
log_info "Found $PHONE_NODE_COUNT phone nodes in the cluster (names starting with 'phone-')"

# Show available phone nodes
if [ "$PHONE_NODE_COUNT" -gt 0 ]; then
    log_info "Available phone nodes:"
    kubectl get nodes --no-headers | grep "^phone-" | awk '{print "  - " $1 " (" $2 ")"}'
fi

# Also check for nodes with device-type=phone label as backup
LABELED_PHONE_COUNT=$(kubectl get nodes -l device-type=phone --no-headers 2>/dev/null | wc -l || echo "0")
if [ "$LABELED_PHONE_COUNT" -gt 0 ]; then
    log_info "Found $LABELED_PHONE_COUNT nodes with device-type=phone label"
fi

if [ "$PHONE_NODE_COUNT" -eq 0 ]; then
    if [ "$LABELED_PHONE_COUNT" -gt 0 ]; then
        log_warn "No nodes with names starting with 'phone-' found, but found $LABELED_PHONE_COUNT labeled phone nodes"
        PHONE_NODE_COUNT="$LABELED_PHONE_COUNT"
    else
        log_warn "No phone nodes found (neither by name pattern nor device-type label)"
        log_info "Check available nodes with: kubectl get nodes --show-labels"
        # Fall back to agent nodes
        AGENT_NODE_COUNT=$(kubectl get nodes --no-headers | grep -v "control-plane\|master" | wc -l)
        PHONE_NODE_COUNT="$AGENT_NODE_COUNT"
        log_warn "Using agent node count ($PHONE_NODE_COUNT) as fallback"
    fi
fi

# Default to 1 replica per phone node, but allow override via environment variable
DEFAULT_REPLICAS="$PHONE_NODE_COUNT"
DESIRED_REPLICAS="${REPLICAS:-$DEFAULT_REPLICAS}"

log_info "Scaling deployment to $DESIRED_REPLICAS replicas (default: 1 per phone node)"
log_info "To override replica count, set REPLICAS environment variable: REPLICAS=5 ./deploy.sh"

# Update deployment replicas to match desired count
if [ -f "k8s/deployment.yaml" ]; then
    # Create a temporary deployment file with the correct replica count and registry address
    sed -e "s/replicas: 1/replicas: $DESIRED_REPLICAS/" \
        -e "s|image: [^/]*:[0-9]*/server-info-server:latest|image: $REGISTRY_ADDRESS/server-info-server:latest|g" \
        k8s/deployment.yaml > /tmp/deployment-scaled.yaml
    log_info "Deploying $DESIRED_REPLICAS replicas across $PHONE_NODE_COUNT phone nodes"
    log_info "Using image: $REGISTRY_ADDRESS/server-info-server:latest"
else
    log_error "Deployment manifest not found at k8s/deployment.yaml"
    exit 1
fi

# Apply Kubernetes manifests
log_info "Applying Kubernetes manifests..."

# Apply RBAC configuration first (required for node-reader service account)
log_info "Applying RBAC configuration..."
kubectl apply -f k8s/node-reader-rbac.yaml || {
    log_error "Failed to apply RBAC configuration"
    exit 1
}

# Deploy the scaled deployment
kubectl apply -f /tmp/deployment-scaled.yaml || {
    log_error "Failed to apply deployment manifest"
    exit 1
}

# Deploy phone server configuration
log_info "Applying phone server configuration..."
kubectl apply -f k8s/phone-server.yaml || {
    log_error "Failed to apply phone server configuration"
    exit 1
}

# Deploy the service
kubectl apply -f k8s/service.yaml || {
    log_error "Failed to apply service manifest"
    exit 1
}

# Clean up temporary file
rm -f /tmp/deployment-scaled.yaml

log_success "Manifests applied successfully"

# Wait for deployment to be ready with regular monitoring
log_info "Waiting for deployment to be ready..."

TIMEOUT=300
ELAPSED=0
INTERVAL=10

while [ $ELAPSED -lt $TIMEOUT ]; do
    # Check if deployment is available
    if kubectl get deployment server-info-server -o jsonpath='{.status.conditions[?(@.type=="Available")].status}' 2>/dev/null | grep -q "True"; then
        log_success "Deployment is ready!"
        break
    fi
    
    # Show current status
    READY_PODS=$(kubectl get pods -l app=server-info-server --no-headers 2>/dev/null | grep -c "Running" || echo "0")
    TOTAL_PODS=$(kubectl get pods -l app=server-info-server --no-headers 2>/dev/null | wc -l || echo "0")
    
    log_info "Still waiting... ($READY_PODS/$TOTAL_PODS pods ready, ${ELAPSED}s elapsed)"
    kubectl get pods -l app=server-info-server --no-headers 2>/dev/null || true
    
    # Check for problematic pods
    PROBLEM_PODS=$(kubectl get pods -l app=server-info-server --no-headers 2>/dev/null | grep -E "(Error|CrashLoopBackOff|ImagePullBackOff|ErrImagePull)" || echo "")
    if [ -n "$PROBLEM_PODS" ]; then
        log_warn "Found problematic pods:"
        echo "$PROBLEM_PODS"
        log_warn "Getting detailed pod information..."
        kubectl describe pods -l app=server-info-server
        log_error "Deployment failed due to pod issues"
        exit 1
    fi
    
    sleep $INTERVAL
    ELAPSED=$((ELAPSED + INTERVAL))
done

# Final check after timeout
if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Deployment failed to become ready within 5 minutes"
    log_warn "Final pod status check..."
    kubectl get pods -l app=server-info-server
    kubectl describe pods -l app=server-info-server
    exit 1
fi

# Check deployment status
log_info "Checking deployment status..."
kubectl get deployment server-info-server
kubectl get pods -l app=server-info-server -o wide

# Get service information
log_info "Service information:"
kubectl get service server-info-server
kubectl get service server-info-server-nodeport

# Try to get the LoadBalancer IP (may take time on some systems)
log_info "Waiting for LoadBalancer IP (this may take a moment)..."
sleep 10

EXTERNAL_IP=$(kubectl get service server-info-server -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
if [ -n "$EXTERNAL_IP" ]; then
    log_success "LoadBalancer IP: $EXTERNAL_IP"
    log_success "Application accessible at: http://$EXTERNAL_IP:8080"
    log_success "Dashboard accessible at: http://$EXTERNAL_IP:8080/dashboard"
else
    # Get node IPs as fallback
    log_info "LoadBalancer IP not assigned yet. Using NodePort access:"
    kubectl get nodes -o wide
    log_success "Application accessible via NodePort at: http://<NODE-IP>:30080"
    log_success "Dashboard accessible via NodePort at: http://<NODE-IP>:30080/dashboard"
fi

echo ""
log_success "Deployment completed successfully!"
echo ""
log_info "Useful commands:"
echo "  Check pods:     kubectl get pods -l app=server-info-server"
echo "  View logs:      kubectl logs -l app=server-info-server"
echo "  Get services:   kubectl get service"
echo "  Scale app:      kubectl scale deployment server-info-server --replicas=<number>"
echo "  Delete app:     ./undeploy.sh"
echo ""
log_info "Run './test.sh' to test the deployed application"
