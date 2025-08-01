#!/bin/bash

# Docker Image Distribution Script for K3s Multi-Node Clusters
# This script distributes a Docker image from the master node to all agent nodes via SSH

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
IMAGE_NAME="server-info-server:latest"
VERBOSE=false

# Logging functions
log() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_verbose() {
    if [ "$VERBOSE" = true ]; then
        echo -e "${YELLOW}[VERBOSE]${NC} $1"
    fi
}

# Help function
show_help() {
    cat << EOF
Docker Image Distribution Script for K3s

Distributes Docker images from master node to agent nodes via SSH.

USAGE:
    ./distribute-image.sh [IMAGE_NAME] [OPTIONS]

ARGUMENTS:
    IMAGE_NAME              Docker image name (default: server-info-server:latest)

OPTIONS:
    -v, --verbose           Enable verbose output
    -h, --help              Show this help message

EXAMPLES:
    # Distribute default image
    ./distribute-image.sh

    # Distribute specific image
    ./distribute-image.sh my-app:v1.0.0

    # Verbose output
    ./distribute-image.sh server-info-server:latest -v

PREREQUISITES:
    1. Run from K3s master/server node
    2. SSH access to agent nodes configured:
       ssh-copy-id root@<agent-ip>
       (password: root)
    3. Image must exist locally on master node

EOF
}

# Function to distribute image to agent nodes
distribute_image() {
    local image_name="$1"
    
    log "Starting image distribution for: $image_name"
    
    # Check if we're on a K3s server (master) node
    if ! sudo k3s kubectl get nodes &>/dev/null; then
        log_error "Not on K3s server node. This script must be run from the master node."
        exit 1
    fi
    
    # Check if the image exists locally
    if ! docker images "$image_name" | grep -v REPOSITORY | grep -q .; then
        log_error "Image '$image_name' not found locally"
        echo "Available images:"
        docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"
        exit 1
    fi
    
    log "Image '$image_name' found locally"
    
    # Get list of agent nodes (non-master nodes)
    log_verbose "Getting list of agent nodes..."
    local agent_nodes
    agent_nodes=$(sudo k3s kubectl get nodes --no-headers | grep -v "control-plane\|master" | awk '{print $1}' || echo "")
    
    if [ -z "$agent_nodes" ]; then
        log "No agent nodes found - this appears to be a single-node cluster"
        exit 0
    fi
    
    log "Found agent nodes:"
    echo "$agent_nodes" | while read -r node; do
        if [ -n "$node" ]; then
            log_verbose "  • $node"
        fi
    done
    
    # Create temporary image file
    local temp_dir
    temp_dir=$(mktemp -d)
    local temp_image_file="$temp_dir/$(echo "$image_name" | tr '/:' '_').tar"
    
    log "Saving image to temporary file: $temp_image_file"
    if ! docker save "$image_name" > "$temp_image_file"; then
        log_error "Failed to save Docker image"
        rm -rf "$temp_dir"
        exit 1
    fi
    
    # Get image size for progress indication
    local image_size
    image_size=$(du -h "$temp_image_file" | cut -f1)
    log "Image file size: $image_size"
    
    # Distribute to each agent node
    local success_count=0
    local total_count=0
    
    echo "$agent_nodes" | while read -r node; do
        if [ -n "$node" ]; then
            total_count=$((total_count + 1))
            log "Processing node: $node"
            
            # Get the node's IP address
            local node_ip
            node_ip=$(sudo k3s kubectl get node "$node" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "")
            
            if [ -z "$node_ip" ]; then
                # Fallback to using node name as hostname
                node_ip="$node"
                log_verbose "Using node name as hostname: $node_ip"
            else
                log_verbose "Node IP: $node_ip"
            fi
            
            # Test SSH connectivity
            log_verbose "Testing SSH connectivity to $node_ip..."
            if ! ssh -n -o ConnectTimeout=10 -o BatchMode=yes root@"$node_ip" true 2>/dev/null; then
                log_error "Cannot SSH to root@$node_ip"
                echo "  Setup SSH access with:"
                echo "    ssh-copy-id root@$node_ip"
                echo "  Password: root"
                continue
            fi
            
            log_verbose "SSH connectivity confirmed"
            
            # Copy image file to remote node
            log "Copying image ($image_size) to $node_ip..."
            local remote_temp_file
            remote_temp_file="/tmp/$(basename "$temp_image_file")"
            
            if scp -q "$temp_image_file" root@"$node_ip":"$remote_temp_file"; then
                log_verbose "Image file copied to $node_ip"
                
                # Import image on remote node
                log "Importing image on $node_ip..."
                if ssh -n root@"$node_ip" "sudo k3s ctr images import '$remote_temp_file'"; then
                    log "✅ Image imported successfully on $node_ip"
                    success_count=$((success_count + 1))
                    
                    # Clean up remote temporary file
                    ssh -n root@"$node_ip" "rm '$remote_temp_file'" 2>/dev/null || true
                    
                    # Verify image on remote node
                    local image_base_name
                    image_base_name=$(echo "$image_name" | cut -d':' -f1)
                    if ssh -n root@"$node_ip" "sudo k3s ctr images list | grep -q '$image_base_name'" 2>/dev/null; then
                        log_verbose "Image verified on $node_ip"
                    fi
                else
                    log_error "Failed to import image on $node_ip"
                    # Clean up remote file on failure
                    ssh -n root@"$node_ip" "rm -f '$remote_temp_file'" 2>/dev/null || true
                fi
            else
                log_error "Failed to copy image file to $node_ip"
            fi
            
            echo ""
        fi
    done
    
    # Clean up local temporary files
    log_verbose "Cleaning up temporary files..."
    rm -rf "$temp_dir"
    
    # Final verification across all nodes
    log "Verifying image distribution across all nodes..."
    echo ""
    
    local all_nodes
    all_nodes=$(sudo k3s kubectl get nodes --no-headers | awk '{print $1}')
    
    echo "$all_nodes" | while read -r node; do
        if [ -n "$node" ]; then
            local node_ip
            node_ip=$(sudo k3s kubectl get node "$node" -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "$node")
            
            # Check if this is the local node
            local local_hostname
            local_hostname=$(hostname)
            local image_base_name
            image_base_name=$(echo "$image_name" | cut -d':' -f1)
            
            if [ "$node" = "$local_hostname" ]; then
                # Local node - check directly
                if sudo k3s ctr images list | grep -q "$image_base_name" 2>/dev/null; then
                    echo "  ✅ $node (master): Image available"
                else
                    echo "  ❌ $node (master): Image missing"
                fi
            else
                # Remote node - check via SSH
                if ssh -n -o ConnectTimeout=5 -o BatchMode=yes root@"$node_ip" "sudo k3s ctr images list | grep -q '$image_base_name'" 2>/dev/null; then
                    echo "  ✅ $node: Image available"
                else
                    echo "  ❌ $node: Image missing or SSH failed"
                fi
            fi
        fi
    done
    
    echo ""
    log "Image distribution completed!"
    log "You can now deploy applications and they should schedule on any node"
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        -*)
            log_error "Unknown option: $1"
            show_help
            exit 1
            ;;
        *)
            IMAGE_NAME="$1"
            shift
            ;;
    esac
done

# Main execution
log "K3s Docker Image Distribution Tool"
log "Image: $IMAGE_NAME"
log "Verbose: $VERBOSE"
echo ""

distribute_image "$IMAGE_NAME"
