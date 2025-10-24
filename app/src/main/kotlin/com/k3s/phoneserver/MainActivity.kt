package com.k3s.phoneserver

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.k3s.phoneserver.ai.AIService
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var logAdapter: RequestLogAdapter
    private lateinit var apiTester: ApiTester
    private lateinit var aiService: AIService
    private var isServerRunning = false
    private var permissionsChecked = false
    private var isApiTestingSectionVisible = false
    private var isApiResponseExpanded = false
    private val permissionManager = AppPermissionManager.getInstance()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResults(permissions)
    }
    
    // Background location permission launcher (Android 10+)
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "✅ Background location access granted - location works in background", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "⚠️ Background location denied - location may not work when app is hidden", Toast.LENGTH_LONG).show()
        }
        permissionManager.saveLocationPermissionState(this)
    }
    
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        Timber.d("Permission result received: $permissions")
        
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
            
            // For Android 10+, check if we need background location permission
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasBackgroundLocation) {
                    // Request background location permission separately after a short delay
                    scheduleBackgroundLocationRequest()
                }
            }
        }
        if (cameraGranted) {
            messages.add("📷 Camera permission granted")
        }
        
        val deniedPermissions = mutableListOf<String>()
        if (!permissionManager.hasBasicLocationPermissions(this)) {
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
        
        // Log detailed permission status for debugging
        logPermissionStatus()
        
        // Auto-start server regardless of optional permissions
        autoStartServer()
    }
    
    private fun logPermissionStatus() {
        val status = permissionManager.getPermissionStatusSummary(this)
        Timber.d("Current permission status: $status")
        
        // Also check individual permissions manually
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        
        Timber.d("Manual permission check - Fine location: $fineLocation, Coarse location: $coarseLocation, Camera: $camera")
    }

    // Gallery image picker for AI testing
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            Toast.makeText(this, "Image selected for AI analysis", Toast.LENGTH_SHORT).show()
        }
    }
    
    private var selectedImageUri: Uri? = null
    
    // Timer for updating ongoing request durations
    private var durationUpdateHandler: android.os.Handler? = null
    private var durationUpdateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_main)

        apiTester = ApiTester()
        aiService = AIService(this)
        setupRecyclerView()
        setupApiTesting()
        observeRequestLogs()
        
        // Initialize AI model system (migrate references and scan for local models)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (migratedCount, scannedCount) = com.k3s.phoneserver.ai.ModelDetector.initializeModelSystem(this@MainActivity)
                if (migratedCount > 0 || scannedCount > 0) {
                    Timber.i("Model system initialized: $migratedCount migrated, $scannedCount new references created")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize AI model system")
            }
        }
        
        // Check if server is already running
        checkServerRunningState()
        
        // Check permissions and handle auto-start after permission flow
        handleStartupPermissionsAndAutoStart()
        
        // Enhanced fallback: Always try to ensure server is running if not explicitly stopped
        findViewById<LinearLayout>(R.id.apiTestingSection).postDelayed({
            if (!isServerRunning) {
                Timber.i("Server not running - attempting to start for persistent operation")
                if (permissionManager.hasRequiredPermissions(this)) {
                    Toast.makeText(this, "Ensuring server :8005 is running persistently...", Toast.LENGTH_SHORT).show()
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
        
        // AI Button handlers
        setupAIButtons()
        
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
            Toast.makeText(this, "Auto-starting server :8005...", Toast.LENGTH_SHORT).show()
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
        
        // Schedule automatic location access 10 seconds after startup to trigger permission request
        scheduleLocationPermissionRequest()
    }
    
    private fun scheduleLocationPermissionRequest() {
        // Wait 10 seconds after startup to attempt location access
        // This helps ensure location permissions are granted early
        findViewById<LinearLayout>(R.id.apiTestingSection).postDelayed({
            if (!permissionManager.hasLocationPermissions(this)) {
                Timber.d("Attempting location access 10 seconds after startup to trigger permission request")
                lifecycleScope.launch {
                    try {
                        val locationService = com.k3s.phoneserver.services.LocationService(this@MainActivity)
                        val location = locationService.getCurrentLocation()
                        if (location != null) {
                            Timber.i("Successfully obtained location: ${location.latitude}, ${location.longitude}")
                            Toast.makeText(this@MainActivity, "📍 Location permissions working - GPS ready", Toast.LENGTH_SHORT).show()
                        } else {
                            Timber.w("Location access returned null - permissions may not be granted")
                            // This will naturally trigger the permission request flow if needed
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to access location 10 seconds after startup - this may trigger permission dialogs")
                        // This is expected behavior if permissions aren't granted yet
                    }
                }
            } else {
                Timber.d("Location permissions already granted - skipping delayed location access")
            }
        }, 10000) // 10 seconds delay
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
                    userAgent = "AI Phone Server Auto-Start",
                    responseData = """{"status": "auto-started", "timestamp": ${System.currentTimeMillis()}, "port": 8005, "permissions": {"camera": $hasCamera, "location": $hasLocation}}""",
                    responseType = "json"
                )
                
                val optionalFeatures = mutableListOf<String>()
                if (!hasCamera) optionalFeatures.add("camera")
                if (!hasLocation) optionalFeatures.add("location")
                
                val message = if (optionalFeatures.isEmpty()) {
                    "AI Phone Server auto-started with all features"
                } else {
                    "AI Phone Server auto-started (${optionalFeatures.joinToString(", ")} features disabled)"
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
        
        // Start the duration update timer
        startDurationUpdateTimer()
    }
    
    private fun startDurationUpdateTimer() {
        // Stop any existing timer
        stopDurationUpdateTimer()
        
        // Timer to refresh ongoing request durations
        durationUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        durationUpdateRunnable = object : Runnable {
            override fun run() {
                RequestLogger.refreshOngoingDurations()
                android.util.Log.d("MainActivity", "Duration update timer tick")
                durationUpdateHandler?.postDelayed(this, 1000) // Update every 1 second for better visibility
            }
        }
        durationUpdateHandler?.post(durationUpdateRunnable!!)
        android.util.Log.d("MainActivity", "Started duration update timer")
    }
    
    private fun stopDurationUpdateTimer() {
        durationUpdateRunnable?.let { runnable ->
            durationUpdateHandler?.removeCallbacks(runnable)
        }
        android.util.Log.d("MainActivity", "Stopped duration update timer")
    }

    private fun checkServerStatus() {
        // Check if service is already running
        val wasRunning = isServerRunning
        isServerRunning = WebServerService.isRunning
        
        if (isServerRunning && !wasRunning) {
            Timber.d("Server detected as running - app returning to foreground")
            Toast.makeText(this, "K3s Server :8005 is running in background", Toast.LENGTH_SHORT).show()
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
        // Update AI models button to reflect current model availability
        updateAIModelsButtonText()
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
        
        // Stop the duration update timer
        stopDurationUpdateTimer()
        durationUpdateHandler = null
        durationUpdateRunnable = null
        
        Timber.d("MainActivity onDestroy - server persistence depends on user preference")
        // Note: We do NOT automatically stop the server when the activity is destroyed
        // The user must explicitly stop the server, or it runs until device reboot
        // This ensures the server survives app kills, task switching, etc.
    }

    private fun checkAndRequestPermissions() {
        val missingCorePermissions = permissionManager.getMissingCorePermissions(this)
        val missingBasicLocationPermissions = permissionManager.getBasicLocationPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        val missingCameraPermissions = permissionManager.getMissingCameraPermissions(this)
        
        // Log current permission status for debugging
        Timber.d("Permission check - Missing core: $missingCorePermissions, Basic Location: $missingBasicLocationPermissions, Camera: $missingCameraPermissions")
        
        // Request basic permissions (don't include background location in initial request)
        val allMissingPermissions = missingCorePermissions + missingBasicLocationPermissions + missingCameraPermissions
        
        if (allMissingPermissions.isEmpty()) {
            // Update and save permission state
            permissionManager.updateAndSaveLocationPermissionState(this)
            autoStartServer()
        } else {
            // Check if we need to show rationale for location permissions
            if (missingBasicLocationPermissions.isNotEmpty()) {
                showLocationPermissionRationale {
                    requestPermissionsWithRationale(allMissingPermissions)
                }
            } else {
                requestPermissionsWithRationale(allMissingPermissions)
            }
        }
    }
    
    private fun showLocationPermissionRationale(onProceed: () -> Unit) {
        val missingBasicLocationPermissions = permissionManager.getBasicLocationPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        // Check if we should show rationale for location permissions
        val shouldShowRationale = missingBasicLocationPermissions.any { permission ->
            shouldShowRequestPermissionRationale(permission)
        }
        
        if (shouldShowRationale || !permissionsChecked) {
            AlertDialog.Builder(this)
                .setTitle("Location Permission")
                .setMessage("AI Phone Server can provide device location information through its API. This enables location-based features for connected applications.\n\nFor background operation when the app is not visible, additional background location access will be requested separately.\n\nLocation access is optional - the server will work without it, but location endpoints will be disabled.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    onProceed()
                }
                .setNegativeButton("Skip") { _, _ ->
                    Toast.makeText(this, "Location features will be disabled", Toast.LENGTH_SHORT).show()
                    // Skip location and proceed with other permissions
                    val missingCorePermissions = permissionManager.getMissingCorePermissions(this)
                    val missingCameraPermissions = permissionManager.getMissingCameraPermissions(this)
                    val remainingPermissions = missingCorePermissions + missingCameraPermissions
                    
                    if (remainingPermissions.isNotEmpty()) {
                        permissionLauncher.launch(remainingPermissions.toTypedArray())
                    } else {
                        autoStartServer()
                    }
                }
                .setCancelable(false)
                .show()
        } else {
            // Permission was permanently denied, show settings dialog
            showPermissionDeniedDialog()
        }
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage("Location permission was permanently denied. To enable location features, please grant the permission in app settings.\n\nWould you like to open app settings?")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Continue Without Location") { _, _ ->
                Toast.makeText(this, "Location features will be disabled", Toast.LENGTH_SHORT).show()
                // Continue with remaining permissions
                val missingCorePermissions = permissionManager.getMissingCorePermissions(this)
                val missingCameraPermissions = permissionManager.getMissingCameraPermissions(this)
                val remainingPermissions = missingCorePermissions + missingCameraPermissions
                
                if (remainingPermissions.isNotEmpty()) {
                    permissionLauncher.launch(remainingPermissions.toTypedArray())
                } else {
                    autoStartServer()
                }
            }
            .setCancelable(false)
            .show()
    }
    
    private fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open app settings")
            Toast.makeText(this, "Could not open app settings", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun requestPermissionsWithRationale(permissions: List<String>) {
        Timber.d("Requesting permissions: ${permissions.joinToString(", ")}")
        permissionLauncher.launch(permissions.toTypedArray())
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
            .setMessage("AI Phone Server can provide camera capture functionality through its API. This allows remote applications to take photos through your device.\n\nCamera access is optional - the server will work without it, but camera endpoints will be disabled.")
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

    private fun scheduleBackgroundLocationRequest() {
        // Request background location permission after a short delay to avoid overwhelming the user
        findViewById<LinearLayout>(R.id.apiTestingSection).postDelayed({
            requestBackgroundLocationPermission()
        }, 3000) // 3 seconds delay
    }

    private fun requestBackgroundLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background Location Access")
                .setMessage("To provide location data when the app is not visible, background location access is required.\n\nThis allows the AI Phone Server to work properly even when the app is in the background.\n\nIn the next dialog, please select 'Allow all the time' for full functionality.")
                .setPositiveButton("Continue") { _, _ ->
                    try {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to request background location permission")
                    }
                }
                .setNegativeButton("Skip") { _, _ ->
                    Toast.makeText(this, "⚠️ Location may not work when app is in background", Toast.LENGTH_LONG).show()
                }
                .show()
        }
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
            userAgent = "AI Phone Server",
            responseData = """{"status": "started", "timestamp": ${System.currentTimeMillis()}, "port": 8005}""",
            responseType = "json"
        )
        
        Toast.makeText(this, "AI Phone Server started on port 8005", Toast.LENGTH_SHORT).show()
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
            userAgent = "AI Phone Server",
            responseData = """{"status": "stopped", "timestamp": ${System.currentTimeMillis()}}""",
            responseType = "json"
        )
        
        Toast.makeText(this, "AI Phone Server stopped", Toast.LENGTH_SHORT).show()
    }

    // Removed saveAutoStartPreference method - now handled by AppPermissionManager.setAutoStartEnabled()

    private fun updateUI() {
        runOnUiThread {
            val statusText = findViewById<TextView>(R.id.textStatus)
            val actionButton = findViewById<Button>(R.id.buttonStartServer)
            
            if (isServerRunning) {
                statusText.text = "AI Phone Server is running"
                actionButton.text = "Stop Server :8005"
            } else {
                statusText.text = "AI Phone Server is stopped"
                actionButton.text = "Start Server :8005"
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
            // Check location permissions first
            if (!permissionManager.hasLocationPermissions(this)) {
                // Log current permission status for debugging
                logPermissionStatus()
                
                AlertDialog.Builder(this)
                    .setTitle("Location Permission Required")
                    .setMessage("Location permission is required to test the location endpoint. Would you like to grant permission?")
                    .setPositiveButton("Grant Permission") { _, _ ->
                        // Force permission request
                        val missingLocationPermissions = permissionManager.getMissingLocationPermissions(this)
                        Timber.d("Manually requesting location permissions: $missingLocationPermissions")
                        permissionLauncher.launch(missingLocationPermissions.toTypedArray())
                    }
                    .setNegativeButton("Test Anyway") { _, _ ->
                        // Test without permission to see the error
                        testApiEndpoint("/location")
                    }
                    .setNeutralButton("Open Settings") { _, _ ->
                        openAppSettings()
                    }
                    .show()
            } else {
                testApiEndpoint("/location")
            }
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
            showApiResponse("Server :8005 is not running. Please start the server first.")
            return
        }
        
        lifecycleScope.launch {
            try {
                showApiResponse("Testing $endpoint... ⏳")
                val result = apiTester.testEndpoint(endpoint, parameters)
                
                showApiResult(result)
            } catch (e: Exception) {
                Timber.e(e, "Error testing API endpoint $endpoint")
                showApiResponse("Error testing $endpoint: ${e.message}")
            }
        }
    }
    
    private fun testObjectDetectionEndpoint(side: String) {
        if (!isServerRunning) {
            showApiResponse("⚠️ Server :8005 is not running. Please start the server first.")
            return
        }

        lifecycleScope.launch {
            try {
                showApiResponse("Testing object detection ($side camera)... ⏳")
                
                // Build the JSON request body for object detection
                val requestBody = """
                {
                    "side": "$side",
                    "threshold": 0.2,
                    "maxResults": 10,
                    "returnImage": true
                }
                """.trimIndent()
                
                val result = apiTester.testPostEndpoint("/ai/object_detection", requestBody)
                
                // Log the demo request to request log
                RequestLogger.logRequest(
                    method = "POST",
                    path = "/ai/object_detection",
                    clientIp = "127.0.0.1",
                    statusCode = result.statusCode,
                    responseTime = result.responseTime,
                    userAgent = "Demo API Test (Object Detection)",
                    responseData = if (result.response.length > 1000) {
                        // Truncate long responses for logging
                        result.response.take(1000) + "... [truncated]"
                    } else {
                        result.response
                    },
                    responseType = if (result.contentType.contains("json", ignoreCase = true)) "object_detection" else "text"
                )
                
                showApiResult(result)
            } catch (e: Exception) {
                Timber.e(e, "Error testing object detection endpoint")
                showApiResponse("❌ Error testing object detection ($side): ${e.message}")
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
                if (result.contentType.contains("application/json", ignoreCase = true)) {
                    val spannableResponse = formatter.formatJsonAsSpannable(result.response)
                    responseTextView.text = spannableResponse
                } else {
                    val formattedResponse = formatter.formatApiResponse(result, this@MainActivity)
                    responseTextView.text = formattedResponse
                }
            } else {
                // Hide image view if no image
                imageView.visibility = View.GONE
                
                // Use syntax highlighting for JSON responses
                if (result.contentType.contains("application/json", ignoreCase = true)) {
                    val spannableResponse = formatter.formatJsonAsSpannable(result.response)
                    responseTextView.text = spannableResponse
                } else {
                    // Use ResponseFormatter to format the response
                    val formattedResponse = formatter.formatApiResponse(result, this@MainActivity)
                    responseTextView.text = formattedResponse
                }
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
            // Calculate the actual content height needed for full display without scrolling
            val contentHeight = calculateRequiredContentHeight(headerTextView, responseTextView, imageView)
            
            // Use the full content height without any maximum limit to eliminate scrolling
            val minHeight = (500 * resources.displayMetrics.density).toInt() // Minimum 500dp
            layoutParams.height = maxOf(contentHeight, minHeight)
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
            
            // Use the full content height without any maximum limit to eliminate scrolling
            val minHeight = (500 * resources.displayMetrics.density).toInt() // Minimum 500dp
            val newHeight = maxOf(contentHeight, minHeight)
            
            val layoutParams = scrollView.layoutParams
            if (layoutParams.height != newHeight) {
                layoutParams.height = newHeight
                scrollView.layoutParams = layoutParams
                scrollView.requestLayout()
            }
        }
    }
    
    private fun setupAIButtons() {
        // Test AI Text endpoint with interactive activity
        findViewById<Button>(R.id.buttonTestAIText).setOnClickListener {
            val intent = Intent(this, AITestActivity::class.java)
            startActivity(intent)
        }
        
        // Test AI Models endpoint
        findViewById<Button>(R.id.buttonTestAIModels).setOnClickListener {
            lifecycleScope.launch {
                val result = apiTester.testEndpoint("/ai/models")
                runOnUiThread {
                    showApiResult(result)
                }
            }
        }
        
        // Manage AI Models
        findViewById<Button>(R.id.buttonManageAIModels).setOnClickListener {
            val intent = Intent(this, AIModelManagerActivity::class.java)
            startActivity(intent)
        }
        
        // Test Object Detection (Rear Camera)
        findViewById<Button>(R.id.buttonTestObjectDetectionRear).setOnClickListener {
            testObjectDetectionEndpoint("rear")
        }
        
        // Test Object Detection (Front Camera)
        findViewById<Button>(R.id.buttonTestObjectDetectionFront).setOnClickListener {
            testObjectDetectionEndpoint("front")
        }
        
        // Update the button text based on available models
        updateAIModelsButtonText()
    }
    
    private fun updateAIModelsButtonText() {
        val button = findViewById<Button>(R.id.buttonManageAIModels)
        val badge = findViewById<TextView>(R.id.textModelCount)
        
        lifecycleScope.launch {
            try {
                val availableModels = withContext(Dispatchers.IO) {
                    com.k3s.phoneserver.ai.ModelDetector.getAvailableModels(this@MainActivity)
                }
                
                runOnUiThread {
                    val modelCount = availableModels.size
                    badge.text = modelCount.toString()
                    
                    if (modelCount > 0) {
                        button.text = "🤖 Manage AI Models"
                        badge.isSelected = true  // Green background
                    } else {
                        button.text = "🤖 Manage AI Models"
                        badge.isSelected = false  // Red background
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    button.text = "🤖 Manage AI Models"
                    badge.text = "0"
                    badge.isSelected = false  // Red background
                }
            }
        }
    }
    
    private fun showAITestDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_test, null)
        val inputText = dialogView.findViewById<android.widget.EditText>(R.id.editTextPrompt)
        val modelSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerModel)
        val imageScalingSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerImageScaling)
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioGroupImageInput)
        val layoutImageInput = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutImageInput)
        val layoutResults = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutResults)
        val textResults = dialogView.findViewById<android.widget.TextView>(R.id.textResults)
        
        // Set up model selection with only available models
        lifecycleScope.launch {
            val availableModels = withContext(Dispatchers.IO) {
                com.k3s.phoneserver.ai.ModelDetector.getAvailableModels(this@MainActivity)
            }
            
            if (availableModels.isEmpty()) {
                // No models available - show message and close dialog
                Toast.makeText(this@MainActivity, "⚠️ No AI models are available. Please add models first.", Toast.LENGTH_LONG).show()
                return@launch
            }
            
            val modelNames = availableModels.map { it.name }
            val modelDisplayNames = availableModels.map { "${it.modelName} (${it.fileName})" }
            
            val modelAdapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, modelDisplayNames)
            modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = modelAdapter
            
            // Set up image scaling selection
            val scalingOptions = arrayOf(
                "NONE - Original quality",
                "SMALL - 512×384 (fast)",
                "MEDIUM - 1024×768 (balanced)",
                "LARGE - 1536×1152 (good quality)",
                "ULTRA - 2048×1536 (best quality)"
            )
            val scalingAdapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, scalingOptions)
            scalingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            imageScalingSpinner.adapter = scalingAdapter
            imageScalingSpinner.setSelection(2) // Default to MEDIUM
            
            // Set default prompt
            inputText.setText("What do you see in this image?")
            
            // Function to update image input visibility based on selected model
            fun updateImageInputVisibility() {
                val selectedModelIndex = modelSpinner.selectedItemPosition
                if (selectedModelIndex >= 0 && selectedModelIndex < availableModels.size) {
                    val selectedModel = availableModels[selectedModelIndex]
                    val isMultimodal = selectedModel.supportsVision
                    layoutImageInput.visibility = if (isMultimodal) android.view.View.VISIBLE else android.view.View.GONE
                    
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
            modelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    updateImageInputVisibility()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("🤖 AI Text Generation Test")
                .setView(dialogView)
                .setPositiveButton("🚀 Generate") { dialog, _ ->
                    val prompt = inputText.text.toString()
                    val selectedModelName = modelNames[modelSpinner.selectedItemPosition]
                    val selectedScaling = getImageScalingFromIndex(imageScalingSpinner.selectedItemPosition)
                    val imageInputType = getSelectedImageInputType(radioGroup)
                    
                    // Don't close dialog, show results instead
                    generateAITextInDialog(prompt, selectedModelName, selectedScaling, imageInputType, layoutResults, textResults)
                }
                .setNegativeButton("Close", null)
                .create()
            
            dialog.show()
        }
    }
    
    private fun getImageScalingFromIndex(index: Int): String {
        return when (index) {
            0 -> "NONE"
            1 -> "SMALL"
            2 -> "MEDIUM"
            3 -> "LARGE"
            4 -> "ULTRA"
            else -> "MEDIUM"
        }
    }
    
    private fun getSelectedImageInputType(radioGroup: android.widget.RadioGroup): ImageInputType {
        return when (radioGroup.checkedRadioButtonId) {
            R.id.radioCaptureRear -> ImageInputType.CAPTURE_REAR
            R.id.radioCaptureFont -> ImageInputType.CAPTURE_FRONT
            R.id.radioSelectImage -> ImageInputType.SELECT_FROM_GALLERY
            else -> ImageInputType.NONE
        }
    }
    
    private enum class ImageInputType {
        NONE, CAPTURE_REAR, CAPTURE_FRONT, SELECT_FROM_GALLERY
    }
    
    private fun generateAITextInDialog(
        prompt: String, 
        model: String, 
        imageScaling: String, 
        imageInputType: ImageInputType,
        layoutResults: android.widget.LinearLayout,
        textResults: android.widget.TextView
    ) {
        lifecycleScope.launch {
            try {
                // Show loading state
                runOnUiThread {
                    layoutResults.visibility = android.view.View.VISIBLE
                    textResults.text = "🔄 Generating response..."
                }
                
                // Build the request body based on image input type
                val jsonBody = when (imageInputType) {
                    ImageInputType.CAPTURE_REAR -> """
                    {
                        "text": "${prompt.replace("\"", "\\\"")}",
                        "model": "$model",
                        "imageScaling": "$imageScaling",
                        "captureConfig": {
                            "camera": "rear"
                        },
                        "temperature": 0.7
                    }
                    """.trimIndent()
                    
                    ImageInputType.CAPTURE_FRONT -> """
                    {
                        "text": "${prompt.replace("\"", "\\\"")}",
                        "model": "$model",
                        "imageScaling": "$imageScaling",
                        "captureConfig": {
                            "camera": "front"
                        },
                        "temperature": 0.7
                    }
                    """.trimIndent()
                    
                    ImageInputType.SELECT_FROM_GALLERY -> {
                        if (selectedImageUri == null) {
                            // Launch gallery picker
                            runOnUiThread {
                                galleryLauncher.launch("image/*")
                                Toast.makeText(this@MainActivity, "Please select an image first", Toast.LENGTH_SHORT).show()
                                layoutResults.visibility = android.view.View.GONE
                            }
                            return@launch
                        }
                        
                        // Convert selected image to base64
                        val base64Image = try {
                            val inputStream = contentResolver.openInputStream(selectedImageUri!!)
                            val bytes = inputStream?.readBytes()
                            inputStream?.close()
                            if (bytes != null) {
                                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            } else {
                                throw Exception("Failed to read image")
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Failed to load image: ${e.message}", Toast.LENGTH_LONG).show()
                                layoutResults.visibility = android.view.View.GONE
                            }
                            return@launch
                        }
                        
                        """
                        {
                            "text": "${prompt.replace("\"", "\\\"")}",
                            "model": "$model",
                            "imageScaling": "$imageScaling",
                            "image": "data:image/jpeg;base64,$base64Image",
                            "temperature": 0.7
                        }
                        """.trimIndent()
                    }
                    
                    ImageInputType.NONE -> """
                    {
                        "text": "${prompt.replace("\"", "\\\"")}",
                        "model": "$model",
                        "imageScaling": "$imageScaling",
                        "temperature": 0.7
                    }
                    """.trimIndent()
                }
                
                val result = apiTester.testPostEndpoint("/ai/text", jsonBody)
                
                // Log the demo AI text request
                runOnUiThread {
                    showAITestResultInDialog(jsonBody, result, textResults)
                }
            } catch (e: Exception) {
                
            }
        }
    }
    
    private fun showAITestResultInDialog(requestJson: String, result: ApiTestResult, textResults: android.widget.TextView) {
        val formatter = ResponseFormatter.getInstance()
        
        // Build combined request and response display
        val combinedText = buildString {
            appendLine("AI Generation Result")
            appendLine("=".repeat(40))
            appendLine()
            appendLine("REQUEST:")
            appendLine(formatter.formatJsonWithHighlighting(requestJson))
            appendLine()
            appendLine("RESPONSE:")
            if (result.success) {
                appendLine("Status: ${result.statusCode} (${result.responseTime}ms)")
                appendLine()
                appendLine(formatter.formatApiResponse(result, this@MainActivity))
            } else {
                appendLine("Error: ${result.error ?: "Unknown error"}")
                appendLine("Status: ${result.statusCode}")
                if (result.response.isNotEmpty()) {
                    appendLine()
                    appendLine(result.response)
                }
            }
        }
        
        textResults.text = combinedText
        
        // Reset selected image after use
        selectedImageUri = null
    }

    private fun generateAITextAdvanced(
        prompt: String, 
        model: String, 
        imageScaling: String, 
        imageInputType: ImageInputType
    ) {
        lifecycleScope.launch {
            try {
                // Build the request body based on image input type
                val jsonBody = when (imageInputType) {
                    ImageInputType.CAPTURE_REAR -> """
                    {
                        "text": "${prompt.replace("\"", "\\\"")}",
                        "model": "$model",
                        "imageScaling": "$imageScaling",
                        "captureConfig": {
                            "camera": "rear"
                        },
                        "temperature": 0.7
                    }
                    """.trimIndent()
                    
                    ImageInputType.CAPTURE_FRONT -> """
                    {
                        "text": "${prompt.replace("\"", "\\\"")}",
                        "model": "$model",
                        "imageScaling": "$imageScaling",
                        "captureConfig": {
                            "camera": "front"
                        },
                        "temperature": 0.7
                    }
                    """.trimIndent()
                    
                    ImageInputType.SELECT_FROM_GALLERY -> {
                        if (selectedImageUri == null) {
                            // Launch gallery picker
                            runOnUiThread {
                                galleryLauncher.launch("image/*")
                                Toast.makeText(this@MainActivity, "Please select an image first", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        
                        // Convert selected image to base64
                        val base64Image = try {
                            val inputStream = contentResolver.openInputStream(selectedImageUri!!)
                            val bytes = inputStream?.readBytes()
                            inputStream?.close()
                            if (bytes != null) {
                                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            } else {
                                throw Exception("Failed to read image")
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Failed to load image: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                        
                        """
                        {
                            "text": "${prompt.replace("\"", "\\\"")}",
                            "model": "$model",
                            "imageScaling": "$imageScaling",
                            "image": "data:image/jpeg;base64,$base64Image",
                            "temperature": 0.7
                        }
                        """.trimIndent()
                    }
                    
                    ImageInputType.NONE -> """
                    {
                        "text": "${prompt.replace("\"", "\\\"")}",
                        "model": "$model",
                        "imageScaling": "$imageScaling",
                        "temperature": 0.7
                    }
                    """.trimIndent()
                }
                
                val result = apiTester.testPostEndpoint("/ai/text", jsonBody)
                runOnUiThread {
                    showAITestResult(jsonBody, result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showAITestResult(requestJson: String, result: ApiTestResult) {
        val responseTextView = findViewById<TextView>(R.id.textApiResponse)
        val formatter = ResponseFormatter.getInstance()
        
        // Build combined request and response display
        val combinedText = buildString {
            appendLine("AI Text Generation Test")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("REQUEST:")
            appendLine(formatter.formatJsonWithHighlighting(requestJson))
            appendLine()
            appendLine("RESPONSE:")
            if (result.success) {
                appendLine("Status: ${result.statusCode} (${result.responseTime}ms)")
                appendLine()
                appendLine(formatter.formatApiResponse(result, this@MainActivity))
            } else {
                appendLine("Error: ${result.error ?: "Unknown error"}")
                appendLine("Status: ${result.statusCode}")
                if (result.response.isNotEmpty()) {
                    appendLine()
                    appendLine(result.response)
                }
            }
        }
        
        responseTextView.text = combinedText
        
        // Reset selected image after use
        selectedImageUri = null
    }

    private fun generateAIText(prompt: String, model: String, includeCamera: Boolean) {
        // Legacy function - redirect to new advanced function
        val imageInputType = if (includeCamera) ImageInputType.CAPTURE_REAR else ImageInputType.NONE
        generateAITextAdvanced(prompt, model, "MEDIUM", imageInputType)
    }
}
