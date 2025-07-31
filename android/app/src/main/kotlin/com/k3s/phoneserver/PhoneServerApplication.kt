package com.k3s.phoneserver

import android.app.Application
import timber.log.Timber

class PhoneServerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.d("K3s Phone Server Application started")
    }
}
