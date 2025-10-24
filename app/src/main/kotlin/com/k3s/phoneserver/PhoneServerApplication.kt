package com.k3s.phoneserver

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.k3s.phoneserver.manager.AppPermissionManager
import com.k3s.phoneserver.server.WebServerService
import timber.log.Timber

class PhoneServerApplication : Application(), DefaultLifecycleObserver {

    private val permissionManager = AppPermissionManager.getInstance()

    override fun onCreate() {
        super<Application>.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.d("AI Phone Server Application started")
        
        // Register lifecycle observer to track app state
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Timber.d("App process moved to foreground")
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Timber.d("App process moved to background - server continues via foreground service")
        
        // Ensure server continues running when app goes to background
        if (WebServerService.isRunning && permissionManager.shouldAutoStartServer(this)) {
            Timber.d("Maintaining server service while app is in background")
            try {
                val intent = Intent(this, WebServerService::class.java)
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to maintain server service in background")
            }
        }
    }

    override fun onTerminate() {
        super<Application>.onTerminate()
        Timber.d("Application terminating - server persistence managed by foreground service")
        // Note: onTerminate() is only called in emulator, not on real devices
        // Real termination is handled by the foreground service START_STICKY behavior
    }
}
