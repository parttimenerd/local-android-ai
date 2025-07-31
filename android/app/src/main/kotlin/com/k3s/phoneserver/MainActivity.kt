package com.k3s.phoneserver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.k3s.phoneserver.adapter.RequestLogAdapter
import com.k3s.phoneserver.logging.RequestLogger
import com.k3s.phoneserver.server.WebServerService
import com.k3s.phoneserver.services.ModelDownloadService
import com.k3s.phoneserver.ui.LicenseAgreementActivity
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var logAdapter: RequestLogAdapter
    private lateinit var modelDownloadService: ModelDownloadService
    private var isServerRunning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startWebServer()
        } else {
            Toast.makeText(this, "Permissions required for server functionality", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check license agreement and model download status first
        modelDownloadService = ModelDownloadService(this)
        
        if (!isLicenseAccepted() || !modelDownloadService.areModelsDownloaded()) {
            // Redirect to license agreement activity
            val intent = Intent(this, LicenseAgreementActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        
        setContentView(R.layout.activity_main)

        setupRecyclerView()
        observeRequestLogs()
        checkAndRequestPermissions()
        
        findViewById<android.widget.Button>(R.id.buttonStartServer).setOnClickListener {
            if (isServerRunning) {
                stopWebServer()
            } else {
                if (hasRequiredPermissions()) {
                    startWebServer()
                } else {
                    checkAndRequestPermissions()
                }
            }
        }
        
        findViewById<android.widget.Button>(R.id.buttonClearLogs).setOnClickListener {
            RequestLogger.clearLogs()
        }
        
        // Setup license notice click handler
        findViewById<android.widget.TextView>(R.id.textLicense).setOnClickListener {
            openAILicense()
        }
        
        updateUI()
    }

    private fun isLicenseAccepted(): Boolean {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("ai_license_accepted", false) && 
               prefs.getBoolean("models_downloaded", false)
    }

    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.WAKE_LOCK
        )
        
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED
        }
    }

    private fun openAILicense() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.apache.org/licenses/LICENSE-2.0"))
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: show a dialog with license information
            showLicenseDialog()
        }
    }

    private fun showLicenseDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("AI Model Licenses")
            .setMessage("""
                This app uses AI models with the following licenses:
                
                📋 Vision Models (Required):
                • MediaPipe models - Apache 2.0 license
                • Free for commercial and personal use
                • Auto-downloaded on first launch
                
                📋 Language Model (Optional):
                • Gemma model - If manually added to assets
                • Enhanced natural language responses
                • Apache 2.0 license
                
                🔗 Full license available at:
                apache.org/licenses/LICENSE-2.0
                
                Please review and comply with all license requirements.
            """.trimIndent())
            .setPositiveButton("Understood") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Open in Browser") { dialog, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ai.google.dev/gemma/terms"))
                    startActivity(intent)
                } catch (e: Exception) {
                    // Silently fail if no browser available
                }
                dialog.dismiss()
            }
            .create()
        
        dialog.show()
    }

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
        isServerRunning = WebServerService.isRunning
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        checkServerStatus()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.WAKE_LOCK
        )
        
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PermissionChecker.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            startWebServer()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startWebServer() {
        Timber.d("Starting web server")
        val intent = Intent(this, WebServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isServerRunning = true
        updateUI()
        
        Toast.makeText(this, "Web server started on port 8005", Toast.LENGTH_SHORT).show()
    }

    private fun stopWebServer() {
        Timber.d("Stopping web server")
        val intent = Intent(this, WebServerService::class.java)
        stopService(intent)
        isServerRunning = false
        updateUI()
        
        Toast.makeText(this, "Web server stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        findViewById<android.widget.Button>(R.id.buttonStartServer).text = if (isServerRunning) {
            "Stop Server"
        } else {
            "Start Server"
        }
        
        findViewById<android.widget.TextView>(R.id.textStatus).text = if (isServerRunning) {
            "🟢 Server running on port 8005\n\n" +
            "Endpoints:\n" +
            "• GET /status - Server status\n" +
            "• GET /location - Current GPS location\n" +
            "• GET /orientation - Compass orientation\n" +
            "• POST /ai/analyze - AI image analysis\n" +
            "• POST /ai/capture - Capture and analyze image\n\n" +
            "The server will continue running in the background even if you close this app."
        } else {
            "🔴 Server stopped\n\nPress 'Start Server' to begin."
        }
    }
}
