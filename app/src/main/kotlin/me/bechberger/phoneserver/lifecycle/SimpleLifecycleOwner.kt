package me.bechberger.phoneserver.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Simple LifecycleOwner for use in contexts where we need a LifecycleOwner
 * but don't have access to an Activity or Fragment (e.g., in API endpoints)
 */
class SimpleLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    
    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
    
    override val lifecycle: Lifecycle = lifecycleRegistry
    
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
