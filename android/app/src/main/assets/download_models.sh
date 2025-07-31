#!/bin/bash

# K3s Phone Server - Development Model Download Script
# Downloads MediaPipe vision models for development and testing
# 
# NOTE: In production, models are downloaded automatically by the app at runtime.
# This script is for development workflow where you can push models via adb.

set -e  # Exit on any error

echo "🤖 K3s Phone Server - Development Model Setup"
echo "=============================================="
echo ""
echo "ℹ️  This script downloads models for DEVELOPMENT use only."
echo "   Production apps download models automatically at runtime."
echo ""

# Define models with their URLs and expected sizes
declare -A MODELS=(
    ["efficientnet_lite0.tflite"]="https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite"
    ["efficientdet_lite0.tflite"]="https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite" 
    ["mobilenet_v3_small.tflite"]="https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
    ["universal_sentence_encoder.tflite"]="https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite"
)

declare -A MODEL_DESCRIPTIONS=(
    ["efficientnet_lite0.tflite"]="EfficientNet Lite (Image Classification)"
    ["efficientdet_lite0.tflite"]="EfficientDet Lite (Object Detection)"
    ["mobilenet_v3_small.tflite"]="MobileNet V3 Small (Image Embeddings)"
    ["universal_sentence_encoder.tflite"]="Universal Sentence Encoder (Text Embeddings)"
)

declare -A MODEL_SIZES=(
    ["efficientnet_lite0.tflite"]="18MB"
    ["efficientdet_lite0.tflite"]="14MB"
    ["mobilenet_v3_small.tflite"]="4MB"
    ["universal_sentence_encoder.tflite"]="6MB"
)

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check for required tools
echo "� Checking prerequisites..."
if ! command_exists curl; then
    echo "❌ Error: curl is required but not installed."
    echo "   Install with: sudo apt install curl (Ubuntu/Debian) or brew install curl (macOS)"
    exit 1
fi

if ! command_exists wget; then
    echo "ℹ️  wget not found, using curl for downloads"
    USE_CURL=true
else
    USE_CURL=false
fi

echo "✅ Prerequisites check complete"
echo ""

# Function to download a file with progress
download_file() {
    local filename="$1"
    local url="$2"
    local description="$3"
    local size="$4"
    
    echo "📥 Downloading: $description ($size)"
    echo "   File: $filename"
    
    if [ "$USE_CURL" = true ]; then
        if ! curl -L --progress-bar -o "$filename" "$url"; then
            echo "❌ Failed to download $filename"
            return 1
        fi
    else
        if ! wget --progress=bar:force -O "$filename" "$url"; then
            echo "❌ Failed to download $filename"
            return 1
        fi
    fi
    
    echo "✅ Downloaded: $filename"
    echo ""
}

# Function to verify file size
verify_file() {
    local filename="$1"
    local min_size="$2"
    
    if [ ! -f "$filename" ]; then
        echo "❌ File not found: $filename"
        return 1
    fi
    
    local file_size=$(stat -c%s "$filename" 2>/dev/null || stat -f%z "$filename" 2>/dev/null || echo "0")
    if [ "$file_size" -lt "$min_size" ]; then
        echo "⚠️  Warning: $filename appears incomplete (size: $file_size bytes)"
        return 1
    fi
    
    echo "✅ Verified: $filename ($(($file_size / 1024 / 1024))MB)"
    return 0
}

# Main download process
echo "🚀 Starting model downloads..."
echo "Total download size: ~42MB"
echo ""

total_models=${#MODELS[@]}
current_model=0
failed_downloads=()

for filename in "${!MODELS[@]}"; do
    current_model=$((current_model + 1))
    echo "[$current_model/$total_models] Processing: $filename"
    
    # Skip if file already exists and is valid
    if verify_file "$filename" 1000000 2>/dev/null; then
        echo "✅ $filename already exists and appears valid, skipping"
        echo ""
        continue
    fi
    
    # Remove partial/invalid file if it exists
    [ -f "$filename" ] && rm "$filename"
    
    # Download the model
    url="${MODELS[$filename]}"
    description="${MODEL_DESCRIPTIONS[$filename]}"
    size="${MODEL_SIZES[$filename]}"
    
    if download_file "$filename" "$url" "$description" "$size"; then
        if verify_file "$filename" 1000000; then
            echo "🎉 Successfully downloaded and verified: $filename"
        else
            echo "⚠️  Downloaded but verification failed: $filename"
            failed_downloads+=("$filename")
        fi
    else
        echo "❌ Download failed: $filename"
        failed_downloads+=("$filename")
    fi
    echo ""
done

# Summary
echo "📊 Download Summary"
echo "=================="
if [ ${#failed_downloads[@]} -eq 0 ]; then
    echo "🎉 All models downloaded successfully!"
    echo ""
    echo "✅ Vision models are ready for K3s Phone Server"
    echo "   - Image classification: efficientnet_lite0.tflite"
    echo "   - Object detection: efficientdet_lite0.tflite"  
    echo "   - Image embeddings: mobilenet_v3_small.tflite"
    echo "   - Text embeddings: universal_sentence_encoder.tflite"
    echo ""
    echo "📱 You can now build and run the Android app with AI capabilities!"
    echo ""
    echo "🔗 Optional: For advanced language model features, download Gemma:"
    echo "   1. Create account at https://huggingface.co/join"
    echo "   2. Accept license at https://huggingface.co/google/gemma-3n-E4B-it"
    echo "   3. Download and copy .task files to this assets folder"
else
    echo "⚠️  Some downloads failed:"
    for failed in "${failed_downloads[@]}"; do
        echo "   - $failed"
    done
    echo ""
    echo "💡 Try running this script again or check your internet connection."
    echo "   The app will still work with partial model downloads."
fi

echo ""
echo "📋 Current assets folder contents:"
ls -la *.tflite *.task 2>/dev/null || echo "   No model files found"
echo ""
echo "🏁 Model download script complete!"
        wget --progress=bar:force:noscroll "$url" -O "$output"
    else
        echo "❌ Error: Neither curl nor wget found. Please install one of them."
        exit 1
    fi
    
    if [ -f "$output" ]; then
        local size
        size=$(du -h "$output" | cut -f1)
        echo "✅ Downloaded: $output ($size)"
    else
        echo "❌ Failed to download: $output"
        exit 1
    fi
}

# Check if running from correct directory
if [ ! -f "../../build.gradle" ]; then
    echo "❌ Error: Please run this script from android/app/src/main/assets/ directory"
    exit 1
fi

echo "🤖 Downloading AI models for K3S Phone Server..."
echo ""

# Create assets directory if it doesn't exist
mkdir -p "$ASSETS_DIR"
cd "$ASSETS_DIR"

# Download EfficientNet Lite for image classification
if [ ! -f "efficientnet_lite0.tflite" ]; then
    download_file \
        "https://tfhub.dev/tensorflow/lite-model/efficientnet/lite0/classification/2?lite-format=tflite" \
        "efficientnet_lite0.tflite" \
        "EfficientNet Lite (Image Classification)"
else
    echo "✅ EfficientNet Lite already exists"
fi

# Download EfficientDet Lite for object detection  
if [ ! -f "efficientdet_lite0.tflite" ]; then
    download_file \
        "https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/1?lite-format=tflite" \
        "efficientdet_lite0.tflite" \
        "EfficientDet Lite (Object Detection)"
else
    echo "✅ EfficientDet Lite already exists"
fi

# Download MobileNet V3 for image embeddings (multimodal)
if [ ! -f "mobilenet_v3_small.tflite" ]; then
    download_file \
        "https://tfhub.dev/google/lite-model/imagenet/mobilenet_v3_small_100_224/feature_vector/5?lite-format=tflite" \
        "mobilenet_v3_small.tflite" \
        "MobileNet V3 Small (Image Embeddings)"
else
    echo "✅ MobileNet V3 Small already exists"
fi

# Download Universal Sentence Encoder for text embeddings (multimodal)
if [ ! -f "universal_sentence_encoder.tflite" ]; then
    download_file \
        "https://tfhub.dev/google/lite-model/universal-sentence-encoder-qa/1?lite-format=tflite" \
        "universal_sentence_encoder.tflite" \
        "Universal Sentence Encoder (Text Embeddings)"
else
    echo "✅ Universal Sentence Encoder already exists"
fi

# Download Gemma model from Hugging Face
if [ ! -f "gemma-2b-it-gpu-int4.bin" ]; then
    echo "📥 Downloading Gemma 2B model from Hugging Face..."
    echo "⚖️  Note: Gemma is subject to Gemma Terms of Use (ai.google.dev/gemma/terms)"
    
    # Check if git-lfs is available for large file download
    if command -v git >/dev/null 2>&1; then
        echo "🔄 Cloning Gemma model repository..."
        git clone https://huggingface.co/kargarisaac/gemma-3n-E4B-it-med gemma_temp
        if [ -f "gemma_temp/model.bin" ]; then
            mv "gemma_temp/model.bin" "gemma-2b-it-gpu-int4.bin"
            rm -rf gemma_temp
            echo "✅ Gemma model downloaded successfully"
        else
            echo "⚠️  Gemma model file not found, trying direct download..."
            rm -rf gemma_temp
            download_file \
                "https://huggingface.co/kargarisaac/gemma-3n-E4B-it-med/resolve/main/model.bin" \
                "gemma-2b-it-gpu-int4.bin" \
                "Gemma 2B Model (Large file, ~1.2GB)"
        fi
    else
        echo "⚠️  Git not available, trying direct download..."
        download_file \
            "https://huggingface.co/kargarisaac/gemma-3n-E4B-it-med/resolve/main/model.bin" \
            "gemma-2b-it-gpu-int4.bin" \
            "Gemma 2B Model (Large file, ~1.2GB)"
    fi
else
    echo "✅ Gemma model already exists"
fi

echo ""
echo "🎉 Multimodal AI models downloaded successfully!"
echo ""
echo "📋 Downloaded models:"
ls -lh *.tflite *.bin 2>/dev/null || echo "No model files found"

echo ""
echo "⚖️  LICENSE NOTICE:"
echo "   Gemma model is subject to Gemma Terms of Use"
echo "   Terms: https://ai.google.dev/gemma/terms"
echo "   Please review and comply with the license terms"
echo ""
echo "🤖 Multimodal capabilities enabled:"
echo "   • Image Classification & Object Detection"
echo "   • Vision-Language Understanding" 
echo "   • Semantic Similarity Analysis"
echo "   • Advanced LLM-powered Responses (Gemma)"
echo "   • Advanced Contextual Responses"
echo ""
echo "🚀 Your multimodal AI-powered Android app is ready to build!"
