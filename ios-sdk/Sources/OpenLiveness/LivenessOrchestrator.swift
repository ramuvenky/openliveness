import Foundation

/// Combines the five layer scores into the single `liveness_decision`
/// that goes into the attestation. Weights, ambient-condition reweighting,
/// abstention renormalization, and decision thresholds here are the
/// on-device mirror of Spec §13 (lines ~1619-1671). The relay accepts
/// the SDK's decision without recomputing it (see
/// `relay/src/attestation/verify.ts:checkAssurance`), so the Kotlin +
/// Python mirrors matter for cross-platform consistency and the
/// benchmark harness's `combined` scorer, not for an immediate server
/// round-trip.
public final class LivenessOrchestrator {

    public struct Weights: Equatable {
        public let layer2: Float
        public let layer3: Float
        public let layer4Lbp: Float
        public let layer4Fourier: Float
        public let layer5: Float
    }

    /// Calibrated 2026-11 (v0.2.0) after the on-device regression where
    /// honest Layer-4 outputs landed live users at ~0.63 even after
    /// preprocessing alignment. Two shifts from v0.1.0:
    ///
    ///   - Fourier dropped to 0 — the pure-math screen-replay detector
    ///     uses ring frequencies calibrated against 2015-era MSU-MFSD
    ///     cameras. On modern phones (1080p+ sensors downsampled to 80×80
    ///     crops) fine skin detail lands in the same ring the detector
    ///     reads as screen pixel pitch, producing ~0.15 on real faces.
    ///     Weighting it toward live detection was actively harmful.
    ///   - LBP weight reduced 0.25 → 0.15 — retrained model card reports
    ///     HTER=0.293 on MSU-MFSD test with optimal threshold 0.358 (< 0.5).
    ///     The model is systematically low-scoring; L2 (challenge) and L5
    ///     (behavioral) carry the bulk of the sum.
    ///
    /// Drained mass (0.10 Fourier + 0.10 LBP) redistributed mostly to L5
    /// (+0.15, behavioral is the second-strongest spoof defense after L2)
    /// with a smaller L2 bump (+0.05). Specifically balanced so
    /// `L2=0.9 + L5=0.0 + abstained L3` fails cleanly: pure photo attacks
    /// that defeat the challenge but have no micro-saccade still land
    /// below INCONCLUSIVE_THRESHOLD=0.60. L3 held at 0.10. Sum = 1.00;
    /// every ambient permutation below also sums to 1.00. The Kotlin
    /// and Python mirrors follow these numbers in lockstep.
    public static let defaultWeights = Weights(
        layer2: 0.50, layer3: 0.10, layer4Lbp: 0.15, layer4Fourier: 0.00, layer5: 0.25
    )

    public static let passThreshold: Float = 0.75
    public static let inconclusiveThreshold: Float = 0.60

    /// Floor below which a Layer-3 confidence is treated as an abstention
    /// rather than a 0 vote. Keeps the weighted sum from being dragged
    /// down by POS-rPPG's expected "insufficient samples" outcome on
    /// short (~10s) capture windows. Must match Kotlin LAYER3_ABSTAIN_EPS
    /// and Python LAYER3_ABSTAIN_EPS exactly.
    private static let layer3AbstainEps: Float = 1e-6

    public init() {}

    /// Pick a weight set based on ambient conditions. Low light shifts
    /// 0.05 off L4 LBP (texture noisier under poor illumination) into L5
    /// (behavioral micro-movement is ambient-invariant); L3 is already
    /// floored at 0.10 by the default so low-light doesn't cut it further.
    /// Motion transfers `(l5 - 0.05)` into l3 — behavioral is contaminated
    /// by jitter, L3's finite integration window still averages the pulse.
    /// low_light is evaluated first; motion stacks on top. The Kotlin
    /// and Python mirrors follow this exact sequential order.
    ///
    /// Permutation sums (all must equal 1.00):
    ///   default      : 0.50 + 0.10 + 0.15 + 0.00 + 0.25 = 1.00
    ///   lowLight     : 0.50 + 0.10 + 0.10 + 0.00 + 0.30 = 1.00
    ///   motion       : 0.50 + 0.30 + 0.15 + 0.00 + 0.05 = 1.00
    ///   lowLight+mot : 0.50 + 0.35 + 0.10 + 0.00 + 0.05 = 1.00
    public static func weights(for ambient: AmbientConditions) -> Weights {
        var l2 = defaultWeights.layer2
        var l3 = defaultWeights.layer3
        var l4Lbp = defaultWeights.layer4Lbp
        let l4F = defaultWeights.layer4Fourier
        var l5 = defaultWeights.layer5

        if ambient.lowLightDetected {
            l4Lbp = 0.10
            l5 = 0.30
        }
        if ambient.motionDetected {
            // C-2: derive the transfer from the l5 drain, not a hardcoded
            // l3. Keeps sum(w)=1.0 across all four lowLight/motion
            // permutations, which the Kotlin/Python mirrors assume.
            let transferred = l5 - 0.05
            l5 = 0.05
            l3 += transferred
        }
        return Weights(layer2: l2, layer3: l3, layer4Lbp: l4Lbp, layer4Fourier: l4F, layer5: l5)
    }

    /// Layer-3 abstention: the on-device Layer3RPPG returns no result
    /// when it can't lock a pulse (MIN_SAMPLES_NORMAL not met, zero-mean
    /// / zero-std guards, SNR under threshold); the session publishes
    /// confidence=0. Keyed on confidence alone so the Python mirror
    /// (whose LayerScores doesn't carry pulse/bpmInRange booleans) can
    /// apply the exact same test. The attestation JSON doesn't carry
    /// those booleans either, so dropping the boolean co-check loses
    /// no information on the honest path.
    public static func isLayer3Abstaining(_ scores: LayerScores) -> Bool {
        return scores.layer3RppgConfidence < layer3AbstainEps
    }

    /// The weights actually applied to the weighted sum: ambient-adjusted
    /// base weights, then — if Layer 3 is abstaining — the L3 slot zeroed
    /// and the remaining four renormalized so they sum to 1.0. Exposed so
    /// parity tests (and future server-side mirrors) can reproduce the
    /// exact weight set the mobile SDK used, without duplicating the
    /// renormalization math.
    public static func effectiveWeights(
        _ scores: LayerScores,
        _ ambient: AmbientConditions
    ) -> Weights {
        let base = weights(for: ambient)
        if !isLayer3Abstaining(scores) { return base }
        let remaining = base.layer2 + base.layer4Lbp + base.layer4Fourier + base.layer5
        return Weights(
            layer2: base.layer2 / remaining,
            layer3: 0,
            layer4Lbp: base.layer4Lbp / remaining,
            layer4Fourier: base.layer4Fourier / remaining,
            layer5: base.layer5 / remaining
        )
    }

    /// Hard rule from Spec §1351-1352 and §1653-1655: a missed challenge
    /// means fail with score 0 — the other layer scores are discarded,
    /// not weighted-in. Without this, a static image that happens to
    /// pass texture + fake rPPG could outscore the missing challenge.
    public func orchestrate(
        layerScores: LayerScores,
        ambientConditions: AmbientConditions,
        hardwareAttestationVerified: Bool = false
    ) -> LivenessResult {
        if !layerScores.layer2ChallengeCompleted {
            return LivenessResult(
                decision: "fail",
                assuranceLevel: "software_only",
                layerScores: layerScores
            )
        }

        let w = LivenessOrchestrator.effectiveWeights(layerScores, ambientConditions)
        let weighted =
            layerScores.layer2ChallengeScore  * w.layer2 +
            layerScores.layer3RppgConfidence  * w.layer3 +
            layerScores.layer4LbpScore        * w.layer4Lbp +
            layerScores.layer4FourierScore    * w.layer4Fourier +
            layerScores.layer5BehavioralScore * w.layer5

        let decision: String
        if weighted >= LivenessOrchestrator.passThreshold {
            decision = "pass"
        } else if weighted >= LivenessOrchestrator.inconclusiveThreshold {
            decision = "inconclusive"
        } else {
            decision = "fail"
        }

        // Spec §1673-1681: IAL2 only when hardware attestation verified
        // AND the decision is "pass". Anything else — including a pass
        // without hw attestation — downgrades to software_only. We do
        // not emit "IAL1"; the schema accepts it for forward compat but
        // the v1.0 orchestrator never produces it.
        let assurance: String =
            (hardwareAttestationVerified && decision == "pass") ? "IAL2" : "software_only"

        return LivenessResult(
            decision: decision,
            assuranceLevel: assurance,
            layerScores: layerScores
        )
    }
}
