package com.k3s.phoneserver.services

import android.content.Context
import android.content.Intent
import android.graphics.Color
import timber.log.Timber

/**
 * Service for displaying full-screen messages with customizable colors and duration
 */
class DisplayService(private val context: Context) {
    
    data class DisplayRequest(
        val text: String,
        val backgroundColor: String = "#000000",
        val textColor: String = "#FFFFFF",
        val duration: Long = 3000L // Duration in milliseconds
    )
    
    data class DisplayResponse(
        val success: Boolean,
        val message: String,
        val displayId: String? = null
    )
    
    /**
     * Show full-screen text with specified colors and duration
     */
    fun showDisplay(request: DisplayRequest): DisplayResponse {
        return try {
            // Parse colors
            val bgColor = parseColor(request.backgroundColor)
            val textColor = parseColor(request.textColor)
            
            if (bgColor == null) {
                return DisplayResponse(
                    success = false,
                    message = "Invalid background color format: ${request.backgroundColor}"
                )
            }
            
            if (textColor == null) {
                return DisplayResponse(
                    success = false,
                    message = "Invalid text color format: ${request.textColor}"
                )
            }
            
            // Validate duration
            val duration = request.duration.coerceIn(100, 30000) // 100ms to 30 seconds
            
            // Create display ID for tracking
            val displayId = "display_${System.currentTimeMillis()}"
            
            // Start display activity
            val intent = Intent(context, DisplayActivity::class.java).apply {
                putExtra("text", request.text)
                putExtra("backgroundColor", bgColor)
                putExtra("textColor", textColor)
                putExtra("duration", duration)
                putExtra("displayId", displayId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            context.startActivity(intent)
            
            Timber.d("Started display: $displayId, text: '${request.text}', duration: ${duration}ms")
            
            DisplayResponse(
                success = true,
                message = "Display started successfully",
                displayId = displayId
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to show display")
            DisplayResponse(
                success = false,
                message = "Failed to show display: ${e.message}"
            )
        }
    }
    
    /**
     * Parse color string (supports #RGB, #RRGGBB, named colors)
     */
    private fun parseColor(colorString: String): Int? {
        return try {
            when {
                colorString.startsWith("#") -> {
                    Color.parseColor(colorString)
                }
                colorString.equals("red", ignoreCase = true) -> Color.RED
                colorString.equals("green", ignoreCase = true) -> Color.GREEN
                colorString.equals("blue", ignoreCase = true) -> Color.BLUE
                colorString.equals("white", ignoreCase = true) -> Color.WHITE
                colorString.equals("black", ignoreCase = true) -> Color.BLACK
                colorString.equals("yellow", ignoreCase = true) -> Color.YELLOW
                colorString.equals("cyan", ignoreCase = true) -> Color.CYAN
                colorString.equals("magenta", ignoreCase = true) -> Color.MAGENTA
                colorString.equals("gray", ignoreCase = true) -> Color.GRAY
                colorString.equals("transparent", ignoreCase = true) -> Color.TRANSPARENT
                else -> {
                    // Try to parse as Color.parseColor
                    Color.parseColor(colorString)
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to parse color: $colorString")
            null
        }
    }
}
