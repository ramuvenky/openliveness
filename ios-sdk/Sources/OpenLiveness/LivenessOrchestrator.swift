import Foundation

/// Combines the five layer scores into the single `liveness_decision`
/// that goes into the attestation. The weights, ambient-condition
/// reweighting, and decision thresholds here are the on-device mirror
/// of Spec §13 (lines ~1619-1671); the server trusts the SDK's output
/// but ALSO re-runs the same math from `layer_scores` before issuing a
/// JWT. Any numeric drift between this file and the Python/TypeScript
/// side causes the server to reject our attestation.
public final class LivenessOrchestrator {

    public struct Weights: Equatable {
        public let layer2: Float
        public let layer3: Float
        public let layer4Lbp: Float
        public let layer4Fourier: Float
        public let layer5: Float
    }

    /// Spec §1623-1631. These sum to 1.0; adjusted sets below do too,
    /// which keeps the weighted score comparable against the 0.75 /
    /// 0.60 thresholds without renormalization.
    public static let defaultWeights = Weights(
        layer2: 0.30, layer3: 0.25, layer4Lbp: 0.20, layer4Fourier: 0.15, layer5: 0.10
    )

    public static let passThreshold: Float = 0.75
    public static let inconclusiveThreshold: Float = 0.60

    public init() {}

    /// Pick a weight set based on ambient conditions. Spec §1633-1644:
    /// low-light cuts the rPPG weight (pulse signal is unreliable) and
    /// shifts that trust into the challenge + behavioral layers; motion
    /// cuts the behavioral weight (jitter contaminates micro-movement)
    /// and shifts to rPPG. When both are true, low_light is evaluated
    /// first and motion stacks on top of its result, matching the
    /// Python reference's sequential adjustment order so server and
    /// client never disagree on the active weights.
    public static func weights(for ambient: AmbientConditions) -> Weights {
        var l2 = defaultWeights.layer2
        var l3 = defaultWeights.layer3
        let l4Lbp = defaultWeights.layer4Lbp
        let l4F = defaultWeights.layer4Fourier
        var l5 = defaultWeights.layer5

        if ambient.lowLightDetected {
            l3 = 0.15
            l2 = 0.35
            l5 = 0.15
        }
        if ambient.motionDetected {
            // C-2: transfer `(l5 - 0.05)` into l3 instead of hard-coding
            // l3 = 0.30. The hardcoded value only summed to 1.0 when
            // low_light was off; when both flags fired the low_light
            // branch had already pushed l5 to 0.15, and hard-setting
            // l3 = 0.30 produced a weight set summing to 1.05 — the
            // weighted score could exceed 1.0 and every threshold
            // shift up with it. Deriving l3 from the l5 it drains keeps
            // the sum invariant across all four permutations.
            let transferred = l5 - 0.05
            l5 = 0.05
            l3 += transferred
        }
        return Weights(layer2: l2, layer3: l3, layer4Lbp: l4Lbp, layer4Fourier: l4F, layer5: l5)
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

        let w = LivenessOrchestrator.weights(for: ambientConditions)
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
