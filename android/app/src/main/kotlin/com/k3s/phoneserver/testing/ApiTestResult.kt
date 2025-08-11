package com.k3s.phoneserver.testing

data class ApiTestResult(
    val endpoint: String,
    val url: String,
    val response: String,
    val success: Boolean,
    val statusCode: Int,
    val responseTime: Long,
    val contentType: String,
    val timestamp: String,
    val error: String? = null
)
