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

# Check for cleanup flag
if [[ "$1" == "--clean" || "$1" == "-c" ]]; then
    echo -e "${YELLOW}[CLEANUP]${NC} Performing full cleanup of geocoder resources..."
    
    # Delete deployment
    if sudo kubectl get deployment "$APP_NAME" -n "$NAMESPACE" &>/dev/null; then
        echo -e "${YELLOW}[DELETE]${NC} Removing deployment..."
        sudo kubectl delete deployment "$APP_NAME" -n "$NAMESPACE" --force --grace-period=0 2>/dev/null || true
    fi
    
    # Delete service
    if sudo kubectl get service "$APP_NAME" -n "$NAMESPACE" &>/dev/null; then
        echo -e "${YELLOW}[DELETE]${NC} Removing service..."
        sudo kubectl delete service "$APP_NAME" -n "$NAMESPACE" --force --grace-period=0 2>/dev/null || true
    fi
    
    # Force delete any remaining pods
    echo -e "${YELLOW}[DELETE]${NC} Removing any remaining pods..."
    sudo kubectl delete pods -l app="$APP_NAME" -n "$NAMESPACE" --force --grace-period=0 2>/dev/null || true
    
    # Wait for complete cleanup
    echo -e "${YELLOW}[WAIT]${NC} Waiting for complete cleanup..."
    timeout 60 bash -c "
        while sudo kubectl get all -l app='$APP_NAME' -n '$NAMESPACE' --no-headers 2>/dev/null | grep -q .; do
            sleep 2
        done
    " || echo -e "${YELLOW}[WARN]${NC} Some resources may still be terminating"
    
    echo -e "${GREEN}[COMPLETE]${NC} Cleanup finished"
    echo -e "${GREEN}[INFO]${NC} Run './deploy.sh' to deploy fresh geocoder service"
    exit 0
fi

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
if ! sudo kubectl cluster-info &>/dev/null; then
    echo -e "${RED}[ERROR]${NC} Cannot connect to Kubernetes cluster"
    echo -e "${RED}[ERROR]${NC} Make sure K3s is running and kubectl is configured"
    exit 1
fi

# Show cluster info
CLUSTER_INFO=$(sudo kubectl config current-context 2>/dev/null || echo "unknown")
NODE_COUNT=$(sudo kubectl get nodes --no-headers 2>/dev/null | wc -l || echo "0")
echo -e "${GREEN}[CLUSTER]${NC} Connected to cluster: $CLUSTER_INFO"
echo -e "${GREEN}[CLUSTER]${NC} Nodes available: $NODE_COUNT"

# Check if deployment already exists
if sudo kubectl get deployment "$APP_NAME" -n "$NAMESPACE" &>/dev/null; then
    echo -e "${YELLOW}[UPDATE]${NC} Deployment '$APP_NAME' already exists. Cleaning up..."
    
    # Delete existing pods to force fresh deployment
    echo -e "${YELLOW}[CLEANUP]${NC} Removing existing geocoder pods..."
    EXISTING_PODS=$(sudo kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" --no-headers 2>/dev/null | awk '{print $1}')
    if [ -n "$EXISTING_PODS" ]; then
        echo "$EXISTING_PODS" | while read pod_name; do
            if [ -n "$pod_name" ]; then
                echo -e "  ${YELLOW}Deleting:${NC} $pod_name"
                sudo kubectl delete pod "$pod_name" -n "$NAMESPACE" --force --grace-period=0 2>/dev/null || true
            fi
        done
        
        # Wait for pods to be fully deleted
        echo -e "${YELLOW}[WAIT]${NC} Waiting for pods to be deleted..."
        timeout 30 bash -c "
            while sudo kubectl get pods -l app='$APP_NAME' -n '$NAMESPACE' --no-headers 2>/dev/null | grep -q .; do
                sleep 1
            done
        " || echo -e "${YELLOW}[WARN]${NC} Some pods may still be terminating"
    else
        echo -e "${GREEN}[INFO]${NC} No existing pods found"
    fi
    
    ACTION="updated"
else
    echo -e "${YELLOW}[DEPLOY]${NC} Creating new deployment '$APP_NAME'..."
    ACTION="deployed"
fi

# Apply the Kubernetes manifests
echo -e "${YELLOW}[APPLY]${NC} Applying deployment manifest..."
if sudo kubectl apply -f "$DEPLOYMENT_FILE" -n "$NAMESPACE"; then
    echo -e "${GREEN}[SUCCESS]${NC} Deployment manifest applied"
else
    echo -e "${RED}[ERROR]${NC} Failed to apply deployment manifest"
    exit 1
fi

echo -e "${YELLOW}[APPLY]${NC} Applying service manifest..."
if sudo kubectl apply -f "$SERVICE_FILE" -n "$NAMESPACE"; then
    echo -e "${GREEN}[SUCCESS]${NC} Service manifest applied"
else
    echo -e "${RED}[ERROR]${NC} Failed to apply service manifest"
    exit 1
fi

# Wait for deployment to be ready with real-time monitoring
echo -e "${YELLOW}[WAIT]${NC} Waiting for deployment to be ready..."

# Start background monitoring
{
    sleep 2
    for i in {1..40}; do
        echo -e "${BLUE}[MONITOR $(printf "%02d" $i)]${NC} Checking deployment status..."
        
        # Get pod status
        POD_STATUS=$(sudo kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" --no-headers 2>/dev/null | head -1)
        if [ -n "$POD_STATUS" ]; then
            POD_NAME=$(echo "$POD_STATUS" | awk '{print $1}')
            POD_READY=$(echo "$POD_STATUS" | awk '{print $2}')
            POD_STATE=$(echo "$POD_STATUS" | awk '{print $3}')
            POD_RESTARTS=$(echo "$POD_STATUS" | awk '{print $4}')
            echo -e "  ${YELLOW}Pod:${NC} $POD_NAME - State: $POD_STATE, Ready: $POD_READY, Restarts: $POD_RESTARTS"
            
            # Show recent events for this pod
            sudo kubectl get events --field-selector involvedObject.name="$POD_NAME" -n "$NAMESPACE" --sort-by='.lastTimestamp' 2>/dev/null | tail -2 | while read line; do
                if [[ "$line" != *"LAST SEEN"* && -n "$line" ]]; then
                    echo -e "  ${BLUE}Event:${NC} $line"
                fi
            done
            
            # If pod is running, show some logs
            if [[ "$POD_STATE" == "Running" ]]; then
                echo -e "  ${GREEN}Logs:${NC}"
                # Clean up emoji and special characters from logs
                sudo kubectl logs "$POD_NAME" -n "$NAMESPACE" --tail=3 2>/dev/null | \
                    sed 's/[🚀🎯📥✅❌🎉]/[*]/g' | \
                    sed 's/[⏹️]/[STOP]/g' | \
                    tr -d '\r' | \
                    while read line; do
                        if [ -n "$line" ]; then
                            echo -e "    $line"
                        fi
                    done
            fi
        else
            echo -e "  ${YELLOW}Status:${NC} No pods found yet..."
        fi
        
        echo ""
        sleep 3
    done
} &
MONITOR_PID=$!

# Wait for deployment with timeout
if sudo kubectl wait --for=condition=available deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=120s; then
    # Stop monitoring
    kill $MONITOR_PID 2>/dev/null || true
    echo -e "${GREEN}[SUCCESS]${NC} Deployment is ready"
else
    # Stop monitoring
    kill $MONITOR_PID 2>/dev/null || true
    echo -e "${RED}[ERROR]${NC} Deployment failed to become ready within 120 seconds"
    
    echo -e "${YELLOW}[DIAGNOSIS]${NC} Detailed deployment status:"
    sudo kubectl describe deployment "$APP_NAME" -n "$NAMESPACE"
    
    echo -e "${YELLOW}[DIAGNOSIS]${NC} Pod details:"
    sudo kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" -o wide
    
    POD_NAME=$(sudo kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" --no-headers 2>/dev/null | head -1 | awk '{print $1}')
    if [ -n "$POD_NAME" ]; then
        echo -e "${YELLOW}[DIAGNOSIS]${NC} Pod logs for $POD_NAME:"
        sudo kubectl logs "$POD_NAME" -n "$NAMESPACE" --tail=20
        
        echo -e "${YELLOW}[DIAGNOSIS]${NC} Pod events for $POD_NAME:"
        sudo kubectl describe pod "$POD_NAME" -n "$NAMESPACE" | grep -A 20 "Events:"
    fi
    
    exit 1
fi

# Get deployment and service status
echo ""
echo -e "${BLUE}[STATUS]${NC} Current deployment status:"
sudo kubectl get deployment "$APP_NAME" -n "$NAMESPACE" -o wide

echo ""
echo -e "${BLUE}[STATUS]${NC} Pod status:"
sudo kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" -o wide

echo ""
echo -e "${BLUE}[STATUS]${NC} Service status:"
sudo kubectl get service "$APP_NAME" -n "$NAMESPACE" -o wide

# Get service endpoints
echo ""
echo -e "${BLUE}[ENDPOINTS]${NC} Service endpoints:"

# Cluster IP
CLUSTER_IP=$(sudo kubectl get service "$APP_NAME" -n "$NAMESPACE" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || echo "unknown")
CLUSTER_PORT=$(sudo kubectl get service "$APP_NAME" -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || echo "unknown")
echo -e "  ${YELLOW}•${NC} ClusterIP: http://$CLUSTER_IP:$CLUSTER_PORT"
echo -e "  ${YELLOW}•${NC} DNS: http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT"

# NodePort (if available)
NODE_PORT=$(sudo kubectl get service "$APP_NAME" -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "")
if [ -n "$NODE_PORT" ] && [ "$NODE_PORT" != "null" ]; then
    echo -e "  ${YELLOW}•${NC} NodePort: http://<node-ip>:$NODE_PORT"
    
    # Try to get node IP
    NODE_IP=$(sudo kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "")
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

if sudo kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl -f -s "$HEALTH_URL" &>/dev/null; then
    echo -e "${GREEN}[SUCCESS]${NC} Health endpoint is responding"
else
    echo -e "${YELLOW}[WARN]${NC} Health endpoint test failed (service might still be starting)"
    echo -e "${YELLOW}[INFO]${NC} You can test manually: sudo kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl '$HEALTH_URL'"
fi

# Show example usage
echo ""
echo -e "${GREEN}[COMPLETE]${NC} Reverse geocoder service $ACTION successfully!"
echo ""
echo -e "${BLUE}[USAGE]${NC} Example API calls:"
echo -e "  ${YELLOW}•${NC} Health check:"
echo -e "    sudo kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/health'"
echo ""
echo -e "  ${YELLOW}•${NC} Reverse geocoding (hybrid method):"
echo -e "    sudo kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/api/reverse-geocode?lat=51.5074&lon=-0.1278&method=hybrid'"
echo ""
echo -e "  ${YELLOW}•${NC} Reverse geocoding (geonames only):"
echo -e "    sudo kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/api/reverse-geocode?lat=40.7128&lon=-74.0060&method=geonames'"
echo ""
echo -e "${BLUE}[MAINTENANCE]${NC} Management commands:"
echo -e "  ${YELLOW}•${NC} View logs: sudo kubectl logs -l app=$APP_NAME -n $NAMESPACE -f"
echo -e "  ${YELLOW}•${NC} Scale: sudo kubectl scale deployment $APP_NAME --replicas=<number> -n $NAMESPACE"
echo -e "  ${YELLOW}•${NC} Clean restart: ./deploy.sh --clean && ./deploy.sh"
echo -e "  ${YELLOW}•${NC} Full cleanup: ./deploy.sh --clean"
