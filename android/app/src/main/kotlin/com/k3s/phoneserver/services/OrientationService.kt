package com.k3s.phoneserver.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import timber.log.Timber
import kotlin.math.sqrt

class OrientationService(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    private var lastAccelerometerReading = FloatArray(3)
    private var lastMagnetometerReading = FloatArray(3)
    private var lastAccelerometerSet = false
    private var lastMagnetometerSet = false
    
    private var currentOrientation = OrientationData()
    
    init {
        startListening()
    }

    private fun startListening() {
        accelerometer?.let { 
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        magnetometer?.let { 
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) 
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccelerometerReading, 0, event.values.size)
                lastAccelerometerSet = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagnetometerReading, 0, event.values.size)
                lastMagnetometerSet = true
            }
        }
        
        if (lastAccelerometerSet && lastMagnetometerSet) {
            updateOrientation()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Handle accuracy changes if needed
    }

    private fun updateOrientation() {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        
        val success = SensorManager.getRotationMatrix(
            rotationMatrix, 
            null,
            lastAccelerometerReading, 
            lastMagnetometerReading
        )
        
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // Convert radians to degrees
            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            
            // Normalize azimuth to 0-360 degrees
            val normalizedAzimuth = if (azimuth < 0) azimuth + 360 else azimuth
            
            // Calculate accuracy based on magnetometer and accelerometer readings
            val magneticStrength = sqrt(
                lastMagnetometerReading[0] * lastMagnetometerReading[0] +
                lastMagnetometerReading[1] * lastMagnetometerReading[1] +
                lastMagnetometerReading[2] * lastMagnetometerReading[2]
            )
            
            val accelerometerStrength = sqrt(
                lastAccelerometerReading[0] * lastAccelerometerReading[0] +
                lastAccelerometerReading[1] * lastAccelerometerReading[1] +
                lastAccelerometerReading[2] * lastAccelerometerReading[2]
            )
            
            // Rough accuracy estimation
            val accuracy = when {
                magneticStrength > 20 && accelerometerStrength in 8.0..12.0 -> "HIGH"
                magneticStrength > 10 && accelerometerStrength in 6.0..15.0 -> "MEDIUM"
                else -> "LOW"
            }
            
            currentOrientation = OrientationData(
                azimuth = normalizedAzimuth,
                pitch = pitch,
                roll = roll,
                accuracy = accuracy,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun getCurrentOrientation(): OrientationData {
        return currentOrientation
    }

    fun cleanup() {
        sensorManager.unregisterListener(this)
    }
}

data class OrientationData(
    val azimuth: Float = 0f,  // Compass direction (0-360 degrees, 0 = North)
    val pitch: Float = 0f,    // Rotation around X-axis
    val roll: Float = 0f,     // Rotation around Y-axis
    val accuracy: String = "UNKNOWN",
    val timestamp: Long = System.currentTimeMillis()
)
