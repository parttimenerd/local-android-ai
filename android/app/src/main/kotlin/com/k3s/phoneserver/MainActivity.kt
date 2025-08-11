package com.k3s.phoneserver

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.k3s.phoneserver.adapter.RequestLogAdapter
import com.k3s.phoneserver.formatting.ResponseFormatter
import com.k3s.phoneserver.logging.RequestLogger
import com.k3s.phoneserver.manager.AppPermissionManager
import com.k3s.phoneserver.server.WebServerService
import com.k3s.phoneserver.testing.ApiTester
import com.k3s.phoneserver.testing.ApiTestResult
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var logAdapter: RequestLogAdapter
    private lateinit var apiTester: ApiTester
    private var isServerRunning = false
    private var permissionsChecked = false
    private var isApiTestingSectionVisible = false
    private var isApiResponseExpanded = false
    private val permissionManager = AppPermissionManager.getInstance()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                             permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        
        permissionsChecked = true
        
        // Update and save permission state
        permissionManager.saveLocationPermissionState(this)
        
        // Show permission status to user
        val messages = mutableListOf<String>()
        if (locationGranted) {
            messages.add("📍 Location permission granted")
        }
        if (cameraGranted) {
            messages.add("📷 Camera permission granted")
        }
        
        val deniedPermissions = mutableListOf<String>()
        if (!permissionManager.hasLocationPermissions(this)) {
            deniedPermissions.add("location")
        }
        if (!permissionManager.hasCameraPermissions(this)) {
            deniedPermissions.add("camera")
        }
        
        if (deniedPermissions.isNotEmpty()) {
            messages.add("⚠️ ${deniedPermissions.joinToString(", ")} permission(s) denied - some features may not work")
        }
        
        if (messages.isNotEmpty()) {
            Toast.makeText(this, messages.joinToString("\n"), Toast.LENGTH_LONG).show()
        }

        // Check for auto-start after permission handling is complete
        checkAutoStartAfterPermissions()
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_main)

        apiTester = ApiTester()
        setupRecyclerView()
        setupApiTesting()
        observeRequestLogs()
        
        // Check if server is already running
        checkServerRunningState()
        
        // Check permissions and handle auto-start after permission flow
        handleStartupPermissionsAndAutoStart()
        
        // Enhanced fallback: Always try to ensure server is running if not explicitly stopped
        findViewById<LinearLayout>(R.id.apiTestingSection).postDelayed({
            if (!isServerRunning) {
                Timber.i("Server not running - attempting to start for persistent operation")
                if (permissionManager.hasRequiredPermissions(this)) {
                    Toast.makeText(this, "Ensuring server is running persistently...", Toast.LENGTH_SHORT).show()
                    startWebServer()
                } else {
                    Timber.w("Missing permissions for persistent server operation")
                    Toast.makeText(this, "Grant permissions for persistent server operation", Toast.LENGTH_LONG).show()
                }
            }
        }, 1000) // Wait 1 second
        
        findViewById<android.widget.Button>(R.id.buttonStartServer).setOnClickListener {
            if (isServerRunning) {
                stopWebServer()
            } else {
                if (permissionManager.hasRequiredPermissions(this)) {
                    startWebServer()
                } else {
                    checkAndRequestPermissions()
                }
            }
        }
        
        findViewById<android.widget.Button>(R.id.buttonClearLogs).setOnClickListener {
            RequestLogger.clearLogs()
        }
        
        // Add prototype footer link
        findViewById<TextView>(R.id.prototypeFooter).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/parttimenerd/k3s-on-phone"))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open link: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Request battery optimization exemption for background operation
        requestBatteryOptimizationExemption()
        
        updateUI()
    }

    private fun checkServerRunningState() {
        // Check if the WebServerService is already running
        val isServiceRunning = isServiceRunning(WebServerService::class.java)
        if (isServiceRunning != isServerRunning) {
            Timber.d("Correcting server running state: service=$isServiceRunning, local=$isServerRunning")
            isServerRunning = isServiceRunning
            updateUI()
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun handleStartupPermissionsAndAutoStart() {
        Timber.d("Starting permission and auto-start flow")
        
        // Check current permission states
        checkPermissionStates()
        permissionManager.saveLocationPermissionState(this)
        
        val hasCameraPermissions = permissionManager.hasCameraPermissions(this)
        val hasRequiredPermissions = permissionManager.hasRequiredPermissions(this)
        val isAutoStartEnabled = permissionManager.isAutoStartEnabled(this)
        
        Timber.d("Startup state - Camera: $hasCameraPermissions, Required: $hasRequiredPermissions, AutoStart: $isAutoStartEnabled, ServerRunning: $isServerRunning")
        
        // Debug: Force check individual permissions
        val hasInternet = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PermissionChecker.PERMISSION_GRANTED
        val hasWakeLock = ContextCompat.checkSelfPermission(this, Manifest.permission.WAKE_LOCK) == PermissionChecker.PERMISSION_GRANTED
        Timber.d("Individual permissions - Internet: $hasInternet, WakeLock: $hasWakeLock")
        
        // If we have required permissions and auto-start is enabled, start immediately
        if (hasRequiredPermissions && isAutoStartEnabled && !isServerRunning) {
            Timber.d("Starting server immediately - all conditions met")
            Toast.makeText(this, "Auto-starting server...", Toast.LENGTH_SHORT).show()
            autoStartServer()
        } else {
            Timber.d("Auto-start conditions not met: required=$hasRequiredPermissions, enabled=$isAutoStartEnabled, running=$isServerRunning")
            // Show debug toast to help understand what's wrong
            val reason = when {
                !hasRequiredPermissions -> "Missing required permissions"
                !isAutoStartEnabled -> "Auto-start is disabled"
                isServerRunning -> "Server already running"
                else -> "Unknown reason"
            }
            Toast.makeText(this, "Auto-start not triggered: $reason", Toast.LENGTH_LONG).show()
        }
        
        // Try to get camera permissions for better UX (if not already granted)
        if (!hasCameraPermissions) {
            Timber.d("Requesting camera permissions")
            requestCameraPermissions()
        }
        
        // If we don't have core permissions yet, request them
        if (!hasRequiredPermissions && !permissionsChecked) {
            Timber.d("Requesting required permissions")
            checkAndRequestPermissions()
        }
    }

    private fun checkPermissionsAndAutoStart() {
        checkPermissionStates()
        
        // Update and save permission state
        permissionManager.saveLocationPermissionState(this)
        
        if (permissionManager.hasRequiredPermissions(this)) {
            autoStartServer()
        } else if (!permissionsChecked) {
            checkAndRequestPermissions()
        }
    }

    private fun checkPermissionStates() {
        permissionsChecked = true
        // Permission state is now handled by AppPermissionManager
        permissionManager.updateAndSaveLocationPermissionState(this)
    }

    private fun autoStartServer() {
        // Always attempt auto-start if enabled, regardless of optional permissions
        if (!isServerRunning && permissionManager.isAutoStartEnabled(this)) {
            val hasCore = permissionManager.hasRequiredPermissions(this)
            val hasCamera = permissionManager.hasCameraPermissions(this)
            val hasLocation = permissionManager.hasLocationPermissions(this)
            
            Timber.d("Auto-start check - Core: $hasCore, Camera: $hasCamera, Location: $hasLocation")
            
            if (hasCore) {
                // Start server with core permissions - camera and location are optional
                Timber.d("Auto-starting web server with available permissions")
                val intent = Intent(this, WebServerService::class.java)
                ContextCompat.startForegroundService(this, intent)
                isServerRunning = true
                updateUI()
                
                // Log auto-start event with permission status
                RequestLogger.logRequest(
                    method = "AUTO",
                    path = "/server/auto-start",
                    clientIp = "localhost",
                    statusCode = 200,
                    responseTime = 0L,
                    userAgent = "K3s Phone Server Auto-Start",
                    responseData = """{"status": "auto-started", "timestamp": ${System.currentTimeMillis()}, "port": 8005, "permissions": {"camera": $hasCamera, "location": $hasLocation}}""",
                    responseType = "json"
                )
                
                val optionalFeatures = mutableListOf<String>()
                if (!hasCamera) optionalFeatures.add("camera")
                if (!hasLocation) optionalFeatures.add("location")
                
                val message = if (optionalFeatures.isEmpty()) {
                    "K3s Phone Server auto-started with all features"
                } else {
                    "K3s Phone Server auto-started (${optionalFeatures.joinToString(", ")} features disabled)"
                }
                
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                Timber.d("Cannot auto-start: missing core permissions")
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Background Operation")
                    .setMessage("To keep the K3s server running reliably in the background, please allow this app to ignore battery optimizations.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to open battery optimization settings")
                        }
                    }
                    .setNegativeButton("Skip", null)
                    .show()
            }
        }
    }

    // Remove these duplicate methods - now handled by AppPermissionManager
    // private fun hasRequiredPermissions() - replaced by permissionManager.hasRequiredPermissions(this)
    // private fun hasLocationPermissions() - replaced by permissionManager.hasLocationPermissions(this)

    private fun setupRecyclerView() {
        logAdapter = RequestLogAdapter()
        val recyclerView = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewLogs)
        recyclerView.apply {
            adapter = logAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun observeRequestLogs() {
        RequestLogger.requestLogs.observe(this) { logs ->
            logAdapter.submitList(logs)
            // Auto-scroll to top for new logs
            if (logs.isNotEmpty()) {
                findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewLogs).scrollToPosition(0)
            }
        }
    }

    private fun checkServerStatus() {
        // Check if service is already running
        val wasRunning = isServerRunning
        isServerRunning = WebServerService.isRunning
        
        if (isServerRunning && !wasRunning) {
            Timber.d("Server detected as running - app returning to foreground")
            Toast.makeText(this, "K3s Server is running in background", Toast.LENGTH_SHORT).show()
        }
        
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        Timber.d("MainActivity onResume - checking server status")
        checkServerStatus()
        // Refresh UI to reflect current server state
        updateUI()
        // Refresh request logs in case server processed requests while app was backgrounded
        observeRequestLogs()
    }

    override fun onPause() {
        super.onPause()
        Timber.d("MainActivity onPause - server continues running in background")
        // Note: We intentionally do NOT stop the server when the app is paused/backgrounded
        // The foreground service keeps the server running
    }

    override fun onStop() {
        super.onStop()
        Timber.d("MainActivity onStop - server remains active via foreground service")
        // Server continues running in background via WebServerService
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("MainActivity onDestroy - server persistence depends on user preference")
        // Note: We do NOT automatically stop the server when the activity is destroyed
        // The user must explicitly stop the server, or it runs until device reboot
        // This ensures the server survives app kills, task switching, etc.
    }

    private fun checkAndRequestPermissions() {
        val missingCorePermissions = permissionManager.getMissingCorePermissions(this)
        val missingLocationPermissions = permissionManager.getMissingLocationPermissions(this)
        val missingCameraPermissions = permissionManager.getMissingCameraPermissions(this)
        
        // Always request location and camera permissions but don't block server start
        val allMissingPermissions = missingCorePermissions + missingLocationPermissions + missingCameraPermissions
        
        if (allMissingPermissions.isEmpty()) {
            // Update and save permission state
            permissionManager.updateAndSaveLocationPermissionState(this)
            autoStartServer()
        } else {
            permissionLauncher.launch(allMissingPermissions.toTypedArray())
        }
    }
    
    private fun requestCameraPermissions() {
        // Request camera permissions early for better UX
        if (!permissionManager.hasCameraPermissions(this)) {
            val cameraPermissions = permissionManager.getMissingCameraPermissions(this)
            if (cameraPermissions.isNotEmpty()) {
                // Show explanation dialog first
                showCameraPermissionDialog {
                    Timber.d("Requesting camera permissions: ${cameraPermissions.joinToString(", ")}")
                    permissionLauncher.launch(cameraPermissions.toTypedArray())
                }
            }
        }
    }
    
    private fun showCameraPermissionDialog(onProceed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission")
            .setMessage("K3s Phone Server can provide camera capture functionality through its API. This allows remote applications to take photos through your device.\n\nCamera access is optional - the server will work without it, but camera endpoints will be disabled.")
            .setPositiveButton("Grant Permission") { _, _ ->
                onProceed()
            }
            .setNegativeButton("Skip") { _, _ ->
                Toast.makeText(this, "Camera features will be disabled", Toast.LENGTH_SHORT).show()
                // Camera permission flow completed
            }
            .setCancelable(false)
            .show()
    }

    private fun checkAutoStartAfterPermissions() {
        val isAutoStartEnabled = permissionManager.isAutoStartEnabled(this)
        val hasRequiredPermissions = permissionManager.hasRequiredPermissions(this)
        
        Timber.d("Auto-start check: enabled=$isAutoStartEnabled, hasRequired=$hasRequiredPermissions, isRunning=$isServerRunning")
        
        // Only auto-start if enabled and we have required permissions
        if (isAutoStartEnabled && hasRequiredPermissions && !isServerRunning) {
            Timber.d("Triggering auto-start")
            autoStartServer()
        } else {
            Timber.d("Auto-start conditions not met")
        }
    }

    private fun startWebServer() {
        Timber.d("Starting web server")
        val intent = Intent(this, WebServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isServerRunning = true
        updateUI()
        
        // Log server start event
        RequestLogger.logRequest(
            method = "SYSTEM",
            path = "/server/start",
            clientIp = "localhost",
            statusCode = 200,
            responseTime = 0L,
            userAgent = "K3s Phone Server",
            responseData = """{"status": "started", "timestamp": ${System.currentTimeMillis()}, "port": 8005}""",
            responseType = "json"
        )
        
        Toast.makeText(this, "K3s Phone Server started on port 8005", Toast.LENGTH_SHORT).show()
    }

    private fun stopWebServer() {
        Timber.d("Stopping web server")
        val intent = Intent(this, WebServerService::class.java)
        stopService(intent)
        isServerRunning = false
        updateUI()
        
        // Log server stop event
        RequestLogger.logRequest(
            method = "SYSTEM",
            path = "/server/stop",
            clientIp = "localhost",
            statusCode = 200,
            responseTime = 0L,
            userAgent = "K3s Phone Server",
            responseData = """{"status": "stopped", "timestamp": ${System.currentTimeMillis()}}""",
            responseType = "json"
        )
        
        Toast.makeText(this, "K3s Phone Server stopped", Toast.LENGTH_SHORT).show()
    }

    // Removed saveAutoStartPreference method - now handled by AppPermissionManager.setAutoStartEnabled()

    private fun updateUI() {
        runOnUiThread {
            val statusText = findViewById<TextView>(R.id.textStatus)
            val actionButton = findViewById<Button>(R.id.buttonStartServer)
            
            if (isServerRunning) {
                statusText.text = "K3s Phone Server is running on port 8005"
                actionButton.text = "Stop Server"
            } else {
                statusText.text = "K3s Phone Server is stopped"
                actionButton.text = "Start Server"
            }
        }
    }

    private fun setupApiTesting() {
        val toggleButton = findViewById<Button>(R.id.buttonToggleApiTesting)
        val apiTestingSection = findViewById<LinearLayout>(R.id.apiTestingSection)
        
        toggleButton.setOnClickListener {
            isApiTestingSectionVisible = !isApiTestingSectionVisible
            if (isApiTestingSectionVisible) {
                apiTestingSection.visibility = View.VISIBLE
                toggleButton.text = "▲ Hide"
            } else {
                apiTestingSection.visibility = View.GONE
                toggleButton.text = "▼ Show"
            }
        }
        
        // Set up API testing buttons
        findViewById<Button>(R.id.buttonTestStatus).setOnClickListener {
            testApiEndpoint("/status")
        }
        
        findViewById<Button>(R.id.buttonTestHealth).setOnClickListener {
            testApiEndpoint("/health")
        }
        
        findViewById<Button>(R.id.buttonTestCapabilities).setOnClickListener {
            testApiEndpoint("/capabilities")
        }
        
        findViewById<Button>(R.id.buttonTestLocation).setOnClickListener {
            testApiEndpoint("/location")
        }
        
        findViewById<Button>(R.id.buttonTestOrientation).setOnClickListener {
            testApiEndpoint("/orientation")
        }
        
        findViewById<Button>(R.id.buttonTestCaptureRear).setOnClickListener {
            testApiEndpoint("/capture", mapOf("side" to "rear"))
        }
        
        findViewById<Button>(R.id.buttonTestCaptureFront).setOnClickListener {
            testApiEndpoint("/capture", mapOf("side" to "front"))
        }
        
        // Copy API response button
        findViewById<Button>(R.id.buttonCopyApiResponse).setOnClickListener {
            copyApiResponseToClipboard()
        }
        
        // Clear API response button
        findViewById<Button>(R.id.buttonClearApiResponse).setOnClickListener {
            clearApiResponse()
        }
        
        // Expand/collapse API response area
        findViewById<Button>(R.id.buttonExpandResponse).setOnClickListener {
            toggleApiResponseSize()
        }
    }
    
    private fun testApiEndpoint(endpoint: String, parameters: Map<String, String> = emptyMap()) {
        if (!isServerRunning) {
            showApiResponse("⚠️ Server is not running. Please start the server first.")
            return
        }
        
        lifecycleScope.launch {
            try {
                showApiResponse("Testing $endpoint... ⏳")
                val result = apiTester.testEndpoint(endpoint, parameters)
                showApiResult(result)
            } catch (e: Exception) {
                Timber.e(e, "Error testing API endpoint $endpoint")
                showApiResponse("❌ Error testing $endpoint: ${e.message}")
            }
        }
    }
    
    private fun showApiResponse(response: String) {
        runOnUiThread {
            val responseTextView = findViewById<TextView>(R.id.textApiResponse)
            responseTextView.text = response
        }
    }
    
    private fun showApiResult(result: ApiTestResult) {
        runOnUiThread {
            val headerTextView = findViewById<TextView>(R.id.textApiResponseHeader)
            val responseTextView = findViewById<TextView>(R.id.textApiResponse)
            val imageView = findViewById<ImageView>(R.id.imageApiResponse)
            
            // Show header with metadata (fewer emojis)
            val status = if (result.success) "OK" else "ERROR"
            val statusText = if (result.error != null) {
                "ERROR: ${result.error}"
            } else {
                "HTTP ${result.statusCode}"
            }
            
            val headerText = buildString {
                appendLine("${result.endpoint} - $status - $statusText (${result.responseTime}ms) [${result.timestamp}]")
                appendLine("URL: ${result.url}")
                if (result.contentType.isNotBlank()) {
                    appendLine("Content-Type: ${result.contentType}")
                }
            }
            
            headerTextView.text = headerText
            headerTextView.visibility = View.VISIBLE
            
            // Check if response contains an image
            val formatter = ResponseFormatter.getInstance()
            val extractedImage = if (result.response.contains("data:image/")) {
                formatter.extractBase64Image(result.response)
            } else {
                null
            }
            
            if (extractedImage != null) {
                // Display the image
                imageView.setImageBitmap(extractedImage)
                imageView.visibility = View.VISIBLE
                
                // Format the response without the base64 data for text display
                val formattedResponse = formatter.formatApiResponse(result, this@MainActivity)
                responseTextView.text = formattedResponse
            } else {
                // Hide image view if no image
                imageView.visibility = View.GONE
                
                // Use ResponseFormatter to format the response
                val formattedResponse = formatter.formatApiResponse(result, this@MainActivity)
                responseTextView.text = formattedResponse
            }
            
            // If the response area is currently expanded, update its size to fit the new content
            if (isApiResponseExpanded) {
                updateExpandedResponseSize()
            }
        }
    }
    
    private fun copyApiResponseToClipboard() {
        val responseTextView = findViewById<TextView>(R.id.textApiResponse)
        val responseText = responseTextView.text.toString()
        
        if (responseText.isNotBlank() && responseText != "Click an endpoint button to test the API") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("API Response", responseText)
            clipboard.setPrimaryClip(clip)
            
            Toast.makeText(this, "Response copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No response to copy", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun clearApiResponse() {
        runOnUiThread {
            val headerTextView = findViewById<TextView>(R.id.textApiResponseHeader)
            val responseTextView = findViewById<TextView>(R.id.textApiResponse)
            val imageView = findViewById<ImageView>(R.id.imageApiResponse)
            
            headerTextView.visibility = View.GONE
            responseTextView.text = "Click an endpoint button to test the API"
            imageView.visibility = View.GONE
            imageView.setImageDrawable(null)
        }
    }
    
    private fun toggleApiResponseSize() {
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollViewApiResponse)
        val expandButton = findViewById<Button>(R.id.buttonExpandResponse)
        val responseTextView = findViewById<TextView>(R.id.textApiResponse)
        val headerTextView = findViewById<TextView>(R.id.textApiResponseHeader)
        val imageView = findViewById<ImageView>(R.id.imageApiResponse)
        
        val layoutParams = scrollView.layoutParams
        val dp250 = (250 * resources.displayMetrics.density).toInt()
        
        if (isApiResponseExpanded) {
            // Collapse to normal size
            layoutParams.height = dp250
            expandButton.text = "📏"
            expandButton.contentDescription = "Expand response to fit content"
            isApiResponseExpanded = false
        } else {
            // Calculate the actual content height needed
            val contentHeight = calculateRequiredContentHeight(headerTextView, responseTextView, imageView)
            val maxHeight = (resources.displayMetrics.heightPixels * 0.7).toInt() // Max 70% of screen
            val finalHeight = minOf(contentHeight, maxHeight)
            
            // Expand to content size (minimum 500dp)
            val dp500 = (500 * resources.displayMetrics.density).toInt()
            layoutParams.height = maxOf(finalHeight, dp500)
            expandButton.text = "📐"
            expandButton.contentDescription = "Collapse response area"
            isApiResponseExpanded = true
        }
        
        scrollView.layoutParams = layoutParams
        scrollView.requestLayout()
    }
    
    private fun calculateRequiredContentHeight(headerTextView: TextView, responseTextView: TextView, imageView: ImageView): Int {
        var totalHeight = 0
        val padding = (16 * resources.displayMetrics.density).toInt() // Account for padding
        val containerWidth = findViewById<android.widget.ScrollView>(R.id.scrollViewApiResponse).width
        val measureWidth = if (containerWidth > 0) containerWidth else resources.displayMetrics.widthPixels - (32 * resources.displayMetrics.density).toInt()
        
        // Measure header text height
        if (headerTextView.visibility == View.VISIBLE && headerTextView.text.isNotEmpty()) {
            headerTextView.measure(
                View.MeasureSpec.makeMeasureSpec(measureWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            totalHeight += headerTextView.measuredHeight + padding
        }
        
        // Measure response text height
        if (responseTextView.text.isNotEmpty()) {
            responseTextView.measure(
                View.MeasureSpec.makeMeasureSpec(measureWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            totalHeight += responseTextView.measuredHeight + padding
        }
        
        // Measure image height
        if (imageView.visibility == View.VISIBLE && imageView.drawable != null) {
            // Calculate image height maintaining aspect ratio
            val drawable = imageView.drawable
            val intrinsicWidth = drawable.intrinsicWidth
            val intrinsicHeight = drawable.intrinsicHeight
            
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                val aspectRatio = intrinsicHeight.toFloat() / intrinsicWidth.toFloat()
                val imageHeight = (measureWidth * aspectRatio).toInt()
                totalHeight += imageHeight + padding
            }
        }
        
        return totalHeight + (padding * 2) // Extra padding for safety
    }
    
    private fun updateExpandedResponseSize() {
        if (!isApiResponseExpanded) return
        
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollViewApiResponse)
        val responseTextView = findViewById<TextView>(R.id.textApiResponse)
        val headerTextView = findViewById<TextView>(R.id.textApiResponseHeader)
        val imageView = findViewById<ImageView>(R.id.imageApiResponse)
        
        // Wait for layout to complete before measuring
        scrollView.post {
            val contentHeight = calculateRequiredContentHeight(headerTextView, responseTextView, imageView)
            val maxHeight = (resources.displayMetrics.heightPixels * 0.7).toInt() // Max 70% of screen
            val finalHeight = minOf(contentHeight, maxHeight)
            
            // Minimum size is 500dp
            val dp500 = (500 * resources.displayMetrics.density).toInt()
            val newHeight = maxOf(finalHeight, dp500)
            
            val layoutParams = scrollView.layoutParams
            if (layoutParams.height != newHeight) {
                layoutParams.height = newHeight
                scrollView.layoutParams = layoutParams
                scrollView.requestLayout()
            }
        }
    }
}
