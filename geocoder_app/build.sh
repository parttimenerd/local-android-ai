#!/bin/bash

# Build script for the reverse geocoder service
# This script builds the Docker image for the standalone geocoder

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
IMAGE_NAME="reverse-geocoder"
IMAGE_TAG="latest"
FULL_IMAGE_NAME="$IMAGE_NAME:$IMAGE_TAG"

echo -e "${GREEN}[INFO]${NC} Building reverse geocoder Docker image..."
echo -e "${GREEN}[INFO]${NC} Working directory: $SCRIPT_DIR"

# Check if we're in the right directory
if [ ! -f "Dockerfile" ] || [ ! -f "pom.xml" ]; then
    echo -e "${RED}[ERROR]${NC} Dockerfile or pom.xml not found in script directory."
    echo -e "${RED}[ERROR]${NC} Script directory: $SCRIPT_DIR"
    exit 1
fi

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} Docker is not installed or not in PATH"
    exit 1
fi

# Check if we want to run Maven locally first (often not needed with Docker multi-stage build)
if [ -n "$USE_LOCAL_MAVEN" ] && command -v mvn &> /dev/null; then
    echo -e "${YELLOW}[MAVEN]${NC} Running Maven compile to verify code..."
    mvn clean compile || {
        echo -e "${RED}[ERROR]${NC} Maven compile failed. Fix compilation errors before building Docker image."
        exit 1
    }
    echo -e "${GREEN}[SUCCESS]${NC} Maven compile successful, proceeding with Docker build"
else
    echo -e "${YELLOW}[INFO]${NC} Skipping local Maven build. Will use Docker multi-stage build."
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
echo -e "${GREEN}[NEXT]${NC} Run './deploy.sh' to deploy the geocoder service to Kubernetes"
echo ""
echo -e "${YELLOW}[INFO]${NC} The geocoder service will be available at:"
echo -e "  ${YELLOW}•${NC} ClusterIP: http://reverse-geocoder.default.svc.cluster.local:8090"
echo -e "  ${YELLOW}•${NC} NodePort: http://<node-ip>:30090 (for debugging)"
echo -e "  ${YELLOW}•${NC} Health check: /health"
echo -e "  ${YELLOW}•${NC} API: /api/reverse-geocode?lat=51.5074&lon=-0.1278&method=hybrid"
