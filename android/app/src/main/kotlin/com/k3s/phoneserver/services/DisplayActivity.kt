package com.k3s.phoneserver.services

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.k3s.phoneserver.R
import timber.log.Timber

/**
 * Full-screen activity for displaying text messages
 */
class DisplayActivity : Activity() {
    
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make it full-screen and immersive
        setupFullScreen()
        
        // Create simple layout programmatically
        val textView = TextView(this).apply {
            id = View.generateViewId()
            textSize = 48f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            gravity = android.view.Gravity.CENTER
        }
        
        setContentView(textView)
        
        // Get parameters from intent
        val text = intent.getStringExtra("text") ?: "Display Test"
        val backgroundColor = intent.getIntExtra("backgroundColor", android.graphics.Color.BLACK)
        val textColor = intent.getIntExtra("textColor", android.graphics.Color.WHITE)
        val duration = intent.getLongExtra("duration", 3000L)
        val displayId = intent.getStringExtra("displayId") ?: "unknown"
        
        // Configure display
        textView.text = text
        textView.setTextColor(textColor)
        textView.setBackgroundColor(backgroundColor)
        
        // Log display start
        Timber.d("Display activity started: $displayId, duration: ${duration}ms")
        
        // Auto-hide after duration
        hideRunnable = Runnable {
            Timber.d("Auto-hiding display: $displayId")
            finish()
        }
        handler.postDelayed(hideRunnable!!, duration)
        
        // Allow tap-to-dismiss
        textView.setOnClickListener {
            Timber.d("Display dismissed by tap: $displayId")
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        hideRunnable?.let { handler.removeCallbacks(it) }
    }
    
    private fun setupFullScreen() {
        // Make the activity full-screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.let { controller ->
            // Hide system bars
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        // Keep screen on while displaying
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Show over lock screen if possible
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    
    override fun onBackPressed() {
        // Allow back button to dismiss
        val displayId = intent.getStringExtra("displayId") ?: "unknown"
        Timber.d("Display dismissed by back button: $displayId")
        super.onBackPressed()
    }
}
