package com.k3s.phoneserver.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.k3s.phoneserver.R
import kotlinx.coroutines.*
import timber.log.Timber

class WebServerService : Service() {

    private var webServer: WebServer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "WebServerChannel"
        
        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("WebServerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("Starting WebServerService")
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("K3s Phone Server")
            .setContentText("Web server running on port 8005")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
            
        startForeground(NOTIFICATION_ID, notification)
        
        serviceScope.launch {
            try {
                webServer = WebServer(this@WebServerService)
                webServer?.start(8005)
                isRunning = true
                Timber.d("Web server started successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start web server")
                isRunning = false
            }
        }
        
        return START_STICKY // Restart if killed by system
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("Destroying WebServerService")
        
        serviceScope.launch {
            webServer?.stop()
            isRunning = false
            serviceScope.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Web Server Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for K3s Phone Server web service"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
