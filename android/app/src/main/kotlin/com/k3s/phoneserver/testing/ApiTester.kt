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
    
    suspend fun testEndpoint(endpoint: String, parameters: Map<String, String> = emptyMap()): ApiTestResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val timestamp = dateFormat.format(Date(startTime))
            
            try {
                val urlString = buildUrl(endpoint, parameters)
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "K3s-Phone-ApiTester/1.0")
                
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
