package com.k3s.phoneserver.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadService(private val context: Context) {

    private val modelsDir = File(context.filesDir, "ai_models")

    data class ModelInfo(
        val filename: String,
        val url: String,
        val description: String,
        val minSize: Long
    )

    private val models = listOf(
        ModelInfo(
            "efficientnet_lite0.tflite",
            "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite",
            "EfficientNet Lite (Image Classification)",
            18_000_000L
        ),
        ModelInfo(
            "efficientdet_lite0.tflite",
            "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite",
            "EfficientDet Lite (Object Detection)",
            7_000_000L
        ),
        ModelInfo(
            "mobilenet_v3_small.tflite",
            "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite",
            "MobileNet V3 Small (Image Embeddings)",
            4_000_000L
        ),
        ModelInfo(
            "universal_sentence_encoder.tflite",
            "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite",
            "Universal Sentence Encoder (Text Embeddings)",
            6_000_000L
        )
        // Note: Only vision models are auto-downloaded
        // Gemma LLM is optional and must be manually placed in assets/gemma.task
    )

    suspend fun downloadAllModels(progressCallback: (Int, String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Create models directory
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs()
                }

                var completedModels = 0
                val totalModels = models.size

                for (model in models) {
                    val targetFile = File(modelsDir, model.filename)
                    
                    // Skip if already downloaded and valid
                    if (isModelValid(targetFile, model.minSize)) {
                        completedModels++
                        val progress = (completedModels * 100) / totalModels
                        progressCallback(progress, "✅ ${model.description} already downloaded")
                        continue
                    }

                    progressCallback(0, "📥 Downloading ${model.description}...")
                    
                    val success = downloadModel(model, targetFile) { bytesDownloaded, totalBytes ->
                        val modelProgress = if (totalBytes > 0) {
                            ((bytesDownloaded * 100) / totalBytes).toInt()
                        } else 0
                        
                        val overallProgress = ((completedModels * 100) + modelProgress) / totalModels
                        progressCallback(overallProgress, "📥 ${model.description}: ${formatBytes(bytesDownloaded)}/${formatBytes(totalBytes)}")
                    }

                    if (success) {
                        completedModels++
                        val progress = (completedModels * 100) / totalModels
                        progressCallback(progress, "✅ ${model.description} downloaded")
                    } else {
                        progressCallback(0, "❌ Failed to download ${model.description}")
                        return@withContext false
                    }
                }

                progressCallback(100, "🎉 All models downloaded successfully!")
                return@withContext true
            } catch (e: Exception) {
                Timber.e(e, "Model download failed")
                progressCallback(0, "❌ Download failed: ${e.message}")
                return@withContext false
            }
        }
    }

    private suspend fun downloadModel(
        model: ModelInfo, 
        targetFile: File,
        progressCallback: (Long, Long) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(model.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "K3sPhoneServer/1.0")

                val totalBytes = connection.contentLengthLong
                var bytesDownloaded = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            progressCallback(bytesDownloaded, totalBytes)
                        }
                    }
                }

                // Verify download
                if (isModelValid(targetFile, model.minSize)) {
                    Timber.d("Successfully downloaded ${model.filename}")
                    return@withContext true
                } else {
                    Timber.e("Downloaded file ${model.filename} is invalid or too small")
                    targetFile.delete()
                    return@withContext false
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download ${model.filename}")
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                return@withContext false
            }
        }
    }

    private fun isModelValid(file: File, minSize: Long): Boolean {
        return file.exists() && file.length() >= minSize
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    fun getModelsDirectory(): String {
        return modelsDir.absolutePath
    }

    fun areModelsDownloaded(): Boolean {
        if (!modelsDir.exists()) return false
        
        return models.all { model ->
            val file = File(modelsDir, model.filename)
            isModelValid(file, model.minSize)
        }
    }
}
