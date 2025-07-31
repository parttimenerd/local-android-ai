#!/bin/bash

# Build script for K3s Phone Server Android App
set -e

echo "Building K3s Phone Server Android App..."

# Check if we're in the android directory
if [ ! -f "build.gradle" ]; then
    echo "Error: Not in android directory. Please run from the android/ folder."
    exit 1
fi

# Make gradlew executable
chmod +x gradlew

# Clean previous builds
echo "Cleaning previous builds..."
./gradlew clean

# Build debug APK
echo "Building debug APK..."
./gradlew assembleDebug

# Build release APK
echo "Building release APK..."
./gradlew assembleRelease

echo ""
echo "Build completed successfully!"
echo ""
echo "APK files:"
echo "Debug:   app/build/outputs/apk/debug/app-debug.apk"
echo "Release: app/build/outputs/apk/release/app-release-unsigned.apk"
echo ""
echo "To install on connected device:"
echo "  adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To test the API (after installing and starting the server):"
echo "  curl http://DEVICE_IP:8005/status"
echo "  curl http://DEVICE_IP:8005/location"
echo "  curl http://DEVICE_IP:8005/orientation"
echo ""
