#!/bin/bash

# Test script for the reverse geocoder service
# This script tests the geocoder service endpoints and verifies functionality

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
NAMESPACE="default"
SERVICE_NAME="reverse-geocoder"
SERVICE_PORT="8090"

echo -e "${BLUE}[TEST]${NC} Testing reverse geocoder service..."
echo ""

# Function to run kubectl command in a temporary pod
run_curl_test() {
    local url="$1"
    local description="$2"
    
    echo -e "${YELLOW}[TEST]${NC} $description"
    echo -e "${YELLOW}[URL]${NC}  $url"
    
    # Run curl in a temporary pod
    local result
    if result=$(kubectl run curl-test-$$-$RANDOM --image=curlimages/curl --rm -i --restart=Never --quiet --command -- curl -s -w "HTTP_CODE:%{http_code}" "$url" 2>/dev/null); then
        
        # Extract HTTP code and response
        local http_code response
        http_code=$(echo "$result" | grep "HTTP_CODE:" | cut -d: -f2)
        response=$(echo "$result" | sed '/HTTP_CODE:/d')
        
        if [ "$http_code" = "200" ]; then
            echo -e "${GREEN}[SUCCESS]${NC} HTTP $http_code"
            echo -e "${GREEN}[RESPONSE]${NC} $response"
        else
            echo -e "${RED}[FAILED]${NC} HTTP $http_code"
            echo -e "${RED}[RESPONSE]${NC} $response"
            return 1
        fi
    else
        echo -e "${RED}[FAILED]${NC} Could not execute curl test"
        return 1
    fi
    
    echo ""
    return 0
}

# Check if kubectl is available
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} kubectl is not installed or not in PATH"
    exit 1
fi

# Check if we can connect to the cluster
if ! kubectl cluster-info &>/dev/null; then
    echo -e "${RED}[ERROR]${NC} Cannot connect to Kubernetes cluster"
    exit 1
fi

# Check if the service exists
echo -e "${YELLOW}[CHECK]${NC} Verifying service deployment..."
if ! kubectl get service "$SERVICE_NAME" -n "$NAMESPACE" &>/dev/null; then
    echo -e "${RED}[ERROR]${NC} Service '$SERVICE_NAME' not found in namespace '$NAMESPACE'"
    echo -e "${YELLOW}[INFO]${NC} Deploy the service first with: ./deploy.sh"
    exit 1
fi

# Check if the deployment is ready
if ! kubectl get deployment "$SERVICE_NAME" -n "$NAMESPACE" &>/dev/null; then
    echo -e "${RED}[ERROR]${NC} Deployment '$SERVICE_NAME' not found"
    exit 1
fi

# Wait for deployment to be ready
echo -e "${YELLOW}[WAIT]${NC} Waiting for deployment to be ready..."
if ! kubectl wait --for=condition=available deployment/"$SERVICE_NAME" -n "$NAMESPACE" --timeout=60s; then
    echo -e "${RED}[ERROR]${NC} Deployment is not ready"
    echo -e "${YELLOW}[INFO]${NC} Check deployment status:"
    kubectl get deployment "$SERVICE_NAME" -n "$NAMESPACE"
    kubectl get pods -l app="$SERVICE_NAME" -n "$NAMESPACE"
    exit 1
fi

# Get service details
SERVICE_IP=$(kubectl get service "$SERVICE_NAME" -n "$NAMESPACE" -o jsonpath='{.spec.clusterIP}')
DNS_NAME="$SERVICE_NAME.$NAMESPACE.svc.cluster.local"

echo -e "${GREEN}[READY]${NC} Service is ready!"
echo -e "${GREEN}[INFO]${NC}  Service IP: $SERVICE_IP"
echo -e "${GREEN}[INFO]${NC}  DNS Name: $DNS_NAME"
echo -e "${GREEN}[INFO]${NC}  Port: $SERVICE_PORT"
echo ""

# Test cases
declare -a tests=(
    "http://$DNS_NAME:$SERVICE_PORT/health|Health endpoint"
    "http://$DNS_NAME:$SERVICE_PORT/api/reverse-geocode?lat=51.5074&lon=-0.1278&method=hybrid|London, UK (hybrid method)"
    "http://$DNS_NAME:$SERVICE_PORT/api/reverse-geocode?lat=40.7128&lon=-74.0060&method=geonames|New York, USA (geonames method)"
    "http://$DNS_NAME:$SERVICE_PORT/api/reverse-geocode?lat=48.8566&lon=2.3522&method=nominatim|Paris, France (nominatim method)"
    "http://$DNS_NAME:$SERVICE_PORT/api/reverse-geocode?lat=35.6762&lon=139.6503|Tokyo, Japan (default method)"
    "http://$DNS_NAME:$SERVICE_PORT/api/reverse-geocode?lat=-33.8688&lon=151.2093|Sydney, Australia"
)

# Run tests
failed_tests=0
total_tests=${#tests[@]}

for test_case in "${tests[@]}"; do
    IFS='|' read -r url description <<< "$test_case"
    
    if ! run_curl_test "$url" "$description"; then
        ((failed_tests++))
    fi
    
    # Small delay between tests
    sleep 1
done

# Test summary
echo "=============================================="
echo -e "${BLUE}[SUMMARY]${NC} Test Results"
echo "=============================================="
echo -e "${GREEN}[PASSED]${NC} $((total_tests - failed_tests))/$total_tests tests"

if [ $failed_tests -eq 0 ]; then
    echo -e "${GREEN}[SUCCESS]${NC} All tests passed! 🎉"
    echo ""
    echo -e "${BLUE}[USAGE]${NC} The reverse geocoder service is working correctly."
    echo -e "${BLUE}[INFO]${NC} Node geolocation monitoring will now use this local service."
    echo -e "${BLUE}[INFO]${NC} Monitor logs with: kubectl logs -l app=$SERVICE_NAME -f"
    exit 0
else
    echo -e "${RED}[FAILED]${NC} $failed_tests tests failed ❌"
    echo ""
    echo -e "${YELLOW}[DEBUG]${NC} Troubleshooting steps:"
    echo -e "  ${YELLOW}•${NC} Check pod logs: kubectl logs -l app=$SERVICE_NAME"
    echo -e "  ${YELLOW}•${NC} Check pod status: kubectl get pods -l app=$SERVICE_NAME"
    echo -e "  ${YELLOW}•${NC} Check service: kubectl describe service $SERVICE_NAME"
    echo -e "  ${YELLOW}•${NC} Verify deployment: kubectl describe deployment $SERVICE_NAME"
    exit 1
fi
