"""On-device orchestrator — combines layer scores into final liveness decision.

Reference implementation. Mirrors `LivenessOrchestrator.kt` exactly;
the server re-runs this module from `layer_scores` before issuing a
JWT. Any drift between this file and the Kotlin/Swift mirrors makes
attestations fail server-side verification.

Spec §13 (lines ~1619-1671). See the Kotlin file for the C-2 fix:
when BOTH low_light AND motion fire, the motion branch derives l3
from the l5 it drains, keeping sum(weights) = 1.0 invariant across
all four permutations.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# Spec §1623-1631. Sum to 1.0; adjusted sets below sum to 1.0 too so
# the weighted score stays comparable against the fixed thresholds
# without renormalization.
DEFAULT_WEIGHTS = {
    "layer2": 0.30,
    "layer3": 0.25,
    "layer4_lbp": 0.20,
    "layer4_fourier": 0.15,
    "layer5": 0.10,
}

PASS_THRESHOLD = 0.75
INCONCLUSIVE_THRESHOLD = 0.60


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
    """Spec §1633-1644. low_light first, motion stacks on top — same
    order the Kotlin mirror uses so server/client never disagree."""
    w = dict(DEFAULT_WEIGHTS)

    if ambient.low_light_detected:
        w["layer3"] = 0.15
        w["layer2"] = 0.35
        w["layer5"] = 0.15

    if ambient.motion_detected:
        # C-2 fix: derive l3 from the l5 drained to 0.05, instead of
        # hard-setting l3=0.30. The hardcoded value only summed to 1.0
        # when low_light was off; when both flags fire the low_light
        # branch had already pushed l5 to 0.15, and hard-setting
        # l3=0.30 made the weights sum to 1.05, letting the weighted
        # score exceed 1.0 and shifting every threshold up with it.
        transferred = w["layer5"] - 0.05
        w["layer5"] = 0.05
        w["layer3"] += transferred

    return w


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

    w = weights_for(ambient_conditions)
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
