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
    val userAgent: String? = null
)

object RequestLogger {
    private val _requestLogs = MutableLiveData<List<RequestLog>>(emptyList())
    val requestLogs: LiveData<List<RequestLog>> = _requestLogs
    
    private val maxLogs = 100
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    fun logRequest(
        method: String,
        path: String,
        clientIp: String,
        statusCode: Int,
        responseTime: Long,
        userAgent: String? = null
    ) {
        val timestamp = dateFormat.format(Date())
        val newLog = RequestLog(timestamp, method, path, clientIp, statusCode, responseTime, userAgent)
        
        val currentLogs = _requestLogs.value ?: emptyList()
        val updatedLogs = (listOf(newLog) + currentLogs).take(maxLogs)
        
        _requestLogs.postValue(updatedLogs)
    }
    
    fun clearLogs() {
        _requestLogs.postValue(emptyList())
    }
}
