package com.k3s.phoneserver.formatting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.k3s.phoneserver.testing.ApiTestResult

class ResponseFormatter private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: ResponseFormatter? = null
        
        fun getInstance(): ResponseFormatter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResponseFormatter().also { INSTANCE = it }
            }
        }
    }
    
    fun formatApiResponse(result: ApiTestResult, context: Context): String {
        if (result.error != null) {
            return "Error: ${result.error}"
        }
        
        // Always clean base64 image data from the response before formatting
        val cleanedResponse = cleanBase64ImageData(result.response)
        
        return when {
            result.contentType.contains("application/json", ignoreCase = true) -> {
                formatJsonWithHighlighting(cleanedResponse)
            }
            result.contentType.contains("image/", ignoreCase = true) -> {
                "[Binary Image Data - ${result.response.length} bytes]"
            }
            result.response.contains("data:image/", ignoreCase = true) -> {
                formatBase64ImageResponse(result.response, result.endpoint, result.url, context)
            }
            else -> cleanedResponse
        }
    }
    
    /**
     * Removes all base64 image data from any string and replaces with readable placeholders
     */
    fun cleanBase64ImageData(input: String): String {
        if (!input.contains("data:image/")) {
            return input
        }
        
        return try {
            // Replace any base64 image data with descriptive placeholders
            val imageDataRegex = "\"data:image/([^;]+);base64,([^\"]+)\"".toRegex()
            input.replace(imageDataRegex) { match ->
                val imageType = match.groupValues[1].uppercase()
                val base64Length = match.groupValues[2].length
                val estimatedBytes = (base64Length * 3L) / 4L
                "\"[IMAGE_${imageType}_${formatBytes(estimatedBytes)}]\""
            }
        } catch (e: Exception) {
            // If regex fails, try simple replacement
            input.replace(Regex("\"data:image/[^\"]+\""), "\"[IMAGE_DATA_HIDDEN]\"")
        }
    }    fun formatJsonWithHighlighting(response: String): String {
        if (response.isBlank()) return response
        
        // Always clean base64 image data before formatting
        val cleanedResponse = cleanBase64ImageData(response)
        
        return try {
            val formatted = formatJsonStructure(cleanedResponse)
            addJsonSyntaxHighlighting(formatted)
        } catch (e: Exception) {
            // If JSON formatting fails, return cleaned response
            cleanedResponse
        }
    }
    
    private fun formatBase64ImageResponse(response: String, endpoint: String, url: String, context: Context): String {
        return try {
            // Check if response contains base64 image data
            if (response.contains("data:image/")) {
                val imageDataRegex = "\"image\"\\s*:\\s*\"data:image/([^;]+);base64,([^\"]+)\"".toRegex()
                val match = imageDataRegex.find(response)
                if (match != null) {
                    val imageType = match.groupValues[1]
                    val base64Data = match.groupValues[2]
                    val estimatedBytes = (base64Data.length * 3L) / 4L // Base64 to bytes ratio
                    
                    // Create a cleaned up JSON response without the base64 data
                    val cleanedResponse = response.replace(
                        "\"data:image/$imageType;base64,$base64Data\"",
                        "\"[IMAGE_DISPLAYED_BELOW]\""
                    )
                    
                    val jsonFormatted = formatJsonWithHighlighting(cleanedResponse)
                    
                    return "$jsonFormatted\n\n[IMAGE: $imageType format, ${formatBytes(estimatedBytes)}]\n[Base64 data hidden for readability]"
                }
            }
            formatJsonWithHighlighting(response)
        } catch (e: Exception) {
            "Error processing image response: ${e.message}\n\nRaw response:\n$response"
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            bytes >= 1024 -> "${bytes / 1024}KB"
            else -> "${bytes}B"
        }
    }
    
    fun extractBase64Image(response: String): Bitmap? {
        return try {
            val imageDataRegex = "\"image\"\\s*:\\s*\"data:image/([^;]+);base64,([^\"]+)\"".toRegex()
            val match = imageDataRegex.find(response)
            if (match != null) {
                val base64Data = match.groupValues[2]
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractParametersFromUrl(url: String): Map<String, String> {
        return try {
            val uri = java.net.URI(url)
            val query = uri.query ?: return emptyMap()
            
            query.split("&").mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0] to parts[1]
                } else {
                    null
                }
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun formatJsonStructure(json: String): String {
        val result = StringBuilder()
        var indent = 0
        var inString = false
        var escapeNext = false
        
        for (char in json) {
            when {
                escapeNext -> {
                    result.append(char)
                    escapeNext = false
                }
                char == '\\' && inString -> {
                    result.append(char)
                    escapeNext = true
                }
                char == '"' -> {
                    result.append(char)
                    if (!escapeNext) {
                        inString = !inString
                    }
                }
                !inString && (char == '{' || char == '[') -> {
                    result.append(char)
                    result.append('\n')
                    indent++
                    result.append("  ".repeat(indent))
                }
                !inString && (char == '}' || char == ']') -> {
                    result.append('\n')
                    indent--
                    result.append("  ".repeat(indent))
                    result.append(char)
                }
                !inString && char == ',' -> {
                    result.append(char)
                    result.append('\n')
                    result.append("  ".repeat(indent))
                }
                !inString && char == ':' -> {
                    result.append(char)
                    result.append(' ')
                }
                char.isWhitespace() && !inString -> {
                    // Skip extra whitespace outside strings
                }
                else -> {
                    result.append(char)
                }
            }
        }
        
        return result.toString()
    }
    
    private fun addJsonSyntaxHighlighting(json: String): String {
        var result = json
        
        // Highlight keys with bold formatting
        result = result.replace(Regex("\"([^\"]+)\"\\s*:")) { match ->
            val key = match.groupValues[1]
            "\"$key\":"
        }
        
        // Highlight string values 
        result = result.replace(Regex(":\\s*\"([^\"]+)\"")) { match ->
            val value = match.groupValues[1]
            ": \"$value\""
        }
        
        // Highlight numbers
        result = result.replace(Regex(":\\s*(-?\\d+\\.?\\d*)")) { match ->
            val number = match.groupValues[1]
            ": $number"
        }
        
        // Highlight booleans
        result = result.replace(Regex(":\\s*(true)")) { ": true" }
        result = result.replace(Regex(":\\s*(false)")) { ": false" }
        
        // Highlight null
        result = result.replace(Regex(":\\s*(null)")) { ": null" }
        
        return result
    }
}
