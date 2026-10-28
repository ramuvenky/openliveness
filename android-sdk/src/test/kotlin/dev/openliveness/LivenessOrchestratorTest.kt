package dev.openliveness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Phase 3 Step 3 — orchestrator unit tests. The weighted scoring + the
 * hard-challenge rule + the IAL2 gate are the three invariants the
 * server also enforces; drift here = server rejects attestation.
 * Mirrors ios-sdk/Tests/LivenessOrchestratorTests.swift so a one-sided
 * numeric change trips its pair test rather than divergent server-side
 * scoring.
 */
class LivenessOrchestratorTest {

    private fun fullPass() = LayerScores(
        layer2ChallengeCompleted = true,
        layer2ChallengeScore = 1.0f,
        layer3PulseDetected = true,
        layer3BpmInRange = true,
        layer3RppgConfidence = 0.9f,
        layer4LbpScore = 0.9f,
        layer4FourierScore = 0.9f,
        layer5BehavioralScore = 0.9f
    )

    // MARK: - Hard rule

    @Test
    fun `missed challenge forces fail regardless of other scores`() {
        // Spec §1351 — a false pass here would mean a static image that
        // passes texture + fakes rPPG could outscore a missing challenge.
        val s = fullPass().copy(layer2ChallengeCompleted = false)
        val r = LivenessOrchestrator().orchestrate(
            s, AmbientConditions(), hardwareAttestationVerified = true
        )
        assertEquals("fail", r.decision)
        assertEquals("software_only", r.assuranceLevel)
    }

    // MARK: - Decision thresholds

    @Test
    fun `weighted score above pass threshold is pass`() {
        // All scores at 0.80 → weighted ≈ 0.80, clearly above the 0.75
        // boundary. We avoid putting the test exactly on 0.75 because
        // Float32 rounding on five multiply-adds can land either side
        // of the threshold, which would flake across platforms.
        val s = fullPass().copy(
            layer2ChallengeScore = 0.80f,
            layer3RppgConfidence = 0.80f,
            layer4LbpScore = 0.80f,
            layer4FourierScore = 0.80f,
            layer5BehavioralScore = 0.80f
        )
        val r = LivenessOrchestrator().orchestrate(s, AmbientConditions(), hardwareAttestationVerified = true)
        assertEquals("pass", r.decision)
        assertEquals("IAL2", r.assuranceLevel)
    }

    @Test
    fun `weighted score in inconclusive band`() {
        // All scores at 0.65 → weighted ≈ 0.65, safely in the
        // [0.60, 0.75) inconclusive band with margin on both sides.
        val s = fullPass().copy(
            layer2ChallengeScore = 0.65f,
            layer3RppgConfidence = 0.65f,
            layer4LbpScore = 0.65f,
            layer4FourierScore = 0.65f,
            layer5BehavioralScore = 0.65f
        )
        val r = LivenessOrchestrator().orchestrate(s, AmbientConditions())
        assertEquals("inconclusive", r.decision)
    }

    @Test
    fun `weighted score below inconclusive fails`() {
        val s = LayerScores(
            layer2ChallengeCompleted = true,
            layer2ChallengeScore = 0.0f,
            layer3RppgConfidence = 0.1f,
            layer4LbpScore = 0.1f,
            layer4FourierScore = 0.1f,
            layer5BehavioralScore = 0.1f
        )
        val r = LivenessOrchestrator().orchestrate(s, AmbientConditions())
        assertEquals("fail", r.decision)
    }

    // MARK: - Ambient reweighting

    @Test
    fun `low light shifts weights from rPPG to challenge and behavioral`() {
        val w = LivenessOrchestrator.weightsFor(AmbientConditions(
            lowLightDetected = true, motionDetected = false, frontCameraConfirmed = true
        ))
        assertEquals(0.35f, w.layer2)
        assertEquals(0.15f, w.layer3)
        assertEquals(0.15f, w.layer5)
        assertEquals(0.20f, w.layer4Lbp)
        assertEquals(0.15f, w.layer4Fourier)
    }

    @Test
    fun `motion shifts weight from behavioral to rPPG`() {
        val w = LivenessOrchestrator.weightsFor(AmbientConditions(
            lowLightDetected = false, motionDetected = true, frontCameraConfirmed = true
        ))
        assertEquals(0.05f, w.layer5)
        assertEquals(0.30f, w.layer3)
        assertEquals(0.30f, w.layer2)
    }

    @Test
    fun `both ambient flags stack - low light then motion`() {
        // Low light sets l5=0.15, l3=0.15, l2=0.35; motion then drains
        // (0.15 - 0.05) = 0.10 from l5 into l3 (C-2), ending at
        // l5=0.05, l3=0.25, l2=0.35. Server mirrors this exact order.
        val w = LivenessOrchestrator.weightsFor(AmbientConditions(
            lowLightDetected = true, motionDetected = true, frontCameraConfirmed = true
        ))
        assertEquals(0.05f, w.layer5)
        assertEquals(0.25f, w.layer3)
        assertEquals(0.35f, w.layer2)
    }

    // C-2: every weight permutation must sum to 1.0. The old `motion`
    // branch hard-coded l3=0.30, which only summed on top of the
    // default weights; stacked on low-light (l5=0.15) it produced a
    // sum of 1.05, meaning a `both=true` session's weighted score
    // could exceed 1.0 and shift all thresholds up with it.
    @Test
    fun `weight sets sum to 1_0 across all ambient permutations`() {
        for (lowLight in listOf(false, true)) {
            for (motion in listOf(false, true)) {
                val w = LivenessOrchestrator.weightsFor(AmbientConditions(
                    lowLightDetected = lowLight,
                    motionDetected = motion,
                    frontCameraConfirmed = true
                ))
                val sum = w.layer2 + w.layer3 + w.layer4Lbp + w.layer4Fourier + w.layer5
                assertEquals(
                    1.0f, sum, 1e-6f,
                    "lowLight=$lowLight motion=$motion sum=$sum"
                )
            }
        }
    }

    // MARK: - IAL2 gating

    @Test
    fun `hardware attestation verified pass yields IAL2`() {
        val r = LivenessOrchestrator().orchestrate(
            fullPass(), AmbientConditions(), hardwareAttestationVerified = true
        )
        assertEquals("pass", r.decision)
        assertEquals("IAL2", r.assuranceLevel)
    }

    @Test
    fun `pass without hardware attestation downgrades to software_only`() {
        val r = LivenessOrchestrator().orchestrate(
            fullPass(), AmbientConditions(), hardwareAttestationVerified = false
        )
        assertEquals("pass", r.decision)
        assertEquals("software_only", r.assuranceLevel)
    }

    @Test
    fun `IAL2 never assigned on non-pass`() {
        // Decision "inconclusive" with hw attestation present must NOT
        // light up IAL2 — the attestation only strengthens an already-
        // passing liveness result.
        val s = fullPass().copy(
            layer2ChallengeScore = 0.65f,
            layer3RppgConfidence = 0.65f,
            layer4LbpScore = 0.65f,
            layer4FourierScore = 0.65f,
            layer5BehavioralScore = 0.65f
        )
        val r = LivenessOrchestrator().orchestrate(
            s, AmbientConditions(), hardwareAttestationVerified = true
        )
        assertEquals("inconclusive", r.decision)
        assertEquals("software_only", r.assuranceLevel)
    }

    @Test
    fun `orchestrator does not emit IAL1`() {
        // Spec §1680: v1.0 orchestrator must never produce IAL1 — only
        // "IAL2" or "software_only". Guards against a future refactor
        // that forgets the forward-compat note.
        val ambients = listOf(
            AmbientConditions(),
            AmbientConditions(lowLightDetected = true, motionDetected = false, frontCameraConfirmed = true),
            AmbientConditions(lowLightDetected = false, motionDetected = true, frontCameraConfirmed = true)
        )
        for (amb in ambients) {
            for (hw in listOf(true, false)) {
                val r = LivenessOrchestrator().orchestrate(fullPass(), amb, hw)
                assertNotEquals("IAL1", r.assuranceLevel)
            }
        }
    }
}
