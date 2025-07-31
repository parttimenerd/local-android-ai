#!/bin/bash

# Test script for the hostname server application
# This script tests the deployed application

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${GREEN}[INFO]${NC} Testing hostname server application..."

# Check if kubectl is available
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} kubectl is not installed or not in PATH"
    echo -e "${YELLOW}[INFO]${NC} On the K3s server, you can use: sudo k3s kubectl"
    exit 1
fi

# Check if curl is available
if ! command -v curl &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} curl is not installed or not in PATH"
    exit 1
fi

# Check if jq is available (optional, for pretty JSON output)
HAS_JQ=false
if command -v jq &> /dev/null; then
    HAS_JQ=true
fi

# Check if the application is deployed
if ! kubectl get deployment hostname-server &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} Application is not deployed. Run './deploy.sh' first."
    exit 1
fi

# Check deployment status
echo -e "${BLUE}[STATUS]${NC} Checking deployment status..."
kubectl get deployment hostname-server
kubectl get pods -l app=hostname-server

# Check if pods are ready
READY_PODS=$(kubectl get pods -l app=hostname-server --no-headers | grep -c "Running" || echo "0")
TOTAL_PODS=$(kubectl get pods -l app=hostname-server --no-headers | wc -l)

echo -e "${BLUE}[INFO]${NC} Pods ready: $READY_PODS/$TOTAL_PODS"

if [ "$READY_PODS" -eq 0 ]; then
    echo -e "${RED}[ERROR]${NC} No pods are ready. Check deployment status:"
    kubectl describe pods -l app=hostname-server
    exit 1
fi

# Get service information
echo -e "${BLUE}[SERVICE]${NC} Getting service information..."
kubectl get service hostname-server
kubectl get service hostname-server-nodeport

# Try LoadBalancer IP first
EXTERNAL_IP=$(kubectl get service hostname-server -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
SERVICE_URL=""

if [ -n "$EXTERNAL_IP" ]; then
    SERVICE_URL="http://$EXTERNAL_IP:8080"
    echo -e "${GREEN}[ACCESS]${NC} Using LoadBalancer IP: $EXTERNAL_IP"
else
    # Fallback to NodePort
    NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
    if [ -n "$NODE_IP" ]; then
        SERVICE_URL="http://$NODE_IP:30080"
        echo -e "${YELLOW}[ACCESS]${NC} Using NodePort access: $NODE_IP:30080"
    else
        echo -e "${RED}[ERROR]${NC} Cannot determine service URL"
        exit 1
    fi
fi

echo -e "${BLUE}[TEST]${NC} Testing application at: $SERVICE_URL"

# Test main endpoint
echo ""
echo -e "${YELLOW}[TEST 1]${NC} Testing main endpoint..."
RESPONSE=$(curl -s -w "HTTP_CODE:%{http_code}" "$SERVICE_URL" || echo "CURL_FAILED")

if [[ "$RESPONSE" == *"CURL_FAILED"* ]]; then
    echo -e "${RED}[FAIL]${NC} Failed to connect to the service"
    echo -e "${YELLOW}[DEBUG]${NC} Checking service and pod status..."
    kubectl get service hostname-server hostname-server-nodeport
    kubectl get pods -l app=hostname-server -o wide
    exit 1
fi

# Extract HTTP code and body
HTTP_CODE=$(echo "$RESPONSE" | sed -n 's/.*HTTP_CODE:\([0-9]*\)$/\1/p')
BODY=$(echo "$RESPONSE" | sed 's/HTTP_CODE:[0-9]*$//')

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}[PASS]${NC} Main endpoint returned HTTP 200"
    if [ "$HAS_JQ" = true ]; then
        echo "$BODY" | jq .
    else
        echo "$BODY"
    fi
else
    echo -e "${RED}[FAIL]${NC} Main endpoint returned HTTP $HTTP_CODE"
    echo "$BODY"
fi

# Test health endpoint
echo ""
echo -e "${YELLOW}[TEST 2]${NC} Testing health endpoint..."
HEALTH_RESPONSE=$(curl -s -w "HTTP_CODE:%{http_code}" "$SERVICE_URL/health" || echo "CURL_FAILED")

if [[ "$HEALTH_RESPONSE" != *"CURL_FAILED"* ]]; then
    HEALTH_HTTP_CODE=$(echo "$HEALTH_RESPONSE" | sed -n 's/.*HTTP_CODE:\([0-9]*\)$/\1/p')
    HEALTH_BODY=$(echo "$HEALTH_RESPONSE" | sed 's/HTTP_CODE:[0-9]*$//')
    
    if [ "$HEALTH_HTTP_CODE" = "200" ]; then
        echo -e "${GREEN}[PASS]${NC} Health endpoint returned HTTP 200"
        if [ "$HAS_JQ" = true ]; then
            echo "$HEALTH_BODY" | jq .
        else
            echo "$HEALTH_BODY"
        fi
    else
        echo -e "${RED}[FAIL]${NC} Health endpoint returned HTTP $HEALTH_HTTP_CODE"
    fi
else
    echo -e "${RED}[FAIL]${NC} Failed to connect to health endpoint"
fi

# Test load balancing (multiple requests)
echo ""
echo -e "${YELLOW}[TEST 3]${NC} Testing load balancing (10 requests)..."
echo "Hostnames seen:"

HOSTNAMES_SEEN=""
for i in {1..10}; do
    RESPONSE=$(curl -s "$SERVICE_URL" 2>/dev/null || echo "")
    if [ -n "$RESPONSE" ]; then
        if [ "$HAS_JQ" = true ]; then
            HOSTNAME=$(echo "$RESPONSE" | jq -r '.hostname' 2>/dev/null || echo "unknown")
        else
            HOSTNAME=$(echo "$RESPONSE" | grep -o '"hostname":"[^"]*"' | cut -d'"' -f4 || echo "unknown")
        fi
        echo "  Request $i: $HOSTNAME"
        
        # Track unique hostnames
        if [[ "$HOSTNAMES_SEEN" != *"$HOSTNAME"* ]]; then
            HOSTNAMES_SEEN="$HOSTNAMES_SEEN $HOSTNAME"
        fi
    else
        echo "  Request $i: FAILED"
    fi
    sleep 0.5
done

UNIQUE_HOSTNAMES=$(echo "$HOSTNAMES_SEEN" | wc -w)
echo -e "${BLUE}[RESULT]${NC} Saw $UNIQUE_HOSTNAMES unique hostnames out of $TOTAL_PODS expected pods"

if [ "$UNIQUE_HOSTNAMES" -eq "$TOTAL_PODS" ]; then
    echo -e "${GREEN}[PASS]${NC} Load balancing working correctly"
elif [ "$UNIQUE_HOSTNAMES" -gt 1 ]; then
    echo -e "${YELLOW}[PARTIAL]${NC} Load balancing partially working"
else
    echo -e "${YELLOW}[INFO]${NC} All requests went to the same pod (may be normal with few requests)"
fi

# Performance test (optional)
echo ""
echo -e "${YELLOW}[TEST 4]${NC} Quick performance test (20 concurrent requests)..."

# Use curl with xargs for simple concurrency test
seq 20 | xargs -I {} -P 10 curl -s -w "Time: %{time_total}s\n" -o /dev/null "$SERVICE_URL" | \
    awk '{sum += $2; count++} END {if (count > 0) printf "Average response time: %.3fs\n", sum/count}'

echo ""
echo -e "${GREEN}[COMPLETE]${NC} Testing completed!"
echo ""
echo -e "${BLUE}[INFO]${NC} Application is accessible at: $SERVICE_URL"
echo -e "${BLUE}[LOGS]${NC} To view application logs: kubectl logs -l app=hostname-server"
echo -e "${BLUE}[SCALE]${NC} To scale the application: kubectl scale deployment hostname-server --replicas=<number>"
