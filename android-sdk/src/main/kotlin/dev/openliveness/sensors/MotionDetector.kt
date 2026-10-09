package dev.openliveness.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reports `motion_detected` for ambient_conditions. Threshold: accelerometer
 * magnitude deviates > 0.3g from the 1g gravity baseline.
 */
class MotionDetector(private val sensorManager: SensorManager) : SensorEventListener {

    var motionDetected = false
        private set

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun start() {
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val g = SensorManager.GRAVITY_EARTH
        val ax = event.values[0] / g
        val ay = event.values[1] / g
        val az = event.values[2] / g
        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble())
        motionDetected = abs(magnitude - 1.0) > 0.3
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
