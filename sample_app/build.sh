#!/bin/bash

# Build script for the server info application
# This script builds the Docker image with Maven and deploys to K3s cluster

set -e

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

# Check if we're in the right directory
if [ ! -f "Dockerfile" ] || [ ! -f "pom.xml" ]; then
    echo -e "${RED}[ERROR]${NC} Dockerfile or pom.xml not found. Run this script from the sample_app directory."
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
        sudo k3s ctr images import <(docker save "$FULL_IMAGE_NAME") || {
            echo -e "${YELLOW}[WARN]${NC} Failed to import to K3s, but image is built locally"
        }
        echo -e "${GREEN}[SUCCESS]${NC} Image imported to K3s"
    fi
    
else
    echo -e "${RED}[ERROR]${NC} Failed to build Docker image"
    exit 1
fi

echo ""
echo -e "${GREEN}[COMPLETE]${NC} Build process completed successfully!"
echo -e "${GREEN}[NEXT]${NC} Run './deploy.sh' to deploy the application to Kubernetes"
