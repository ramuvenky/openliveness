package dev.openliveness

/**
 * Combines the five layer scores into the single `liveness_decision`
 * that goes into the attestation. Weights, ambient-condition reweighting,
 * abstention renormalization, and decision thresholds here are the
 * on-device mirror of Spec §13 (lines ~1619-1671). The relay accepts
 * the SDK's decision without recomputing it (see
 * `relay/src/attestation/verify.ts:checkAssurance`), so the Swift +
 * Python mirrors matter for cross-platform consistency and the benchmark
 * harness's `combined` scorer, not for an immediate server round-trip.
 */
class LivenessOrchestrator {

    data class Weights(
        val layer2: Float,
        val layer3: Float,
        val layer4Lbp: Float,
        val layer4Fourier: Float,
        val layer5: Float
    )

    companion object {
        /**
         * Calibrated 2026-11 (v0.2.0) after the on-device regression
         * where honest Layer-4 outputs landed live users at ~0.63 even
         * after preprocessing alignment. Two shifts from v0.1.0:
         *
         *  - **Fourier dropped to 0** — the pure-math screen-replay
         *    detector in `core/algorithms/layer4_texture.py::fourier_score`
         *    uses hand-tuned ring frequencies calibrated against the
         *    2015-era MSU-MFSD cameras. On modern phone captures (1080p+
         *    sensors downsampled to 80×80 crops) fine skin detail lands
         *    in the same ring the detector reads as screen pixel pitch,
         *    producing ~0.15 on real faces. Weighting it toward live
         *    detection was actively harmful; zero until the detector is
         *    retuned on a modern corpus.
         *
         *  - **LBP weight reduced 0.25 → 0.15** — model card HTER=0.293
         *    on MSU-MFSD test with optimal threshold 0.358 (< 0.5)
         *    means the model is systematically low-scoring. Its signal
         *    is real but modest; L2 (active challenge) and L5
         *    (behavioral) carry the bulk of the sum.
         *
         * Drained mass (0.10 Fourier + 0.10 LBP) redistributed mostly
         * to L5 (+0.15, behavioral micro-movement proved reliable on-
         * device and is the second-strongest spoof defense after L2)
         * with a smaller bump to L2 (+0.05). Specifically balanced so
         * `L2=0.9 + L5=0.0 + abstained L3` fails cleanly: pure photo
         * attacks that defeat the challenge but have no micro-saccade
         * still land below INCONCLUSIVE_THRESHOLD=0.60. L3 held at 0.10
         * because POS-rPPG on 12s windows abstains most of the time
         * and the abstention renormaliser compensates anyway.
         *
         * Sum = 1.00; every ambient permutation below also sums to 1.00.
         */
        val DEFAULT_WEIGHTS = Weights(
            layer2 = 0.50f,
            layer3 = 0.10f,
            layer4Lbp = 0.15f,
            layer4Fourier = 0.00f,
            layer5 = 0.25f
        )
        const val PASS_THRESHOLD: Float = 0.75f
        const val INCONCLUSIVE_THRESHOLD: Float = 0.60f

        /** Floor below which a Layer-3 confidence combined with both
         *  liveness flags unset is treated as an abstention rather than
         *  a 0 vote. Keeps the weighted sum from being dragged down by
         *  POS-rPPG's expected "insufficient samples" outcome on short
         *  (~10s) capture windows. */
        private const val LAYER3_ABSTAIN_EPS: Float = 1e-6f

        /**
         * Pick a weight set based on ambient conditions. Low-light cuts
         * nothing further off Layer 3 (default is already floored at
         * 0.10) and shifts weight from Layer 4 LBP into Layer 5 —
         * behavioral micro-movement is ambient-invariant while LBP
         * texture is noisier under poor illumination. Motion cuts the
         * behavioral weight (jitter contaminates micro-movement) and
         * transfers the drained mass into Layer 3. low_light is
         * evaluated first; motion stacks on top. The Python and Swift
         * reference implementations follow this same sequential order.
         *
         * Permutation sums (all must equal 1.00):
         *   default      : 0.50 + 0.10 + 0.15 + 0.00 + 0.25 = 1.00
         *   lowLight     : 0.50 + 0.10 + 0.10 + 0.00 + 0.30 = 1.00
         *   motion       : 0.50 + 0.30 + 0.15 + 0.00 + 0.05 = 1.00
         *   lowLight+mot : 0.50 + 0.35 + 0.10 + 0.00 + 0.05 = 1.00
         */
        fun weightsFor(ambient: AmbientConditions): Weights {
            var l2 = DEFAULT_WEIGHTS.layer2
            var l3 = DEFAULT_WEIGHTS.layer3
            var l4Lbp = DEFAULT_WEIGHTS.layer4Lbp
            val l4F = DEFAULT_WEIGHTS.layer4Fourier
            var l5 = DEFAULT_WEIGHTS.layer5

            if (ambient.lowLightDetected) {
                l4Lbp = 0.10f
                l5 = 0.30f
            }
            if (ambient.motionDetected) {
                // C-2: transfer `(l5 - 0.05)` into l3 instead of hard-
                // coding l3. Deriving the delta from the l5 drain keeps
                // the sum invariant across all four permutations, which
                // the Swift/Python mirrors assume.
                val transferred = l5 - 0.05f
                l5 = 0.05f
                l3 += transferred
            }
            return Weights(l2, l3, l4Lbp, l4F, l5)
        }

        /**
         * Layer-3 abstention: compute() returns null (and CDLSession
         * then publishes confidence=0 + both flags=false) whenever
         * MIN_SAMPLES_NORMAL isn't reached, zero-mean/zero-std guards
         * fire, or SNR fails to lock. This is "no signal", not "zero
         * liveness evidence", and must not drag the weighted sum down
         * as if the scorer voted 0 — doing that was the on-device
         * regression that pinned live users below 0.60 after Phase 4
         * wired real detectors into the sample app.
         *
         * Keyed on confidence alone so the Python mirror (whose
         * `LayerScores` doesn't carry the pulse/bpmInRange booleans —
         * the attestation JSON doesn't include them either) can apply
         * the exact same test without an extended schema. CDLSession
         * zeros the booleans in lockstep with confidence when compute()
         * returns null, so dropping the boolean co-check loses no
         * information on the honest path.
         */
        fun isLayer3Abstaining(scores: LayerScores): Boolean {
            return scores.layer3RppgConfidence < LAYER3_ABSTAIN_EPS
        }

        /**
         * The weights actually applied to the weighted sum: ambient-
         * adjusted base weights, then — if Layer 3 is abstaining — the
         * L3 slot zeroed and the remaining four renormalized so they
         * sum to 1.0. Exposed so parity tests (and future server-side
         * mirrors) can reproduce the exact weight set the mobile SDK
         * used, without duplicating the renormalization math.
         */
        fun effectiveWeights(scores: LayerScores, ambient: AmbientConditions): Weights {
            val base = weightsFor(ambient)
            if (!isLayer3Abstaining(scores)) return base
            val remaining = base.layer2 + base.layer4Lbp + base.layer4Fourier + base.layer5
            return Weights(
                layer2 = base.layer2 / remaining,
                layer3 = 0f,
                layer4Lbp = base.layer4Lbp / remaining,
                layer4Fourier = base.layer4Fourier / remaining,
                layer5 = base.layer5 / remaining
            )
        }
    }

    /**
     * Hard rule from Spec §1351-1352 and §1653-1655: a missed challenge
     * means fail with score 0 — the other layer scores are discarded,
     * not weighted-in. Without this, a static image that happens to
     * pass texture + fake rPPG could outscore the missing challenge.
     */
    fun orchestrate(
        layerScores: LayerScores,
        ambientConditions: AmbientConditions,
        hardwareAttestationVerified: Boolean = false
    ): LivenessResult {
        if (!layerScores.layer2ChallengeCompleted) {
            return LivenessResult(
                decision = "fail",
                assuranceLevel = "software_only",
                layerScores = layerScores
            )
        }

        val w = effectiveWeights(layerScores, ambientConditions)

        val weighted =
            layerScores.layer2ChallengeScore  * w.layer2 +
            layerScores.layer3RppgConfidence  * w.layer3 +
            layerScores.layer4LbpScore        * w.layer4Lbp +
            layerScores.layer4FourierScore    * w.layer4Fourier +
            layerScores.layer5BehavioralScore * w.layer5

        val decision = when {
            weighted >= PASS_THRESHOLD -> "pass"
            weighted >= INCONCLUSIVE_THRESHOLD -> "inconclusive"
            else -> "fail"
        }

        // Spec §1673-1681: IAL2 only when hardware attestation verified
        // AND the decision is "pass". Anything else — including a pass
        // without hw attestation — downgrades to software_only. We do
        // not emit "IAL1"; the schema accepts it for forward compat but
        // the v1.0 orchestrator never produces it.
        val assurance = if (hardwareAttestationVerified && decision == "pass") "IAL2" else "software_only"

        return LivenessResult(
            decision = decision,
            assuranceLevel = assurance,
            layerScores = layerScores
        )
    }
}
