package com.k3s.phoneserver.services

import android.content.Context

class AIService(private val context: Context) {

    private val gemmaAIService = GemmaAIService(context)

    suspend fun analyzeImage(task: String, imageBase64: String?): String {
        return gemmaAIService.analyzeImage(task, imageBase64)
    }

    suspend fun captureAndAnalyze(task: String, camera: CameraSelection = CameraSelection.BACK): String {
        return gemmaAIService.captureAndAnalyze(task, camera)
    }

    suspend fun captureImage(camera: CameraSelection = CameraSelection.BACK): String? {
        return gemmaAIService.captureImage(camera)
    }

    /**
     * Check if AI services are available and functional
     */
    fun isAIAvailable(): Boolean {
        return gemmaAIService.isAIAvailable()
    }

    /**
     * Get detailed AI capabilities information
     */
    fun getAICapabilities(): Map<String, Any> {
        return gemmaAIService.getAICapabilities()
    }

    fun cleanup() {
        gemmaAIService.cleanup()
    }
}
