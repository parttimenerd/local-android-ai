#!/usr/bin/env bash
set -euo pipefail

# HTTPBin K3s Deployment Script
# Manages httpbin deployments on remote K3s single-node cluster via Tailscale funnel
#
# Usage: ./httpbin.sh COMMAND -u FUNNEL_URL -k SECRET_KEY [OPTIONS]

SCRIPT_VERSION="1.0.0"
SCRIPT_NAME="$(basename "$0")"

# Default values
FUNNEL_URL=""
SECRET_KEY=""
NAMESPACE="default"
VERBOSE=false
DRY_RUN=false
COMMAND=""

# Exit codes
EXIT_SUCCESS=0
EXIT_INVALID_ARGS=1
EXIT_NETWORK_FAILED=2
EXIT_DEPLOY_FAILED=3

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log() {
    echo -e "${GREEN}[INFO]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

log_verbose() {
    if [ "$VERBOSE" = true ]; then
        echo -e "${YELLOW}[VERBOSE]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
    fi
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1" >&2
}

log_step() {
    echo -e "\n${BLUE}==== $1 ====${NC}" >&2
}

show_help() {
    cat << EOF
HTTPBin K3s Deployment Script v${SCRIPT_VERSION}

Manages httpbin deployments on a remote K3s single-node cluster via Tailscale funnel.

USAGE:
    $SCRIPT_NAME COMMAND -u FUNNEL_URL -k SECRET_KEY [OPTIONS]

COMMANDS:
    deploy          Deploy httpbin to the cluster
    undeploy        Remove httpbin from the cluster
    status          Show deployment status and access information
    test            Test httpbin endpoints and connectivity

REQUIRED PARAMETERS:
    -u, --url FUNNEL_URL       Tailscale funnel URL of the K3s cluster
    -k, --secret-key KEY       Secret key for kubeconfig access

OPTIONS:
    -n, --namespace NAMESPACE  Kubernetes namespace (default: default)
    --verbose                  Enable verbose logging
    --dry-run                  Show what would be done without executing
    --help                     Show this help message

EXAMPLES:
    # Deploy httpbin
    $SCRIPT_NAME deploy -u https://phone-01.tailxxxx.ts.net -k mySecretKey123

    # Check status
    $SCRIPT_NAME status -u https://phone-01.tailxxxx.ts.net -k mySecretKey123

    # Test endpoints
    $SCRIPT_NAME test -u https://phone-01.tailxxxx.ts.net -k mySecretKey123

    # Remove deployment
    $SCRIPT_NAME undeploy -u https://phone-01.tailxxxx.ts.net -k mySecretKey123

    # Deploy to specific namespace
    $SCRIPT_NAME deploy -u https://phone-01.tailxxxx.ts.net -k mySecretKey123 -n web-apps

    # Verbose mode
    $SCRIPT_NAME status -u https://phone-01.tailxxxx.ts.net -k mySecretKey123 --verbose

HTTPBIN FEATURES:
    • HTTP request/response testing service
    • JSON responses for debugging and testing
    • Various endpoints: /get, /post, /headers, /status/*, etc.
    • Accessible via NodePort on port 30080

REQUIREMENTS:
    • kubectl installed locally
    • Network access to Tailscale funnel URL
    • Valid secret key for kubeconfig access

EOF
}

# Parse command line arguments
parse_args() {
    if [[ $# -eq 0 ]]; then
        log_error "No command provided"
        show_help
        exit $EXIT_INVALID_ARGS
    fi

    # First argument is the command
    COMMAND="$1"
    shift

    # Validate command
    case "$COMMAND" in
        deploy|undeploy|status|test)
            ;;
        --help)
            show_help
            exit $EXIT_SUCCESS
            ;;
        *)
            log_error "Unknown command: $COMMAND"
            log_error "Valid commands: deploy, undeploy, status, test"
            show_help
            exit $EXIT_INVALID_ARGS
            ;;
    esac

    while [[ $# -gt 0 ]]; do
        case $1 in
            -u|--url)
                FUNNEL_URL="$2"
                shift 2
                ;;
            -k|--secret-key)
                SECRET_KEY="$2"
                shift 2
                ;;
            -n|--namespace)
                NAMESPACE="$2"
                shift 2
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            --help)
                show_help
                exit $EXIT_SUCCESS
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                exit $EXIT_INVALID_ARGS
                ;;
        esac
    done

    # Validate required parameters
    if [ -z "$FUNNEL_URL" ]; then
        log_error "Funnel URL is required (-u/--url)"
        exit $EXIT_INVALID_ARGS
    fi

    if [ -z "$SECRET_KEY" ]; then
        log_error "Secret key is required (-k/--secret-key)"
        exit $EXIT_INVALID_ARGS
    fi
}

# Check prerequisites
check_prerequisites() {
    log_step "Checking prerequisites..."

    # Check kubectl
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed or not in PATH"
        log_error "Please install kubectl: https://kubernetes.io/docs/tasks/tools/"
        exit $EXIT_INVALID_ARGS
    fi

    log_verbose "kubectl is available"

    # Check curl
    if ! command -v curl &> /dev/null; then
        log_error "curl is not installed or not in PATH"
        exit $EXIT_INVALID_ARGS
    fi

    log_verbose "curl is available"
    log "✅ Prerequisites check passed"
}

# Setup kubeconfig
setup_kubeconfig() {
    log_step "Setting up kubeconfig..."

    local temp_kubeconfig="/tmp/k3s-kubeconfig-$$.yaml"
    local kubeconfig_url
    
    # Check if the URL already contains /kubeconfig path
    if [[ "$FUNNEL_URL" == *"/kubeconfig"* ]]; then
        kubeconfig_url="${FUNNEL_URL}?key=${SECRET_KEY}"
    else
        kubeconfig_url="${FUNNEL_URL}/kubeconfig?key=${SECRET_KEY}"
    fi

    log_verbose "Downloading kubeconfig from: $kubeconfig_url"

    # Download kubeconfig
    if ! curl -s --connect-timeout 15 --max-time 30 "$kubeconfig_url" -o "$temp_kubeconfig" 2>/dev/null; then
        log_error "Failed to download kubeconfig from: $kubeconfig_url"
        log_error "Check:"
        log_error "  • Funnel URL is correct and accessible"
        log_error "  • Secret key is correct"
        log_error "  • Network connectivity to the cluster"
        exit $EXIT_NETWORK_FAILED
    fi

    # Validate kubeconfig
    if [ ! -s "$temp_kubeconfig" ]; then
        log_error "Downloaded kubeconfig is empty"
        exit $EXIT_NETWORK_FAILED
    fi

    # Test cluster connectivity
    log_verbose "Testing cluster connectivity..."
    if ! kubectl --kubeconfig="$temp_kubeconfig" cluster-info --request-timeout=15s >/dev/null 2>&1; then
        log_error "Failed to connect to K3s cluster"
        log_error "Check cluster status and network connectivity"
        rm -f "$temp_kubeconfig"
        exit $EXIT_NETWORK_FAILED
    fi

    log "✅ Successfully connected to K3s cluster"
    echo "$temp_kubeconfig"
}

# Ensure namespace exists
ensure_namespace() {
    local kubeconfig="$1"
    
    if [ "$NAMESPACE" != "default" ]; then
        log_verbose "Checking namespace: $NAMESPACE"
        if ! kubectl --kubeconfig="$kubeconfig" get namespace "$NAMESPACE" >/dev/null 2>&1; then
            log "Creating namespace: $NAMESPACE"
            if [ "$DRY_RUN" = false ]; then
                kubectl --kubeconfig="$kubeconfig" create namespace "$NAMESPACE"
            else
                log "DRY RUN: Would create namespace $NAMESPACE"
            fi
        fi
    fi
}

# Ensure required funnel ports are configured
ensure_funnel_ports() {
    log "Checking and configuring required Tailscale funnel ports..."
    
    # Get current funnel status
    local funnel_status
    if funnel_status=$(tailscale funnel status 2>/dev/null); then
        log_verbose "Current funnel status:"
        echo "$funnel_status" | while IFS= read -r line; do log_verbose "  $line"; done
    else
        log_warn "Unable to get funnel status"
        return 1
    fi
    
    # Check if required ports are configured
    local has_main_domain=false
    local has_k3s_api=false
    local has_nodeport=false
    
    # Parse funnel status to check for required ports
    if echo "$funnel_status" | grep -q "https://.*\.ts\.net[^:]"; then
        has_main_domain=true
        log_verbose "✅ Main domain (443) - kubeconfig server found"
    fi
    
    if echo "$funnel_status" | grep -q ":6443"; then
        has_k3s_api=true
        log_verbose "✅ Port 6443 - K3s API found"
    fi
    
    if echo "$funnel_status" | grep -q ":30080"; then
        has_nodeport=true
        log_verbose "✅ Port 30080 - NodePort services found"
    fi
    
    # Configure missing ports using the kubeconfig server API
    local base_url
    if [[ "$FUNNEL_URL" == *"/kubeconfig"* ]]; then
        base_url=$(echo "$FUNNEL_URL" | sed 's|/kubeconfig.*||')
    else
        base_url="$FUNNEL_URL"
    fi
    
    local configured_any=false
    
    # Configure port 6443 if missing
    if [ "$has_k3s_api" = false ]; then
        log "Configuring K3s API port (6443)..."
        if response=$(curl -s -X POST "${base_url}/ports/open?key=${SECRET_KEY}&port=6443" 2>/dev/null); then
            if echo "$response" | grep -q '"success".*true'; then
                log "✅ K3s API port (6443) configured successfully"
                configured_any=true
            else
                log_warn "Failed to configure K3s API port (6443)"
                log_verbose "Response: $response"
            fi
        else
            log_warn "Could not communicate with kubeconfig server to configure port 6443"
        fi
    fi
    
    # Configure port 30080 if missing
    if [ "$has_nodeport" = false ]; then
        log "Configuring NodePort services port (30080)..."
        if response=$(curl -s -X POST "${base_url}/ports/open?key=${SECRET_KEY}&port=30080" 2>/dev/null); then
            if echo "$response" | grep -q '"success".*true'; then
                log "✅ NodePort services port (30080) configured successfully"
                configured_any=true
            else
                log_warn "Failed to configure NodePort services port (30080)"
                log_verbose "Response: $response"
            fi
        else
            log_warn "Could not communicate with kubeconfig server to configure port 30080"
        fi
    fi
    
    # If we configured any ports, wait a moment for them to become active
    if [ "$configured_any" = true ]; then
        log "Waiting for funnel configuration to take effect..."
        sleep 5
        
        # Get updated funnel status
        if funnel_status=$(tailscale funnel status 2>/dev/null); then
            log_verbose "Updated funnel status:"
            echo "$funnel_status" | while IFS= read -r line; do log_verbose "  $line"; done
        fi
    fi
    
    # Final status check
    local missing_ports=()
    if [ "$has_main_domain" = false ] && ! echo "$(tailscale funnel status 2>/dev/null)" | grep -q "https://.*\.ts\.net[^:]"; then
        missing_ports+=("main domain (443)")
    fi
    if [ "$has_k3s_api" = false ] && ! echo "$(tailscale funnel status 2>/dev/null)" | grep -q ":6443"; then
        missing_ports+=("K3s API (6443)")
    fi
    if [ "$has_nodeport" = false ] && ! echo "$(tailscale funnel status 2>/dev/null)" | grep -q ":30080"; then
        missing_ports+=("NodePort (30080)")
    fi
    
    if [ ${#missing_ports[@]} -eq 0 ]; then
        log "✅ All required funnel ports are configured"
        return 0
    else
        log_warn "⚠️  Some ports may still be missing: ${missing_ports[*]}"
        log "Note: HTTPBin will still be deployed, but external access may be limited"
        log "Manual configuration may be required with: tailscale funnel --help"
        return 1
    fi
}

# Deploy httpbin
deploy_httpbin() {
    local kubeconfig="$1"
    
    log_step "Deploying httpbin..."

    # Ensure all required funnel ports are configured before deployment
    # This verifies that the three required ports are open:
    # - Main domain (443): kubeconfig server for getting kube file, ports, status
    # - K3s API (6443): kubectl access  
    # - NodePort (30080): HTTPBin external access
    ensure_funnel_ports

    ensure_namespace "$kubeconfig"

    # Check if HTTPBin is already deployed and remove it first
    if kubectl --kubeconfig="$kubeconfig" get deployment httpbin -n "$NAMESPACE" >/dev/null 2>&1; then
        log "Found existing HTTPBin deployment, removing it first..."
        
        if [ "$DRY_RUN" = true ]; then
            log "DRY RUN: Would remove existing httpbin deployment"
        else
            # Remove existing deployment and service
            kubectl --kubeconfig="$kubeconfig" delete deployment httpbin -n "$NAMESPACE" --ignore-not-found=true
            kubectl --kubeconfig="$kubeconfig" delete service httpbin -n "$NAMESPACE" --ignore-not-found=true
            
            # Wait for pods to be terminated
            log "Waiting for existing HTTPBin pods to terminate..."
            local wait_count=0
            while kubectl --kubeconfig="$kubeconfig" get pods -l app=httpbin -n "$NAMESPACE" --no-headers 2>/dev/null | grep -q .; do
                if [ $wait_count -ge 30 ]; then
                    log_warn "Timeout waiting for pods to terminate, proceeding anyway..."
                    break
                fi
                sleep 2
                wait_count=$((wait_count + 1))
            done
            
            log "✅ Existing HTTPBin deployment removed"
        fi
    fi

    if [ "$DRY_RUN" = true ]; then
        log "DRY RUN: Would deploy httpbin to namespace $NAMESPACE"
        return 0
    fi

    # Create httpbin deployment
    cat <<EOF | kubectl --kubeconfig="$kubeconfig" apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: httpbin
  namespace: $NAMESPACE
  labels:
    app: httpbin
spec:
  replicas: 1
  selector:
    matchLabels:
      app: httpbin
  template:
    metadata:
      labels:
        app: httpbin
    spec:
      containers:
      - name: httpbin
        image: kennethreitz/httpbin
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "64Mi"
            cpu: "50m"
          limits:
            memory: "128Mi"
            cpu: "100m"
        readinessProbe:
          httpGet:
            path: /get
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /get
            port: 80
          initialDelaySeconds: 15
          periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: httpbin
  namespace: $NAMESPACE
  labels:
    app: httpbin
spec:
  type: NodePort
  ports:
  - port: 80
    targetPort: 80
    nodePort: 30080
  selector:
    app: httpbin
EOF

    # Wait for deployment to be ready
    log "Waiting for httpbin deployment to be ready..."
    if kubectl --kubeconfig="$kubeconfig" wait --for=condition=available --timeout=120s deployment/httpbin -n "$NAMESPACE"; then
        log "✅ HTTPBin deployed successfully"
        
        # Test that the deployment is actually working
        test_deployment_endpoints "$kubeconfig"
        
        show_access_info "$kubeconfig"
    else
        log_error "HTTPBin deployment failed or timed out"
        log "Check deployment status with: kubectl get pods -n $NAMESPACE"
        exit $EXIT_DEPLOY_FAILED
    fi
}

# Test deployment endpoints after deployment
test_deployment_endpoints() {
    local kubeconfig="$1"
    
    log "Testing deployment endpoints..."
    
    # Extract base URL from FUNNEL_URL (remove /kubeconfig path if present)
    local base_url
    if [[ "$FUNNEL_URL" == *"/kubeconfig"* ]]; then
        base_url=$(echo "$FUNNEL_URL" | sed 's|/kubeconfig.*||')
    else
        base_url="$FUNNEL_URL"
    fi
    
    # Extract domain and construct HTTP URL for port 30080
    local domain
    domain=$(echo "$base_url" | sed 's|https\?://||')
    local cluster_url="http://${domain}:30080"
    
    # Test basic connectivity with a simple GET request
    log_verbose "Testing HTTPBin connectivity at: $cluster_url"
    local max_attempts=6
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        log_verbose "Testing attempt $attempt/$max_attempts..."
        
        if response=$(curl -s --connect-timeout 5 --max-time 10 "$cluster_url/get" 2>/dev/null); then
            if echo "$response" | grep -q '"url"'; then
                log "✅ HTTPBin is responding correctly via funnel"
                return 0
            else
                log_verbose "Response received but format unexpected"
            fi
        else
            log_verbose "No response received"
        fi
        
        if [ $attempt -lt $max_attempts ]; then
            log_verbose "Waiting 10 seconds before retry..."
            sleep 10
        fi
        attempt=$((attempt + 1))
    done
    
    log_warn "⚠️  HTTPBin deployment succeeded but external access via funnel may not be working"
    log "This could be due to:"
    log "  • Funnel configuration still propagating (try again in a few minutes)"
    log "  • Network connectivity issues"
    log "  • Port 30080 not properly exposed via Tailscale funnel"
    log "Manual testing: curl $cluster_url/get"
    return 1
}

# Undeploy httpbin
undeploy_httpbin() {
    local kubeconfig="$1"
    
    log_step "Removing httpbin deployment..."

    if [ "$DRY_RUN" = true ]; then
        log "DRY RUN: Would remove httpbin from namespace $NAMESPACE"
        return 0
    fi

    # Check if deployment exists
    if ! kubectl --kubeconfig="$kubeconfig" get deployment httpbin -n "$NAMESPACE" >/dev/null 2>&1; then
        log_warn "HTTPBin deployment not found in namespace $NAMESPACE"
        return 0
    fi

    # Remove deployment and service
    log "Removing httpbin deployment and service..."
    kubectl --kubeconfig="$kubeconfig" delete deployment httpbin -n "$NAMESPACE" --ignore-not-found=true
    kubectl --kubeconfig="$kubeconfig" delete service httpbin -n "$NAMESPACE" --ignore-not-found=true

    log "✅ HTTPBin deployment removed successfully"
}

# Show access information
show_access_info() {
    local kubeconfig="$1"
    
    log_step "Access Information"

    # Get service details
    local nodeport
    if nodeport=$(kubectl --kubeconfig="$kubeconfig" get service httpbin -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null); then
        # Extract domain from FUNNEL_URL (remove /kubeconfig path if present)
        local base_url
        if [[ "$FUNNEL_URL" == *"/kubeconfig"* ]]; then
            base_url=$(echo "$FUNNEL_URL" | sed 's|/kubeconfig.*||')
        else
            base_url="$FUNNEL_URL"
        fi
        
        # Extract domain and construct HTTP URL for port 30080
        local domain
        domain=$(echo "$base_url" | sed 's|https\?://||')
        local cluster_url="http://${domain}:30080"
        
        log "🌐 HTTPBin Access URLs:"
        log "   External: $cluster_url"
        log "   Cluster:  http://httpbin.$NAMESPACE.svc.cluster.local"
        echo
        log "🧪 Example requests:"
        log "   curl $cluster_url/get"
        log "   curl $cluster_url/status/200"
        log "   curl -X POST $cluster_url/post -d '{\"test\":\"data\"}'"
        log "   curl $cluster_url/headers"
        echo
        log "📄 Full endpoint list: $cluster_url"
        echo
        log "ℹ️  Note: Port 30080 is exposed via Tailscale funnel for NodePort services"
    else
        log_warn "Could not retrieve service information"
    fi
}

# Show deployment status
show_status() {
    local kubeconfig="$1"
    
    log_step "HTTPBin Deployment Status"

    # Check if deployment exists
    if ! kubectl --kubeconfig="$kubeconfig" get deployment httpbin -n "$NAMESPACE" >/dev/null 2>&1; then
        log "❌ HTTPBin deployment not found in namespace $NAMESPACE"
        log "Run '$SCRIPT_NAME deploy -u $FUNNEL_URL -k ***' to deploy"
        return 1
    fi

    # Get deployment status
    log "📊 Deployment Status:"
    kubectl --kubeconfig="$kubeconfig" get deployment httpbin -n "$NAMESPACE" -o wide

    echo
    log "📦 Pod Status:"
    kubectl --kubeconfig="$kubeconfig" get pods -l app=httpbin -n "$NAMESPACE" -o wide

    echo
    log "🌐 Service Status:"
    kubectl --kubeconfig="$kubeconfig" get service httpbin -n "$NAMESPACE" -o wide

    # Show access info if deployment is ready
    local ready_replicas
    ready_replicas=$(kubectl --kubeconfig="$kubeconfig" get deployment httpbin -n "$NAMESPACE" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
    
    if [ "$ready_replicas" -gt 0 ]; then
        echo
        show_access_info "$kubeconfig"
    else
        echo
        log_warn "HTTPBin deployment is not ready yet"
        log "Check pod logs with: kubectl logs -l app=httpbin -n $NAMESPACE"
    fi
}

# Test httpbin endpoints
test_httpbin() {
    local kubeconfig="$1"
    
    log_step "Testing HTTPBin endpoints..."

    # Check if deployment exists and is ready
    if ! kubectl --kubeconfig="$kubeconfig" get deployment httpbin -n "$NAMESPACE" >/dev/null 2>&1; then
        log_error "HTTPBin deployment not found in namespace $NAMESPACE"
        log "Run '$SCRIPT_NAME deploy -u $FUNNEL_URL -k ***' to deploy first"
        exit $EXIT_DEPLOY_FAILED
    fi

    # Get service nodeport (for reference, but we'll use the standard funnel port)
    local nodeport
    if ! nodeport=$(kubectl --kubeconfig="$kubeconfig" get service httpbin -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null); then
        log_error "Could not get httpbin service port"
        exit $EXIT_DEPLOY_FAILED
    fi

    # Extract base URL from FUNNEL_URL (remove /kubeconfig path if present)
    local base_url
    if [[ "$FUNNEL_URL" == *"/kubeconfig"* ]]; then
        base_url=$(echo "$FUNNEL_URL" | sed 's|/kubeconfig.*||')
    else
        base_url="$FUNNEL_URL"
    fi
    
    # Extract domain and construct HTTP URL for port 30080
    local domain
    domain=$(echo "$base_url" | sed 's|https\?://||')
    local cluster_url="http://${domain}:30080"
    log "🧪 Testing HTTPBin at: $cluster_url"
    log_verbose "Note: Using funnel port 30080 (NodePort $nodeport mapped via Tailscale funnel)"
    echo

    # Test basic GET endpoint
    log "Testing GET /get..."
    if response=$(curl -s --connect-timeout 10 --max-time 15 "$cluster_url/get" 2>/dev/null); then
        if echo "$response" | grep -q '"url"'; then
            log "✅ GET /get - OK"
            log_verbose "Response contains expected JSON structure"
        else
            log_warn "⚠️  GET /get - Unexpected response format"
            log_verbose "Response: $response"
        fi
    else
        log_error "❌ GET /get - Failed"
    fi

    # Test status endpoint
    log "Testing GET /status/200..."
    if curl -s --connect-timeout 10 --max-time 15 "$cluster_url/status/200" >/dev/null 2>&1; then
        log "✅ GET /status/200 - OK"
    else
        log_error "❌ GET /status/200 - Failed"
    fi

    # Test headers endpoint
    log "Testing GET /headers..."
    if response=$(curl -s --connect-timeout 10 --max-time 15 "$cluster_url/headers" 2>/dev/null); then
        if echo "$response" | grep -q '"headers"'; then
            log "✅ GET /headers - OK"
            log_verbose "Headers endpoint working correctly"
        else
            log_warn "⚠️  GET /headers - Unexpected response"
        fi
    else
        log_error "❌ GET /headers - Failed"
    fi

    # Test POST endpoint
    log "Testing POST /post..."
    if response=$(curl -s --connect-timeout 10 --max-time 15 -X POST -H "Content-Type: application/json" -d '{"test":"data"}' "$cluster_url/post" 2>/dev/null); then
        if echo "$response" | grep -q '"json"' && echo "$response" | grep -q '"test"'; then
            log "✅ POST /post - OK"
            log_verbose "POST data correctly echoed back"
        else
            log_warn "⚠️  POST /post - Unexpected response format"
        fi
    else
        log_error "❌ POST /post - Failed"
    fi

    echo
    log "🌐 Access URLs:"
    log "   Main: $cluster_url"
    log "   Docs: $cluster_url/html"
    log "   Spec: $cluster_url/spec.json"
}

# Global temp kubeconfig file for cleanup
TEMP_KUBECONFIG=""

# Cleanup function
cleanup_on_exit() {
    if [ -n "$TEMP_KUBECONFIG" ] && [ -f "$TEMP_KUBECONFIG" ]; then
        rm -f "$TEMP_KUBECONFIG"
    fi
}

# Main function
main() {
    if [ "$DRY_RUN" = true ]; then
        log "🔍 DRY RUN MODE - No changes will be made"
    fi

    # Set up cleanup trap
    trap cleanup_on_exit EXIT

    check_prerequisites
    
    local kubeconfig
    kubeconfig=$(setup_kubeconfig)
    TEMP_KUBECONFIG="$kubeconfig"

    case "$COMMAND" in
        deploy)
            deploy_httpbin "$kubeconfig"
            ;;
        undeploy)
            undeploy_httpbin "$kubeconfig"
            ;;
        status)
            show_status "$kubeconfig"
            ;;
        test)
            test_httpbin "$kubeconfig"
            ;;
        *)
            log_error "Unknown command: $COMMAND"
            exit $EXIT_INVALID_ARGS
            ;;
    esac
}

# Script entry point
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    parse_args "$@"
    main
fi
