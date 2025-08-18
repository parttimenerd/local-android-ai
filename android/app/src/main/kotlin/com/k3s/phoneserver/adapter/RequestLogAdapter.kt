package com.k3s.phoneserver.adapter

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.k3s.phoneserver.R
import com.k3s.phoneserver.logging.RequestLog
import com.k3s.phoneserver.formatting.ResponseFormatter

class RequestLogAdapter : ListAdapter<RequestLog, RequestLogAdapter.RequestLogViewHolder>(RequestLogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestLogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_request_log, parent, false)
        return RequestLogViewHolder(view)
    }

    override fun onBindViewHolder(holder: RequestLogViewHolder, position: Int) {
        val requestLog = getItem(position)
        holder.bind(requestLog)
        
        // For ongoing requests, force refresh to show live duration
        if (requestLog.isOngoing) {
            android.util.Log.d("RequestLogAdapter", "Binding ongoing request at position $position")
        }
    }

    class RequestLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTimestamp: TextView = itemView.findViewById(R.id.textTimestamp)
        private val textMethod: TextView = itemView.findViewById(R.id.textMethod)
        private val textPath: TextView = itemView.findViewById(R.id.textPath)
        private val textStatusCode: TextView = itemView.findViewById(R.id.textStatusCode)
        private val textResponseTime: TextView = itemView.findViewById(R.id.textResponseTime)

        fun bind(requestLog: RequestLog) {
            textTimestamp.text = requestLog.timestamp
            textMethod.text = requestLog.method
            textPath.text = requestLog.path
            
            if (requestLog.isOngoing) {
                textStatusCode.text = "..."
                textStatusCode.setTextColor(Color.parseColor("#2196F3")) // Blue for ongoing
                
                // Show live duration for ongoing requests
                val currentDuration = com.k3s.phoneserver.logging.RequestLogger.getCurrentDuration(requestLog)
                val durationSeconds = currentDuration / 1000.0
                textResponseTime.text = String.format("%.2fs", durationSeconds)
                textResponseTime.setTextColor(Color.parseColor("#2196F3")) // Blue for ongoing
                
                android.util.Log.d("RequestLogAdapter", "Ongoing request: ${requestLog.path}, duration: ${String.format("%.2fs", durationSeconds)}")
            } else {
                textStatusCode.text = requestLog.statusCode.toString()
                val durationSeconds = requestLog.responseTime / 1000.0
                textResponseTime.text = String.format("%.2fs", durationSeconds)
                
                // Color code status codes for completed requests
                val statusColor = when (requestLog.statusCode) {
                    in 200..299 -> Color.parseColor("#4CAF50") // Green for success
                    in 300..399 -> Color.parseColor("#FF9800") // Orange for redirect
                    in 400..499 -> Color.parseColor("#F44336") // Red for client error
                    in 500..599 -> Color.parseColor("#9C27B0") // Purple for server error
                    else -> Color.parseColor("#757575") // Gray for unknown
                }
                textStatusCode.setTextColor(statusColor)
                textResponseTime.setTextColor(Color.parseColor("#757575")) // Gray for completed
            }

            // Color code methods
            val methodColor = when (requestLog.method) {
                "GET" -> Color.parseColor("#2196F3") // Blue
                "POST" -> Color.parseColor("#4CAF50") // Green
                "PUT" -> Color.parseColor("#FF9800") // Orange
                "DELETE" -> Color.parseColor("#F44336") // Red
                else -> Color.parseColor("#757575") // Gray
            }
            textMethod.setTextColor(methodColor)
            
            // Add click listener to show details
            itemView.setOnClickListener {
                showRequestDetails(requestLog)
            }
        }
        
        private fun showRequestDetails(requestLog: RequestLog) {
            // Check if this is an image response with actual JSON data
            if (requestLog.responseType == "image" && requestLog.responseData?.contains("data:image/") == true) {
                // Try to extract and show the actual image
                showImageDialog(requestLog)
            } else {
                showTextDialog(requestLog)
            }
        }
        
        private fun showImageDialog(requestLog: RequestLog) {
            val context = itemView.context
            
            // Create a custom dialog layout with ImageView
            val imageView = ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = (context.resources.displayMetrics.heightPixels * 0.6).toInt()
                setPadding(16, 16, 16, 16)
            }
            
            val textView = TextView(context).apply {
                val durationSeconds = requestLog.responseTime / 1000.0
                text = "Image captured at ${requestLog.timestamp}\nResponse time: ${String.format("%.2fs", durationSeconds)}\nStatus: ${requestLog.statusCode}"
                setPadding(16, 16, 16, 8)
                textSize = 14f
            }
            
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(textView)
                addView(imageView)
            }
            
            // Try to extract and decode the base64 image from the JSON response
            val extractedImage = requestLog.responseData?.let { jsonResponse ->
                ResponseFormatter.getInstance().extractBase64Image(jsonResponse)
            }
            
            if (extractedImage != null) {
                imageView.setImageBitmap(extractedImage)
                
                AlertDialog.Builder(context)
                    .setTitle("Image Response - ${requestLog.path}")
                    .setView(container)
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Show JSON") { _, _ ->
                        showTextDialog(requestLog)
                    }
                    .show()
            } else {
                // Fallback to text dialog if image extraction fails
                showTextDialog(requestLog)
            }
        }
        
    private fun showRemovedImageDialog() {
        AlertDialog.Builder(itemView.context)
            .setTitle("Image Data Removed")
            .setMessage("Image data has been removed to save memory. Only the 50 most recent image requests retain their data.")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }        private fun showTextDialog(requestLog: RequestLog) {
            val context = itemView.context
            
            // For JSON responses, create a formatted dialog with syntax highlighting
            val isJsonResponse = (requestLog.responseType == "json" || 
                                 requestLog.responseType == "ai_text_success" ||
                                 requestLog.responseType == "ai_text_error" ||
                                 requestLog.responseData?.startsWith("{") == true ||
                                 requestLog.responseData?.startsWith("[") == true) && 
                                 !requestLog.responseData.isNullOrBlank()
                                 
            if (isJsonResponse) {
                showFormattedJsonDialog(requestLog)
                return
            }
            
            val details = buildString {
                appendLine("Request Details")
                appendLine()
                appendLine("Timestamp: ${requestLog.timestamp}")
                appendLine("Method: ${requestLog.method}")
                appendLine("Path: ${requestLog.path}")
                appendLine("Client IP: ${requestLog.clientIp}")
                appendLine("Status Code: ${requestLog.statusCode}")
                val durationSeconds = requestLog.responseTime / 1000.0
                appendLine("Response Time: ${String.format("%.2fs", durationSeconds)}")
                if (!requestLog.userAgent.isNullOrBlank()) {
                    appendLine("User Agent: ${requestLog.userAgent}")
                }
                
                // Add status description
                val statusDescription = when (requestLog.statusCode) {
                    200 -> "OK - Request succeeded"
                    201 -> "Created - Resource created successfully"
                    400 -> "Bad Request - Invalid request format"
                    401 -> "Unauthorized - Authentication required"
                    403 -> "Forbidden - Access denied"
                    404 -> "Not Found - Resource not found"
                    500 -> "Internal Server Error - Server error occurred"
                    else -> "HTTP Status ${requestLog.statusCode}"
                }
                appendLine()
                appendLine("Status: $statusDescription")
                
                // Add request body if available
                if (!requestLog.requestBody.isNullOrBlank()) {
                    appendLine()
                    appendLine("Request Body:")
                    appendLine()
                    
                    // Format JSON request body with highlighting
                    try {
                        val formatted = ResponseFormatter.getInstance().formatJsonWithHighlighting(requestLog.requestBody)
                        appendLine(formatted)
                    } catch (e: Exception) {
                        // If not valid JSON, show as plain text
                        appendLine(requestLog.requestBody)
                    }
                }
                
                // Add response data if available
                if (!requestLog.responseData.isNullOrBlank()) {
                    appendLine()
                    appendLine("Response:")
                    appendLine()
                    
                    when (requestLog.responseType) {
                        "json" -> {
                            // Format JSON with clean syntax highlighting (base64 data automatically removed)
                            val formatted = ResponseFormatter.getInstance().formatJsonWithHighlighting(requestLog.responseData)
                            appendLine(formatted)
                        }
                        "image" -> {
                            // Handle image responses
                            if (requestLog.responseData.contains("IMAGE_DATA_REMOVED")) {
                                appendLine("Image data removed to save memory")
                                appendLine("(Only the 50 most recent image requests retain full data)")
                            } else if (requestLog.responseData.contains("data:image/")) {
                                // Use ResponseFormatter to clean base64 data universally
                                val cleaned = ResponseFormatter.getInstance().cleanBase64ImageData(requestLog.responseData)
                                val formatted = ResponseFormatter.getInstance().formatJsonWithHighlighting(cleaned)
                                appendLine(formatted)
                            } else {
                                appendLine(requestLog.responseData)
                            }
                        }
                        "error" -> {
                            // Clean any base64 data from error messages too
                            val cleanedError = ResponseFormatter.getInstance().cleanBase64ImageData(requestLog.responseData ?: "")
                            appendLine("Error: $cleanedError")
                        }
                        "text" -> {
                            // Clean any base64 data from text responses
                            val cleanedText = ResponseFormatter.getInstance().cleanBase64ImageData(requestLog.responseData ?: "")
                            appendLine(cleanedText)
                        }
                        else -> {
                            if (!requestLog.responseData.isNullOrBlank()) {
                                // Clean any base64 data from unknown response types
                                val cleanedData = ResponseFormatter.getInstance().cleanBase64ImageData(requestLog.responseData)
                                appendLine(cleanedData)
                            }
                        }
                    }
                }
            }
            
            AlertDialog.Builder(context)
                .setTitle("Request Log Details")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .show()
        }
        
        private fun showFormattedJsonDialog(requestLog: RequestLog) {
            val context = itemView.context
            
            // Build content with proper spannable formatting
            val spannableBuilder = android.text.SpannableStringBuilder()
            
            // Add header information
            spannableBuilder.append("${requestLog.method} ${requestLog.path}\n")
            val durationSeconds = requestLog.responseTime / 1000.0
            spannableBuilder.append("${requestLog.timestamp} | Status: ${requestLog.statusCode} | ${String.format("%.2fs", durationSeconds)}\n")
            spannableBuilder.append("Client: ${requestLog.clientIp}\n\n")
            
            // Add request body if available
            if (!requestLog.requestBody.isNullOrBlank()) {
                spannableBuilder.append("REQUEST BODY:\n")
                try {
                    val formattedRequestBody = ResponseFormatter.getInstance().formatJsonAsSpannable(requestLog.requestBody)
                    spannableBuilder.append(formattedRequestBody)
                    spannableBuilder.append("\n\n")
                } catch (e: Exception) {
                    spannableBuilder.append("${requestLog.requestBody}\n\n")
                }
            }
            
            // Add response data
            spannableBuilder.append("RESPONSE:\n")
            if (!requestLog.responseData.isNullOrBlank()) {
                try {
                    val formattedResponse = ResponseFormatter.getInstance().formatJsonAsSpannable(requestLog.responseData)
                    spannableBuilder.append(formattedResponse)
                } catch (e: Exception) {
                    spannableBuilder.append(requestLog.responseData ?: "")
                }
            } else {
                spannableBuilder.append("No response data")
            }
            
            // Create a TextView for displaying formatted content
            val textView = TextView(context).apply {
                text = spannableBuilder
                setPadding(16, 16, 16, 16)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                // Enable text selection and movement methods for better interaction
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
            
            // Create a ScrollView to contain the TextView
            val scrollView = android.widget.ScrollView(context).apply {
                addView(textView)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * 0.6).toInt()
                )
            }
            
            val title = buildString {
                append("Request Log Details - ${requestLog.path}")
                appendLine()
                val durationSeconds = requestLog.responseTime / 1000.0
                append("${requestLog.timestamp} | ${requestLog.method} | ${requestLog.statusCode} | ${String.format("%.2fs", durationSeconds)}")
            }
            
            AlertDialog.Builder(context)
                .setTitle("Request Details")
                .setView(scrollView)
                .setPositiveButton("OK", null)
                .setNegativeButton("Copy All") { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Request Details", spannableBuilder.toString())
                    clipboard.setPrimaryClip(clip)
                }
                .create()
                .show()
        }
    }

    class RequestLogDiffCallback : DiffUtil.ItemCallback<RequestLog>() {
        override fun areItemsTheSame(oldItem: RequestLog, newItem: RequestLog): Boolean {
            return oldItem.requestId == newItem.requestId
        }

        override fun areContentsTheSame(oldItem: RequestLog, newItem: RequestLog): Boolean {
            // For ongoing requests, never consider content as the same to force updates
            if (oldItem.isOngoing || newItem.isOngoing) {
                return false
            }
            // For completed requests, compare all fields
            return oldItem == newItem
        }
    }
}
