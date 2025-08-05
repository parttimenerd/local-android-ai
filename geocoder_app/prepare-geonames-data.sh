#!/bin/bash

# GeoNames data preparation script
# Downloads and extracts GeoNames data for faster container startup

set -e

# Determine script directory and change to it
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# GeoNames configuration
GEONAMES_BASE_URL="https://download.geonames.org/export/dump/"
DATA_DIR="$SCRIPT_DIR/geonames-data"
RESOURCES_DIR="$SCRIPT_DIR/src/main/resources/geonames-data"

# Countries to support (ISO 2-letter codes)
COUNTRIES=("DE" "US" "GB" "FR" "IT" "ES" "NL" "BE" "AT" "CH" "DK" "SE" "NO" "FI" "PL")

echo -e "${GREEN}[INFO]${NC} Preparing GeoNames data for build-time caching..."
echo -e "${GREEN}[INFO]${NC} This will download and extract data for ${#COUNTRIES[@]} countries"

# Create directories
mkdir -p "$DATA_DIR"
mkdir -p "$RESOURCES_DIR"

# Function to download and extract a country's data
process_country() {
    local country="$1"
    local zip_file="$DATA_DIR/${country}.zip"
    local txt_file="$DATA_DIR/${country}.txt"
    local resource_txt="$RESOURCES_DIR/${country}.txt"
    
    echo -e "${YELLOW}[PROCESSING]${NC} Country: $country"
    
    # Download if not already present or if file is old
    if [ ! -f "$zip_file" ] || [ "$(find "$zip_file" -mtime +7 2>/dev/null | wc -l)" -gt 0 ]; then
        echo -e "  ${YELLOW}Downloading${NC} ${country}.zip..."
        if curl -L -f -o "$zip_file" "${GEONAMES_BASE_URL}${country}.zip"; then
            echo -e "  ${GREEN}✓${NC} Downloaded ${country}.zip"
        else
            echo -e "  ${RED}✗${NC} Failed to download ${country}.zip"
            return 1
        fi
    else
        echo -e "  ${GREEN}✓${NC} ${country}.zip already exists and is recent"
    fi
    
    # Extract if needed
    if [ ! -f "$txt_file" ] || [ "$zip_file" -nt "$txt_file" ]; then
        echo -e "  ${YELLOW}Extracting${NC} ${country}.txt..."
        
        # First, check what's in the zip file
        echo -e "  ${YELLOW}DEBUG${NC} Checking zip contents..."
        if unzip -l "$zip_file" | head -10; then
            echo -e "  ${GREEN}INFO${NC} Zip file contents listed successfully"
        else
            echo -e "  ${RED}WARN${NC} Could not list zip contents"
        fi
        
        # Try to extract the specific file
        if unzip -j -o "$zip_file" "${country}.txt" -d "$DATA_DIR" >/dev/null 2>&1; then
            echo -e "  ${GREEN}✓${NC} Extracted ${country}.txt"
        else
            echo -e "  ${YELLOW}WARN${NC} Standard extraction failed, trying alternative approaches..."
            
            # Try extracting all files and finding the right one
            temp_extract_dir="$DATA_DIR/temp_${country}"
            mkdir -p "$temp_extract_dir"
            
            if unzip -o "$zip_file" -d "$temp_extract_dir" >/dev/null 2>&1; then
                echo -e "  ${GREEN}INFO${NC} Extracted all files to temporary directory"
                
                # Look for the country file (case insensitive)
                found_file=$(find "$temp_extract_dir" -iname "${country}.txt" | head -1)
                if [ -n "$found_file" ] && [ -f "$found_file" ]; then
                    cp "$found_file" "$txt_file"
                    echo -e "  ${GREEN}✓${NC} Found and copied ${country}.txt from temporary extraction"
                else
                    echo -e "  ${YELLOW}INFO${NC} Looking for any .txt files in extraction..."
                    txt_files=$(find "$temp_extract_dir" -name "*.txt")
                    if [ -n "$txt_files" ]; then
                        echo -e "  ${YELLOW}INFO${NC} Available txt files:"
                        echo "$txt_files"
                        # Use the first .txt file if it exists
                        first_txt=$(echo "$txt_files" | head -1)
                        if [ -f "$first_txt" ]; then
                            cp "$first_txt" "$txt_file"
                            echo -e "  ${GREEN}✓${NC} Used alternative file: $(basename "$first_txt")"
                        fi
                    else
                        echo -e "  ${RED}✗${NC} No .txt files found in zip archive"
                    fi
                fi
                
                # Clean up temp directory
                rm -rf "$temp_extract_dir"
            else
                echo -e "  ${RED}✗${NC} Failed to extract ${country}.zip completely"
                
                # As a last resort, try to re-download if the file seems corrupted
                echo -e "  ${YELLOW}RECOVERY${NC} Attempting to re-download ${country}.zip..."
                if curl -L -f -o "$zip_file.new" "${GEONAMES_BASE_URL}${country}.zip"; then
                    mv "$zip_file.new" "$zip_file"
                    echo -e "  ${GREEN}INFO${NC} Re-downloaded ${country}.zip, retrying extraction..."
                    
                    if unzip -j -o "$zip_file" "${country}.txt" -d "$DATA_DIR" >/dev/null 2>&1; then
                        echo -e "  ${GREEN}✓${NC} Extracted ${country}.txt after re-download"
                    else
                        echo -e "  ${RED}✗${NC} Still failed to extract after re-download"
                        return 1
                    fi
                else
                    echo -e "  ${RED}✗${NC} Failed to re-download ${country}.zip"
                    return 1
                fi
            fi
        fi
    else
        echo -e "  ${GREEN}✓${NC} ${country}.txt already extracted and up-to-date"
    fi
    
    # Copy to resources directory for packaging
    if [ -f "$txt_file" ]; then
        echo -e "  ${YELLOW}Copying${NC} to resources..."
        cp "$txt_file" "$resource_txt"
        echo -e "  ${GREEN}✓${NC} Copied to resources: $(basename "$resource_txt")"
        
        # Show file stats
        local file_size
        local line_count
        file_size=$(du -h "$resource_txt" | cut -f1)
        line_count=$(wc -l < "$resource_txt")
        echo -e "  ${GREEN}INFO${NC} File size: $file_size, Lines: $line_count"
    else
        echo -e "  ${RED}✗${NC} No extracted file found for $country"
        return 1
    fi
    
    echo ""
    return 0  # Explicitly return success
}

# Process all countries
successful=0
failed=0

for country in "${COUNTRIES[@]}"; do
    if process_country "$country"; then
        successful=$((successful + 1))
    else
        failed=$((failed + 1))
        echo -e "${RED}[ERROR]${NC} Failed to process $country"
        echo ""
    fi
done

# Summary
echo -e "${GREEN}[SUMMARY]${NC} GeoNames data preparation completed"
echo -e "  ${GREEN}✓${NC} Successfully processed: $successful countries"
if [ $failed -gt 0 ]; then
    echo -e "  ${RED}✗${NC} Failed to process: $failed countries"
fi

# Show total resources
if [ -d "$RESOURCES_DIR" ]; then
    total_size=$(du -sh "$RESOURCES_DIR" | cut -f1)
    total_files=$(find "$RESOURCES_DIR" -name "*.txt" | wc -l)
    echo -e "  ${GREEN}INFO${NC} Total resources: $total_files files, $total_size"
fi

echo ""
if [ $failed -eq 0 ]; then
    echo -e "${GREEN}[SUCCESS]${NC} All GeoNames data prepared successfully!"
    echo -e "${GREEN}[NEXT]${NC} Data is ready for Docker build. Run './build.sh' to build the container."
else
    echo -e "${YELLOW}[WARNING]${NC} Some countries failed to process, but build can continue."
fi

echo ""
echo -e "${YELLOW}[INFO]${NC} The following files are now available in the container:"
if [ -d "$RESOURCES_DIR" ]; then
    for txt_file in "$RESOURCES_DIR"/*.txt; do
        if [ -f "$txt_file" ]; then
            country=$(basename "$txt_file" .txt)
            size=$(du -h "$txt_file" | cut -f1)
            echo -e "  ${YELLOW}•${NC} $country: $size"
        fi
    done
fi
