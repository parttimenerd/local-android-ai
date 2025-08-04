#!/bin/bash

# Deploy script for the reverse geocoder service
# This script deploys the geocoder to the K3s cluster

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

# Configuration
NAMESPACE="default"
APP_NAME="reverse-geocoder"
DEPLOYMENT_FILE="k8s/deployment.yaml"
SERVICE_FILE="k8s/service.yaml"

echo -e "${GREEN}[INFO]${NC} Deploying reverse geocoder service to K3s cluster..."
echo -e "${GREEN}[INFO]${NC} Working directory: $SCRIPT_DIR"
echo -e "${GREEN}[INFO]${NC} Namespace: $NAMESPACE"

# Check if required files exist
if [ ! -f "$DEPLOYMENT_FILE" ] || [ ! -f "$SERVICE_FILE" ]; then
    echo -e "${RED}[ERROR]${NC} Kubernetes manifests not found:"
    echo -e "${RED}[ERROR]${NC} - Deployment: $DEPLOYMENT_FILE"
    echo -e "${RED}[ERROR]${NC} - Service: $SERVICE_FILE"
    exit 1
fi

# Check if kubectl is available
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} kubectl is not installed or not in PATH"
    exit 1
fi

# Check if we can connect to the cluster
echo -e "${YELLOW}[CLUSTER]${NC} Checking cluster connectivity..."
if ! kubectl cluster-info &>/dev/null; then
    echo -e "${RED}[ERROR]${NC} Cannot connect to Kubernetes cluster"
    echo -e "${RED}[ERROR]${NC} Make sure K3s is running and kubectl is configured"
    exit 1
fi

# Show cluster info
CLUSTER_INFO=$(kubectl config current-context 2>/dev/null || echo "unknown")
NODE_COUNT=$(kubectl get nodes --no-headers 2>/dev/null | wc -l || echo "0")
echo -e "${GREEN}[CLUSTER]${NC} Connected to cluster: $CLUSTER_INFO"
echo -e "${GREEN}[CLUSTER]${NC} Nodes available: $NODE_COUNT"

# Check if deployment already exists
if kubectl get deployment "$APP_NAME" -n "$NAMESPACE" &>/dev/null; then
    echo -e "${YELLOW}[UPDATE]${NC} Deployment '$APP_NAME' already exists. Updating..."
    ACTION="updated"
else
    echo -e "${YELLOW}[DEPLOY]${NC} Creating new deployment '$APP_NAME'..."
    ACTION="deployed"
fi

# Apply the Kubernetes manifests
echo -e "${YELLOW}[APPLY]${NC} Applying deployment manifest..."
if kubectl apply -f "$DEPLOYMENT_FILE" -n "$NAMESPACE"; then
    echo -e "${GREEN}[SUCCESS]${NC} Deployment manifest applied"
else
    echo -e "${RED}[ERROR]${NC} Failed to apply deployment manifest"
    exit 1
fi

echo -e "${YELLOW}[APPLY]${NC} Applying service manifest..."
if kubectl apply -f "$SERVICE_FILE" -n "$NAMESPACE"; then
    echo -e "${GREEN}[SUCCESS]${NC} Service manifest applied"
else
    echo -e "${RED}[ERROR]${NC} Failed to apply service manifest"
    exit 1
fi

# Wait for deployment to be ready
echo -e "${YELLOW}[WAIT]${NC} Waiting for deployment to be ready..."
if kubectl wait --for=condition=available deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=120s; then
    echo -e "${GREEN}[SUCCESS]${NC} Deployment is ready"
else
    echo -e "${RED}[ERROR]${NC} Deployment failed to become ready within 120 seconds"
    echo -e "${YELLOW}[INFO]${NC} Checking deployment status..."
    kubectl describe deployment "$APP_NAME" -n "$NAMESPACE"
    exit 1
fi

# Get deployment and service status
echo ""
echo -e "${BLUE}[STATUS]${NC} Current deployment status:"
kubectl get deployment "$APP_NAME" -n "$NAMESPACE" -o wide

echo ""
echo -e "${BLUE}[STATUS]${NC} Pod status:"
kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" -o wide

echo ""
echo -e "${BLUE}[STATUS]${NC} Service status:"
kubectl get service "$APP_NAME" -n "$NAMESPACE" -o wide

# Get service endpoints
echo ""
echo -e "${BLUE}[ENDPOINTS]${NC} Service endpoints:"

# Cluster IP
CLUSTER_IP=$(kubectl get service "$APP_NAME" -n "$NAMESPACE" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || echo "unknown")
CLUSTER_PORT=$(kubectl get service "$APP_NAME" -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || echo "unknown")
echo -e "  ${YELLOW}•${NC} ClusterIP: http://$CLUSTER_IP:$CLUSTER_PORT"
echo -e "  ${YELLOW}•${NC} DNS: http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT"

# NodePort (if available)
NODE_PORT=$(kubectl get service "$APP_NAME" -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "")
if [ -n "$NODE_PORT" ] && [ "$NODE_PORT" != "null" ]; then
    echo -e "  ${YELLOW}•${NC} NodePort: http://<node-ip>:$NODE_PORT"
    
    # Try to get node IP
    NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "")
    if [ -n "$NODE_IP" ] && [ "$NODE_IP" != "null" ]; then
        echo -e "  ${YELLOW}•${NC} External access: http://$NODE_IP:$NODE_PORT"
    fi
fi

# Test the health endpoint
echo ""
echo -e "${YELLOW}[TEST]${NC} Testing health endpoint..."
HEALTH_URL="http://$CLUSTER_IP:$CLUSTER_PORT/health"

# Wait a moment for the service to be fully ready
sleep 5

if kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl -f -s "$HEALTH_URL" &>/dev/null; then
    echo -e "${GREEN}[SUCCESS]${NC} Health endpoint is responding"
else
    echo -e "${YELLOW}[WARN]${NC} Health endpoint test failed (service might still be starting)"
    echo -e "${YELLOW}[INFO]${NC} You can test manually: kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl '$HEALTH_URL'"
fi

# Show example usage
echo ""
echo -e "${GREEN}[COMPLETE]${NC} Reverse geocoder service $ACTION successfully!"
echo ""
echo -e "${BLUE}[USAGE]${NC} Example API calls:"
echo -e "  ${YELLOW}•${NC} Health check:"
echo -e "    kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/health'"
echo ""
echo -e "  ${YELLOW}•${NC} Reverse geocoding (hybrid method):"
echo -e "    kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/api/reverse-geocode?lat=51.5074&lon=-0.1278&method=hybrid'"
echo ""
echo -e "  ${YELLOW}•${NC} Reverse geocoding (geonames only):"
echo -e "    kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/api/reverse-geocode?lat=40.7128&lon=-74.0060&method=geonames'"
echo ""
echo -e "${BLUE}[LOGS]${NC} To view logs: kubectl logs -l app=$APP_NAME -n $NAMESPACE -f"
echo -e "${BLUE}[SCALE]${NC} To scale: kubectl scale deployment $APP_NAME --replicas=<number> -n $NAMESPACE"
echo -e "${BLUE}[DELETE]${NC} To undeploy: kubectl delete -f k8s/ -n $NAMESPACE"
