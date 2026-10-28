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

    func testLowLightShiftsWeightsFromRppgToChallengeAndBehavioral() {
        let w = LivenessOrchestrator.weights(for: AmbientConditions(
            lowLightDetected: true, motionDetected: false, frontCameraConfirmed: true
        ))
        XCTAssertEqual(w.layer2, 0.35)
        XCTAssertEqual(w.layer3, 0.15)
        XCTAssertEqual(w.layer5, 0.15)
        // Layer 4 weights unaffected by low light.
        XCTAssertEqual(w.layer4Lbp, 0.20)
        XCTAssertEqual(w.layer4Fourier, 0.15)
    }

    func testMotionShiftsWeightFromBehavioralToRppg() {
        let w = LivenessOrchestrator.weights(for: AmbientConditions(
            lowLightDetected: false, motionDetected: true, frontCameraConfirmed: true
        ))
        XCTAssertEqual(w.layer5, 0.05)
        XCTAssertEqual(w.layer3, 0.30)
        XCTAssertEqual(w.layer2, 0.30) // untouched
    }

    func testBothAmbientFlagsStackLowLightThenMotion() {
        // Low light sets l5=0.15, l3=0.15, l2=0.35; motion then drains
        // (0.15 - 0.05) = 0.10 from l5 into l3 (C-2), ending at
        // l5=0.05, l3=0.25, l2=0.35. Server mirrors this exact order.
        let w = LivenessOrchestrator.weights(for: AmbientConditions(
            lowLightDetected: true, motionDetected: true, frontCameraConfirmed: true
        ))
        XCTAssertEqual(w.layer5, 0.05)
        XCTAssertEqual(w.layer3, 0.25)
        XCTAssertEqual(w.layer2, 0.35)
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
