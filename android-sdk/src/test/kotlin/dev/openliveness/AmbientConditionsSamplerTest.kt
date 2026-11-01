package dev.openliveness

import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import dev.openliveness.sensors.AmbientConditionsSampler
import dev.openliveness.sensors.LightSensor
import dev.openliveness.sensors.MotionDetector
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 4 Step 8 — tests the composition that fans start / stop / read
 * across the two per-sensor detectors. The individual detectors
 * (LightSensor, MotionDetector) are driven directly by the Android
 * sensor framework which can't be exercised in a JVM test — but the
 * sampler is pure composition so it IS testable with mocked
 * detectors, which is where the integrator-facing contract lives
 * (start() fans out, stop() fans out, snapshot() reads both).
 */
class AmbientConditionsSamplerTest {

    private val handler: Handler = mockk(relaxed = true)

    private fun sampler(light: LightSensor, motion: MotionDetector) =
        AmbientConditionsSampler(light = light, motion = motion, handler = handler)

    @Test
    fun `start fans out to both sensors with the shared handler`() {
        // A fan-out bug (e.g., calling light.start twice and never
        // starting motion) would make the orchestrator's motion
        // reweighting silently inert while low-light looked wired —
        // the exact silent-failure class the single-facade design
        // exists to prevent.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)

        sampler(light, motion).start()

        verify(exactly = 1) { light.start(handler) }
        verify(exactly = 1) { motion.start(handler) }
    }

    @Test
    fun `stop after start fans out to both sensors`() {
        // After the idempotency guard was added, stop() without a prior
        // start() is a no-op (see "stop before start is a no-op"); the
        // fan-out contract applies only after the lifecycle has entered
        // the started state.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)

        val s = sampler(light, motion)
        s.start()
        s.stop()

        verify(exactly = 1) { light.stop() }
        verify(exactly = 1) { motion.stop() }
    }

    @Test
    fun `snapshot reads both current flags into AmbientConditions`() {
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)
        every { light.lowLightDetected } returns true
        every { motion.motionDetected } returns true

        val a = sampler(light, motion).snapshot()

        assertTrue(a.lowLightDetected)
        assertTrue(a.motionDetected)
        // frontCameraConfirmed stays true (the sampler doesn't own this
        // signal — it lives in the CameraX LENS_FACING_FRONT enforcement).
        // Pinned here so a future refactor that silently sets it to false
        // trips the test instead of silently flipping an attestation field.
        assertTrue(a.frontCameraConfirmed)
    }

    @Test
    fun `snapshot reads false defaults before any sensor event has fired`() {
        // On a device with no light sensor, LightSensor.lowLightDetected
        // stays at its initial `false` and the registerListener call is
        // a no-op. snapshot() must still return a valid AmbientConditions
        // rather than throwing — a cold session on a sensor-light device
        // (chromebook / tablet) must still complete liveness.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)
        every { light.lowLightDetected } returns false
        every { motion.motionDetected } returns false

        val a = sampler(light, motion).snapshot()

        assertFalse(a.lowLightDetected)
        assertFalse(a.motionDetected)
    }

    @Test
    fun `snapshot reflects partial reads - low light only`() {
        // Mixed-reading case: dim room but phone held still. The
        // orchestrator's low-light branch should relax Layer-3's SNR
        // bar WITHOUT the motion penalty firing. If the sampler
        // conflated the two flags (OR'd them, or shared the stale
        // bit) this test would see motionDetected=true and the
        // orchestrator would double-penalize.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)
        every { light.lowLightDetected } returns true
        every { motion.motionDetected } returns false

        val a = sampler(light, motion).snapshot()

        assertTrue(a.lowLightDetected)
        assertFalse(a.motionDetected)
    }

    @Test
    fun `two snapshots see current readings each time (not cached)`() {
        // Important for a session that mid-run transitions from
        // still-and-lit to shaky-and-dim: a cached snapshot would
        // report the first reading forever and the orchestrator
        // would apply the wrong weights. The sampler must re-read
        // the detector fields on every call.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)
        every { light.lowLightDetected } returnsMany listOf(false, true)
        every { motion.motionDetected } returnsMany listOf(false, true)

        val s = sampler(light, motion)
        val first = s.snapshot()
        val second = s.snapshot()

        assertFalse(first.lowLightDetected)
        assertFalse(first.motionDetected)
        assertTrue(second.lowLightDetected)
        assertTrue(second.motionDetected)
    }

    @Test
    fun `handler param is forwarded verbatim to each detector start`() {
        // The main-looper routing is the whole reason the sampler
        // takes a Handler; if the sampler ever "simplified" to
        // passing null to the detectors, callbacks would fire on
        // whatever thread SensorManager picked and the volatile
        // read guarantees in LightSensor / MotionDetector would
        // suddenly matter for correctness instead of defensive
        // posture. This pins the forwarding contract.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)
        val specificHandler: Handler = mockk(relaxed = true)

        AmbientConditionsSampler(light = light, motion = motion, handler = specificHandler).start()

        verify(exactly = 1) { light.start(specificHandler) }
        verify(exactly = 1) { motion.start(specificHandler) }
    }

    // Smoke test on the real detectors with a mocked SensorManager:
    // proves the Context-based constructor path builds a sampler that
    // start()s both sensors without blowing up. A broken registration
    // call (wrong constants, wrong argument order) would throw here.
    @Test
    fun `real detectors start cleanly against a mocked SensorManager`() {
        val sm = mockk<SensorManager>(relaxed = true)
        // getDefaultSensor is called in each detector's init. Returning
        // null is legal (no-sensor device) and keeps registerListener
        // unreached — the start()s become no-ops.
        every { sm.getDefaultSensor(any()) } returns null
        val light = LightSensor(sm)
        val motion = MotionDetector(sm)
        val s = AmbientConditionsSampler(light = light, motion = motion, handler = handler)

        // Must not throw.
        s.start()
        s.stop()
        val a = s.snapshot()
        assertEquals(AmbientConditions(), a)
    }

    // --- Review fixes: fault isolation, idempotency, context normalisation --

    @Test
    fun `start isolates a throwing detector so the other still starts`() {
        // High #1 — ambient sensing is a defense-in-depth reweighting
        // signal, not a hard liveness gate. A SecurityException from a
        // flaky vendor sensor stack must not cascade into failing the
        // whole CDLSession. The sampler catches per-detector and lets
        // the other one come up; snapshot() still returns a valid
        // AmbientConditions reading the remaining sensor's current flag.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)
        every { light.start(any()) } throws SecurityException("vendor-sensor stack is being weird")
        every { motion.motionDetected } returns true

        val s = sampler(light, motion)
        s.start()  // must not throw

        verify(exactly = 1) { light.start(handler) }
        verify(exactly = 1) { motion.start(handler) }
        val a = s.snapshot()
        // light flag stays at its initial `false` (no event ever fired),
        // which is the safe default. motion read goes through to the
        // detector that did start cleanly.
        assertFalse(a.lowLightDetected)
        assertTrue(a.motionDetected)
    }

    @Test
    fun `stop isolates a throwing detector so the other still stops`() {
        // Mirror of the start-side guarantee: a sensor that misbehaves
        // on unregister must not block the other sensor's unregister
        // from running — otherwise we leak a sensor registration past
        // the session end.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)
        every { light.stop() } throws RuntimeException("flaky")

        val s = sampler(light, motion)
        s.start()
        s.stop()  // must not throw

        verify(exactly = 1) { light.stop() }
        verify(exactly = 1) { motion.stop() }
    }

    @Test
    fun `double start is a no-op`() {
        // Medium #2 — the idempotency guard pins the lifecycle to
        // start→stop pairs. A double-start wouldn't physically register
        // twice (SensorManager de-dupes) but a subsequent single stop
        // paired against the "second" conceptual start would leave the
        // detector listening past the session; the AtomicBoolean closes
        // that window by making the second start a hard no-op.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)

        val s = sampler(light, motion)
        s.start()
        s.start()

        verify(exactly = 1) { light.start(handler) }
        verify(exactly = 1) { motion.start(handler) }
    }

    @Test
    fun `stop before start is a no-op`() {
        // Prevents a bogus `unregisterListener` call against a listener
        // that was never registered. Harmless on current Android but
        // reads as a bug in a crash trace.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)

        sampler(light, motion).stop()

        verify(exactly = 0) { light.stop() }
        verify(exactly = 0) { motion.stop() }
    }

    @Test
    fun `double stop is a no-op`() {
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)

        val s = sampler(light, motion)
        s.start()
        s.stop()
        s.stop()

        verify(exactly = 1) { light.stop() }
        verify(exactly = 1) { motion.stop() }
    }

    @Test
    fun `start-stop-start cycle re-registers`() {
        // Legitimate lifecycle: a caller that re-uses the sampler for
        // a second session must get both sensors re-registered. If the
        // AtomicBoolean guard were a one-shot latch (set-and-stay) this
        // test would catch the regression.
        val light = mockk<LightSensor>(relaxed = true)
        val motion = mockk<MotionDetector>(relaxed = true)

        val s = sampler(light, motion)
        s.start()
        s.stop()
        s.start()

        verify(exactly = 2) { light.start(handler) }
        verify(exactly = 2) { motion.start(handler) }
        verify(exactly = 1) { light.stop() }
        verify(exactly = 1) { motion.stop() }
    }

    @Test
    fun `public constructor pins to applicationContext`() {
        // Medium #1 — Activity-leak defense. If a future caller
        // constructs the sampler from an Activity instead of going
        // through CDLSession, the sampler itself must still normalise
        // to applicationContext so the retained SensorManager-owning
        // reference doesn't pin the Activity's view hierarchy.
        //
        // Verification strategy: the activity-like mock has NO stub for
        // getSystemService, so if the sampler called it on the activity
        // mockk would raise MockKException. Stubbing applicationContext
        // → appCtx and only stubbing getSystemService on appCtx proves
        // the lookup went through the normalised context.
        val sm = mockk<SensorManager>(relaxed = true)
        every { sm.getDefaultSensor(any()) } returns null
        val appCtx = mockk<Context>()
        every { appCtx.getSystemService(Context.SENSOR_SERVICE) } returns sm
        val activityCtx = mockk<Context>()
        every { activityCtx.applicationContext } returns appCtx

        // Must not throw — if the sampler reached for activityCtx.getSystemService
        // (which has no stub) mockk would raise.
        val s = AmbientConditionsSampler(activityCtx)
        s.start()
        s.stop()

        verify { activityCtx.applicationContext }
        verify { appCtx.getSystemService(Context.SENSOR_SERVICE) }
    }

    @Test
    fun `public constructor falls back when applicationContext is null`() {
        // Edge case: unit-test harnesses sometimes hand a bare Context
        // whose applicationContext field is null (e.g., Robolectric-light
        // fakes). The `?: context` fallback keeps construction working
        // against the original reference rather than NPE'ing before the
        // sampler is even built.
        val sm = mockk<SensorManager>(relaxed = true)
        every { sm.getDefaultSensor(any()) } returns null
        val bareCtx = mockk<Context>()
        every { bareCtx.applicationContext } returns null
        every { bareCtx.getSystemService(Context.SENSOR_SERVICE) } returns sm

        // Must not throw.
        AmbientConditionsSampler(bareCtx)

        verify { bareCtx.getSystemService(Context.SENSOR_SERVICE) }
    }
}
