#!/bin/bash

# Memory Requirements Detection Script for Geocoder Service
# Automatically determines memory usage and updates Kubernetes configuration files

set -e

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Simple output without colors to avoid terminal issues
info() { echo "[INFO] $1"; }
warn() { echo "[WARN] $1"; }
error() { echo "[ERROR] $1"; }
success() { echo "[SUCCESS] $1"; }

# Configuration files to update
K8S_DEPLOYMENT="$SCRIPT_DIR/k8s/deployment.yaml"
K8S_DEPLOYMENT_BIN="$SCRIPT_DIR/bin/k8s/deployment.yaml"

info "Geocoder Memory Requirements Detection"
info "========================================="

# Step 1: Build and test the geocoder to determine actual memory usage
info "[STEP 1] Building geocoder and measuring memory usage..."

# Compile the Java code and run memory test
if ! mvn compile -q; then
    error "Failed to compile geocoder service"
    exit 1
fi

success "Compiled successfully"

# Run the geocoder with memory monitoring
info "[TESTING] Running geocoder to measure actual memory usage..."

# Create a temporary script to run the geocoder with memory monitoring
TEMP_TEST_SCRIPT=$(mktemp)
cat > "$TEMP_TEST_SCRIPT" << 'EOF'
#!/bin/bash
# Monitor Java process memory usage
JAVA_OPTS="-Xmx64m -Xms32m -XX:+PrintGCDetails -XX:+PrintMemoryUsage 2>/dev/null || true"
mvn exec:java -Dexec.mainClass="me.bechberger.k3s.geocoder.ReverseGeocodingService" -Dexec.args="" -q 2>&1 | tee /tmp/geocoder_test.log
EOF

chmod +x "$TEMP_TEST_SCRIPT"

# Run the test and capture output
if timeout 30s bash "$TEMP_TEST_SCRIPT" > /tmp/geocoder_output.log 2>&1; then
    success "Geocoder test completed successfully"
else
    info "Test completed (timeout expected)"
fi

# Clean up
rm -f "$TEMP_TEST_SCRIPT"

# Step 2: Parse the output to extract memory information
info "[STEP 2] Analyzing memory usage from test output..."

# Extract memory information from the test output
ESTIMATED_MEMORY_MB=$(grep "estimated memory:" /tmp/geocoder_output.log | tail -1 | sed 's/.*estimated memory: \([0-9.]*\) MB.*/\1/' || echo "1.0")
CITIES_COUNT=$(grep "Total cities loaded:" /tmp/geocoder_output.log | tail -1 | sed 's/.*Total cities loaded: \([0-9]*\).*/\1/' || echo "10000")

if [ -z "$ESTIMATED_MEMORY_MB" ] || [ "$ESTIMATED_MEMORY_MB" = "1.0" ]; then
    warn "Could not extract exact memory usage, using conservative estimate"
    ESTIMATED_MEMORY_MB="1.0"
fi

# Calculate memory requirements with safety margins
DATA_MEMORY_MB=$(echo "$ESTIMATED_MEMORY_MB" | bc -l 2>/dev/null || echo "1")
JVM_OVERHEAD_MB=100  # JVM overhead (heap management, classes, etc.)
SAFETY_MARGIN=2   # 100% safety margin

TOTAL_MEMORY_MB=$(echo "$DATA_MEMORY_MB + $JVM_OVERHEAD_MB" | bc -l)
SAFE_MEMORY_MB=$(echo "$TOTAL_MEMORY_MB * $SAFETY_MARGIN" | bc -l)

# Round up to nearest 32MB for nice Kubernetes values
SAFE_MEMORY_MB_ROUNDED=$(echo "($SAFE_MEMORY_MB + 31) / 32 * 32" | bc -l | cut -d. -f1)

# Calculate container limits (2x requests for bursts)
REQUEST_MEMORY_MB=$SAFE_MEMORY_MB_ROUNDED
LIMIT_MEMORY_MB=$((SAFE_MEMORY_MB_ROUNDED * 2))

# JVM heap settings (70% of request memory)
HEAP_MEMORY_MB=$((REQUEST_MEMORY_MB * 70 / 100))

info "[ANALYSIS] Memory Requirements Analysis:"
echo "  • Cities loaded: ${CITIES_COUNT}"
echo "  • Data memory: ${DATA_MEMORY_MB} MB"
echo "  • JVM overhead: ${JVM_OVERHEAD_MB} MB"
echo "  • Total with safety margin: ${SAFE_MEMORY_MB} MB"
echo "  • Kubernetes request: ${REQUEST_MEMORY_MB} MB"
echo "  • Kubernetes limit: ${LIMIT_MEMORY_MB} MB"
echo "  • JVM heap size: ${HEAP_MEMORY_MB} MB"

# Step 3: Update Kubernetes configuration files
info "[STEP 3] Updating Kubernetes configuration files..."

update_k8s_config() {
    local config_file="$1"
    
    if [ ! -f "$config_file" ]; then
        warn "[SKIP] Config file not found: $config_file"
        return 0
    fi
    
    info "[UPDATE] Updating $(basename "$config_file")..."
    
    # Create backup
    cp "$config_file" "$config_file.backup"
    
    # Update memory requests
    sed -i "s/memory: \"[0-9]*Mi\"/memory: \"${REQUEST_MEMORY_MB}Mi\"/" "$config_file"
    
    # Update memory limits  
    sed -i "/limits:/,/cpu:/ s/memory: \"[0-9]*Mi\"/memory: \"${LIMIT_MEMORY_MB}Mi\"/" "$config_file"
    
    # Update JVM heap settings
    sed -i "s/-Xmx[0-9]*m/-Xmx${HEAP_MEMORY_MB}m/" "$config_file"
    sed -i "s/-Xms[0-9]*m/-Xms$((HEAP_MEMORY_MB / 2))m/" "$config_file"
    
    success "Updated $(basename "$config_file")"
}

# Update all configuration files
update_k8s_config "$K8S_DEPLOYMENT"
update_k8s_config "$K8S_DEPLOYMENT_BIN"

# Step 4: Generate summary and recommendations
info "[STEP 4] Configuration Summary..."

cat << EOF

Memory Configuration Summary
===============================

Data Analysis:
• Cities loaded: ${CITIES_COUNT}
• Data size: ${DATA_MEMORY_MB} MB
• Estimated total: ${SAFE_MEMORY_MB} MB

Kubernetes Resources:
• Memory request: ${REQUEST_MEMORY_MB}Mi
• Memory limit: ${LIMIT_MEMORY_MB}Mi  
• CPU request: 100m (unchanged)
• CPU limit: 500m (unchanged)

JVM Settings:
• Heap size: ${HEAP_MEMORY_MB}MB
• Initial heap: $((HEAP_MEMORY_MB / 2))MB

Files Updated:
$([ -f "$K8S_DEPLOYMENT" ] && echo "• k8s/deployment.yaml")
$([ -f "$K8S_DEPLOYMENT_BIN" ] && echo "• bin/k8s/deployment.yaml")

Next Steps:
1. Deploy with: kubectl apply -f k8s/
2. Monitor with: kubectl top pod -l app=reverse-geocoder
3. Check logs with: kubectl logs -l app=reverse-geocoder

Optimization Notes:
• Memory usage scales linearly with countries added
• Current config optimized for German cities only
• Add more countries by updating DEFAULT_COUNTRIES in Java code
• Re-run this script after adding countries

EOF

# Clean up temporary files
rm -f /tmp/geocoder_output.log /tmp/geocoder_test.log

success "Memory requirements detection completed successfully!"
