#!/bin/bash

# Model Verification Script
# Checks if all required AI models are present and valid

set -e

ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "🔍 Verifying AI models in: $ASSETS_DIR"

# Function to check file size and existence
check_model() {
    local filename="$1"
    local min_size="$2"
    local description="$3"
    
    if [ -f "$filename" ]; then
        local size
        size=$(stat -c%s "$filename" 2>/dev/null || stat -f%z "$filename" 2>/dev/null || echo "0")
        local size_mb=$((size / 1024 / 1024))
        
        if [ "$size" -gt "$min_size" ]; then
            echo "✅ $description: $filename (${size_mb}MB)"
            return 0
        else
            echo "❌ $description: $filename exists but is too small (${size_mb}MB)"
            return 1
        fi
    else
        echo "❌ $description: $filename not found"
        return 1
    fi
}

# Change to assets directory
cd "$ASSETS_DIR"

echo ""
echo "📋 Model Verification Report:"
echo "=============================="

# Check required models
MODELS_OK=0

# Image Classification (5MB minimum)
if check_model "efficientnet_lite0.tflite" 5000000 "EfficientNet Lite (Image Classification)"; then
    ((MODELS_OK++))
fi

# Object Detection (6MB minimum)  
if check_model "efficientdet_lite0.tflite" 6000000 "EfficientDet Lite (Object Detection)"; then
    ((MODELS_OK++))
fi

# Image Embedder (2MB minimum)
if check_model "mobilenet_v3_small.tflite" 2000000 "MobileNet V3 Small (Image Embeddings)"; then
    ((MODELS_OK++))
fi

# Text Embedder (100MB minimum)
if check_model "universal_sentence_encoder.tflite" 100000000 "Universal Sentence Encoder (Text Embeddings)"; then
    ((MODELS_OK++))
fi

# Gemma LLM (1GB minimum)
if check_model "gemma-2b-it-gpu-int4.bin" 1000000000 "Gemma 2B LLM (Language Model)"; then
    ((MODELS_OK++))
fi

echo ""
echo "📊 Results: $MODELS_OK/5 models verified"

if [ "$MODELS_OK" -eq 5 ]; then
    echo "🎉 All AI models are present and ready!"
    echo ""
    echo "⚖️  License Reminder:"
    echo "   Gemma model usage is subject to Gemma Terms of Use"
    echo "   Terms: https://ai.google.dev/gemma/terms"
    echo ""
    echo "🚀 Your multimodal AI system is ready to use!"
    exit 0
elif [ "$MODELS_OK" -ge 2 ]; then
    echo "⚠️  Some models missing - basic functionality available"
    echo "   Run ./download_models.sh to download missing models"
    exit 1
else
    echo "❌ Critical models missing - AI features may not work"
    echo "   Run ./download_models.sh to download required models"
    exit 2
fi
