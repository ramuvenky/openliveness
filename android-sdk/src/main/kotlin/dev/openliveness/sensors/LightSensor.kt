package dev.openliveness.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/** Reports `low_light_detected` from TYPE_LIGHT readings < 50 lux. */
class LightSensor(private val sensorManager: SensorManager) : SensorEventListener {
    // @Volatile because the sensor callback fires on whatever Looper
    // thread the Handler is bound to (main looper when driven by
    // AmbientConditionsSampler) while CDLSession.start() reads this on
    // Dispatchers.IO. Without the memory barrier the IO-thread read is
    // free to see a stale value indefinitely.
    @Volatile
    var lowLightDetected = false
        private set

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    /**
     * Begin listening. [handler] determines which Looper the sensor
     * callbacks fire on. Required (no default) because
     * `registerListener(..., handler = null)` falls back to the caller's
     * Looper and would throw from a Dispatchers.IO coroutine — the
     * sampler path that owns this class. Making the parameter mandatory
     * forces each call site to be explicit about threading.
     */
    fun start(handler: Handler) {
        if (sensor != null) {
            sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler
            )
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
