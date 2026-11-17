"""On-device orchestrator — combines layer scores into final liveness decision.

Reference implementation. Mirrors `LivenessOrchestrator.kt` and the Swift
port exactly. The relay accepts the SDK's decision without recomputing
it (`relay/src/attestation/verify.ts:checkAssurance` only cross-checks
IAL2 vs hw attestation), so this module's role is cross-platform
parity + the benchmark harness's `combined` scorer, not a server
round-trip. The golden-fixture suite (`core/tests/golden/`) is the
tripwire for drift between the three ports.

Spec §13 (lines ~1619-1671). See the Kotlin file for the C-2 fix:
when BOTH low_light AND motion fire, the motion branch derives l3
from the l5 it drains, keeping sum(weights) = 1.0 invariant across
all four permutations.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# Calibrated 2026-11 (v0.2.0) after the on-device regression where
# honest Layer-4 outputs landed live users at ~0.63 even after
# preprocessing alignment. Two shifts from v0.1.0:
#
#   * Fourier dropped to 0 — the pure-math screen-replay detector uses
#     ring frequencies calibrated against 2015-era MSU-MFSD cameras. On
#     modern phones (1080p+ sensors downsampled to 80x80 crops) fine
#     skin detail lands in the same ring the detector reads as screen
#     pixel pitch, producing ~0.15 on real faces. Weighting it toward
#     live detection was actively harmful; zero until retuned on a
#     modern corpus.
#   * LBP weight reduced 0.25 -> 0.15 — retrained model card reports
#     HTER=0.293 on MSU-MFSD test with optimal threshold 0.358 (< 0.5),
#     meaning the model is systematically low-scoring. L2 (challenge)
#     and L5 (behavioral) carry the bulk of the sum.
#
# Drained mass (0.10 Fourier + 0.10 LBP) redistributed mostly to L5
# (+0.15, behavioral is the second-strongest spoof defense after L2)
# with a smaller L2 bump (+0.05). Specifically balanced so
# `L2=0.9 + L5=0.0 + abstained L3` fails cleanly: pure photo attacks
# that defeat the challenge but have no micro-saccade still land below
# INCONCLUSIVE_THRESHOLD=0.60. L3 held at 0.10 because POS-rPPG on 12s
# windows abstains most of the time. Sum = 1.00; every ambient
# permutation also sums to 1.00. The Kotlin `LivenessOrchestratorTest`
# and Swift mirror follow these numbers in lockstep; the orchestrator
# golden fixtures regenerate from this module.
DEFAULT_WEIGHTS = {
    "layer2": 0.50,
    "layer3": 0.10,
    "layer4_lbp": 0.15,
    "layer4_fourier": 0.00,
    "layer5": 0.25,
}

PASS_THRESHOLD = 0.75
INCONCLUSIVE_THRESHOLD = 0.60

# Floor below which a Layer-3 confidence is treated as an abstention
# rather than a 0 vote. Keeps the weighted sum from being dragged down
# by POS-rPPG's expected "insufficient samples" outcome on short
# (~10s) capture windows. Must match Kotlin LAYER3_ABSTAIN_EPS and
# the Swift mirror exactly.
LAYER3_ABSTAIN_EPS = 1e-6


@dataclass
class LayerScores:
    layer2_challenge_completed: bool
    layer2_challenge_score: float
    layer3_rppg_confidence: float
    layer4_lbp_score: float
    layer4_fourier_score: float
    layer5_behavioral_score: float


@dataclass
class AmbientConditions:
    low_light_detected: bool = False
    motion_detected: bool = False
    front_camera_confirmed: bool = True


@dataclass
class LivenessResult:
    decision: str  # "pass" | "inconclusive" | "fail"
    assurance_level: str  # "IAL2" | "software_only"
    weighted_score: float
    weights_applied: dict
    layer_scores: LayerScores


def weights_for(ambient: AmbientConditions) -> dict:
    """Pick a weight set based on ambient conditions. Low light shifts
    0.05 off L4 LBP (texture noisier under poor illumination) into L5
    (behavioral micro-movement is ambient-invariant); L3 is already
    floored at 0.10 by the default so low-light doesn't cut it further.
    Motion transfers `(l5 - 0.05)` into l3 — behavioral is contaminated
    by jitter, L3's finite integration window still averages the pulse.
    low_light is evaluated first; motion stacks on top. The Kotlin and
    Swift mirrors follow this exact sequential order.

    Permutation sums (all must equal 1.00):
      default      : 0.50 + 0.10 + 0.15 + 0.00 + 0.25 = 1.00
      lowLight     : 0.50 + 0.10 + 0.10 + 0.00 + 0.30 = 1.00
      motion       : 0.50 + 0.30 + 0.15 + 0.00 + 0.05 = 1.00
      lowLight+mot : 0.50 + 0.35 + 0.10 + 0.00 + 0.05 = 1.00
    """
    w = dict(DEFAULT_WEIGHTS)

    if ambient.low_light_detected:
        w["layer4_lbp"] = 0.10
        w["layer5"] = 0.30

    if ambient.motion_detected:
        # C-2: derive the transfer from the l5 drain, not a hardcoded
        # l3. Keeps sum(w)=1.0 across all four lowLight/motion permutations.
        transferred = w["layer5"] - 0.05
        w["layer5"] = 0.05
        w["layer3"] += transferred

    return w


def is_layer3_abstaining(layer_scores: LayerScores) -> bool:
    """Detect Layer-3 abstention. The on-device Layer3RPPG.compute()
    returns null when it can't lock a pulse (MIN_SAMPLES_NORMAL not met,
    zero-mean/zero-std guards, SNR under threshold); CDLSession then
    publishes confidence=0. Keyed on confidence alone so the Python
    LayerScores (which doesn't carry pulse_detected / bpm_in_range)
    matches the Kotlin check exactly. The attestation JSON also doesn't
    carry those booleans, so a future server-side mirror of this check
    would have no additional information to use.
    """
    return layer_scores.layer3_rppg_confidence < LAYER3_ABSTAIN_EPS


def effective_weights(
    layer_scores: LayerScores, ambient: AmbientConditions
) -> dict:
    """Weights actually applied to the weighted sum: ambient-adjusted
    base weights, then — if Layer 3 is abstaining — the L3 slot zeroed
    and the remaining four renormalized to sum to 1.0. Exposed so parity
    tests (and future server-side mirrors) can reproduce the exact weight
    set the mobile SDK used, without duplicating the renormalization math.
    """
    base = weights_for(ambient)
    if not is_layer3_abstaining(layer_scores):
        return base
    remaining = (
        base["layer2"]
        + base["layer4_lbp"]
        + base["layer4_fourier"]
        + base["layer5"]
    )
    return {
        "layer2": base["layer2"] / remaining,
        "layer3": 0.0,
        "layer4_lbp": base["layer4_lbp"] / remaining,
        "layer4_fourier": base["layer4_fourier"] / remaining,
        "layer5": base["layer5"] / remaining,
    }


def orchestrate(
    layer_scores: LayerScores,
    ambient_conditions: AmbientConditions,
    hardware_attestation_verified: bool = False,
) -> LivenessResult:
    """Combine layer scores + ambient-adjusted weights into a decision.

    Spec §1351-1352, §1653-1655: a missed challenge forces fail with
    score 0 — the other layer scores are discarded, not weighted-in.
    Without this a static image that passed texture + fake rPPG could
    outscore a missing challenge.
    """
    if not layer_scores.layer2_challenge_completed:
        return LivenessResult(
            decision="fail",
            assurance_level="software_only",
            weighted_score=0.0,
            weights_applied=dict(DEFAULT_WEIGHTS),
            layer_scores=layer_scores,
        )

    w = effective_weights(layer_scores, ambient_conditions)

    weighted = (
        layer_scores.layer2_challenge_score * w["layer2"]
        + layer_scores.layer3_rppg_confidence * w["layer3"]
        + layer_scores.layer4_lbp_score * w["layer4_lbp"]
        + layer_scores.layer4_fourier_score * w["layer4_fourier"]
        + layer_scores.layer5_behavioral_score * w["layer5"]
    )

    if weighted >= PASS_THRESHOLD:
        decision = "pass"
    elif weighted >= INCONCLUSIVE_THRESHOLD:
        decision = "inconclusive"
    else:
        decision = "fail"

    # Spec §1673-1681: IAL2 iff hw attestation verified AND pass. The
    # v1.0 orchestrator never emits "IAL1"; the schema accepts it for
    # forward compatibility only.
    assurance = (
        "IAL2"
        if (hardware_attestation_verified and decision == "pass")
        else "software_only"
    )
    return LivenessResult(
        decision=decision,
        assurance_level=assurance,
        weighted_score=weighted,
        weights_applied=w,
        layer_scores=layer_scores,
    )
