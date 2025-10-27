package me.bechberger.phoneserver.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.os.IBinder
import androidx.core.app.NotificationCompat
import me.bechberger.phoneserver.MainActivity
import me.bechberger.phoneserver.R
import kotlinx.coroutines.*
import timber.log.Timber

class WebServerService : Service() {

    private var webServer: WebServer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "WebServerChannel"
        const val ACTION_STOP_SERVER = "me.bechberger.phoneserver.STOP_SERVER"

        @Volatile
        private var _isRunning = false

        val isRunning: Boolean
            get() = _isRunning

        internal fun setRunning(running: Boolean) {
            _isRunning = running
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("WebServerService created")
    }

    override fun onStartCommand(intent: Intent?, _flags: Int, startId: Int): Int {
        Timber.d("Starting WebServerService - ensuring persistent background operation")

        // Log how the service was started for debugging
        when {
            _flags and START_FLAG_REDELIVERY != 0 -> {
                Timber.i("Service restarted - redelivering intent (system recovered from crash)")
            }
            _flags and START_FLAG_RETRY != 0 -> {
                Timber.i("Service restarted by system after being killed (automatic restart)")
            }
            else -> {
                Timber.d("Service started normally")
            }
        }

        // Handle stop action from notification (only way to stop the server)
        if (intent?.action == ACTION_STOP_SERVER) {
            Timber.i("Stop server action received from notification - user requested shutdown")
            setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Always ensure the server is running regardless of how we got here
        ensureServerRunning()

        // Create persistent notification
        createAndShowNotification()

        // Return START_STICKY for maximum persistence - system will restart us
        return START_STICKY
    }

    private fun ensureServerRunning() {
        serviceScope.launch {
            try {
                if (webServer == null || !isRunning) {
                    Timber.i("Starting/restarting web server on port 8005")
                    webServer?.stop() // Clean stop if exists
                    webServer = WebServer(this@WebServerService)
                    webServer?.start(8005)
                    setRunning(true)
                    Timber.i("Web server started successfully on port 8005 in background service")
                } else {
                    Timber.d("Web server already running, maintaining service")
                    setRunning(true)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start web server in background service - will retry")
                setRunning(false)

                // Schedule a retry after a short delay
                serviceScope.launch {
                    delay(5000) // Wait 5 seconds
                    if (!isRunning) {
                        Timber.i("Retrying server start after failure")
                        ensureServerRunning()
                    }
                }
            }
        }
    }

    private fun createAndShowNotification() {
        // Create a clickable notification that opens the main activity
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create stop action for notification
        val stopIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local AI Phone Server")
            .setContentText("Server running persistently on port 8005 - Always available")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop Server", stopPendingIntent)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.w("WebServerService being destroyed - this should only happen on explicit stop")

        // Only actually stop if this was an intentional shutdown
        if (!isRunning) {
            Timber.i("Intentional shutdown - stopping web server")
            serviceScope.launch {
                webServer?.stop()
                serviceScope.cancel()
            }
        } else {
            Timber.w("Unexpected destruction while server should be running - system will restart us")
            // Don't stop the web server - let the system restart us via START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.d("Task removed (app swiped away) - server continues running in background")
        // Do nothing - we want the server to keep running even if app is removed from recent tasks
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Timber.w("System memory critically low - but keeping server running")
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Timber.d("App moved to background - server continues")
            }
        }
        // Don't stop the server regardless of memory pressure
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Web Server Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for Local AI Phone Server web service"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
