#!/bin/bash

# Build script for the server info application
# This script builds the Docker image with Maven and deploys to K3s cluster
#
# COMMON DEPLOYMENT ISSUES AND SOLUTIONS:
#
# 1. Pod scheduling failures with "didn't have free ports":
#    - Cause: LoadBalancer service + hostNetwork deployment creates port conflicts
#    - Solution: Remove LoadBalancer service (this script auto-fixes it)
#    - Why: hostNetwork pods bind directly to host ports, LoadBalancer also tries to bind
#
# 2. Image pull errors (ErrImageNeverPull):
#    - Cause: Image not available on all K3s nodes
#    - Solution: Run this build script on each node, or use a registry
#    - Why: K3s doesn't sync images between nodes automatically
#
# 3. Service conflicts:
#    - Issue: Multiple services trying to use same ports
#    - Check: kubectl get svc -A | grep <port>
#    - Fix: Delete conflicting services or change ports
#
# 4. Host networking troubleshooting:
#    - With hostNetwork: pods accessible on <node-ip>:<container-port>
#    - Without hostNetwork: use LoadBalancer or NodePort services
#    - Don't mix: hostNetwork + LoadBalancer creates conflicts
#

set -e

# Determine script directory and change to it
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Image name and tag
IMAGE_NAME="server-info-server"
IMAGE_TAG="latest"
FULL_IMAGE_NAME="$IMAGE_NAME:$IMAGE_TAG"

echo -e "${GREEN}[INFO]${NC} Building server info Docker image..."
echo -e "${GREEN}[INFO]${NC} Working directory: $SCRIPT_DIR"

# Check if we're in the right directory (should now always be true)
if [ ! -f "Dockerfile" ] || [ ! -f "pom.xml" ]; then
    echo -e "${RED}[ERROR]${NC} Dockerfile or pom.xml not found in script directory."
    echo -e "${RED}[ERROR]${NC} Script directory: $SCRIPT_DIR"
    exit 1
fi

# Validate Maven project structure
if [ ! -d "src/main/java" ]; then
    echo -e "${RED}[ERROR]${NC} Maven project structure not found (src/main/java missing)."
    exit 1
fi

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} Docker is not installed or not in PATH"
    exit 1
fi

# Optional: Run Maven compile/test locally if Maven is available
if command -v mvn &> /dev/null; then
    echo -e "${YELLOW}[MAVEN]${NC} Running Maven validate and compile..."
    mvn clean compile || {
        echo -e "${RED}[ERROR]${NC} Maven compile failed. Fix compilation errors before building Docker image."
        exit 1
    }
    echo -e "${GREEN}[SUCCESS]${NC} Maven compile successful"
else
    echo -e "${YELLOW}[INFO]${NC} Maven not found locally. Build will use Docker multi-stage build."
fi

# Build the Docker image
echo -e "${YELLOW}[BUILD]${NC} Building Docker image: $FULL_IMAGE_NAME"
docker build -t "$FULL_IMAGE_NAME" . || {
    echo -e "${RED}[ERROR]${NC} Docker build failed"
    exit 1
}

# Check if the image was built successfully
if docker images "$IMAGE_NAME" | grep -q "$IMAGE_TAG"; then
    echo -e "${GREEN}[SUCCESS]${NC} Docker image '$FULL_IMAGE_NAME' built successfully"
    
    # Show image size
    IMAGE_SIZE=$(docker images "$FULL_IMAGE_NAME" --format "table {{.Size}}" | tail -1)
    echo -e "${GREEN}[INFO]${NC} Image size: $IMAGE_SIZE"
    
    # If running on K3s, also import the image to K3s
    if command -v k3s &> /dev/null; then
        echo -e "${YELLOW}[K3S]${NC} Importing image to K3s..."
        
        # Check for LoadBalancer service conflicts when using hostNetwork
        echo -e "${YELLOW}[K3S]${NC} Checking for service conflicts..."
        if sudo k3s kubectl get deployment "$IMAGE_NAME" &>/dev/null; then
            # Check if deployment uses hostNetwork
            if sudo k3s kubectl get deployment "$IMAGE_NAME" -o yaml | grep -q "hostNetwork: true"; then
                # Check if LoadBalancer service exists
                if sudo k3s kubectl get service "$IMAGE_NAME" &>/dev/null && \
                   sudo k3s kubectl get service "$IMAGE_NAME" -o yaml | grep -q "type: LoadBalancer"; then
                    echo -e "${YELLOW}[WARN]${NC} Found LoadBalancer service with hostNetwork deployment!"
                    echo -e "${YELLOW}[WARN]${NC} This creates port conflicts - LoadBalancer + hostNetwork are incompatible"
                    echo -e "${YELLOW}[FIX]${NC} Removing conflicting LoadBalancer service..."
                    sudo k3s kubectl delete service "$IMAGE_NAME" || true
                    echo -e "${GREEN}[FIXED]${NC} Conflicting service removed. Pods should schedule correctly now."
                    echo -e "${GREEN}[INFO]${NC} With hostNetwork, your app is directly accessible on node IPs:8080"
                fi
            fi
        fi
        
        # Create temporary file for the image
        TEMP_IMAGE_FILE=$(mktemp --suffix=.tar)
        echo -e "${YELLOW}[K3S]${NC} Using temporary file: $TEMP_IMAGE_FILE"
        
        # Save image to temporary file and import to K3s
        echo -e "${YELLOW}[K3S]${NC} Saving Docker image to temporary file..."
        if docker save "$FULL_IMAGE_NAME" > "$TEMP_IMAGE_FILE"; then
            echo -e "${YELLOW}[K3S]${NC} Importing image to K3s container runtime..."
            if sudo k3s ctr images import "$TEMP_IMAGE_FILE"; then
                echo -e "${GREEN}[SUCCESS]${NC} Image imported to K3s successfully"
                
                # Verify the image is available in K3s
                if sudo k3s ctr images list | grep -q "$IMAGE_NAME"; then
                    echo -e "${GREEN}[VERIFY]${NC} Image verified in K3s container runtime"
                else
                    echo -e "${YELLOW}[WARN]${NC} Image import reported success but not found in K3s runtime"
                fi
                
            else
                echo -e "${YELLOW}[WARN]${NC} Failed to import to K3s, but image is built locally"
                echo -e "${YELLOW}[INFO]${NC} You can still deploy using the local Docker image"
                echo -e "${YELLOW}[INFO]${NC} Alternative: Use 'imagePullPolicy: Never' in deployment.yaml"
            fi
        else
            echo -e "${YELLOW}[WARN]${NC} Failed to save Docker image for K3s import"
        fi
        
        # Clean up temporary file
        echo -e "${YELLOW}[K3S]${NC} Cleaning up temporary file..."
        rm -f "$TEMP_IMAGE_FILE"
    fi
    
else
    echo -e "${RED}[ERROR]${NC} Failed to build Docker image"
    exit 1
fi

echo ""
echo -e "${GREEN}[COMPLETE]${NC} Build process completed successfully!"
echo -e "${GREEN}[NEXT]${NC} Run './deploy.sh' to deploy the application to Kubernetes"
echo ""
echo -e "${YELLOW}[TROUBLESHOOTING]${NC} If deployment issues occur:"
echo -e "  ${YELLOW}•${NC} Check pod status: sudo k3s kubectl get pods -o wide"
echo -e "  ${YELLOW}•${NC} Check services: sudo k3s kubectl get svc"
echo -e "  ${YELLOW}•${NC} Describe failed pods: sudo k3s kubectl describe pod <pod-name>"
echo -e "  ${YELLOW}•${NC} View pod logs: sudo k3s kubectl logs <pod-name>"
echo ""
echo -e "${YELLOW}[MULTI-NODE]${NC} For multi-node clusters:"
echo -e "  ${YELLOW}•${NC} The deploy script will handle image distribution automatically"
echo -e "  ${YELLOW}•${NC} Or use the registry: ../registry.sh push $FULL_IMAGE_NAME"
echo -e "  ${YELLOW}•${NC} With hostNetwork: app accessible on each node's IP:8080"
