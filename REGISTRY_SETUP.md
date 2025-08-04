# Docker Registry Setup Integration

The setup.sh script now includes automatic Docker registry configuration for both K3s server and agent nodes.

## What's New

### Automatic Registry Setup on Server Nodes
When installing K3s as a server (`./setup.sh`), the script now automatically:
1. Sets up a local Docker registry on port 5000
2. Configures Docker daemon for insecure registry access
3. Configures K3s containerd for the local registry
4. Imports images and starts the registry service

### Automatic Registry Configuration on Agent Nodes
When joining an agent to the cluster (`./setup.sh -k TOKEN -u URL`), the script now automatically:
1. Extracts the master node IP from the K3s URL
2. Configures Docker daemon for insecure registry access to `MASTER_IP:5000`
3. Restarts Docker daemon with the new configuration

## Docker Registry Functions

### `setup_docker_insecure_registry()`
- Configures `/etc/docker/daemon.json` for insecure registry access
- Handles JSON validation and merging with existing configuration
- Automatically restarts Docker daemon when needed
- Safe for multiple calls - won't duplicate entries

### `setup_local_registry()`
- Calls `./registry.sh setup` to configure the complete registry environment
- Only runs on server nodes during K3s server installation
- Integrates with K3s containerd configuration

## Manual Registry Operations

You can still use the registry management script directly:

```bash
# Setup registry (server nodes only)
./registry.sh setup

# Check registry status
./registry.sh status

# Import images to registry
./registry.sh import

# Remove registry
./registry.sh remove
```

## Benefits

1. **Zero Manual Configuration**: Registry setup happens automatically during K3s installation
2. **Consistent Configuration**: All nodes get proper Docker daemon settings
3. **Error Prevention**: Eliminates Docker HTTPS→HTTP errors for local registries
4. **Development Friendly**: Works seamlessly with deploy-watch.sh automation

## Registry Address Detection

The system automatically detects the registry address:
- **Server nodes**: Uses `localhost:5000` for local registry
- **Agent nodes**: Extracts master IP from K3s URL and uses `MASTER_IP:5000`
- **Deployment scripts**: Use `./registry.sh get-address` for dynamic detection

## Docker Daemon Configuration

The `/etc/docker/daemon.json` file is automatically managed:
```json
{
  "insecure-registries": ["MASTER_IP:5000"]
}
```

The configuration is merged with any existing Docker settings and validated before applying.

## Troubleshooting

### Registry Not Accessible
1. Check Docker daemon configuration: `cat /etc/docker/daemon.json`
2. Verify Docker service: `sudo systemctl status docker`
3. Test registry connectivity: `curl -f http://MASTER_IP:5000/v2/`

### Agent Node Issues
1. Ensure K3s URL is accessible from agent
2. Check that master node has registry running: `./registry.sh status`
3. Verify Docker daemon restarted: `sudo systemctl status docker`

### Manual Fix
If automatic setup fails, you can manually configure:
```bash
# On agent nodes
./setup.sh setup_docker_insecure_registry "MASTER_IP:5000"
```
