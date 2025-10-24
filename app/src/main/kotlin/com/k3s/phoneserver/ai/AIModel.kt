package com.k3s.phoneserver.ai

import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend

/**
 * Supported AI models for LLM inference.
 * Based on MediaPipe LLM inference with specific models for the K3s Phone Server.
 */
enum class AIModel(
    val modelName: String,
    val fileName: String, // Just the filename (without path)
    val url: String,
    val licenseUrl: String,
    val preferredBackend: Backend?,
    val thinking: Boolean,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val supportsVision: Boolean,
    val maxTokens: Int,
    val description: String,
    val needsAuth: Boolean = false,
    val licenseStatement: String? = null
) {
    GEMMA_3_1B_IT(
        modelName = "Gemma 3n E2B IT",
        fileName = "gemma-3n-E2B-it-int4.task",
        url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/blob/b2b54222ba849ee74ac9f88d6af2470b390afa9e/gemma-3n-E2B-it-int4.task",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        preferredBackend = Backend.CPU,
        thinking = false,
        temperature = 1.0f,
        topK = 64,
        topP = 0.95f,
        supportsVision = true,
        maxTokens = 2048,
        description = "Gemma 3 2B model optimized for instruction following",
        needsAuth = true,
        licenseStatement = "This response was generated using Gemma, a model developed by Google. Usage is subject to the Gemma Terms of Use: https://ai.google.dev/gemma/terms"
    ),
    
    DEEPSEEK_R1_DISTILL_QWEN_1_5B(
        modelName = "DeepSeek-R1 Distill Qwen 1.5B",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
        url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        preferredBackend = Backend.CPU,
        thinking = true,
        temperature = 0.6f,
        topK = 40,
        topP = 0.7f,
        supportsVision = false,
        maxTokens = 1280,
        description = "DeepSeek R1 distilled model with reasoning capabilities",
        needsAuth = false,
        licenseStatement = "This response was generated using DeepSeek R1, developed by DeepSeek AI."
    ),
    
    LLAMA_3_2_1B_INSTRUCT(
        modelName = "Llama 3.2 1B Instruct",
        fileName = "Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        url = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct/resolve/main/Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct",
        preferredBackend = Backend.CPU,
        thinking = false,
        temperature = 0.6f,
        topK = 64,
        topP = 0.9f,
        supportsVision = false,
        maxTokens = 1280,
        description = "Meta's Llama 3.2 1B model for instruction following",
        needsAuth = true,
        licenseStatement = "This response was generated using Llama, a model developed by Meta. Usage is subject to the Llama license terms."
    ),
    
    LLAMA_3_2_3B_INSTRUCT(
        modelName = "Llama 3.2 3B Instruct",
        fileName = "Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        url = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct",
        preferredBackend = Backend.CPU,
        thinking = false,
        temperature = 0.6f,
        topK = 64,
        topP = 0.9f,
        supportsVision = false,
        maxTokens = 1280,
        description = "Meta's Llama 3.2 3B model for instruction following",
        needsAuth = true,
        licenseStatement = "This response was generated using Llama, a model developed by Meta. Usage is subject to the Llama license terms."
    ), 
    
    TINYLLAMA_1_1B_CHAT(
        modelName = "TinyLlama 1.1B Chat",
        fileName = "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1024.task",
        url = "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/TinyLlama/TinyLlama-1.1B-Chat-v1.0",
        preferredBackend = Backend.CPU,
        thinking = false,
        temperature = 0.7f,
        topK = 40,
        topP = 0.9f,
        supportsVision = false,
        maxTokens = 1024,
        description = "Compact 1.1B parameter model optimized for chat",
        needsAuth = false,
        licenseStatement = "This response was generated using TinyLlama."
    );

    companion object {
        /**
         * Get model by name string
         */
        fun fromString(modelName: String): AIModel? {
            return values().find { it.name == modelName || it.modelName == modelName }
        }
        
        /**
         * Get all available models
         */
        fun getAllModels(): List<AIModel> = values().toList()
    }
}