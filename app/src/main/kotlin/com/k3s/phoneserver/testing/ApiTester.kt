package com.k3s.phoneserver.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class ApiTester {
    
    private val baseUrl = "http://localhost:8005"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    /**
     * Quick health check to verify server connectivity
     */
    suspend fun isServerReachable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/ai/models")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000 // 5 seconds for health check
                connection.readTimeout = 10000 // 10 seconds for health check
                connection.setRequestProperty("Accept", "application/json")
                
                val responseCode = connection.responseCode
                Timber.d("Server health check: $responseCode")
                
                connection.disconnect()
                responseCode in 200..299
            } catch (e: Exception) {
                Timber.w("Server health check failed: ${e.message}")
                false
            }
        }
    }
    
    suspend fun testEndpoint(endpoint: String, parameters: Map<String, String> = emptyMap()): ApiTestResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val timestamp = dateFormat.format(Date(startTime))
            
            try {
                val urlString = buildUrl(endpoint, parameters)
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000 // 30 seconds to connect
                connection.readTimeout = 300000 // 5 minutes for AI requests
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "AI-Phone-ApiTester/1.0")
                
                val responseCode = connection.responseCode
                val responseTime = System.currentTimeMillis() - startTime
                val contentType = connection.getHeaderField("Content-Type") ?: ""
                
                val responseBody = if (responseCode < 400) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }
                } else {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream)).use { reader ->
                        reader.readText()
                    }
                }
                
                ApiTestResult(
                    success = responseCode in 200..299,
                    endpoint = endpoint,
                    url = urlString,
                    statusCode = responseCode,
                    responseTime = responseTime,
                    response = responseBody,
                    contentType = contentType,
                    timestamp = timestamp,
                    method = "GET",
                    error = null
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Error testing endpoint $endpoint")
                val responseTime = System.currentTimeMillis() - startTime
                
                ApiTestResult(
                    success = false,
                    endpoint = endpoint,
                    url = buildUrl(endpoint, parameters),
                    statusCode = 0,
                    responseTime = responseTime,
                    response = "",
                    contentType = "",
                    timestamp = timestamp,
                    method = "GET",
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    suspend fun testPostEndpoint(endpoint: String, jsonBody: String): ApiTestResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val timestamp = dateFormat.format(Date(startTime))
            
            Timber.d("=== API Request Debug ===")
            Timber.d("URL: $baseUrl$endpoint")
            Timber.d("Method: POST")
            Timber.d("Request body length: ${jsonBody.length}")
            Timber.d("Request body: $jsonBody")
            
            try {
                val url = URL("$baseUrl$endpoint")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.connectTimeout = 30000 // 30 seconds to connect
                connection.readTimeout = 600000 // 10 minutes for AI processing
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "AI-Phone-ApiTester/1.0")
                connection.doOutput = true
                
                Timber.d("Sending request...")
                // Write request body
                connection.outputStream.use { outputStream ->
                    outputStream.write(jsonBody.toByteArray())
                    outputStream.flush()
                }
                
                Timber.d("Request sent, waiting for response...")
                val responseCode = connection.responseCode
                val responseTime = System.currentTimeMillis() - startTime
                val contentType = connection.getHeaderField("Content-Type") ?: ""
                
                Timber.d("Response received - Code: $responseCode, Time: ${responseTime}ms, ContentType: $contentType")
                
                val responseBody = if (responseCode < 400) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        val response = reader.readText()
                        Timber.d("Success response body length: ${response.length}")
                        Timber.d("Success response body: $response")
                        response
                    }
                } else {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream)).use { reader ->
                        val response = reader.readText()
                        Timber.w("Error response body length: ${response.length}")
                        Timber.w("Error response body: $response")
                        response
                    }
                }
                
                ApiTestResult(
                    success = responseCode in 200..299,
                    endpoint = endpoint,
                    url = "$baseUrl$endpoint",
                    statusCode = responseCode,
                    responseTime = responseTime,
                    response = responseBody,
                    contentType = contentType,
                    timestamp = timestamp,
                    method = "POST",
                    error = null
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Error testing POST endpoint $endpoint")
                val responseTime = System.currentTimeMillis() - startTime
                
                ApiTestResult(
                    success = false,
                    endpoint = endpoint,
                    url = "$baseUrl$endpoint",
                    statusCode = 0,
                    responseTime = responseTime,
                    response = "",
                    contentType = "",
                    timestamp = timestamp,
                    method = "POST",
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    private fun buildUrl(endpoint: String, parameters: Map<String, String>): String {
        val url = StringBuilder("$baseUrl$endpoint")
        
        if (parameters.isNotEmpty()) {
            url.append("?")
            parameters.entries.forEachIndexed { index, entry ->
                if (index > 0) url.append("&")
                url.append("${entry.key}=${entry.value}")
            }
        }
        
        return url.toString()
    }
}
