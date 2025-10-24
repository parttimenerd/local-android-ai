package com.k3s.phoneserver.cache

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

object ImageCacheManager {
    private const val MAX_IMAGES = 50
    private val imageCache = ConcurrentHashMap<String, Bitmap>()
    private val imageKeys = mutableListOf<String>()
    
    @Synchronized
    fun cacheImage(key: String, bitmap: Bitmap): String {
        // Add new image
        imageCache[key] = bitmap
        imageKeys.add(0, key) // Add to front
        
        // Remove oldest images if we exceed limit
        while (imageKeys.size > MAX_IMAGES) {
            val oldestKey = imageKeys.removeAt(imageKeys.size - 1)
            imageCache.remove(oldestKey)
        }
        
        return key
    }
    
    fun getImage(key: String): Bitmap? {
        return imageCache[key]
    }
    
    fun hasImage(key: String): Boolean {
        return imageCache.containsKey(key)
    }
    
    fun getImageOrPlaceholder(key: String): String? {
        return when {
            hasImage(key) -> key // Image is available
            else -> "Image removed (only last $MAX_IMAGES images kept)"
        }
    }
    
    fun generateImageKey(prefix: String = "img"): String {
        return "${prefix}_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000, 9999)}"
    }
    
    @Synchronized
    fun clearCache() {
        imageCache.clear()
        imageKeys.clear()
    }
    
    fun getCacheInfo(): String {
        return "Images cached: ${imageCache.size}/$MAX_IMAGES"
    }
}
