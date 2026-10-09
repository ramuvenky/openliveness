package dev.openliveness.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Reports `low_light_detected` from TYPE_LIGHT readings < 50 lux. */
class LightSensor(private val sensorManager: SensorManager) : SensorEventListener {
    var lowLightDetected = false
        private set

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    fun start() {
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        lowLightDetected = event.values[0] < 50f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
