package dev.openliveness.sensors

import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.openliveness.AmbientConditions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 4 Step 8 — wires the real [LightSensor] + [MotionDetector] into
 * one lifecycle-aware facade that CDLSession can start/stop/snapshot
 * without touching Android sensor APIs itself.
 *
 * **Why one facade instead of two injects**: the orchestrator reads BOTH
 * `lowLightDetected` and `motionDetected` from a single [AmbientConditions]
 * value, so the only semantically-meaningful operation on the pair is
 * "give me the current pair." A single `.snapshot()` keeps the two reads
 * atomic from the caller's perspective (both reads come from the same
 * instant in the sampler's lifetime) and removes an easy footgun where
 * an integrator wires only one of the two sensors.
 *
 * **Threading**: callbacks fire on the main looper (via the Handler
 * passed to `registerListener`), so [snapshot] can safely be called
 * from any thread — the volatile fields on both detectors give a
 * well-defined cross-thread read. `start()` and `stop()` are themselves
 * thread-safe to call from any thread because they only route through
 * SensorManager (which Android documents as thread-safe) + the Handler
 * (dispatch is lock-free) + an [AtomicBoolean] idempotency guard.
 *
 * **Battery**: `start()` registers at SENSOR_DELAY_NORMAL (light) /
 * SENSOR_DELAY_GAME (accel). Both are inexpensive but should not run
 * indefinitely — CDLSession calls `.stop()` in the outer finally of
 * `start()` so a session that errors mid-way still releases the sensors.
 */
class AmbientConditionsSampler internal constructor(
    private val light: LightSensor,
    private val motion: MotionDetector,
    /** Handler used for sensor callback dispatch. Default = main looper.
     *  Overridable for tests that need deterministic delivery via a
     *  Robolectric or test-looper harness. */
    private val handler: Handler,
) {

    // Idempotency guard. Two concerns met at once:
    //   1) a double-start wouldn't physically re-register (SensorManager
    //      ignores duplicate registrations), but a mis-paired stop could
    //      then unregister a listener another caller still thinks is
    //      live. The latch pins the lifecycle to start→stop pairs.
    //   2) a stop-without-start would call sensorManager.unregisterListener
    //      on a never-registered listener; that's harmless on current
    //      Android but reads as a bug in a crash trace.
    private val started = AtomicBoolean(false)

    /**
     * Public construction path. Pins the SensorManager lookup to the
     * applicationContext so an Activity-leak path through this sampler
     * is impossible — same pattern CDLSession uses at construction time.
     * Belt-and-braces: CDLSession already normalises the context before
     * handing it here, but any future caller that constructs directly
     * from an Activity is still safe.
     */
    constructor(context: Context) : this(
        sensorManager = (context.applicationContext ?: context)
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager,
    )

    private constructor(sensorManager: SensorManager) : this(
        light = LightSensor(sensorManager),
        motion = MotionDetector(sensorManager),
        handler = Handler(Looper.getMainLooper()),
    )

    /**
     * Begin ambient sensing. Idempotent — a second call is a no-op
     * (returns before touching either detector). Fault-isolated: if
     * one detector's `start()` throws (e.g., a SecurityException from
     * a vendor sensor framework), the other still runs and the sampler
     * stays in the started state so `.stop()` still unregisters what
     * did come up. Rationale: ambient sensing is a defense-in-depth
     * reweighting signal, not a hard liveness gate — a vendor-sensor
     * misbehavior must not fail the main CDLSession flow.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            light.start(handler)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "LightSensor.start failed; ambient low-light signal unavailable", e)
        }
        try {
            motion.start(handler)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "MotionDetector.start failed; ambient motion signal unavailable", e)
        }
    }

    /**
     * Release both sensor registrations. Idempotent — a stop without a
     * prior start, or a second stop, is a no-op. Fault-isolated for the
     * same reason as [start]: an unregister that throws on one detector
     * must not leave the other registered.
     */
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        try {
            light.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "LightSensor.stop failed; may leak sensor registration", e)
        }
        try {
            motion.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "MotionDetector.stop failed; may leak sensor registration", e)
        }
    }

    /**
     * Current readings as an [AmbientConditions] instance. Call after
     * both sensors have had at least one event tick; before that, both
     * flags are their safe-default `false` (NOT low light, NOT in
     * motion), which is also what a legacy caller who passed `null` for
     * the sampler would get. The default values are safe-biased: a
     * stale "no low light" means Layer 3's stricter threshold is
     * applied, which is the conservative branch; a stale "no motion"
     * means no motion-penalty reweighting, which is correct on a still
     * device.
     *
     * `frontCameraConfirmed` stays at its [AmbientConditions] default of
     * `true` — the actual confirmation lives in the capture pipeline's
     * `CameraSelector.LENS_FACING_FRONT` enforcement and isn't a sensor
     * reading. Carried here only so a caller doesn't need to construct
     * two separate ambient objects.
     */
    fun snapshot(): AmbientConditions = AmbientConditions(
        lowLightDetected = light.lowLightDetected,
        motionDetected = motion.motionDetected,
    )

    private companion object {
        private const val LOG_TAG = "OpenLiveness"
    }
}
