#!/bin/bash

# K3s Phone Cluster Dashboard
# Displays cluster node locations on a map and shows object detection results

set -e

# Configuration
DEFAULT_PORT=8007sh

# K3s Phone Cluster Dashboard
# Shows node locations on a map and object detection results from cameras

set -e

# Configuration
DEFAULT_PORT=8080
DASHBOARD_PORT=${DASHBOARD_PORT:-$DEFAULT_PORT}
DASHBOARD_DIR="/tmp/k3s-dashboard"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log() {
    echo -e "${GREEN}[DASHBOARD]${NC} $1"
}

log_error() {
    echo -e "${RED}[DASHBOARD ERROR]${NC} $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[DASHBOARD WARN]${NC} $1"
}

# Check dependencies
check_dependencies() {
    local missing_deps=()
    
    if ! command -v python3 &> /dev/null; then
        missing_deps+=("python3")
    fi
    
    if ! python3 -c "import http.server" &> /dev/null; then
        missing_deps+=("python3-http.server")
    fi
    
    if ! command -v kubectl &> /dev/null; then
        missing_deps+=("kubectl")
    fi
    
    if ! command -v jq &> /dev/null; then
        missing_deps+=("jq")
    fi
    
    if [ ${#missing_deps[@]} -gt 0 ]; then
        log_error "Missing dependencies: ${missing_deps[*]}"
        log "Installing missing dependencies..."
        sudo apt-get update -qq
        
        for dep in "${missing_deps[@]}"; do
            case $dep in
                "python3")
                    sudo apt-get install -y python3
                    ;;
                "jq")
                    sudo apt-get install -y jq
                    ;;
                "kubectl")
                    log_error "kubectl not found - please ensure K3s is installed"
                    return 1
                    ;;
            esac
        done
    fi
}

# Create dashboard directory and files
setup_dashboard_files() {
    log "Setting up dashboard files in $DASHBOARD_DIR"
    
    mkdir -p "$DASHBOARD_DIR"
    
    # Create the main HTML file
    cat > "$DASHBOARD_DIR/index.html" << 'EOF'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>K3s Phone Cluster Dashboard</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            margin: 0;
            padding: 20px;
            background-color: #f5f5f5;
        }
        
        .header {
            text-align: center;
            margin-bottom: 20px;
            color: #333;
        }
        
        .container {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            max-width: 1400px;
            margin: 0 auto;
        }
        
        .panel {
            background: white;
            border-radius: 8px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            padding: 20px;
        }
        
        .panel h2 {
            margin-top: 0;
            color: #2c3e50;
            border-bottom: 2px solid #3498db;
            padding-bottom: 10px;
        }
        
        #map {
            height: 500px;
            border-radius: 6px;
            border: 1px solid #ddd;
        }
        
        .object-detection {
            max-height: 500px;
            overflow-y: auto;
        }
        
        .detection-entry {
            border: 1px solid #e0e0e0;
            border-radius: 6px;
            margin-bottom: 15px;
            padding: 15px;
            background: #fafafa;
        }
        
        .detection-header {
            font-weight: bold;
            color: #2c3e50;
            margin-bottom: 10px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        
        .detection-time {
            font-size: 0.9em;
            color: #7f8c8d;
        }
        
        .objects-list {
            margin-top: 10px;
        }
        
        .object-item {
            display: inline-block;
            background: #3498db;
            color: white;
            padding: 4px 8px;
            border-radius: 12px;
            font-size: 0.8em;
            margin: 2px;
        }
        
        .error {
            color: #e74c3c;
            font-style: italic;
        }
        
        .loading {
            color: #f39c12;
            font-style: italic;
        }
        
        .status-indicator {
            display: inline-block;
            width: 10px;
            height: 10px;
            border-radius: 50%;
            margin-right: 8px;
        }
        
        .status-online { background-color: #2ecc71; }
        .status-offline { background-color: #e74c3c; }
        .status-unknown { background-color: #95a5a6; }
        
        .update-info {
            text-align: center;
            color: #7f8c8d;
            font-size: 0.9em;
            margin-top: 20px;
        }
        
        @media (max-width: 768px) {
            .container {
                grid-template-columns: 1fr;
            }
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>📱 K3s Phone Cluster Dashboard</h1>
        <p>Real-time node locations and object detection from rear cameras</p>
    </div>
    
    <div class="container">
        <div class="panel">
            <h2>🗺️ Node Locations</h2>
            <div id="map"></div>
            <div id="map-status"></div>
        </div>
        
        <div class="panel">
            <h2>📸 Object Detection (Rear Cameras)</h2>
            <div id="object-detection" class="object-detection">
                <div class="loading">Loading object detection data...</div>
            </div>
        </div>
    </div>
    
    <div class="update-info">
        <p>🔄 Updates every 20 seconds | Last update: <span id="last-update">Never</span></p>
    </div>

    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <script>
        // Initialize map
        const map = L.map('map').setView([52.5200, 13.4050], 10); // Default to Berlin
        
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '© OpenStreetMap contributors'
        }).addTo(map);
        
        let nodeMarkers = {};
        let lastUpdate = null;
        
        // Update functions
        async function updateNodeLocations() {
            try {
                const response = await fetch('/api/nodes');
                const nodes = await response.json();
                
                // Clear existing markers
                Object.values(nodeMarkers).forEach(marker => map.removeLayer(marker));
                nodeMarkers = {};
                
                if (nodes.length === 0) {
                    document.getElementById('map-status').innerHTML = 
                        '<div class="error">No nodes with location data found</div>';
                    return;
                }
                
                let bounds = [];
                
                nodes.forEach(node => {
                    if (node.lat && node.lon) {
                        const marker = L.marker([node.lat, node.lon]).addTo(map);
                        
                        const statusClass = node.ready ? 'status-online' : 'status-offline';
                        const statusText = node.ready ? 'Online' : 'Offline';
                        
                        marker.bindPopup(`
                            <b>${node.name}</b><br>
                            <span class="status-indicator ${statusClass}"></span>${statusText}<br>
                            Location: ${node.city || 'Unknown'}<br>
                            Coordinates: ${node.lat.toFixed(4)}, ${node.lon.toFixed(4)}<br>
                            Phone Server: ${node.hasPhoneServer ? '✅ Available' : '❌ Not detected'}
                        `);
                        
                        nodeMarkers[node.name] = marker;
                        bounds.push([node.lat, node.lon]);
                    }
                });
                
                if (bounds.length > 0) {
                    map.fitBounds(bounds, { padding: [20, 20] });
                    document.getElementById('map-status').innerHTML = 
                        `<div style="color: #27ae60;">📍 Showing ${bounds.length} nodes</div>`;
                } else {
                    document.getElementById('map-status').innerHTML = 
                        '<div class="error">No nodes with valid coordinates found</div>';
                }
                
            } catch (error) {
                console.error('Error updating node locations:', error);
                document.getElementById('map-status').innerHTML = 
                    '<div class="error">Error loading node data</div>';
            }
        }
        
        async function updateObjectDetection() {
            try {
                const response = await fetch('/api/object-detection');
                const detections = await response.json();
                
                const container = document.getElementById('object-detection');
                
                if (detections.length === 0) {
                    container.innerHTML = '<div class="error">No object detection data available</div>';
                    return;
                }
                
                let html = '';
                detections.forEach(detection => {
                    const statusClass = detection.success ? 'status-online' : 'status-offline';
                    const timeAgo = getTimeAgo(detection.timestamp);
                    
                    html += `
                        <div class="detection-entry">
                            <div class="detection-header">
                                <span>
                                    <span class="status-indicator ${statusClass}"></span>
                                    ${detection.node}
                                </span>
                                <span class="detection-time">${timeAgo}</span>
                            </div>
                    `;
                    
                    if (detection.success && detection.objects && detection.objects.length > 0) {
                        html += '<div class="objects-list">';
                        detection.objects.forEach(obj => {
                            html += `<span class="object-item">${obj.label} (${(obj.confidence * 100).toFixed(1)}%)</span>`;
                        });
                        html += '</div>';
                    } else if (detection.success) {
                        html += '<div class="error">No objects detected</div>';
                    } else {
                        html += `<div class="error">Error: ${detection.error || 'Detection failed'}</div>`;
                    }
                    
                    html += '</div>';
                });
                
                container.innerHTML = html;
                
            } catch (error) {
                console.error('Error updating object detection:', error);
                document.getElementById('object-detection').innerHTML = 
                    '<div class="error">Error loading object detection data</div>';
            }
        }
        
        function getTimeAgo(timestamp) {
            const now = new Date();
            const time = new Date(timestamp);
            const diffMs = now - time;
            const diffSecs = Math.floor(diffMs / 1000);
            const diffMins = Math.floor(diffSecs / 60);
            
            if (diffSecs < 60) return `${diffSecs}s ago`;
            if (diffMins < 60) return `${diffMins}m ago`;
            return time.toLocaleTimeString();
        }
        
        function updateLastUpdateTime() {
            document.getElementById('last-update').textContent = new Date().toLocaleTimeString();
        }
        
        // Update data
        async function updateAll() {
            await Promise.all([
                updateNodeLocations(),
                updateObjectDetection()
            ]);
            updateLastUpdateTime();
        }
        
        // Initial load
        updateAll();
        
        // Update every 20 seconds
        setInterval(updateAll, 20000);
    </script>
</body>
</html>
EOF

    # Create the Python web server
    cat > "$DASHBOARD_DIR/server.py" << 'EOF'
#!/usr/bin/env python3

import http.server
import socketserver
import json
import subprocess
import re
import time
import threading
from urllib.parse import urlparse, parse_qs
from datetime import datetime
import logging

class DashboardHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory='/tmp/k3s-dashboard', **kwargs)
    
    def do_GET(self):
        if self.path == '/api/nodes':
            self.serve_nodes_api()
        elif self.path == '/api/object-detection':
            self.serve_object_detection_api()
        else:
            super().do_GET()
    
    def serve_nodes_api(self):
        try:
            nodes = self.get_cluster_nodes()
            self.send_json_response(nodes)
        except Exception as e:
            logging.error(f"Error getting nodes: {e}")
            self.send_json_response([], status=500)
    
    def serve_object_detection_api(self):
        try:
            detections = self.get_object_detections()
            self.send_json_response(detections)
        except Exception as e:
            logging.error(f"Error getting object detections: {e}")
            self.send_json_response([], status=500)
    
    def send_json_response(self, data, status=200):
        self.send_response(status)
        self.send_header('Content-type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())
    
    def get_cluster_nodes(self):
        try:
            # Get nodes with labels
            result = subprocess.run([
                'kubectl', 'get', 'nodes', '-o', 'json'
            ], capture_output=True, text=True, timeout=30)
            
            if result.returncode != 0:
                logging.error(f"kubectl failed: {result.stderr}")
                return []
            
            nodes_data = json.loads(result.stdout)
            nodes = []
            
            for node in nodes_data.get('items', []):
                name = node['metadata']['name']
                labels = node['metadata'].get('labels', {})
                status = node.get('status', {})
                
                # Check if node is ready
                ready = False
                for condition in status.get('conditions', []):
                    if condition['type'] == 'Ready' and condition['status'] == 'True':
                        ready = True
                        break
                
                # Extract location data from labels
                lat = labels.get('phone.location.latitude')
                lon = labels.get('phone.location.longitude')
                city = labels.get('phone.location.city')
                
                # Check if this node has phone server capabilities
                has_phone_server = self.check_phone_server(name)
                
                node_info = {
                    'name': name,
                    'ready': ready,
                    'hasPhoneServer': has_phone_server,
                    'lat': float(lat) if lat else None,
                    'lon': float(lon) if lon else None,
                    'city': city
                }
                
                nodes.append(node_info)
            
            return nodes
            
        except Exception as e:
            logging.error(f"Error getting cluster nodes: {e}")
            return []
    
    def check_phone_server(self, node_name):
        """Check if a node has phone server running on port 8005"""
        try:
            # Try to connect to the node's port 8005
            result = subprocess.run([
                'curl', '-s', '--connect-timeout', '3', '--max-time', '5',
                f'http://{node_name}:8005/status'
            ], capture_output=True, text=True, timeout=10)
            
            return 'K3s Phone Server' in result.stdout
        except:
            return False
    
    def get_object_detections(self):
        """Get object detection results from all nodes with phone servers"""
        try:
            nodes = self.get_cluster_nodes()
            detections = []
            
            for node in nodes:
                if node['hasPhoneServer']:
                    detection = self.get_node_object_detection(node['name'])
                    if detection:
                        detections.append(detection)
            
            # Sort by timestamp, newest first
            detections.sort(key=lambda x: x.get('timestamp', ''), reverse=True)
            return detections
            
        except Exception as e:
            logging.error(f"Error getting object detections: {e}")
            return []
    
    def get_node_object_detection(self, node_name):
        """Get object detection from a specific node"""
        try:
            # Call object detection API on the node
            result = subprocess.run([
                'curl', '-s', '--connect-timeout', '5', '--max-time', '15',
                '-X', 'POST',
                '-H', 'Content-Type: application/json',
                '-d', '{"side": "rear", "threshold": 0.3, "maxResults": 5}',
                f'http://{node_name}:8005/ai/object_detection'
            ], capture_output=True, text=True, timeout=20)
            
            if result.returncode == 0 and result.stdout:
                try:
                    response = json.loads(result.stdout)
                    
                    return {
                        'node': node_name,
                        'timestamp': datetime.now().isoformat(),
                        'success': response.get('success', False),
                        'objects': response.get('detections', []),
                        'error': response.get('error')
                    }
                except json.JSONDecodeError:
                    pass
            
            return {
                'node': node_name,
                'timestamp': datetime.now().isoformat(),
                'success': False,
                'objects': [],
                'error': 'No response or invalid JSON'
            }
            
        except Exception as e:
            return {
                'node': node_name,
                'timestamp': datetime.now().isoformat(),
                'success': False,
                'objects': [],
                'error': str(e)
            }

def main():
    port = 8007
    
    # Setup logging
    logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
    
    with socketserver.TCPServer(("", port), DashboardHandler) as httpd:
        print(f"🌐 K3s Phone Cluster Dashboard running at http://localhost:{port}")
        print(f"📱 Monitoring node locations and object detection")
        print(f"🔄 Updates every 20 seconds")
        print("Press Ctrl+C to stop")
        
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n👋 Dashboard stopped")

if __name__ == "__main__":
    main()
EOF

    chmod +x "$DASHBOARD_DIR/server.py"
}

# Start the dashboard
start_dashboard() {
    log "Starting K3s Phone Cluster Dashboard on port $DASHBOARD_PORT"
    
    # Check if port is already in use
    if netstat -tuln 2>/dev/null | grep -q ":$DASHBOARD_PORT " || ss -tuln 2>/dev/null | grep -q ":$DASHBOARD_PORT "; then
        log_error "Port $DASHBOARD_PORT is already in use"
        log "Try stopping any existing dashboard or use a different port:"
        log "  DASHBOARD_PORT=8081 ./dashboard.sh start"
        return 1
    fi
    
    cd "$DASHBOARD_DIR"
    
    # Start the server
    log "🌐 Dashboard available at: http://localhost:$DASHBOARD_PORT"
    log "📱 Monitoring cluster nodes and object detection"
    log "🔄 Data updates every 20 seconds"
    echo ""
    log "Press Ctrl+C to stop the dashboard"
    
    python3 server.py
}

# Stop any running dashboard
stop_dashboard() {
    log "Stopping dashboard..."
    
    # Find and kill any running dashboard processes
    pkill -f "python3.*server.py" 2>/dev/null || true
    pkill -f "k3s-dashboard" 2>/dev/null || true
    
    log "Dashboard stopped"
}

# Show help
show_help() {
    cat << EOF
K3s Phone Cluster Dashboard

USAGE:
    ./dashboard.sh [COMMAND]

COMMANDS:
    start       Start the dashboard web server (default)
    stop        Stop any running dashboard
    setup       Setup dashboard files only (don't start)
    help        Show this help message

OPTIONS:
    DASHBOARD_PORT    Port to run dashboard on (default: 8007)

EXAMPLES:
    ./dashboard.sh                    # Start dashboard on port 8007
    DASHBOARD_PORT=8081 ./dashboard.sh start  # Start on custom port
    ./dashboard.sh stop               # Stop dashboard

FEATURES:
    📍 Interactive map showing all cluster node locations
    📸 Real-time object detection from rear cameras
    🔄 Auto-refresh every 20 seconds
    📱 Responsive design for mobile devices

REQUIREMENTS:
    - kubectl configured for the cluster
    - Python 3 with http.server
    - Agent nodes with K3s Phone Server port forwarding
    - Internet access for map tiles

EOF
}

# Main command handling
main() {
    local command="${1:-start}"
    
    case "$command" in
        "start")
            check_dependencies
            setup_dashboard_files
            start_dashboard
            ;;
        "stop")
            stop_dashboard
            ;;
        "setup")
            check_dependencies
            setup_dashboard_files
            log "Dashboard files created in $DASHBOARD_DIR"
            log "Run './dashboard.sh start' to launch the dashboard"
            ;;
        "help"|"-h"|"--help")
            show_help
            ;;
        *)
            log_error "Unknown command: $command"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
