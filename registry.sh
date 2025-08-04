#!/bin/bash

# Local Docker Registry Management Script for K3s
# This script manages a local Docker registry for K3s clusters
# Supports setup, configuration, image management, and cluster integration

set -e

# Script version
VERSION="1.0.0"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Registry configuration
REGISTRY_NAME="k3s-local-registry"
REGISTRY_PORT="5000"
REGISTRY_DATA_DIR="/var/lib/k3s-registry"
REGISTRY_CONFIG_DIR="/etc/k3s-registry"

# Logging functions
log() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_verbose() {
    if [ "$VERBOSE" = true ]; then
        echo -e "${YELLOW}[VERBOSE]${NC} $1"
    fi
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# Help function
show_help() {
    cat << EOF
Local Docker Registry Management Script v${VERSION}

Manages a local Docker registry for K3s clusters with automatic node configuration.

USAGE:
    ./setup-registry.sh COMMAND [OPTIONS]

COMMANDS:
    setup                       Set up and start the local registry
    start                       Start the registry service
    stop                        Stop the registry service
    restart                     Restart the registry service
    status                      Show registry status and information
    configure-k3s               Configure K3s nodes to use the registry
    list, ls                    List all images in the registry
    delete IMAGE[:TAG]          Delete an image from the registry
    push IMAGE[:TAG]            Tag and push a local image to the registry
    pull IMAGE[:TAG]            Pull an image from the registry
    cleanup                     Remove unused registry data
    remove                      Completely remove the registry and data
    info                        Show detailed registry information
    address                     Show registry address for use in manifests
    logs                        Show registry container logs

OPTIONS:
    -p, --port PORT             Registry port (default: 5000)
    -d, --data-dir DIR          Registry data directory (default: /var/lib/k3s-registry)
    -n, --name NAME             Registry container name (default: k3s-local-registry)
    -v, --verbose               Enable verbose output
    -h, --help                  Show this help message
    --version                   Show version information

EXAMPLES:
    # Set up the registry and configure K3s nodes
    ./setup-registry.sh setup

    # List all images in the registry
    ./setup-registry.sh list

    # Push a local image to the registry
    ./setup-registry.sh push my-app:latest

    # Delete an image from the registry
    ./setup-registry.sh delete my-app:latest

    # Show registry status
    ./setup-registry.sh status

    # Configure K3s to use custom port
    ./setup-registry.sh setup -p 5001

NOTES:
    - Registry runs on all network interfaces (0.0.0.0)
    - Data persists in $REGISTRY_DATA_DIR
    - K3s nodes are automatically configured to use the registry
    - Registry is configured as insecure (HTTP) for local development
    - Use HTTPS and authentication for production environments

EOF
}

# Function to check dependencies
check_dependencies() {
    local missing=()
    
    if ! command -v docker &>/dev/null; then
        missing+=("docker")
    fi
    
    if ! command -v k3s &>/dev/null; then
        missing+=("k3s")
    fi
    
    if ! command -v curl &>/dev/null; then
        missing+=("curl")
    fi
    
    if [ ${#missing[@]} -gt 0 ]; then
        log_error "Missing required dependencies: ${missing[*]}"
        echo ""
        echo "Please install the missing dependencies:"
        for dep in "${missing[@]}"; do
            case "$dep" in
                docker)
                    echo "  Docker: https://docs.docker.com/engine/install/"
                    ;;
                k3s)
                    echo "  K3s: curl -sfL https://get.k3s.io | sh -"
                    ;;
                curl)
                    echo "  curl: sudo apt-get install curl"
                    ;;
            esac
        done
        exit 1
    fi
}

# Function to get master node IP
get_master_ip() {
    # Try multiple methods to get the master IP
    local master_ip
    
    # Method 1: Use hostname -I
    master_ip=$(hostname -I | awk '{print $1}' 2>/dev/null || echo "")
    
    # Method 2: Use ip route
    if [ -z "$master_ip" ]; then
        master_ip=$(ip route get 8.8.8.8 | awk '{print $7; exit}' 2>/dev/null || echo "")
    fi
    
    # Method 3: Use kubectl to get node IP
    if [ -z "$master_ip" ] && command -v k3s &>/dev/null; then
        local node_name
        node_name=$(hostname)
        master_ip=$(sudo k3s kubectl get node "$node_name" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "")
    fi
    
    # Fallback
    if [ -z "$master_ip" ]; then
        master_ip="localhost"
    fi
    
    echo "$master_ip"
}

# Function to get registry address
get_registry_address() {
    local master_ip
    master_ip=$(get_master_ip)
    echo "${master_ip}:${REGISTRY_PORT}"
}

# Function to check if registry is running
is_registry_running() {
    docker ps --filter "name=$REGISTRY_NAME" --filter "status=running" | grep -q "$REGISTRY_NAME"
}

# Function to check if registry container exists
registry_exists() {
    docker ps -a --filter "name=$REGISTRY_NAME" | grep -q "$REGISTRY_NAME"
}

# Function to configure Docker daemon for insecure registries
configure_docker_insecure_registry() {
    local master_ip="$1"
    local registry_address="${master_ip}:${REGISTRY_PORT}"
    
    log "Configuring Docker daemon for insecure registry: $registry_address"
    
    # Create or update Docker daemon configuration
    local daemon_config="/etc/docker/daemon.json"
    local temp_config="/tmp/docker-daemon.json"
    
    # Check if daemon.json exists
    if [ -f "$daemon_config" ]; then
        # Parse existing configuration and add insecure registry
        if command -v jq &>/dev/null; then
            # Use jq if available for proper JSON manipulation
            sudo jq --arg registry "$registry_address" \
                '.["insecure-registries"] += [$registry] | .["insecure-registries"] |= unique' \
                "$daemon_config" | sudo tee "$temp_config" >/dev/null
        else
            # Fallback: simple text manipulation (less robust)
            if grep -q "insecure-registries" "$daemon_config"; then
                # Add to existing insecure-registries array
                sudo sed "s/\"insecure-registries\"[[:space:]]*:[[:space:]]*\[\([^]]*\)\]/\"insecure-registries\": [\1, \"$registry_address\"]/g" \
                    "$daemon_config" | sudo tee "$temp_config" >/dev/null
            else
                # Add insecure-registries to existing config
                sudo sed 's/{/{\n  "insecure-registries": ["'"$registry_address"'"],/' \
                    "$daemon_config" | sudo tee "$temp_config" >/dev/null
            fi
        fi
    else
        # Create new daemon.json
        sudo tee "$temp_config" >/dev/null << EOF
{
  "insecure-registries": ["$registry_address"]
}
EOF
    fi
    
    # Validate JSON and apply
    if command -v jq &>/dev/null && jq . "$temp_config" >/dev/null 2>&1; then
        sudo cp "$temp_config" "$daemon_config"
        sudo chmod 644 "$daemon_config"
        log "✅ Docker daemon configuration updated"
        
        # Restart Docker daemon
        log "Restarting Docker daemon..."
        if sudo systemctl restart docker; then
            log "✅ Docker daemon restarted successfully"
            
            # Wait for Docker to be ready
            for i in {1..30}; do
                if docker info >/dev/null 2>&1; then
                    log "✅ Docker daemon is ready"
                    break
                fi
                if [ $i -eq 30 ]; then
                    log_error "Docker daemon failed to restart properly"
                    return 1
                fi
                sleep 1
            done
        else
            log_error "Failed to restart Docker daemon"
            return 1
        fi
    else
        log_error "Failed to create valid Docker daemon configuration"
        return 1
    fi
    
    # Cleanup
    sudo rm -f "$temp_config" 2>/dev/null || true
}

# Function to setup the registry
setup_registry() {
    log_step "Setting up local Docker registry..."
    
    # Create data directory
    log "Creating registry data directory: $REGISTRY_DATA_DIR"
    sudo mkdir -p "$REGISTRY_DATA_DIR"
    sudo chown "$(whoami):$(whoami)" "$REGISTRY_DATA_DIR" 2>/dev/null || true
    
    # Create config directory
    sudo mkdir -p "$REGISTRY_CONFIG_DIR"
    
    # Remove existing container if it exists
    if registry_exists; then
        log "Removing existing registry container..."
        docker rm -f "$REGISTRY_NAME" >/dev/null 2>&1 || true
    fi
    
    # Pull registry image
    log "Pulling registry image..."
    docker pull registry:2
    
    # Start registry container
    log "Starting registry container on port $REGISTRY_PORT..."
    docker run -d \
        --restart=always \
        --name "$REGISTRY_NAME" \
        -p "$REGISTRY_PORT:5000" \
        -v "$REGISTRY_DATA_DIR:/var/lib/registry" \
        -e REGISTRY_STORAGE_DELETE_ENABLED=true \
        registry:2
    
    # Wait for registry to be ready
    log "Waiting for registry to be ready..."
    local master_ip
    master_ip=$(get_master_ip)
    
    for i in {1..30}; do
        if curl -s "http://$master_ip:$REGISTRY_PORT/v2/" | grep -q "{}"; then
            log "✅ Registry is ready and responding"
            break
        fi
        if [ $i -eq 30 ]; then
            log_error "Registry failed to start properly"
            return 1
        fi
        sleep 1
    done
    
    # Configure Docker daemon for insecure registry
    configure_docker_insecure_registry "$master_ip"
    
    # Configure K3s nodes
    configure_k3s_nodes
    
    echo ""
    log "🎉 Registry setup completed!"
    log "Registry URL: http://$master_ip:$REGISTRY_PORT"
    log "Registry API: http://$master_ip:$REGISTRY_PORT/v2/"
    log "Data directory: $REGISTRY_DATA_DIR"
    echo ""
    log "Next steps:"
    echo "  • Push images: ./setup-registry.sh push my-image:tag"
    echo "  • List images: ./setup-registry.sh list"
    echo "  • Check status: ./setup-registry.sh status"
}

# Function to configure K3s nodes to use the registry
configure_k3s_nodes() {
    log_step "Configuring K3s nodes to use local registry..."
    
    local master_ip
    master_ip=$(get_master_ip)
    local registry_url="$master_ip:$REGISTRY_PORT"
    
    # Create registries.yaml for K3s
    local registries_config="/etc/rancher/k3s/registries.yaml"
    
    log "Creating K3s registry configuration..."
    sudo mkdir -p /etc/rancher/k3s
    
    cat << EOF | sudo tee "$registries_config" > /dev/null
mirrors:
  "$registry_url":
    endpoint:
      - "http://$registry_url"
configs:
  "$registry_url":
    insecure_skip_verify: true
EOF
    
    log "✅ Master node registry configuration created"
    
    # Restart K3s server
    log "Restarting K3s server to apply registry configuration..."
    sudo systemctl restart k3s
    
    # Wait for K3s to be ready
    log "Waiting for K3s server to be ready..."
    for i in {1..60}; do
        if sudo k3s kubectl get nodes &>/dev/null; then
            log "✅ K3s server is ready"
            break
        fi
        if [ $i -eq 60 ]; then
            log_warn "K3s server took longer than expected to restart"
            break
        fi
        sleep 1
    done
    
    # Configure agent nodes
    log "Configuring agent nodes..."
    local agent_nodes
    agent_nodes=$(sudo k3s kubectl get nodes --no-headers | grep -v "control-plane\|master" | awk '{print $1}' 2>/dev/null || echo "")
    
    if [ -n "$agent_nodes" ]; then
        local configured_count=0
        echo "$agent_nodes" | while read -r node; do
            if [ -n "$node" ]; then
                local node_ip
                node_ip=$(sudo k3s kubectl get node "$node" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "$node")
                
                log "Configuring agent node: $node ($node_ip)"
                
                # Test SSH connectivity
                if ssh -n -o ConnectTimeout=5 -o BatchMode=yes root@"$node_ip" true 2>/dev/null; then
                    # Copy registry config
                    if ssh root@"$node_ip" "mkdir -p /etc/rancher/k3s" && \
                       scp "$registries_config" root@"$node_ip":"$registries_config" 2>/dev/null; then
                        
                        # Restart K3s agent
                        if ssh -n root@"$node_ip" "systemctl restart k3s-agent" 2>/dev/null; then
                            log "✅ Agent node $node configured successfully"
                            configured_count=$((configured_count + 1))
                        else
                            log_warn "⚠ Failed to restart K3s agent on $node"
                        fi
                    else
                        log_warn "⚠ Failed to copy registry config to $node"
                    fi
                else
                    log_warn "⚠ Cannot SSH to $node ($node_ip) - manual configuration needed"
                    echo "   Manual setup: scp $registries_config root@$node_ip:$registries_config"
                    echo "   Then restart: ssh root@$node_ip 'systemctl restart k3s-agent'"
                fi
            fi
        done
        
        log "Agent node configuration completed"
    else
        log "No agent nodes found - single node setup"
    fi
    
    log "✅ K3s registry configuration completed"
}

# Function to start the registry
start_registry() {
    log_step "Starting registry..."
    
    if is_registry_running; then
        log "Registry is already running"
        return 0
    fi
    
    if registry_exists; then
        log "Starting existing registry container..."
        docker start "$REGISTRY_NAME"
    else
        log_error "Registry container does not exist. Run 'setup' first."
        return 1
    fi
    
    # Wait for registry to be ready
    local master_ip
    master_ip=$(get_master_ip)
    
    for i in {1..15}; do
        if curl -s "http://$master_ip:$REGISTRY_PORT/v2/" | grep -q "{}"; then
            log "✅ Registry started successfully"
            return 0
        fi
        sleep 1
    done
    
    log_error "Registry failed to start properly"
    return 1
}

# Function to stop the registry
stop_registry() {
    log_step "Stopping registry..."
    
    if is_registry_running; then
        docker stop "$REGISTRY_NAME"
        log "✅ Registry stopped"
    else
        log "Registry is not running"
    fi
}

# Function to show registry status
show_status() {
    log_step "Registry Status"
    echo ""
    
    local master_ip
    master_ip=$(get_master_ip)
    
    # Container status
    if registry_exists; then
        local status
        status=$(docker inspect "$REGISTRY_NAME" --format '{{.State.Status}}' 2>/dev/null || echo "unknown")
        echo "Container Status: $status"
        
        if [ "$status" = "running" ]; then
            echo "✅ Registry is running"
            echo "🌐 Registry URL: http://$master_ip:$REGISTRY_PORT"
            echo "📊 Registry API: http://$master_ip:$REGISTRY_PORT/v2/"
            
            # Test connectivity
            if curl -s "http://$master_ip:$REGISTRY_PORT/v2/" | grep -q "{}"; then
                echo "🔗 Registry is responding"
            else
                echo "⚠ Registry not responding"
            fi
        else
            echo "❌ Registry is not running"
        fi
    else
        echo "❌ Registry container does not exist"
        echo "Run './setup-registry.sh setup' to create the registry"
    fi
    
    echo ""
    
    # Image count
    if is_registry_running; then
        local image_count
        image_count=$(curl -s "http://$master_ip:$REGISTRY_PORT/v2/_catalog" 2>/dev/null | grep -o '"repositories":\[[^]]*\]' | grep -o ',' | wc -l 2>/dev/null || echo "0")
        image_count=$((image_count + 1))
        echo "📦 Images stored: $image_count"
    fi
    
    # Data directory info
    if [ -d "$REGISTRY_DATA_DIR" ]; then
        local data_size
        data_size=$(du -sh "$REGISTRY_DATA_DIR" 2>/dev/null | cut -f1 || echo "unknown")
        echo "💾 Data directory: $REGISTRY_DATA_DIR ($data_size)"
    fi
    
    # K3s configuration status
    if [ -f "/etc/rancher/k3s/registries.yaml" ]; then
        echo "⚙️ K3s configuration: Active"
        if grep -q "$master_ip:$REGISTRY_PORT" "/etc/rancher/k3s/registries.yaml" 2>/dev/null; then
            echo "✅ K3s configured for this registry"
        else
            echo "⚠ K3s configuration may be outdated"
        fi
    else
        echo "⚠ K3s not configured for registry"
    fi
}

# Function to list images in the registry
list_images() {
    log_step "Listing images in registry..."
    
    if ! is_registry_running; then
        log_error "Registry is not running"
        return 1
    fi
    
    local master_ip
    master_ip=$(get_master_ip)
    
    # Get catalog
    local catalog
    catalog=$(curl -s "http://$master_ip:$REGISTRY_PORT/v2/_catalog" 2>/dev/null)
    
    if [ -z "$catalog" ] || ! echo "$catalog" | grep -q "repositories"; then
        log_error "Failed to retrieve image catalog"
        return 1
    fi
    
    # Parse repositories
    local repositories
    repositories=$(echo "$catalog" | grep -o '"[^"]*"' | grep -v "repositories" | tr -d '"' | sort)
    
    if [ -z "$repositories" ]; then
        echo "📦 No images found in registry"
        return 0
    fi
    
    echo "📦 Images in registry:"
    echo ""
    
    local total_images=0
    
    while read -r repo; do
        if [ -n "$repo" ]; then
            echo "Repository: $repo"
            
            # Get tags for this repository
            local tags
            tags=$(curl -s "http://$master_ip:$REGISTRY_PORT/v2/$repo/tags/list" 2>/dev/null)
            
            if echo "$tags" | grep -q "tags"; then
                local tag_list
                tag_list=$(echo "$tags" | grep -o '"[^"]*"' | grep -v -E "(name|tags)" | tr -d '"' | sort)
                
                if [ -n "$tag_list" ]; then
                    while read -r tag; do
                        if [ -n "$tag" ]; then
                            echo "  └── $repo:$tag"
                            total_images=$((total_images + 1))
                        fi
                    done <<< "$tag_list"
                else
                    echo "  └── (no tags)"
                fi
            else
                echo "  └── (error retrieving tags)"
            fi
            echo ""
        fi
    done <<< "$repositories"
    
    echo "Total images: $total_images"
}

# Function to delete an image
delete_image() {
    local image="$1"
    
    if [ -z "$image" ]; then
        log_error "Image name required"
        echo "Usage: ./setup-registry.sh delete IMAGE[:TAG]"
        return 1
    fi
    
    log_step "Deleting image: $image"
    
    if ! is_registry_running; then
        log_error "Registry is not running"
        return 1
    fi
    
    # Parse image name and tag
    local repo tag
    if [[ "$image" == *":"* ]]; then
        repo="${image%:*}"
        tag="${image#*:}"
    else
        repo="$image"
        tag="latest"
    fi
    
    local master_ip
    master_ip=$(get_master_ip)
    
    # Get manifest
    log "Getting manifest for $repo:$tag..."
    local manifest_response
    manifest_response=$(curl -s -i -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
        "http://$master_ip:$REGISTRY_PORT/v2/$repo/manifests/$tag" 2>/dev/null)
    
    if [ -z "$manifest_response" ]; then
        log_error "Failed to get manifest for $repo:$tag"
        return 1
    fi
    
    # Extract digest
    local digest
    digest=$(echo "$manifest_response" | grep -i "docker-content-digest" | awk '{print $2}' | tr -d '\r\n' | head -1)
    
    if [ -z "$digest" ]; then
        log_error "Could not find digest for $repo:$tag"
        echo "Image may not exist or registry doesn't support deletion"
        return 1
    fi
    
    # Delete manifest
    log "Deleting manifest with digest: $digest"
    if curl -s -X DELETE "http://$master_ip:$REGISTRY_PORT/v2/$repo/manifests/$digest" 2>/dev/null; then
        log "✅ Image $repo:$tag deleted successfully"
        echo ""
        log_warn "Note: Registry garbage collection may be needed to free disk space"
        echo "Run: ./setup-registry.sh cleanup"
    else
        log_error "Failed to delete image $repo:$tag"
        return 1
    fi
}

# Function to push an image to the registry
push_image() {
    local image="$1"
    
    if [ -z "$image" ]; then
        log_error "Image name required"
        echo "Usage: ./setup-registry.sh push IMAGE[:TAG]"
        return 1
    fi
    
    log_step "Pushing image to registry: $image"
    
    if ! is_registry_running; then
        log_error "Registry is not running"
        return 1
    fi
    
    # Check if local image exists
    if ! docker images "$image" --format "table {{.Repository}}:{{.Tag}}" | grep -q "$image"; then
        log_error "Local image '$image' not found"
        echo "Available local images:"
        docker images --format "table {{.Repository}}:{{.Tag}}"
        return 1
    fi
    
    local master_ip
    master_ip=$(get_master_ip)
    local registry_url="$master_ip:$REGISTRY_PORT"
    
    # Parse image name and tag
    local image_name tag registry_image
    if [[ "$image" == *":"* ]]; then
        image_name="${image%:*}"
        tag="${image#*:}"
    else
        image_name="$image"
        tag="latest"
        image="$image:latest"
    fi
    
    registry_image="$registry_url/$image_name:$tag"
    
    log "Tagging image: $image → $registry_image"
    docker tag "$image" "$registry_image"
    
    log "Pushing image to registry..."
    if docker push "$registry_image"; then
        log "✅ Image pushed successfully"
        echo ""
        log "Image available as: $registry_image"
        log "Use in K3s deployments with:"
        echo "  image: $registry_image"
        echo "  imagePullPolicy: Always"
    else
        log_error "Failed to push image"
        return 1
    fi
}

# Function to pull an image from the registry
pull_image() {
    local image="$1"
    
    if [ -z "$image" ]; then
        log_error "Image name required"
        echo "Usage: ./setup-registry.sh pull IMAGE[:TAG]"
        return 1
    fi
    
    log_step "Pulling image from registry: $image"
    
    if ! is_registry_running; then
        log_error "Registry is not running"
        return 1
    fi
    
    local master_ip
    master_ip=$(get_master_ip)
    local registry_url="$master_ip:$REGISTRY_PORT"
    
    # Construct full registry image name
    local registry_image="$registry_url/$image"
    
    log "Pulling image: $registry_image"
    if docker pull "$registry_image"; then
        log "✅ Image pulled successfully"
        
        # Tag without registry prefix for convenience
        local local_tag
        if [[ "$image" == *":"* ]]; then
            local_tag="$image"
        else
            local_tag="$image:latest"
        fi
        
        docker tag "$registry_image" "$local_tag"
        log "Tagged locally as: $local_tag"
    else
        log_error "Failed to pull image"
        return 1
    fi
}

# Function to cleanup unused registry data
cleanup_registry() {
    log_step "Cleaning up registry data..."
    
    if ! is_registry_running; then
        log_error "Registry is not running"
        return 1
    fi
    
    log "Running garbage collection in registry container..."
    
    # Run garbage collection
    if docker exec "$REGISTRY_NAME" bin/registry garbage-collect /etc/docker/registry/config.yml; then
        log "✅ Garbage collection completed"
        
        # Show space reclaimed
        if [ -d "$REGISTRY_DATA_DIR" ]; then
            local data_size
            data_size=$(du -sh "$REGISTRY_DATA_DIR" 2>/dev/null | cut -f1 || echo "unknown")
            log "Current data directory size: $data_size"
        fi
    else
        log_error "Garbage collection failed"
        return 1
    fi
}

# Function to show detailed registry information
show_info() {
    log_step "Registry Information"
    echo ""
    
    local master_ip
    master_ip=$(get_master_ip)
    
    echo "🏷️  Registry Details:"
    echo "   Name: $REGISTRY_NAME"
    echo "   Port: $REGISTRY_PORT"
    echo "   URL: http://$master_ip:$REGISTRY_PORT"
    echo "   API: http://$master_ip:$REGISTRY_PORT/v2/"
    echo ""
    
    echo "📁 Storage:"
    echo "   Data directory: $REGISTRY_DATA_DIR"
    echo "   Config directory: $REGISTRY_CONFIG_DIR"
    echo ""
    
    if is_registry_running; then
        echo "🔧 Container Details:"
        docker inspect "$REGISTRY_NAME" --format "   Image: {{.Config.Image}}"
        docker inspect "$REGISTRY_NAME" --format "   Created: {{.Created}}"
        docker inspect "$REGISTRY_NAME" --format "   Started: {{.State.StartedAt}}"
        echo ""
        
        echo "🌐 Network:"
        docker inspect "$REGISTRY_NAME" --format "   IP Address: {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}"
        docker inspect "$REGISTRY_NAME" --format "   Ports: {{range \$p, \$conf := .NetworkSettings.Ports}}{{\$p}} -> {{range \$conf}}{{.HostPort}}{{end}} {{end}}"
        echo ""
        
        echo "💾 Storage Usage:"
        if [ -d "$REGISTRY_DATA_DIR" ]; then
            du -sh "$REGISTRY_DATA_DIR"/* 2>/dev/null | sed 's/^/   /' || echo "   (empty)"
        fi
    else
        echo "⚠️  Registry is not running"
    fi
    
    echo ""
    echo "⚙️  K3s Integration:"
    if [ -f "/etc/rancher/k3s/registries.yaml" ]; then
        echo "   Configuration: /etc/rancher/k3s/registries.yaml"
        if grep -q "$master_ip:$REGISTRY_PORT" "/etc/rancher/k3s/registries.yaml" 2>/dev/null; then
            echo "   Status: ✅ Configured"
        else
            echo "   Status: ⚠️ Configuration may be outdated"
        fi
    else
        echo "   Status: ❌ Not configured"
    fi
}

# Function to show registry logs
show_logs() {
    if registry_exists; then
        log_step "Registry logs:"
        docker logs "$REGISTRY_NAME" --tail 50 -f
    else
        log_error "Registry container does not exist"
        return 1
    fi
}

# Function to completely remove the registry
remove_registry() {
    log_step "Removing registry completely..."
    
    echo "⚠️  This will:"
    echo "   • Stop and remove the registry container"
    echo "   • Delete all registry data in $REGISTRY_DATA_DIR"
    echo "   • Remove K3s registry configuration"
    echo ""
    read -p "Are you sure? (y/N): " -n 1 -r
    echo ""
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log "Operation cancelled"
        return 0
    fi
    
    # Stop and remove container
    if registry_exists; then
        log "Removing registry container..."
        docker rm -f "$REGISTRY_NAME" >/dev/null 2>&1 || true
    fi
    
    # Remove data directory
    if [ -d "$REGISTRY_DATA_DIR" ]; then
        log "Removing data directory..."
        sudo rm -rf "$REGISTRY_DATA_DIR"
    fi
    
    # Remove K3s configuration
    if [ -f "/etc/rancher/k3s/registries.yaml" ]; then
        log "Removing K3s registry configuration..."
        sudo rm -f "/etc/rancher/k3s/registries.yaml"
        
        # Restart K3s to apply changes
        log "Restarting K3s to apply changes..."
        sudo systemctl restart k3s
    fi
    
    log "✅ Registry removed completely"
}

# Parse command line arguments
VERBOSE=false
COMMAND=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -p|--port)
            REGISTRY_PORT="$2"
            shift 2
            ;;
        -d|--data-dir)
            REGISTRY_DATA_DIR="$2"
            shift 2
            ;;
        -n|--name)
            REGISTRY_NAME="$2"
            shift 2
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        --version)
            echo "Local Docker Registry Management Script v${VERSION}"
            exit 0
            ;;
        setup|start|stop|restart|status|configure-k3s|list|ls|delete|push|pull|cleanup|remove|info|address|logs)
            COMMAND="$1"
            shift
            break
            ;;
        *)
            log_error "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Check if command was provided
if [ -z "$COMMAND" ]; then
    log_error "Command required"
    echo ""
    show_help
    exit 1
fi

# Check dependencies for most commands
case "$COMMAND" in
    setup|start|stop|restart|status|configure-k3s|list|delete|push|pull|cleanup|info|logs)
        check_dependencies
        ;;
esac

# Execute command
case "$COMMAND" in
    setup)
        setup_registry
        ;;
    start)
        start_registry
        ;;
    stop)
        stop_registry
        ;;
    restart)
        stop_registry
        sleep 2
        start_registry
        ;;
    status)
        show_status
        ;;
    configure-k3s)
        configure_k3s_nodes
        ;;
    list|ls)
        list_images
        ;;
    delete)
        if [ $# -eq 0 ]; then
            log_error "Image name required for delete command"
            echo "Usage: ./setup-registry.sh delete IMAGE[:TAG]"
            exit 1
        fi
        delete_image "$1"
        ;;
    push)
        if [ $# -eq 0 ]; then
            log_error "Image name required for push command"
            echo "Usage: ./setup-registry.sh push IMAGE[:TAG]"
            exit 1
        fi
        push_image "$1"
        ;;
    pull)
        if [ $# -eq 0 ]; then
            log_error "Image name required for pull command"
            echo "Usage: ./setup-registry.sh pull IMAGE[:TAG]"
            exit 1
        fi
        pull_image "$1"
        ;;
    cleanup)
        cleanup_registry
        ;;
    remove)
        remove_registry
        ;;
    info)
        show_info
        ;;
    address)
        get_registry_address
        ;;
    logs)
        show_logs
        ;;
    *)
        log_error "Unknown command: $COMMAND"
        echo "Use --help for available commands"
        exit 1
        ;;
esac
