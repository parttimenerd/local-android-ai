# AI Model Information

This document describes the AI models used by the GemmaAIService for advanced multimodal analysis. **All models are automatically downloaded when the app first runs.**

## Required Models

### 1. Image Classification Model
- **File**: `efficientnet_lite0.tflite`
- **Source**: [TensorFlow Hub - EfficientNet Lite](https://tfhub.dev/tensorflow/lite-model/efficientnet/lite0/classification/2)
- **Purpose**: General image classification with 1000+ categories
- **Size**: ~5MB

### 2. Object Detection Model  
- **File**: `efficientdet_lite0.tflite`
- **Source**: [TensorFlow Hub - EfficientDet Lite](https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/1)
- **Purpose**: Real-time object detection and localization
- **Size**: ~6MB

### 3. Image Embedding Model (Multimodal)
- **File**: `mobilenet_v3_small.tflite`
- **Source**: [TensorFlow Hub - MobileNet V3](https://tfhub.dev/google/lite-model/imagenet/mobilenet_v3_small_100_224/feature_vector/5)
- **Purpose**: Extract semantic image embeddings for multimodal reasoning
- **Size**: ~2MB

### 4. Text Embedding Model (Multimodal)
- **File**: `universal_sentence_encoder.tflite`
- **Source**: [TensorFlow Hub - Universal Sentence Encoder](https://tfhub.dev/google/lite-model/universal-sentence-encoder-qa/1)
- **Purpose**: Extract semantic text embeddings for vision-language understanding
- **Size**: ~100MB

### 5. Gemma Language Model
- **File**: `gemma-2b-it-gpu-int4.bin`
- **Source**: [Hugging Face - Gemma 3n E4B IT Med](https://huggingface.co/kargarisaac/gemma-3n-E4B-it-med)
- **Purpose**: Advanced text generation and multimodal reasoning
- **Size**: ~1.2GB
- **License**: Gemma Terms of Use (see License section below)

## Automatic Download System

**No manual intervention required!** The app automatically:

1. **First Launch**: Shows license agreement for Gemma model terms
2. **License Acceptance**: User accepts Gemma Terms of Use at ai.google.dev/gemma/terms
3. **Automatic Download**: Downloads all 5 models (~1.3GB total) with progress tracking
4. **Local Storage**: Models stored in app's private files directory
5. **Ready to Use**: Full multimodal AI capabilities enabled

The download process includes:
- Progress tracking with visual feedback
- Error handling and retry logic
- File validation after download
- Graceful fallback if models fail to download

## Model Information

| Model | Size | Purpose | Download Location |
|-------|------|---------|------------------|
| EfficientNet Lite | ~5MB | Image Classification | App's private files |
| EfficientDet Lite | ~6MB | Object Detection | App's private files |
| MobileNet V3 Small | ~2MB | Image Embeddings | App's private files |
| Universal Sentence Encoder | ~100MB | Text Embeddings | App's private files |
| Gemma 2B | ~1.2GB | Language Generation | App's private files |

**Total Download Size**: ~1.3GB (one-time download on first app launch)

## License Information

### Gemma Model License
The Gemma language model is provided under and subject to the **Gemma Terms of Use** found at [ai.google.dev/gemma/terms](https://ai.google.dev/gemma/terms).

**Important**: By downloading and using the Gemma model, you agree to comply with these terms. Please review the license carefully before use.

### Other Models
- TensorFlow Lite models (EfficientNet, EfficientDet, MobileNet, Universal Sentence Encoder) are provided under Apache 2.0 License
- MediaPipe framework is provided under Apache 2.0 License

## Fallback Behavior

If models fail to download or are not available, the app will:
1. Show clear error messages in the license agreement screen
2. Allow retry of the download process
3. Use basic image analysis for color and size information as fallback
4. Provide rule-based responses instead of AI-generated text
5. Still function with all core features (location, orientation, web server, camera)

## Performance Notes

- Models are automatically downloaded to app's private storage on first launch
- All models are loaded on first AI service use
- Image processing runs on background threads
- GPU acceleration is used when available
- Memory usage scales with model size (~1.5GB RAM recommended for full functionality)

## Troubleshooting

**License agreement not showing**: Check that the app has internet connectivity for the initial download.

**Download fails**: Ensure sufficient storage space (~2GB free) and stable internet connection. The app will show retry options.

**Out of memory errors**: Restart the app or clear other apps from memory. The models are optimized for mobile devices.

**Slow inference**: Check if GPU acceleration is enabled in build.gradle dependencies and ensure device has sufficient RAM.
