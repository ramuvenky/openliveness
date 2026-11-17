import XCTest
@testable import OpenLiveness

/// Phase 3 Step 3 — orchestrator unit tests. The weighted scoring + the
/// hard-challenge rule + the IAL2 gate are the three invariants the
/// server also enforces; drift here = server rejects attestation. The
/// sibling file android-sdk/.../LivenessOrchestratorTest.kt mirrors
/// each case so a one-sided numeric change trips its pair test.
final class LivenessOrchestratorTests: XCTestCase {

    /// A fully-passing score vector before any field is tweaked per-test.
    private func fullPass() -> LayerScores {
        var s = LayerScores()
        s.layer2ChallengeCompleted = true
        s.layer2ChallengeScore = 1.0
        s.layer3PulseDetected = true
        s.layer3BpmInRange = true
        s.layer3RppgConfidence = 0.9
        s.layer4LbpScore = 0.9
        s.layer4FourierScore = 0.9
        s.layer5BehavioralScore = 0.9
        return s
    }

    // MARK: - Hard rule

    func testMissedChallengeForcesFailRegardlessOfOtherScores() {
        // Spec §1351 — a false pass here would mean a static image that
        // passes texture + fakes rPPG could outscore a missing challenge.
        var s = fullPass()
        s.layer2ChallengeCompleted = false
        let r = LivenessOrchestrator().orchestrate(
            layerScores: s,
            ambientConditions: AmbientConditions(),
            hardwareAttestationVerified: true
        )
        XCTAssertEqual(r.decision, "fail")
        XCTAssertEqual(r.assuranceLevel, "software_only")
    }

    // MARK: - Decision thresholds

    func testWeightedScoreAbovePassThresholdIsPass() {
        // All scores at 0.80 → weighted ≈ 0.80, clearly above the 0.75
        // boundary. We avoid putting the test exactly on 0.75 because
        // Float32 rounding on five multiply-adds can land either side
        // of the threshold, which would flake across platforms.
        var s = fullPass()
        s.layer2ChallengeScore = 0.80
        s.layer3RppgConfidence = 0.80
        s.layer4LbpScore = 0.80
        s.layer4FourierScore = 0.80
        s.layer5BehavioralScore = 0.80
        let r = LivenessOrchestrator().orchestrate(
            layerScores: s, ambientConditions: AmbientConditions(), hardwareAttestationVerified: true
        )
        XCTAssertEqual(r.decision, "pass")
        XCTAssertEqual(r.assuranceLevel, "IAL2")
    }

    func testWeightedScoreInconclusiveBand() {
        // All scores at 0.65 → weighted ≈ 0.65, safely in the
        // [0.60, 0.75) inconclusive band with margin on both sides.
        var s = fullPass()
        s.layer2ChallengeScore = 0.65
        s.layer3RppgConfidence = 0.65
        s.layer4LbpScore = 0.65
        s.layer4FourierScore = 0.65
        s.layer5BehavioralScore = 0.65
        let r = LivenessOrchestrator().orchestrate(
            layerScores: s, ambientConditions: AmbientConditions()
        )
        XCTAssertEqual(r.decision, "inconclusive")
    }

    func testWeightedScoreBelowInconclusiveFails() {
        var s = LayerScores()
        s.layer2ChallengeCompleted = true
        s.layer2ChallengeScore = 0.0 // completed but slow (shouldn't happen but tests the math)
        s.layer3RppgConfidence = 0.1
        s.layer4LbpScore = 0.1
        s.layer4FourierScore = 0.1
        s.layer5BehavioralScore = 0.1
        let r = LivenessOrchestrator().orchestrate(
            layerScores: s, ambientConditions: AmbientConditions()
        )
        XCTAssertEqual(r.decision, "fail")
    }

    // MARK: - Ambient reweighting

    // Float32 tolerance for weight assertions: motion branch computes
    // l3 += (l5 - 0.05), and the subtract-then-add path can drift the
    // result by one ULP across platforms. The Kotlin mirror uses the
    // same tolerance for the same reason.
    private let weightEps: Float = 1e-6

    func testLowLightShiftsWeightFromLBPIntoBehavioral() {
        // v0.2.0 calibration: L3 is already floored at 0.10 by the
        // default weights, so low-light no longer cuts L3 further.
        // Instead it shifts 0.05 off L4 LBP (texture noisier under poor
        // illumination) into L5 (behavioral micro-movement is ambient-
        // invariant). L2 stays at its default; L4 Fourier is 0 in v0.2.0.
        let w = LivenessOrchestrator.weights(for: AmbientConditions(
            lowLightDetected: true, motionDetected: false, frontCameraConfirmed: true
        ))
        XCTAssertEqual(w.layer2, 0.50, accuracy: weightEps)
        XCTAssertEqual(w.layer3, 0.10, accuracy: weightEps)
        XCTAssertEqual(w.layer4Lbp, 0.10, accuracy: weightEps)
        XCTAssertEqual(w.layer4Fourier, 0.00, accuracy: weightEps)
        XCTAssertEqual(w.layer5, 0.30, accuracy: weightEps)
    }

    func testMotionShiftsWeightFromBehavioralToRppg() {
        // Motion transfer: (l5 - 0.05) drains into l3. From v0.2.0 default
        // (l5=0.25), transferred=0.20 → l5=0.05, l3=0.10+0.20=0.30.
        let w = LivenessOrchestrator.weights(for: AmbientConditions(
            lowLightDetected: false, motionDetected: true, frontCameraConfirmed: true
        ))
        XCTAssertEqual(w.layer2, 0.50, accuracy: weightEps)
        XCTAssertEqual(w.layer3, 0.30, accuracy: weightEps)
        XCTAssertEqual(w.layer4Lbp, 0.15, accuracy: weightEps)
        XCTAssertEqual(w.layer4Fourier, 0.00, accuracy: weightEps)
        XCTAssertEqual(w.layer5, 0.05, accuracy: weightEps)
    }

    func testBothAmbientFlagsStackLowLightThenMotion() {
        // Low light sets l4Lbp=0.10, l5=0.30 (l3 stays 0.10, l2 stays 0.50);
        // motion then drains (0.30 - 0.05) = 0.25 from l5 into l3 (C-2),
        // ending at l5=0.05, l3=0.35, l2=0.50, l4Lbp=0.10, l4F=0.00.
        // Sum = 1.00. Kotlin/Python mirror this exact sequential order.
        let w = LivenessOrchestrator.weights(for: AmbientConditions(
            lowLightDetected: true, motionDetected: true, frontCameraConfirmed: true
        ))
        XCTAssertEqual(w.layer2, 0.50, accuracy: weightEps)
        XCTAssertEqual(w.layer3, 0.35, accuracy: weightEps)
        XCTAssertEqual(w.layer4Lbp, 0.10, accuracy: weightEps)
        XCTAssertEqual(w.layer4Fourier, 0.00, accuracy: weightEps)
        XCTAssertEqual(w.layer5, 0.05, accuracy: weightEps)
    }

    // MARK: - Layer 3 abstention renormalization

    func testLayer3AbstentionWithStrongOthersPasses() {
        // Post-2026-11 calibration: L3 confidence < 1e-6 drops its weight
        // and renormalizes the remaining four to sum to 1.0. Strong
        // signals on L2+L4+L5 carry the session rather than being dragged
        // below 0.60 by a 0-confidence vote on a signal that simply
        // wasn't measurable.
        var s = fullPass()
        s.layer2ChallengeScore = 0.9
        s.layer3PulseDetected = false
        s.layer3BpmInRange = false
        s.layer3RppgConfidence = 0.0
        s.layer4LbpScore = 0.9
        s.layer4FourierScore = 0.9
        s.layer5BehavioralScore = 0.9
        let r = LivenessOrchestrator().orchestrate(
            layerScores: s, ambientConditions: AmbientConditions(),
            hardwareAttestationVerified: true
        )
        XCTAssertEqual(r.decision, "pass")
        XCTAssertEqual(r.assuranceLevel, "IAL2")
    }

    func testLayer3AbstentionHonestPhotoAttackFails() {
        // Companion to the live-user test. Same L2=0.9 + L3 abstention,
        // but honest low Layer-4 + no micro-saccade (L5=0.0) must still
        // fail. The v0.2.0 reweight (drained Fourier/LBP mass into L5)
        // was specifically balanced so L2 alone can't carry the sum
        // above INCONCLUSIVE=0.60 when L5 is flat:
        //   remaining = 0.50 + 0.15 + 0.00 + 0.25 = 0.90
        //   weighted  = 0.9*(0.50/0.90) + 0.15*(0.15/0.90) + 0 + 0
        //             = 0.500 + 0.025 = 0.525 → fail
        var s = LayerScores()
        s.layer2ChallengeCompleted = true
        s.layer2ChallengeScore = 0.9
        s.layer3RppgConfidence = 0.0
        s.layer4LbpScore = 0.15
        s.layer4FourierScore = 0.0
        s.layer5BehavioralScore = 0.0
        let r = LivenessOrchestrator().orchestrate(
            layerScores: s, ambientConditions: AmbientConditions()
        )
        XCTAssertEqual(r.decision, "fail")
    }

    // C-2: every weight permutation must sum to 1.0. The old `motion`
    // branch hard-coded l3=0.30, which only summed on top of the
    // default weights; stacked on low-light (l5=0.15) it produced a
    // sum of 1.05 — a `both=true` session's weighted score could
    // exceed 1.0 and shift all thresholds up with it.
    func testWeightSetsSumTo1AcrossAllAmbientPermutations() {
        for lowLight in [false, true] {
            for motion in [false, true] {
                let w = LivenessOrchestrator.weights(for: AmbientConditions(
                    lowLightDetected: lowLight,
                    motionDetected: motion,
                    frontCameraConfirmed: true
                ))
                let sum = w.layer2 + w.layer3 + w.layer4Lbp + w.layer4Fourier + w.layer5
                XCTAssertEqual(
                    sum, 1.0, accuracy: 1e-6,
                    "lowLight=\(lowLight) motion=\(motion) sum=\(sum)"
                )
            }
        }
    }

    // MARK: - IAL2 gating

    func testHardwareAttestationVerifiedPassYieldsIAL2() {
        let r = LivenessOrchestrator().orchestrate(
            layerScores: fullPass(),
            ambientConditions: AmbientConditions(),
            hardwareAttestationVerified: true
        )
        XCTAssertEqual(r.decision, "pass")
        XCTAssertEqual(r.assuranceLevel, "IAL2")
    }

    func testPassWithoutHardwareAttestationDowngradesToSoftwareOnly() {
        // Spec §1677: no hw attestation + pass → software_only.
        let r = LivenessOrchestrator().orchestrate(
            layerScores: fullPass(),
            ambientConditions: AmbientConditions(),
            hardwareAttestationVerified: false
        )
        XCTAssertEqual(r.decision, "pass")
        XCTAssertEqual(r.assuranceLevel, "software_only")
    }

    func testIAL2NeverAssignedOnNonPass() {
        // Decision "inconclusive" with hw attestation present must NOT
        // light up IAL2 — the attestation only strengthens an already-
        // passing liveness result.
        var s = fullPass()
        s.layer2ChallengeScore = 0.65
        s.layer3RppgConfidence = 0.65
        s.layer4LbpScore = 0.65
        s.layer4FourierScore = 0.65
        s.layer5BehavioralScore = 0.65
        let r = LivenessOrchestrator().orchestrate(
            layerScores: s,
            ambientConditions: AmbientConditions(),
            hardwareAttestationVerified: true
        )
        XCTAssertEqual(r.decision, "inconclusive")
        XCTAssertEqual(r.assuranceLevel, "software_only")
    }

    func testOrchestratorDoesNotEmitIAL1() {
        // Spec §1680: v1.0 orchestrator must never produce IAL1 — only
        // "IAL2" or "software_only". Guards against a future refactor
        // that forgets the forward-compat note.
        for ambient in [AmbientConditions(),
                        AmbientConditions(lowLightDetected: true, motionDetected: false, frontCameraConfirmed: true),
                        AmbientConditions(lowLightDetected: false, motionDetected: true, frontCameraConfirmed: true)] {
            for hw in [true, false] {
                let r = LivenessOrchestrator().orchestrate(
                    layerScores: fullPass(),
                    ambientConditions: ambient,
                    hardwareAttestationVerified: hw
                )
                XCTAssertNotEqual(r.assuranceLevel, "IAL1")
            }
        }
    }
}
