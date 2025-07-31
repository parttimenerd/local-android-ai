#!/bin/bash

# Deploy script for the server info application
# This script deploys the application to the K3s cluster

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

log_info "Deploying server info application to Kubernetes..."

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

# Check if the Docker image exists
if ! docker images server-info-server:latest | grep -q latest; then
    log_warn "Docker image not found locally. Building..."
    ./build.sh || {
        log_error "Failed to build image"
        exit 1
    }
fi

# Import Docker image into K3s containerd (required for imagePullPolicy: Never)
log_info "Importing Docker image into K3s containerd..."
if command -v k3s &> /dev/null; then
    # We're on the K3s server node
    docker save server-info-server:latest | sudo k3s ctr images import - || {
        log_error "Failed to import image into K3s containerd"
        exit 1
    }
    log_success "Image imported successfully into K3s"
else
    log_warn "k3s command not found. Make sure to run this on the K3s server node"
    log_info "Or manually import the image: docker save server-info-server:latest | sudo k3s ctr images import -"
fi

# Get number of nodes for replica scaling
NODE_COUNT=$(kubectl get nodes --no-headers | wc -l)
log_info "Found $NODE_COUNT nodes in the cluster"

# Update deployment replicas to match node count
if [ -f "k8s/deployment.yaml" ]; then
    # Create a temporary deployment file with the correct replica count
    sed "s/replicas: 1/replicas: $NODE_COUNT/" k8s/deployment.yaml > /tmp/deployment-scaled.yaml
    log_info "Scaling deployment to $NODE_COUNT replicas (one per node)"
else
    log_error "Deployment manifest not found at k8s/deployment.yaml"
    exit 1
fi

# Apply Kubernetes manifests
log_info "Applying Kubernetes manifests..."

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
