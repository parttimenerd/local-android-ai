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

# Function to print pod logs on error
print_pod_logs() {
    local app_name="$1"
    local namespace="$2"
    echo -e "${RED}[ERROR LOGS]${NC} Recent pod logs for debugging:"
    echo "================== POD LOGS =================="
    kubectl logs -l app="$app_name" -n "$namespace" --tail=50 --since=5m || echo "No logs available"
    echo "=============================================="
    
    echo -e "${RED}[POD STATUS]${NC} Current pod status:"
    kubectl get pods -l app="$app_name" -n "$namespace" -o wide || echo "No pods found"
    
    echo -e "${RED}[POD EVENTS]${NC} Recent events:"
    kubectl get events -n "$namespace" --sort-by='.lastTimestamp' --field-selector involvedObject.kind=Pod | tail -10 || echo "No events available"
}

# Function to check node disk space
check_node_resources() {
    echo -e "${YELLOW}[RESOURCES]${NC} Checking node resources..."
    
    # Check disk space on nodes
    local disk_warnings=0
    while IFS= read -r node; do
        # Check for disk pressure taints
        local disk_pressure
        disk_pressure=$(kubectl get node "$node" -o jsonpath='{.spec.taints[?(@.key=="node.kubernetes.io/disk-pressure")].effect}' 2>/dev/null)
        
        if [ -n "$disk_pressure" ]; then
            echo -e "${RED}[WARN]${NC} Node '$node' has disk pressure taint: $disk_pressure"
            disk_warnings=$((disk_warnings + 1))
        else
            echo -e "${GREEN}[OK]${NC} Node '$node' disk status: OK"
        fi
    done < <(kubectl get nodes --no-headers -o custom-columns=NAME:.metadata.name 2>/dev/null)
    
    if [ $disk_warnings -gt 0 ]; then
        echo -e "${YELLOW}[WARNING]${NC} Found $disk_warnings node(s) with disk pressure"
        echo -e "${YELLOW}[INFO]${NC} This may cause pod evictions. Consider cleaning up disk space."
        echo -e "${YELLOW}[INFO]${NC} Check disk usage with: df -h"
    fi
}

# Check if Docker image exists locally to avoid slow pulls
echo -e "${YELLOW}[IMAGE]${NC} Checking for local Docker image..."
if docker images reverse-geocoder:latest --format "table {{.Repository}}:{{.Tag}}" | grep -q "reverse-geocoder:latest"; then
    echo -e "${GREEN}[SUCCESS]${NC} Docker image 'reverse-geocoder:latest' found locally"
else
    echo -e "${RED}[ERROR]${NC} Docker image 'reverse-geocoder:latest' not found locally"
    echo -e "${RED}[ERROR]${NC} Please run './build.sh' first to build the image"
    echo -e "${YELLOW}[INFO]${NC} The deployment will fail without a local image (imagePullPolicy: Never)"
    exit 1
fi

# Check node resources before deployment
check_node_resources

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
    print_pod_logs "$APP_NAME" "$NAMESPACE"
    exit 1
fi

echo -e "${YELLOW}[APPLY]${NC} Applying service manifest..."
if kubectl apply -f "$SERVICE_FILE" -n "$NAMESPACE"; then
    echo -e "${GREEN}[SUCCESS]${NC} Service manifest applied"
else
    echo -e "${RED}[ERROR]${NC} Failed to apply service manifest"
    print_pod_logs "$APP_NAME" "$NAMESPACE"
    exit 1
fi

# Wait for deployment to be ready with progress monitoring
echo -e "${YELLOW}[WAIT]${NC} Waiting for deployment to be ready..."

# First wait for rollout to start
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=60s

# Then wait for pods to be ready
TIMEOUT=90
ELAPSED=0
INTERVAL=3

while [ $ELAPSED -lt $TIMEOUT ]; do
    READY_PODS=$(kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" --no-headers 2>/dev/null | grep -c "Running" || echo "0")
    TOTAL_PODS=$(kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l || echo "0")
    
    if [ "$READY_PODS" -gt 0 ] && [ "$READY_PODS" -eq "$TOTAL_PODS" ]; then
        echo -e "${GREEN}[SUCCESS]${NC} Deployment is ready ($READY_PODS/$TOTAL_PODS pods)"
        break
    fi
    
    echo -e "${YELLOW}[PROGRESS]${NC} Waiting for pods: $READY_PODS/$TOTAL_PODS ready (${ELAPSED}s elapsed)"
    
    # Check for startup problems
    PROBLEM_PODS=$(kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" --no-headers 2>/dev/null | grep -E "(Error|CrashLoopBackOff|ImagePullBackOff|ErrImagePull|Pending)" || echo "")
    if [ -n "$PROBLEM_PODS" ]; then
        echo -e "${RED}[ERROR]${NC} Found problematic pods:"
        echo "$PROBLEM_PODS"
        echo ""
        print_pod_logs "$APP_NAME" "$NAMESPACE"
        echo ""
        echo -e "${RED}[TROUBLESHOOT]${NC} Common issues and solutions:"
        echo -e "  ${YELLOW}•${NC} CrashLoopBackOff: Application is crashing - check logs above"
        echo -e "  ${YELLOW}•${NC} ImagePullBackOff: Docker image not found - run './build.sh' first"
        echo -e "  ${YELLOW}•${NC} Pending: Node resource constraints or scheduling issues"
        echo -e "  ${YELLOW}•${NC} Evicted: Node disk/memory pressure - clean up resources"
        exit 1
    fi
    
    sleep $INTERVAL
    ELAPSED=$((ELAPSED + INTERVAL))
done

if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}[ERROR]${NC} Deployment failed to become ready within 90 seconds"
    echo ""
    
    # Detailed error analysis
    echo -e "${YELLOW}[ANALYSIS]${NC} Analyzing deployment failure..."
    
    # Check pod status and events
    echo -e "${BLUE}[DEBUG]${NC} Current pod status:"
    kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" -o wide
    
    echo ""
    echo -e "${BLUE}[DEBUG]${NC} Pod events and conditions:"
    pod_name=$(kubectl get pods -l app="$APP_NAME" -n "$NAMESPACE" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    
    if [ -n "$pod_name" ]; then
        echo "Pod: $pod_name"
        kubectl describe pod "$pod_name" -n "$NAMESPACE" | grep -A 20 "Events:"
        
        echo ""
        echo -e "${BLUE}[DEBUG]${NC} Pod logs (last 50 lines):"
        print_pod_logs "$APP_NAME" "$NAMESPACE"
        
        # Check if pod is in CrashLoopBackOff
        pod_status=$(kubectl get pod "$pod_name" -n "$NAMESPACE" -o jsonpath='{.status.phase}' 2>/dev/null)
        container_state=$(kubectl get pod "$pod_name" -n "$NAMESPACE" -o jsonpath='{.status.containerStatuses[0].state}' 2>/dev/null)
        
        echo ""
        echo -e "${BLUE}[DEBUG]${NC} Pod phase: $pod_status"
        echo -e "${BLUE}[DEBUG]${NC} Container state: $container_state"
        
        if echo "$container_state" | grep -q "waiting"; then
            reason=$(kubectl get pod "$pod_name" -n "$NAMESPACE" -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}' 2>/dev/null)
            message=$(kubectl get pod "$pod_name" -n "$NAMESPACE" -o jsonpath='{.status.containerStatuses[0].state.waiting.message}' 2>/dev/null)
            echo -e "${RED}[ERROR]${NC} Container waiting reason: $reason"
            echo -e "${RED}[ERROR]${NC} Container waiting message: $message"
        fi
    else
        echo -e "${RED}[ERROR]${NC} No pods found for app: $APP_NAME"
    fi
    
    echo ""
    echo -e "${YELLOW}[DEBUG]${NC} Deployment status details:"
    kubectl describe deployment "$APP_NAME" -n "$NAMESPACE"
    
    echo ""
    echo -e "${YELLOW}[TROUBLESHOOTING]${NC} Common issues to check:"
    echo "  1. Docker image exists and is built correctly"
    echo "  2. Main class path is correct in the image"
    echo "  3. Application dependencies are included"
    echo "  4. Health check endpoints are responding"
    echo "  5. Sufficient disk space and resources available"
    echo "  6. Network connectivity and DNS resolution"
    
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
echo -e "    kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/api/reverse-geocode?lat=52.5200&lon=13.4050&method=geonames'"
echo ""
echo -e "  ${YELLOW}•${NC} Reverse geocoding (geonames only):"
echo -e "    kubectl run curl-test --image=curlimages/curl --rm -i --restart=Never --command -- curl 'http://$APP_NAME.$NAMESPACE.svc.cluster.local:$CLUSTER_PORT/api/reverse-geocode?lat=48.1351&lon=11.5820&method=geonames'"
echo ""
echo -e "${BLUE}[LOGS]${NC} To view logs: kubectl logs -l app=$APP_NAME -n $NAMESPACE -f"
echo -e "${BLUE}[SCALE]${NC} To scale: kubectl scale deployment $APP_NAME --replicas=<number> -n $NAMESPACE"
echo -e "${BLUE}[DELETE]${NC} To undeploy: kubectl delete -f k8s/ -n $NAMESPACE"
