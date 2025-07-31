package com.k3s.phoneserver.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.k3s.phoneserver.R
import com.k3s.phoneserver.services.ModelDownloadService
import kotlinx.coroutines.launch
import timber.log.Timber

class LicenseAgreementActivity : AppCompatActivity() {

    private lateinit var modelDownloadService: ModelDownloadService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license_agreement)
        
        modelDownloadService = ModelDownloadService(this)
        
        setupUI()
    }

    private fun setupUI() {
        findViewById<android.widget.TextView>(R.id.textLicenseContent).text = """
            Welcome to K3s Phone Server with AI Vision Capabilities!
            
            This app provides AI vision features with optional language model support.
            
            📋 AUTOMATIC DOWNLOADS (Required)
            
            The following models download automatically:
            • EfficientNet Lite - Image classification (~18MB)
            • EfficientDet Lite - Object detection (~14MB)  
            • MobileNet V3 - Image embeddings (~4MB)
            • Universal Sentence Encoder - Text processing (~6MB)
            
            These provide core vision AI features like object detection and image analysis.
            
            📋 OPTIONAL: ADVANCED AI (Manual Setup)
            
            For enhanced language understanding and reasoning:
            • Add "gemma.task" file to assets folder (requires manual download)
            • Enables advanced multimodal AI responses
            • Without Gemma: Vision analysis with structured responses
            • With Gemma: Natural language explanations and reasoning
            
            📋 TERMS & CONDITIONS
            
            All models use Apache 2.0 license:
            • Free for commercial and personal use
            • Models cached locally for offline operation
            • Internet required only for initial download (~42MB)
            
            📡 WHAT HAPPENS NEXT
            
            • Vision models download automatically (~30 seconds)
            • App works immediately with vision-only features
            • Add gemma.task manually for enhanced AI (optional)
            
            All required models are open-source and ready to use!
        """.trimIndent()

        findViewById<android.widget.Button>(R.id.buttonViewFullTerms).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.apache.org/licenses/LICENSE-2.0"))
                startActivity(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to open browser")
            }
        }

        findViewById<android.widget.Button>(R.id.buttonDecline).setOnClickListener {
            finish()
        }

        findViewById<android.widget.Button>(R.id.buttonAcceptAndDownload).setOnClickListener {
            acceptLicenseAndDownload()
        }
    }

    private fun acceptLicenseAndDownload() {
        // Mark license as accepted
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("ai_license_accepted", true).apply()
        
        // Start model download
        findViewById<android.widget.Button>(R.id.buttonAcceptAndDownload).isEnabled = false
        findViewById<android.widget.Button>(R.id.buttonDecline).isEnabled = false
        findViewById<android.widget.TextView>(R.id.textStatus).text = "Downloading vision models... (~42MB)"
        findViewById<android.widget.ProgressBar>(R.id.progressBar).visibility = android.view.View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val success = modelDownloadService.downloadAllModels { progress, status ->
                    runOnUiThread {
                        findViewById<android.widget.ProgressBar>(R.id.progressBar).progress = progress
                        findViewById<android.widget.TextView>(R.id.textStatus).text = status
                    }
                }
                
                if (success) {
                    findViewById<android.widget.TextView>(R.id.textStatus).text = "✅ Vision models ready! Starting app..."
                    
                    // Mark models as downloaded
                    prefs.edit().putBoolean("models_downloaded", true).apply()
                    
                    // Start main activity
                    val intent = Intent(this@LicenseAgreementActivity, com.k3s.phoneserver.MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    findViewById<android.widget.TextView>(R.id.textStatus).text = "❌ Download failed. Please check your internet connection and try again."
                    findViewById<android.widget.Button>(R.id.buttonAcceptAndDownload).isEnabled = true
                    findViewById<android.widget.Button>(R.id.buttonDecline).isEnabled = true
                    findViewById<android.widget.ProgressBar>(R.id.progressBar).visibility = android.view.View.GONE
                }
            } catch (e: Exception) {
                Timber.e(e, "Model download failed")
                findViewById<android.widget.TextView>(R.id.textStatus).text = "❌ Download failed: ${e.message}"
                findViewById<android.widget.Button>(R.id.buttonAcceptAndDownload).isEnabled = true
                findViewById<android.widget.Button>(R.id.buttonDecline).isEnabled = true
                findViewById<android.widget.ProgressBar>(R.id.progressBar).visibility = android.view.View.GONE
            }
        }
    }
}
