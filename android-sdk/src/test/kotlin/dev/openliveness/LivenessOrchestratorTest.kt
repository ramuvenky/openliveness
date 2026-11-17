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

    // Float32 tolerance for weight assertions: motion branch computes
    // l3 += (l5 - 0.05f), and the subtract-then-add path can drift the
    // result by one ULP (e.g. 0.10f + (0.15f - 0.05f) → 0.20000002f).
    // Swift/Python mirrors do the same math in their own float widths,
    // so a one-ULP tolerance is the honest cross-platform assertion.
    private val WEIGHT_EPS = 1e-6f

    @Test
    fun `low light shifts weight from LBP into behavioral`() {
        // v0.2.0 calibration: L3 is already floored at 0.10 by the
        // default weights (POS-rPPG AUC 0.484), so low-light no longer
        // cuts L3 further. Instead it shifts 0.05 off L4 LBP (texture
        // noisier under poor illumination) into L5 (behavioral micro-
        // movement is ambient-invariant). L2 stays at its default; L4
        // Fourier was dropped to 0 in v0.2.0 (see DEFAULT_WEIGHTS).
        val w = LivenessOrchestrator.weightsFor(AmbientConditions(
            lowLightDetected = true, motionDetected = false, frontCameraConfirmed = true
        ))
        assertEquals(0.50f, w.layer2, WEIGHT_EPS)
        assertEquals(0.10f, w.layer3, WEIGHT_EPS)
        assertEquals(0.10f, w.layer4Lbp, WEIGHT_EPS)
        assertEquals(0.00f, w.layer4Fourier, WEIGHT_EPS)
        assertEquals(0.30f, w.layer5, WEIGHT_EPS)
    }

    @Test
    fun `motion shifts weight from behavioral to rPPG`() {
        // Motion transfer: (l5 - 0.05) drains into l3. From v0.2.0 default
        // (l5=0.25), transferred=0.20 → l5=0.05, l3=0.10+0.20=0.30.
        val w = LivenessOrchestrator.weightsFor(AmbientConditions(
            lowLightDetected = false, motionDetected = true, frontCameraConfirmed = true
        ))
        assertEquals(0.50f, w.layer2, WEIGHT_EPS)
        assertEquals(0.30f, w.layer3, WEIGHT_EPS)
        assertEquals(0.15f, w.layer4Lbp, WEIGHT_EPS)
        assertEquals(0.00f, w.layer4Fourier, WEIGHT_EPS)
        assertEquals(0.05f, w.layer5, WEIGHT_EPS)
    }

    @Test
    fun `both ambient flags stack - low light then motion`() {
        // Low light sets l4Lbp=0.10, l5=0.30 (l3 stays 0.10, l2 stays 0.50);
        // motion then drains (0.30 - 0.05) = 0.25 from l5 into l3 (C-2),
        // ending at l5=0.05, l3=0.35, l2=0.50, l4Lbp=0.10, l4F=0.00.
        // Sum = 1.00. Swift/Python mirror this exact sequential order.
        val w = LivenessOrchestrator.weightsFor(AmbientConditions(
            lowLightDetected = true, motionDetected = true, frontCameraConfirmed = true
        ))
        assertEquals(0.50f, w.layer2, WEIGHT_EPS)
        assertEquals(0.35f, w.layer3, WEIGHT_EPS)
        assertEquals(0.10f, w.layer4Lbp, WEIGHT_EPS)
        assertEquals(0.00f, w.layer4Fourier, WEIGHT_EPS)
        assertEquals(0.05f, w.layer5, WEIGHT_EPS)
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

    // MARK: - Layer 3 abstention path

    // Phase 4 wired real Layer3RPPG into CDLSession. When compute() returns
    // null (insufficient samples / zero-mean / zero-std guards), CDLSession
    // publishes confidence=0 + both flags=false. Post-2026-11 calibration,
    // the orchestrator detects this as an abstention and renormalizes the
    // remaining four weights to sum to 1.0, so the other layers carry the
    // session at full weight rather than being dragged down by a 0-confidence
    // vote on a signal that simply wasn't measurable.

    @Test
    fun `layer3 abstention with strong others passes under renormalization`() {
        // v0.2.0 calibration: when L3 abstains (null compute →
        // !pulse && !bpmInRange && confidence≈0), its weight is dropped
        // and the remaining four weights renormalize to sum to 1.0.
        // Strong signals on L2+L4+L5 therefore carry the session rather
        // than being dragged below 0.60 by a 0-confidence vote on a
        // signal that simply wasn't measurable. Arithmetic:
        //   remaining = 0.50 + 0.15 + 0.00 + 0.25 = 0.90
        //   weighted  = 0.9 * (0.50+0.15+0.00+0.25)/0.90 = 0.9 → pass
        // Previously (Phase 4 Step 5) this test asserted "inconclusive"
        // under the old weights where L3=0.25 and no renormalization;
        // it flipped to "pass" the same PR the calibration shipped.
        val s = fullPass().copy(
            layer2ChallengeScore = 0.9f,
            layer3PulseDetected = false,
            layer3BpmInRange = false,
            layer3RppgConfidence = 0f,
            layer4LbpScore = 0.9f,
            layer4FourierScore = 0.9f,
            layer5BehavioralScore = 0.9f,
        )
        val r = LivenessOrchestrator().orchestrate(
            s, AmbientConditions(), hardwareAttestationVerified = true
        )
        assertEquals("pass", r.decision)
        assertEquals("IAL2", r.assuranceLevel)
    }

    @Test
    fun `layer3 compute failure under low light still passes with strong others`() {
        // Low-light weight set + L3 abstention renormalization under v0.2.0.
        // Under low light: L2=0.50, L3=0.10, L4lbp=0.10, L4f=0.00, L5=0.30.
        // L3 abstains (confidence=0 + both flags false) so its 0.10 drops
        // and the remaining 0.90 renormalizes:
        //   L2=0.556, L4lbp=0.111, L4f=0.000, L5=0.333 (sum=1.0)
        // Weighted with all others at 0.9: 0.9 * 1.0 = 0.9 → pass. The
        // rPPG failure in a dim room does not single-handedly doom the
        // session — that's the composition of low-light reweighting +
        // abstention renorm working together.
        val s = fullPass().copy(
            layer2ChallengeScore = 0.9f,
            layer3PulseDetected = false,
            layer3BpmInRange = false,
            layer3RppgConfidence = 0f,
            layer4LbpScore = 0.9f,
            layer4FourierScore = 0.9f,
            layer5BehavioralScore = 0.9f,
        )
        val amb = AmbientConditions(
            lowLightDetected = true, motionDetected = false, frontCameraConfirmed = true
        )
        val r = LivenessOrchestrator().orchestrate(
            s, amb, hardwareAttestationVerified = true
        )
        assertEquals("pass", r.decision)
        assertEquals("IAL2", r.assuranceLevel)
    }

    @Test
    fun `layer3 booleans do not affect decision - only confidence does`() {
        // Drift guard: the orchestrator never reads the two L3 booleans
        // (pulseDetected, bpmInRange). It reads confidence into the
        // weighted sum directly and into isLayer3Abstaining; neither
        // path touches the flags. The Python mirror's LayerScores
        // doesn't even carry these fields, so a future refactor that
        // starts hard-gating on the booleans would break cross-platform
        // parity. Flip both flags across all four permutations with
        // confidence fixed at 0.9 — decision must stay identical.
        val baseline = fullPass()
        val baseR = LivenessOrchestrator().orchestrate(
            baseline, AmbientConditions(), hardwareAttestationVerified = true
        )
        for (pulse in listOf(true, false)) {
            for (inRange in listOf(true, false)) {
                val s = baseline.copy(
                    layer3PulseDetected = pulse,
                    layer3BpmInRange = inRange,
                )
                val r = LivenessOrchestrator().orchestrate(
                    s, AmbientConditions(), hardwareAttestationVerified = true
                )
                assertEquals(
                    baseR.decision, r.decision,
                    "pulse=$pulse inRange=$inRange should not shift decision"
                )
                assertEquals(baseR.assuranceLevel, r.assuranceLevel)
            }
        }
    }

    // MARK: - Sample-app Layer-3/4 wiring regression + v0.2.0 calibration
    //
    // History (preserved so the WHY of the current weights is auditable):
    //
    // Before android-sample wired Layer3RPPG + Layer4Texture into
    // MediaPipeLandmarkStream + CDLSession, the sample constructed
    // CDLSession with layer3=null / layer4=null. CDLSession then
    // overwrote only Layer 2 + Layer 5 on top of
    // AttestationBuilder.stubLayerScores(), so the attestation shipped
    // with layer3RppgConfidence = 0.85 and both Layer-4 scores = 0.85
    // regardless of what the device actually captured. A printed photo
    // moved through the challenge sequence scored weighted ≈ 0.89 and
    // was accepted (ramu, 2026-11-13).
    //
    // Wiring Layer 3/4 into the sample shipped real detector outputs to
    // the orchestrator and flipped live users to fail — stub 0.85s had
    // been masking the gap between pre-wiring thresholds and realistic
    // Layer-3/4 outputs. The v0.1.0 recalibration (L2=0.45, L3=0.10,
    // L4lbp=0.25, L4f=0.10, L5=0.10) + L3-abstention renormalization got
    // live users across, but honest Layer-4 outputs on modern phone
    // captures still landed real faces at ~0.63 after preprocessing
    // alignment. The v0.2.0 reweight shipped alongside the pad-matched
    // LBP retrain: Fourier dropped to 0 (its 2015-era ring frequencies
    // false-trigger on 1080p+ sensors downsampled to 80×80), LBP went
    // 0.25→0.15 (model HTER=0.293 means systematically low scoring),
    // drained mass redistributed to L5 (+0.15) and L2 (+0.05). Specifically
    // balanced so L2=0.9 + L5=0.0 + abstained L3 fails cleanly, protecting
    // the photo-attack path that the v0.1.0 numbers had barely preserved.
    // Thresholds stayed at 0.75 / 0.60. Swift + Python mirror these numbers.
    //
    // The two tests below pin both sides: a static 0.85 stub still
    // trivially passes (so Layer 3/4 wiring in the sample must stay
    // real, not revert to stubs), and the live-user abstention path
    // now passes without a per-threshold bump.

    @Test
    fun `sample stub layer3 and 4 trivially pass under low light - pre-wiring behavior`() {
        // The exact attestation observed on-device (ramu, 2026-11-13)
        // before the sample wired Layer 3/4. Layer 2 completed because
        // a photo moved through the gesture prompts; the 0.85 stubs on
        // Layer 3/4 carried the weighted sum above PASS_THRESHOLD. With
        // the v0.2.0 weights, under low light (l2=0.50, l3=0.10,
        // l4lbp=0.10, l4f=0.00, l5=0.30):
        //   0.9*0.50 + 0.85*0.10 + 0.85*0.10 + 0.85*0.00 + 1.0*0.30
        //   = 0.450 + 0.085 + 0.085 + 0.000 + 0.300 = 0.920 → pass
        // (abstention check is false because confidence=0.85 > EPS).
        //
        // Re-introducing this state — e.g. by reverting the sample's
        // Layer3RPPG / Layer4Texture wiring or re-defaulting layer3/
        // layer4 constructor args in CDLSession — makes a photo pass
        // again. This test staying green is the tripwire.
        val stubbed = LayerScores(
            layer2ChallengeCompleted = true,
            layer2ChallengeScore = 0.9f,
            layer3PulseDetected = true,
            layer3BpmInRange = true,
            layer3RppgConfidence = 0.85f,
            layer4LbpScore = 0.85f,
            layer4FourierScore = 0.85f,
            layer5BehavioralScore = 1.0f,
            layer5MicrosaccadeDetected = true,
        )
        val amb = AmbientConditions(
            lowLightDetected = true, motionDetected = false, frontCameraConfirmed = true
        )
        val r = LivenessOrchestrator().orchestrate(stubbed, amb)
        assertEquals(
            "pass", r.decision,
            "stub 0.85s on Layer 3/4 must weight-sum to a pass under low " +
            "light — this is the pre-wiring state that let a photo through. " +
            "If this ever fails, Layer 3/4 defaults changed; confirm the " +
            "sample integration still exercises REAL detectors, not stubs."
        )
    }

    @Test
    fun `live user with L3 abstaining and realistic L4 passes under v0_2_0 calibration`() {
        // The scenario the v0.2.0 calibration was built to fix: a real
        // user whose 10-second capture window is too short for
        // Layer3RPPG.compute() to lock a pulse (MIN_SAMPLES_NORMAL not
        // met → null → 0 confidence + both flags false), and Layer 4
        // lands at ~0.5 on a face outside the MSU-MFSD training
        // distribution. Under the new weights + abstention renorm:
        //   remaining = 0.50 + 0.15 + 0.00 + 0.25 = 0.90
        //   weighted  = 0.9*(0.50/0.90) + 0.5*(0.15/0.90)
        //             + 0.5*(0.00/0.90) + 1.0*(0.25/0.90)
        //             = 0.500 + 0.083 + 0.000 + 0.278 = 0.861 → pass
        //
        // Pre-calibration this landed at ~0.63 and failed, making live
        // users on-device unable to pass. If someone later tightens
        // weights or thresholds in a way that re-breaks this scenario,
        // this test flips to inconclusive/fail and forces the matching
        // Swift/Python mirror edit + benchmark re-run in the same PR.
        //
        // Pair tests below pin the photo-attack side: the same L2 score
        // with honest Layer 4 (low score on a printed photo) + no micro-
        // saccade still fails, so this calibration did not reopen the
        // attack path.
        val wired = LayerScores(
            layer2ChallengeCompleted = true,
            layer2ChallengeScore = 0.9f,
            layer3PulseDetected = false,
            layer3BpmInRange = false,
            layer3RppgConfidence = 0f,
            layer4LbpScore = 0.5f,
            layer4FourierScore = 0.5f,
            layer5BehavioralScore = 1.0f,
            layer5MicrosaccadeDetected = true,
        )
        val r = LivenessOrchestrator().orchestrate(wired, AmbientConditions())
        assertEquals(
            "pass", r.decision,
            "live user with L3 abstaining + realistic L4=0.5 must pass " +
            "post-calibration. If this flips back to inconclusive, the " +
            "DEFAULT_WEIGHTS or abstention renormalization changed; " +
            "confirm the photo-attack companion tests still fail."
        )
    }

    @Test
    fun `honest photo attack with L3 abstaining fails under v0_2_0 calibration`() {
        // Companion to the live-user test. Same L2=0.9 (photo defeated
        // the challenge gesture by translation), same L3 abstention,
        // but Layer 4 now reports honest low scores for a printed photo
        // (retrained LBP at HTER=0.293 is still a useful "attack" vote
        // on this corpus), and Layer 5 catches the static photo with no
        // micro-saccade signal:
        //   remaining = 0.50 + 0.15 + 0.00 + 0.25 = 0.90
        //   weighted  = 0.9*(0.50/0.90) + 0.15*(0.15/0.90)
        //             + 0.0*(0.00/0.90) + 0.0*(0.25/0.90)
        //             = 0.500 + 0.025 + 0.0 + 0.0 = 0.525 → fail
        //
        // L5=0.0 here is the load-bearing invariant the v0.2.0 reweight
        // was balanced around: drained Fourier/LBP mass went into L5
        // (+0.15) specifically so a pure photo (no micro-saccade) can't
        // ride L2=0.9 alone above INCONCLUSIVE=0.60 even with L3
        // abstaining. Pair with Python's identical test.
        val photo = LayerScores(
            layer2ChallengeCompleted = true,
            layer2ChallengeScore = 0.9f,
            layer3PulseDetected = false,
            layer3BpmInRange = false,
            layer3RppgConfidence = 0f,
            layer4LbpScore = 0.15f,
            layer4FourierScore = 0.0f,
            layer5BehavioralScore = 0.0f,
            layer5MicrosaccadeDetected = false,
        )
        val r = LivenessOrchestrator().orchestrate(photo, AmbientConditions())
        assertEquals(
            "fail", r.decision,
            "a photo defeating Layer 2 with honest low L4 + no micro-saccade " +
            "must still fail. If this flips to pass/inconclusive, the " +
            "calibration reopened the photo-attack path."
        )
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
