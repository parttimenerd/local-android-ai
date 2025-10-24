package com.k3s.phoneserver.logging

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.*

data class RequestLog(
    val timestamp: String,
    val method: String,
    val path: String,
    val clientIp: String,
    val statusCode: Int,
    val responseTime: Long,
    val userAgent: String? = null,
    val responseData: String? = null,
    val responseType: String? = null,
    val requestBody: String? = null, // Add request body field
    val requestId: String = "${timestamp}_${method}_${path}_${System.nanoTime()}", // Unique identifier for updates
    val startTime: Long = System.currentTimeMillis(), // Track when request started
    val isOngoing: Boolean = statusCode == 0 // 0 status code indicates ongoing request
)

object RequestLogger {
    private val _requestLogs = MutableLiveData<List<RequestLog>>(emptyList())
    val requestLogs: LiveData<List<RequestLog>> = _requestLogs
    
    private val maxLogs = 100
    private val maxImageLogsWithData = 50 // Keep full base64 data for only the 50 most recent image requests
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    fun logRequest(
        method: String,
        path: String,
        clientIp: String,
        statusCode: Int,
        responseTime: Long,
        userAgent: String? = null,
        responseData: String? = null,
        responseType: String? = null,
        requestBody: String? = null
    ): String {
        val timestamp = dateFormat.format(Date())
        val newLog = RequestLog(timestamp, method, path, clientIp, statusCode, responseTime, userAgent, responseData, responseType, requestBody)
        
        val currentLogs = _requestLogs.value ?: emptyList()
        val updatedLogs = (listOf(newLog) + currentLogs).take(maxLogs)
        
        // Clean up old image data to preserve memory
        val cleanedLogs = cleanupOldImageData(updatedLogs)
        
        _requestLogs.postValue(cleanedLogs)
        
        return newLog.requestId
    }
    
    fun updateRequest(
        requestId: String,
        statusCode: Int,
        responseTime: Long,
        responseData: String? = null,
        responseType: String? = null
    ) {
        val currentLogs = _requestLogs.value ?: emptyList()
        val updatedLogs = currentLogs.map { log ->
            if (log.requestId == requestId) {
                log.copy(
                    statusCode = statusCode,
                    responseTime = responseTime,
                    responseData = responseData,
                    responseType = responseType,
                    isOngoing = false // Request is now complete
                )
            } else {
                log
            }
        }
        
        // Clean up old image data to preserve memory
        val cleanedLogs = cleanupOldImageData(updatedLogs)
        
        _requestLogs.postValue(cleanedLogs)
    }
    
    /**
     * Get current duration for ongoing requests
     */
    fun getCurrentDuration(requestLog: RequestLog): Long {
        return if (requestLog.isOngoing) {
            System.currentTimeMillis() - requestLog.startTime
        } else {
            requestLog.responseTime
        }
    }
    
    /**
     * Refresh durations for ongoing requests - call this periodically to update UI
     */
    fun refreshOngoingDurations() {
        val currentLogs = _requestLogs.value ?: emptyList()
        val hasOngoingRequests = currentLogs.any { it.isOngoing }
        
        if (hasOngoingRequests) {
            // Force a UI update by posting the same list with a timestamp change
            // This ensures the adapter gets notified even if DiffUtil thinks nothing changed
            _requestLogs.postValue(currentLogs.toList()) // Create a new list instance
            android.util.Log.d("RequestLogger", "Refreshed durations for ${currentLogs.count { it.isOngoing }} ongoing requests")
        }
    }
    
    private fun cleanupOldImageData(logs: List<RequestLog>): List<RequestLog> {
        val imageLogs = logs.filter { it.responseType == "image" && it.responseData?.contains("data:image/") == true }
        val imageLogsToKeep = imageLogs.take(maxImageLogsWithData).map { it.timestamp + it.path }
        
        return logs.map { log ->
            if (log.responseType == "image" && 
                log.responseData?.contains("data:image/") == true && 
                (log.timestamp + log.path) !in imageLogsToKeep) {
                // Replace base64 image data with placeholder for old requests
                val cleanedResponseData = log.responseData?.let { responseData ->
                    val imageDataRegex = "\"data:image/([^;]+);base64,([^\"]+)\"".toRegex()
                    responseData.replace(imageDataRegex) { match ->
                        val imageType = match.groupValues[1]
                        "\"[IMAGE_DATA_REMOVED_${imageType.uppercase()}]\""
                    }
                } ?: "Image data removed to save memory"
                
                log.copy(responseData = cleanedResponseData)
            } else {
                log
            }
        }
    }
    
    fun clearLogs() {
        _requestLogs.postValue(emptyList())
    }
}
