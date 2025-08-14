package com.k3s.phoneserver.formatting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Base64
import com.k3s.phoneserver.testing.ApiTestResult
import org.json.JSONObject

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
    }
    
    /**
     * Create a SpannableString with JSON syntax highlighting
     */
    fun formatJsonAsSpannable(response: String): SpannableString {
        if (response.isBlank()) return SpannableString(response)
        
        // Always clean base64 image data before formatting
        val cleanedResponse = cleanBase64ImageData(response)
        
        return try {
            val formatted = formatJsonStructure(cleanedResponse)
            addJsonSyntaxHighlightingSpannable(formatted)
        } catch (e: Exception) {
            // If JSON formatting fails, return cleaned response as spannable
            SpannableString(cleanedResponse)
        }
    }
    
    /**
     * Add syntax highlighting to JSON using SpannableString
     */
    private fun addJsonSyntaxHighlightingSpannable(json: String): SpannableString {
        val spannable = SpannableString(json)
        
        // Colors for different JSON elements
        val keyColor = Color.parseColor("#2196F3")      // Blue for keys
        val stringColor = Color.parseColor("#4CAF50")   // Green for string values
        val numberColor = Color.parseColor("#FF9800")   // Orange for numbers
        val booleanColor = Color.parseColor("#9C27B0")  // Purple for booleans
        val nullColor = Color.parseColor("#757575")     // Gray for null
        val structureColor = Color.parseColor("#607D8B") // Blue-gray for brackets/braces
        
        // Highlight JSON keys (quoted strings followed by colon)
        val keyRegex = Regex("\"([^\"]+)\"\\s*:")
        keyRegex.findAll(json).forEach { match ->
            val start = match.range.first + 1 // Skip opening quote
            val end = match.range.first + 1 + match.groupValues[1].length // Before closing quote
            spannable.setSpan(ForegroundColorSpan(keyColor), start, end, 0)
        }
        
        // Highlight string values (quoted strings after colon, not keys)
        val stringValueRegex = Regex(":\\s*\"([^\"]+)\"")
        stringValueRegex.findAll(json).forEach { match ->
            val valueStart = match.value.indexOf('"', 1) + 1 // Find second quote
            val start = match.range.first + valueStart
            val end = start + match.groupValues[1].length
            spannable.setSpan(ForegroundColorSpan(stringColor), start, end, 0)
        }
        
        // Highlight numbers
        val numberRegex = Regex(":\\s*(-?\\d+\\.?\\d*)")
        numberRegex.findAll(json).forEach { match ->
            val numberStart = match.value.indexOf(match.groupValues[1])
            val start = match.range.first + numberStart
            val end = start + match.groupValues[1].length
            spannable.setSpan(ForegroundColorSpan(numberColor), start, end, 0)
        }
        
        // Highlight booleans
        val booleanRegex = Regex(":\\s*(true|false)")
        booleanRegex.findAll(json).forEach { match ->
            val booleanStart = match.value.indexOf(match.groupValues[1])
            val start = match.range.first + booleanStart
            val end = start + match.groupValues[1].length
            spannable.setSpan(ForegroundColorSpan(booleanColor), start, end, 0)
        }
        
        // Highlight null values
        val nullRegex = Regex(":\\s*(null)")
        nullRegex.findAll(json).forEach { match ->
            val nullStart = match.value.indexOf("null")
            val start = match.range.first + nullStart
            val end = start + 4 // "null".length
            spannable.setSpan(ForegroundColorSpan(nullColor), start, end, 0)
        }
        
        // Highlight structural characters
        val structureChars = listOf('{', '}', '[', ']', ',', ':')
        json.forEachIndexed { index, char ->
            if (char in structureChars) {
                spannable.setSpan(ForegroundColorSpan(structureColor), index, index + 1, 0)
            }
        }
        
        return spannable
    }
    
    fun formatJsonWithHighlighting(response: String): String {
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
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                // Check for rotation metadata in the response
                val rotation = extractRotationFromResponse(response)
                if (rotation != 0 && bitmap != null) {
                    return rotateImageIfNeeded(bitmap, rotation)
                }
                
                bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extract rotation information from JSON response metadata
     */
    private fun extractRotationFromResponse(response: String): Int {
        return try {
            val jsonObject = JSONObject(response)
            if (jsonObject.has("metadata")) {
                val metadata = jsonObject.getJSONObject("metadata")
                if (metadata.has("rotation")) {
                    metadata.getInt("rotation")
                } else {
                    0
                }
            } else {
                0
            }
        } catch (e: Exception) {
            0 // Default to no rotation if parsing fails
        }
    }
    
    /**
     * Rotate bitmap based on EXIF orientation/rotation degrees
     */
    private fun rotateImageIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return bitmap
        }
        
        return try {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            
            // Recycle the original bitmap if it's different from the rotated one
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            
            rotatedBitmap
        } catch (e: Exception) {
            // If rotation fails, return the original bitmap
            bitmap
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
        var inArray = false
        var arrayDepth = 0
        var arrayStartPos = -1
        
        var i = 0
        while (i < json.length) {
            val char = json[i]
            
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
                !inString && char == '[' -> {
                    // Check if this array should be compact
                    val arrayContent = findMatchingBracket(json, i)
                    if (shouldCompactArray(json.substring(i, arrayContent + 1))) {
                        // Write compact array
                        val compactArray = formatCompactArray(json.substring(i, arrayContent + 1))
                        result.append(compactArray)
                        i = arrayContent
                    } else {
                        // Regular array formatting
                        result.append(char)
                        result.append('\n')
                        indent++
                        result.append("  ".repeat(indent))
                    }
                }
                !inString && char == '{' -> {
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
            i++
        }
        
        return result.toString()
    }
    
    private fun findMatchingBracket(json: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escapeNext = false
        
        for (i in start until json.length) {
            val char = json[i]
            when {
                escapeNext -> escapeNext = false
                char == '\\' && inString -> escapeNext = true
                char == '"' && !escapeNext -> inString = !inString
                !inString && char == '[' -> depth++
                !inString && char == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return json.length - 1
    }
    
    private fun shouldCompactArray(arrayStr: String): Boolean {
        // Remove outer brackets and whitespace
        val content = arrayStr.substring(1, arrayStr.length - 1).trim()
        if (content.isEmpty()) return true // Empty array
        
        // Check if array contains only primitives (no nested objects/arrays)
        var inString = false
        var escapeNext = false
        var depth = 0
        
        for (char in content) {
            when {
                escapeNext -> escapeNext = false
                char == '\\' && inString -> escapeNext = true
                char == '"' && !escapeNext -> inString = !inString
                !inString && (char == '{' || char == '[') -> {
                    depth++
                    if (depth > 0) return false // Contains nested structures
                }
                !inString && (char == '}' || char == ']') -> depth--
            }
        }
        
        // Also check length - keep compact if reasonably short
        return content.length < 200
    }
    
    private fun formatCompactArray(arrayStr: String): String {
        // Remove extra whitespace but preserve structure
        return arrayStr.replace(Regex("\\s*,\\s*"), ", ")
                       .replace(Regex("\\[\\s*"), "[")
                       .replace(Regex("\\s*\\]"), "]")
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
