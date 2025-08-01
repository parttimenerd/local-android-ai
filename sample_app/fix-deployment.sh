#!/bin/bash

# Deployment troubleshooting and fix script
# This script automatically detects and fixes common K3s deployment issues

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
IMAGE_NAME="server-info-server"

echo -e "${BLUE}[DIAGNOSTIC]${NC} K3s Deployment Troubleshooter"
echo -e "${BLUE}[INFO]${NC} Checking for common deployment issues..."
echo ""

# Check if K3s is available
if ! command -v k3s &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} K3s is not installed or not in PATH"
    exit 1
fi

# Function to check and fix LoadBalancer + hostNetwork conflicts
check_service_conflicts() {
    echo -e "${YELLOW}[CHECK]${NC} Scanning for LoadBalancer + hostNetwork conflicts..."
    
    if sudo k3s kubectl get deployment "$IMAGE_NAME" &>/dev/null; then
        # Check if deployment uses hostNetwork
        if sudo k3s kubectl get deployment "$IMAGE_NAME" -o yaml | grep -q "hostNetwork: true"; then
            echo -e "${BLUE}[FOUND]${NC} Deployment uses hostNetwork"
            
            # Check if LoadBalancer service exists
            if sudo k3s kubectl get service "$IMAGE_NAME" &>/dev/null; then
                SERVICE_TYPE=$(sudo k3s kubectl get service "$IMAGE_NAME" -o jsonpath='{.spec.type}')
                if [ "$SERVICE_TYPE" = "LoadBalancer" ]; then
                    echo -e "${RED}[CONFLICT]${NC} Found LoadBalancer service with hostNetwork deployment!"
                    echo -e "${YELLOW}[EXPLAIN]${NC} This creates port conflicts:"
                    echo -e "  • LoadBalancer creates svclb pods that bind to host ports"
                    echo -e "  • hostNetwork pods also try to bind to the same host ports"
                    echo -e "  • Result: Pods can't schedule due to port conflicts"
                    echo ""
                    read -p "Fix this conflict by removing LoadBalancer service? (y/N): " -n 1 -r
                    echo
                    if [[ $REPLY =~ ^[Yy]$ ]]; then
                        echo -e "${YELLOW}[FIX]${NC} Removing conflicting LoadBalancer service..."
                        sudo k3s kubectl delete service "$IMAGE_NAME"
                        echo -e "${GREEN}[FIXED]${NC} Conflicting service removed"
                        echo -e "${GREEN}[INFO]${NC} Your app is now accessible on node IPs:8080"
                        return 0
                    else
                        echo -e "${YELLOW}[SKIP]${NC} Keeping LoadBalancer service (manual fix needed)"
                        return 1
                    fi
                else
                    echo -e "${GREEN}[OK]${NC} Service type '$SERVICE_TYPE' is compatible with hostNetwork"
                fi
            else
                echo -e "${GREEN}[OK]${NC} No conflicting LoadBalancer service found"
            fi
        else
            echo -e "${BLUE}[INFO]${NC} Deployment uses regular networking (not hostNetwork)"
        fi
    else
        echo -e "${YELLOW}[INFO]${NC} No deployment named '$IMAGE_NAME' found"
    fi
}

# Function to check pod scheduling issues
check_pod_scheduling() {
    echo -e "${YELLOW}[CHECK]${NC} Checking for pod scheduling issues..."
    
    PENDING_PODS=$(sudo k3s kubectl get pods -l app="$IMAGE_NAME" --field-selector=status.phase=Pending -o name 2>/dev/null || true)
    
    if [ -n "$PENDING_PODS" ]; then
        echo -e "${RED}[ISSUE]${NC} Found pending pods:"
        sudo k3s kubectl get pods -l app="$IMAGE_NAME" --field-selector=status.phase=Pending
        echo ""
        echo -e "${YELLOW}[ANALYZE]${NC} Checking scheduling failures..."
        
        for pod in $PENDING_PODS; do
            POD_NAME=$(basename "$pod")
            echo -e "${BLUE}[POD]${NC} $POD_NAME:"
            sudo k3s kubectl describe pod "$POD_NAME" | grep -A5 -B5 "Events:" | tail -10
            echo ""
        done
        
        # Check for common port conflict messages
        if sudo k3s kubectl describe pods -l app="$IMAGE_NAME" | grep -q "didn't have free ports"; then
            echo -e "${RED}[DIAGNOSIS]${NC} Port conflict detected!"
            echo -e "${YELLOW}[SOLUTION]${NC} This is likely a LoadBalancer + hostNetwork conflict"
            echo -e "${YELLOW}[ACTION]${NC} Running automatic fix..."
            check_service_conflicts
        fi
    else
        echo -e "${GREEN}[OK]${NC} No pending pods found"
    fi
}

# Function to check image availability
check_image_availability() {
    echo -e "${YELLOW}[CHECK]${NC} Checking image availability across nodes..."
    
    # Get all nodes
    NODES=$(sudo k3s kubectl get nodes -o jsonpath='{.items[*].metadata.name}')
    
    for node in $NODES; do
        echo -e "${BLUE}[NODE]${NC} $node:"
        
        # Check if image exists on this node
        if sudo k3s kubectl debug node/"$node" -it --image=busybox -- chroot /host crictl images | grep -q "$IMAGE_NAME" 2>/dev/null; then
            echo -e "  ${GREEN}✓${NC} Image '$IMAGE_NAME' found"
        else
            echo -e "  ${RED}✗${NC} Image '$IMAGE_NAME' NOT found"
            echo -e "  ${YELLOW}[SOLUTION]${NC} Run './build.sh' on node '$node' to import image"
        fi
    done
}

# Function to show deployment status
show_deployment_status() {
    echo -e "${YELLOW}[STATUS]${NC} Current deployment status:"
    echo ""
    
    if sudo k3s kubectl get deployment "$IMAGE_NAME" &>/dev/null; then
        sudo k3s kubectl get deployment "$IMAGE_NAME"
        echo ""
        sudo k3s kubectl get pods -l app="$IMAGE_NAME" -o wide
        echo ""
        sudo k3s kubectl get services -l app="$IMAGE_NAME"
    else
        echo -e "${YELLOW}[INFO]${NC} No deployment named '$IMAGE_NAME' found"
    fi
}

# Main execution
echo -e "${BLUE}[START]${NC} Running diagnostics..."
echo ""

# Run all checks
check_service_conflicts
echo ""
check_pod_scheduling  
echo ""
check_image_availability
echo ""
show_deployment_status

echo ""
echo -e "${GREEN}[COMPLETE]${NC} Diagnostic complete!"
echo ""
echo -e "${YELLOW}[MANUAL COMMANDS]${NC} Useful troubleshooting commands:"
echo -e "  ${YELLOW}•${NC} Check all pods: sudo k3s kubectl get pods -A -o wide"
echo -e "  ${YELLOW}•${NC} Check services: sudo k3s kubectl get svc -A"
echo -e "  ${YELLOW}•${NC} Check events: sudo k3s kubectl get events --sort-by='.lastTimestamp'"
echo -e "  ${YELLOW}•${NC} Delete problematic pods: sudo k3s kubectl delete pod <pod-name>"
echo -e "  ${YELLOW}•${NC} Restart deployment: sudo k3s kubectl rollout restart deployment/$IMAGE_NAME"
