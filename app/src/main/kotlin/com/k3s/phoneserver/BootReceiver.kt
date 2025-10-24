package com.k3s.phoneserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.k3s.phoneserver.manager.AppPermissionManager
import com.k3s.phoneserver.server.WebServerService
import timber.log.Timber

/**
 * Boot receiver that automatically starts the web server service
 * when the device boots up or the app is updated.
 */
class BootReceiver : BroadcastReceiver() {

    private val permissionManager = AppPermissionManager.getInstance()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Timber.d("BootReceiver received action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                Timber.i("System boot/update detected - attempting to auto-start K3s server")
                
                // Always try to start the service for maximum persistence
                try {
                    val serviceIntent = Intent(context, WebServerService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Timber.i("AI Phone Server auto-started on boot/update")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto-start AI Phone Server on boot")
                }
                
                // Log permission status for debugging
                if (permissionManager.shouldAutoStartServer(context)) {
                    Timber.d("Auto-start conditions met: ${permissionManager.getPermissionStatusSummary(context)}")
                } else {
                    Timber.w("Auto-start conditions not fully met but starting anyway: ${permissionManager.getPermissionStatusSummary(context)}")
                }
            }
        }
    }
}
