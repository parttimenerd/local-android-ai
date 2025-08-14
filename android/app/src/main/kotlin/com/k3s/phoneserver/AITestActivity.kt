package com.k3s.phoneserver

import android.os.Bundle
import android.view.View
import android.widget.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.k3s.phoneserver.ai.ModelDetector
import com.k3s.phoneserver.logging.RequestLogger
import com.k3s.phoneserver.testing.ApiTester
import com.k3s.phoneserver.testing.ApiTestResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class AITestActivity : AppCompatActivity() {
    
    private lateinit var inputText: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var radioGroup: RadioGroup
    private lateinit var layoutImageInput: LinearLayout
    private lateinit var layoutResults: LinearLayout
    private lateinit var textResults: TextView
    private lateinit var imageViewCaptured: ImageView
    private lateinit var generateButton: Button
    private lateinit var clearButton: Button
    
    private val apiTester = ApiTester()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var markwon: Markwon
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_test)
        
        // Set up the action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "🤖 AI Text Generation"
        
        // Initialize Markwon for better text formatting
        markwon = Markwon.create(this)
        
        initializeViews()
        setupModelSelection()
        setupImageInputOptions()
        setupButtons()
        
        // Set default prompt
        inputText.setText("Hello")
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    private fun initializeViews() {
        inputText = findViewById(R.id.editTextPrompt)
        modelSpinner = findViewById(R.id.spinnerModel)
        radioGroup = findViewById(R.id.radioGroupImageInput)
        layoutImageInput = findViewById(R.id.layoutImageInput)
        layoutResults = findViewById(R.id.layoutResults)
        textResults = findViewById(R.id.textResults)
        imageViewCaptured = findViewById(R.id.imageViewCaptured)
        generateButton = findViewById(R.id.buttonGenerate)
        clearButton = findViewById(R.id.buttonClear)
    }
    
    
    private fun setupModelSelection() {
        lifecycleScope.launch {
            val availableModels = withContext(Dispatchers.IO) {
                ModelDetector.getAvailableModels(this@AITestActivity)
            }
            
            if (availableModels.isEmpty()) {
                // No models available - show message
                Toast.makeText(this@AITestActivity, "⚠️ No AI models are available. Please add models first.", Toast.LENGTH_LONG).show()
                generateButton.isEnabled = false
                return@launch
            }
            
            val modelDisplayNames = availableModels.map { "${it.modelName} (${it.fileName})" }
            
            val modelAdapter = ArrayAdapter(this@AITestActivity, android.R.layout.simple_spinner_item, modelDisplayNames)
            modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = modelAdapter
            
            // Function to update image input visibility based on selected model
            fun updateImageInputVisibility() {
                val selectedModelIndex = modelSpinner.selectedItemPosition
                if (selectedModelIndex >= 0 && selectedModelIndex < availableModels.size) {
                    val selectedModel = availableModels[selectedModelIndex]
                    val isMultimodal = selectedModel.supportsVision
                    layoutImageInput.visibility = if (isMultimodal) View.VISIBLE else View.GONE
                    
                    // Update prompt hint based on model capability
                    if (isMultimodal) {
                        inputText.hint = "Type your message here (you can include image input)..."
                    } else {
                        inputText.hint = "Type your message here..."
                        // Reset to text-only if model doesn't support vision
                        radioGroup.check(R.id.radioNoImage)
                    }
                }
            }
            
            // Set initial visibility
            updateImageInputVisibility()
            
            // Update visibility when model selection changes
            modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateImageInputVisibility()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
    private fun setupImageInputOptions() {
        // Default to no image
        radioGroup.check(R.id.radioNoImage)
        
        // Set up listener for radio button changes to update prompt
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            updatePromptBasedOnImageInput(checkedId)
        }
    }
    
    private fun updatePromptBasedOnImageInput(checkedId: Int) {
        val currentText = inputText.text.toString()
        
        // Only update if the current text is empty or is one of the default prompts
        if (currentText.isEmpty() || currentText == "Hello" || currentText == "Describe the scene in one sentence") {
            when (checkedId) {
                R.id.radioFrontCamera, R.id.radioRearCamera -> {
                    inputText.setText("Describe the scene in one sentence")
                }
                R.id.radioNoImage -> {
                    inputText.setText("Hello")
                }
            }
        }
    }
    
    private fun setupButtons() {
        generateButton.setOnClickListener {
            generateAIText()
        }
        
        clearButton.setOnClickListener {
            clearResults()
        }
    }
    
    private fun generateAIText() {
        val prompt = inputText.text.toString()
        if (prompt.isBlank()) {
            Toast.makeText(this, "Please enter a prompt", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            val availableModels = withContext(Dispatchers.IO) {
                ModelDetector.getAvailableModels(this@AITestActivity)
            }
            
            if (availableModels.isEmpty()) {
                Toast.makeText(this@AITestActivity, "No models available", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val selectedModelName = availableModels[modelSpinner.selectedItemPosition].name
            val imageInputType = getSelectedImageInputType(radioGroup)
            
            // Show loading state
            generateButton.isEnabled = false
            generateButton.text = "⏳ Processing..."
            layoutResults.visibility = View.VISIBLE
            imageViewCaptured.visibility = View.GONE
            
            // Show better loading message
            val loadingMessage = buildString {
                appendLine("🔄 Processing your request...")
            }
            markwon.setMarkdown(textResults, loadingMessage)
            
            try {
                // Simple logging
                Timber.d("Testing AI text generation - Model: $selectedModelName, Prompt: $prompt")
                
                // Generate AI text using the same logic as MainActivity
                generateAITextInActivity(prompt, selectedModelName, imageInputType)
                
            } catch (e: Exception) {
                Timber.e(e, "Error generating AI text")
                val errorMessage = buildString {
                    appendLine("❌ **Error occurred:**")
                    appendLine()
                    appendLine("```")
                    appendLine("${e.message}")
                    appendLine("```")
                    appendLine()
                    appendLine("**Error Type:** ${e.javaClass.simpleName}")
                    appendLine()
                    appendLine("**Troubleshooting:**")
                    if (imageInputType != "none") {
                        appendLine("• **Image processing**: This may take 1-2 minutes for complex images")
                        appendLine("• **Camera timeout**: Try with a simpler image or no image")
                        appendLine("• **Memory issues**: Large images may cause processing failures")
                    }
                    appendLine("• **Server connection**: Check if the server is running on localhost:8005")
                    appendLine("• **Model loading**: Verify the selected model is properly loaded")
                    appendLine("• **Network**: Ensure stable connection during image processing")
                    appendLine("• **Prompt complexity**: Try a simpler prompt")
                    appendLine()
                    appendLine("**Debug Info:**")
                    appendLine("- Model: $selectedModelName")
                    appendLine("- Image Input: $imageInputType")
                    appendLine("- Prompt Length: ${prompt.length} characters")
                }
                markwon.setMarkdown(textResults, errorMessage)
            } finally {
                generateButton.isEnabled = true
                generateButton.text = "🚀 Generate"
            }
        }
    }
    
    private fun clearResults() {
        layoutResults.visibility = View.GONE
        textResults.text = ""
        imageViewCaptured.visibility = View.GONE
        imageViewCaptured.setImageBitmap(null)
    }
    private fun getSelectedImageInputType(radioGroup: RadioGroup): String {
        return when (radioGroup.checkedRadioButtonId) {
            R.id.radioFrontCamera -> "front_camera"
            R.id.radioRearCamera -> "rear_camera"
            R.id.radioNoImage -> "none"
            else -> "none"
        }
    }
    
    private suspend fun generateAITextInActivity(
        prompt: String,
        model: String,
        imageInputType: String
    ) {
        withContext(Dispatchers.IO) {
            try {
                Timber.d("=== AI Request Debug ===")
                Timber.d("Prompt: '$prompt'")
                Timber.d("Model: '$model'")
                Timber.d("Image Input Type: '$imageInputType'")
                
                val requestBody = JsonObject().apply {
                    addProperty("text", prompt)
                    addProperty("model", model)
                    addProperty("temperature", 0.7)
                    addProperty("topK", 40)
                    addProperty("topP", 0.95)
                    addProperty("returnImage", false)  // Off by default as requested
                    
                    // Add image capture config if needed
                    if (imageInputType != "none") {
                        val captureConfig = JsonObject().apply {
                            addProperty("camera", if (imageInputType == "front_camera") "front" else "rear")
                        }
                        add("captureConfig", captureConfig)
                    }
                }

                val jsonBody = gson.toJson(requestBody)
                Timber.d("Request JSON: $jsonBody")
                Timber.d("Making API call to: /ai/text")
                
                val result = apiTester.testPostEndpoint("/ai/text", jsonBody)
                
                Timber.d("=== AI Response Debug ===")
                Timber.d("Status Code: ${result.statusCode}")
                Timber.d("Success: ${result.success}")
                Timber.d("Response Time: ${result.responseTime}ms")
                Timber.d("Content Type: ${result.contentType}")
                Timber.d("Raw Response: ${result.response}")
                Timber.d("Error: ${result.error}")
                
                withContext(Dispatchers.Main) {
                    // Check if the request failed and show detailed error
                    if (!result.success || result.statusCode >= 400) {
                        Timber.w("API request failed - showing error")
                        showAPIErrorInActivity(jsonBody, result, imageInputType != "none")
                    } else {
                        Timber.d("API request successful - showing result")
                        showAITestResultInActivity(jsonBody, result)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val detailedError = buildString {
                        appendLine("❌ **Network/Connection Error**")
                        appendLine()
                        appendLine("**Error:** ${e.message}")
                        appendLine("**Error Type:** ${e.javaClass.simpleName}")
                        appendLine()
                        if (e.message?.contains("timeout", ignoreCase = true) == true) {
                            appendLine("🔍 **Timeout Detected:**")
                            appendLine("- Server may not be running on localhost:8005")
                            appendLine("- Processing taking longer than 2 minutes")
                            appendLine("- Network connectivity issues")
                            appendLine()
                        }
                        if (imageInputType != "none") {
                            appendLine("⚠️ **Image Processing Note:** Image requests can take 1-2 minutes")
                            appendLine()
                        }
                        appendLine("**Troubleshooting:**")
                        appendLine("• Check if server is running: `curl http://localhost:8005/health`")
                        appendLine("• Verify no firewall blocking port 8005")
                        appendLine("• Try without image first to isolate the issue")
                        appendLine("• Check device memory - large images may cause timeouts")
                        appendLine()
                        appendLine("**Debug Info:**")
                        appendLine("- URL: http://localhost:8005/ai/text")
                        appendLine("- Timeout: 2 minutes (120 seconds)")
                        appendLine("- Request: ${if (imageInputType != "none") "Image + Text" else "Text only"}")
                        appendLine()
                        appendLine("**Request Details:**")
                        appendLine("- Model: $model")
                        appendLine("- Prompt: \"$prompt\"")
                        if (imageInputType != "none") {
                            appendLine("- Camera: $imageInputType")
                        }
                    }
                    markwon.setMarkdown(textResults, detailedError)
                }
            }
        }
    }
    
    private fun showAPIErrorInActivity(requestJson: String, result: ApiTestResult, hasImage: Boolean) {
        val formattedRequest = try {
            formatJson(requestJson)
        } catch (e: Exception) {
            requestJson
        }
        
        val errorDetails = buildString {
            appendLine("❌ **API Request Failed**")
            appendLine()
            
            when (result.statusCode) {
                0 -> {
                    appendLine("**Connection Error**")
                    appendLine()
                    appendLine("Could not connect to server. The request timed out or failed to connect.")
                    if (hasImage) {
                        appendLine()
                        appendLine("⚠️ **Image Processing Timeout**: Image requests can take 1-2 minutes")
                        appendLine("Consider trying without an image first.")
                    }
                }
                400 -> {
                    appendLine("**Bad Request (400)**")
                    appendLine()
                    appendLine("The server rejected the request format.")
                }
                404 -> {
                    appendLine("**Not Found (404)**")
                    appendLine()
                    appendLine("The AI endpoint was not found. Server may not be running properly.")
                }
                500 -> {
                    appendLine("**Server Error (500)**")
                    appendLine()
                    appendLine("The server encountered an internal error during processing.")
                    if (hasImage) {
                        appendLine("This often happens with image processing - try with a smaller image.")
                    }
                }
                else -> {
                    appendLine("**HTTP Error ${result.statusCode}**")
                    appendLine()
                    appendLine("Unexpected server response.")
                }
            }
            
            if (result.response.isNotEmpty()) {
                appendLine()
                appendLine("**Server Response:**")
                appendLine("```")
                appendLine(result.response)
                appendLine("```")
            }
            
            if (result.error != null) {
                appendLine()
                appendLine("**Client Error:**")
                appendLine("```")
                appendLine(result.error)
                appendLine("```")
            }
            
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("### 📤 Request Details")
            appendLine("- **Endpoint:** POST ${result.endpoint}")
            appendLine("- **Status Code:** ${result.statusCode}")
            appendLine("- **Response Time:** ${result.responseTime}ms")
            if (hasImage) {
                appendLine("- **Request Type:** Image + Text")
            } else {
                appendLine("- **Request Type:** Text only")
            }
            appendLine()
            appendLine("**Request Body:**")
            appendLine("```json")
            appendLine(formattedRequest)
            appendLine("```")
            appendLine()
            appendLine("**Raw Response:**")
            appendLine("```json")
            appendLine(result.response)
            appendLine("```")
        }
        
        markwon.setMarkdown(textResults, errorDetails)
        
        // Show error toast
        val toastMessage = when (result.statusCode) {
            0 -> if (hasImage) "⏳ Image processing timeout - try without image" else "❌ Connection failed"
            400 -> "❌ Bad request format"
            404 -> "❌ AI service not found"
            500 -> "⚠️ Server error during processing"
            else -> "❌ Request failed (${result.statusCode})"
        }
        
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
    }
    
    private fun showAITestResultInActivity(requestJson: String, result: ApiTestResult) {
        Timber.d("=== Response Parsing Debug ===")
        
        val formattedRequest = try {
            formatJson(requestJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to format request JSON")
            requestJson
        }
        
        var extractedImageData: String? = null
        var rotation = 0
        var responseTime = 0L
        var modelUsed = ""
        
        val formattedResponse = try {
            Timber.d("Attempting to parse response as JSON...")
            val jsonElement = JsonParser.parseString(result.response)
            val responseObject = jsonElement.asJsonObject
            
            Timber.d("Response JSON structure: ${responseObject.keySet()}")
            
            // Extract the actual AI response text if it exists
            val aiResponseText = if (responseObject.has("response")) {
                val responseText = responseObject.get("response").asString
                Timber.d("Found 'response' field: '$responseText'")
                responseText
            } else {
                Timber.d("No 'response' field found, using raw response")
                result.response
            }
            
            // Extract metadata
            if (responseObject.has("metadata")) {
                val metadata = responseObject.getAsJsonObject("metadata")
                Timber.d("Found metadata: ${metadata.keySet()}")
                
                if (metadata.has("inferenceTime")) {
                    responseTime = metadata.get("inferenceTime").asLong
                    Timber.d("Inference time: ${responseTime}ms")
                }
                if (metadata.has("model")) {
                    modelUsed = metadata.get("model").asString
                    Timber.d("Model used: '$modelUsed'")
                }
                if (metadata.has("rotation")) {
                    rotation = metadata.get("rotation").asInt
                    Timber.d("Image rotation: ${rotation}°")
                }
            } else {
                Timber.d("No metadata found in response")
            }
            
            // Extract image data if present
            if (responseObject.has("image")) {
                extractedImageData = responseObject.get("image").asString
                Timber.d("Found image data, length: ${extractedImageData?.length ?: 0}")
            } else {
                Timber.d("No image data found in response")
            }
            
            Timber.d("Final AI response text: '$aiResponseText'")
            
            aiResponseText
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse response JSON, using raw response")
            Timber.d("Raw response that failed to parse: '${result.response}'")
            result.response
        }
        
        // Display captured image if available
        if (extractedImageData != null) {
            displayCapturedImage(extractedImageData, rotation)
        } else {
            imageViewCaptured.visibility = View.GONE
        }
        
        val resultText = buildString {
            appendLine(formattedResponse)
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("### 📊 Response Details")
            appendLine("- **Status:** ${result.statusCode}")
            appendLine("- **URL:** ${result.url}")
            if (modelUsed.isNotEmpty()) {
                appendLine("- **Model:** $modelUsed")
            }
            if (responseTime > 0) {
                appendLine("- **Response Time:** ${responseTime}ms (${String.format("%.2f", responseTime / 1000.0)}s)")
            }
            if (rotation != 0) {
                appendLine("- **Image Rotation:** ${rotation}°")
            }
            appendLine("- **Endpoint:** POST ${result.endpoint}")
            appendLine("- **Content Type:** ${result.contentType}")
            appendLine()
            appendLine("### 📤 Request Details")
            appendLine("```json")
            appendLine(formattedRequest)
            appendLine("```")
            appendLine()
            appendLine("### 📥 Raw API Response")
            appendLine("```json")
            // Use ResponseFormatter for pretty-printed JSON with syntax highlighting
            val formatter = com.k3s.phoneserver.formatting.ResponseFormatter.getInstance()
            appendLine(formatter.formatJsonWithHighlighting(result.response))
            appendLine("```")
        }
        
        // Use Markwon to render the response with proper formatting
        markwon.setMarkdown(textResults, resultText)
        
        // Show success/error toast
        val statusMessage = when {
            result.statusCode in 200..299 -> {
                if (responseTime > 0) {
                    "✅ Success! Response in ${responseTime}ms"
                } else {
                    "✅ AI text generated successfully!"
                }
            }
            result.statusCode == 400 -> "❌ Bad request - check your input"
            result.statusCode == 500 -> "⚠️ Server error during AI generation"
            else -> "❌ Request failed (${result.statusCode})"
        }
        
        Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
    }
    
    private fun displayCapturedImage(base64Image: String, rotationDegrees: Int) {
        try {
            // Remove data URL prefix if present
            val base64Data = if (base64Image.startsWith("data:image/")) {
                base64Image.substring(base64Image.indexOf(",") + 1)
            } else {
                base64Image
            }
            
            // Decode base64 to bitmap
            val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
            val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            if (originalBitmap != null) {
                // Apply rotation if needed
                val displayBitmap = if (rotationDegrees != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    Bitmap.createBitmap(
                        originalBitmap, 
                        0, 0, 
                        originalBitmap.width, 
                        originalBitmap.height, 
                        matrix, 
                        true
                    )
                } else {
                    originalBitmap
                }
                
                imageViewCaptured.setImageBitmap(displayBitmap)
                imageViewCaptured.visibility = View.VISIBLE
                
                Timber.d("Displayed captured image with rotation: ${rotationDegrees}°")
            } else {
                imageViewCaptured.visibility = View.GONE
                Timber.w("Failed to decode captured image")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error displaying captured image")
            imageViewCaptured.visibility = View.GONE
        }
    }
    
    private fun formatJson(json: String): String {
        return try {
            val jsonElement = JsonParser.parseString(json)
            gson.toJson(jsonElement)
        } catch (e: Exception) {
            json
        }
    }
}
